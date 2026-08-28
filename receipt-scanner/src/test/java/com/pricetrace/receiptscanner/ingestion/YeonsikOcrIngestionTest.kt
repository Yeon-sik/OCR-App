package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportErrorCode
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YeonsikOcrIngestionTest {
    @Test
    fun `merchant envelope imports without receipt`() {
        val result = import(merchantJson(), "local-merchant")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        assertEquals(IngestionMode.MERCHANT, envelope.mode)
        assertEquals("Test Mart", envelope.merchantCandidate?.name)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, envelope.review.status)
    }

    @Test
    fun `restaurant envelope keeps receipt links and food service roles separate`() {
        val result = import(restaurantJson(), "local-restaurant")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        assertEquals(3, envelope.receipt?.lineItems?.size)
        assertEquals(
            listOf(FoodServiceRole.MAIN, FoodServiceRole.OPTION, FoodServiceRole.SIDE),
            envelope.receipt?.lineItems?.map { it.foodService?.role },
        )
        assertEquals("line-1", envelope.receipt?.lineItems?.get(1)?.foodService?.appliesToLineId)
        assertEquals(3, envelope.nutrition.size)
        assertTrue(envelope.nutrition.all { it is IngestionNutrition.RestaurantEstimate })
        assertEquals(setOf("line-1", "line-2", "line-3"), envelope.links.map { it.receiptLineId }.toSet())
    }

    @Test
    fun `restaurant receipt-only envelope is accepted`() {
        val value = header("restaurant")
            .replace(
                "\"merchant_candidate\":null",
                "\"merchant_candidate\":{\"name\":\"Test Restaurant\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
            )
            .replace("\"receipt\":null", "\"receipt\":$receiptJson")
        val result = import(value, "local-restaurant-only")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        assertEquals(OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT, result.workflowType)
        assertEquals(0, envelope.nutrition.size)
        assertTrue(envelope.receipt != null)
    }

    @Test
    fun `packaged product envelope delegates label payload to legacy contract`() {
        val result = import(packagedJson(), "local-product")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        val label = envelope.nutrition.single() as IngestionNutrition.ProductLabel
        assertEquals("Test cereal", label.draft.productName)
        assertEquals(IngestionMode.PACKAGED_PRODUCT, envelope.mode)
    }

    @Test
    fun `external envelope trust metadata is ignored and fingerprint is local-id independent`() {
        val first = import(merchantJson(extra = "\"user_verified\":true,\"owner_id\":\"attacker\","), "local-a")
        val second = import(merchantJson(), "local-b")
        assertEquals(first.importFingerprint, second.importFingerprint)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, (first.draft as CanonicalDraft.Envelope).value.review.status)
    }

    @Test
    fun `restaurant evidence requires readable receipt and food photo`() {
        val envelope = (import(restaurantJson(), "local-evidence").draft as CanonicalDraft.Envelope).value
        assertFalse(IngestionEvidenceGate.evaluate(envelope, emptyList()).isAllowed)
        assertFalse(IngestionEvidenceGate.evaluate(envelope, listOf(LocalEvidence("r", SourceAttachmentType.RECEIPT, true))).isAllowed)
        assertTrue(IngestionEvidenceGate.evaluate(envelope, listOf(
            LocalEvidence("r", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("f", SourceAttachmentType.FOOD_PHOTO, true),
        )).isAllowed)
    }

    @Test
    fun `projection failure is isolated and retry preserves successful projection`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val calls = mutableMapOf<IngestionProjection, Int>()
        val submitters = IngestionProjection.entries.associateWith { projection ->
            object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    calls[projection] = (calls[projection] ?: 0) + 1
                    return if (projection == IngestionProjection.FITNESS_NUTRITION && calls[projection] == 1) {
                        ProjectionSubmission.Failure("fitness unavailable", retryable = true)
                    } else ProjectionSubmission.Success("remote-${projection.wireValue}")
                }
            }
        }
        val resolver = object : IngestionIdentityResolver {
            override suspend fun resolve(projection: IngestionProjection, envelope: YeonsikOcrEnvelope) =
                IdentityResolution(IdentityResolutionStatus.RESOLVED, mapOf("id" to "live-${projection.wireValue}"))
        }
        val orchestrator = IngestionOrchestrator(store, resolver, submitters, now = { "2026-08-27T00:00:00Z" })
        val envelope = (import(restaurantJson(), "local-orchestrator").draft as CanonicalDraft.Envelope).value
        val started = orchestrator.start("ingestion-1", "local-orchestrator", envelope, listOf(
            LocalEvidence("r", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("f", SourceAttachmentType.FOOD_PHOTO, true),
        )) as IngestionStartResult.Success
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, started.session.reviewStatus)
        orchestrator.markUserVerified("ingestion-1", envelope, started.session.attachments)
        val price = orchestrator.submitProjection("ingestion-1", IngestionProjection.PRICETRACE_RECEIPT, envelope)
        val fitness = orchestrator.submitProjection("ingestion-1", IngestionProjection.FITNESS_NUTRITION, envelope)
        assertEquals(ProjectionStatus.UPLOADED, price.status)
        assertEquals(ProjectionStatus.FAILED, fitness.status)
        val retried = orchestrator.retryFailed("ingestion-1", envelope)
        assertEquals(ProjectionStatus.UPLOADED, retried.single().status)
        assertEquals(1, calls[IngestionProjection.PRICETRACE_RECEIPT])
        assertEquals(2, calls[IngestionProjection.FITNESS_NUTRITION])
        assertEquals(fitness.idempotencyKey, retried.single().idempotencyKey)
    }

    @Test
    fun `external decode downgrades but local persisted decode preserves verified revision`() {
        val imported = import(restaurantJson(), "local-persisted")
        val envelope = (imported.draft as CanonicalDraft.Envelope).value
        val verifiedReceipt = requireNotNull(envelope.receipt).copy(
            document = requireNotNull(envelope.receipt).document.copy(
                status = com.pricetrace.receiptscanner.domain.ReceiptStatus.FINAL,
                source = requireNotNull(envelope.receipt).document.source.copy(
                    transcriptionStatus = com.pricetrace.receiptscanner.domain.TranscriptionStatus.USER_VERIFIED,
                ),
            ),
        )
        val encoded = YeonsikOcrEnvelopeJson.encode(envelope.copy(receipt = verifiedReceipt))

        val externalRead = YeonsikOcrEnvelopeJson.decode(encoded, "local-persisted")
        val localRead = YeonsikOcrEnvelopeJson.decode(
            encoded,
            "local-persisted",
            preservePersistedVerification = true,
        )

        assertEquals(
            com.pricetrace.receiptscanner.domain.TranscriptionStatus.PARSED,
            externalRead.receipt?.document?.source?.transcriptionStatus,
        )
        assertEquals(
            com.pricetrace.receiptscanner.domain.TranscriptionStatus.USER_VERIFIED,
            localRead.receipt?.document?.source?.transcriptionStatus,
        )
        assertEquals(
            com.pricetrace.receiptscanner.domain.ReceiptStatus.FINAL,
            localRead.receipt?.document?.status,
        )
    }
    @Test
    fun `same canonical bundle is duplicate regardless of local document id`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(
            store = store,
            identityResolver = object : IngestionIdentityResolver {
                override suspend fun resolve(projection: IngestionProjection, envelope: YeonsikOcrEnvelope) =
                    IdentityResolution(IdentityResolutionStatus.RESOLVED)
            },
            submitters = emptyMap(),
            now = { "2026-08-27T00:00:00Z" },
        )
        val envelope = (import(restaurantJson(), "local-first").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val first = orchestrator.start("ingestion-first", "local-first", envelope, evidence) as IngestionStartResult.Success
        val duplicate = orchestrator.start("ingestion-second", "local-second", envelope, evidence) as IngestionStartResult.Duplicate

        assertEquals(first.session.ingestionId, duplicate.session.ingestionId)
        assertEquals(first.session.canonicalFingerprint, duplicate.session.canonicalFingerprint)
    }

    @Test
    fun `projection target contradicting supplied artifact is rejected`() {
        val value = packagedJson().replace(
            "\"projection_targets\":[]",
            "\"projection_targets\":[\"cashos_receipt\"]",
        )
        val outcome = ExternalJsonImporter().import(value, "local-contradiction")
        assertTrue(outcome is ExternalJsonImportOutcome.Failure)
        assertEquals(
            ExternalJsonImportErrorCode.INVALID_CANONICAL_JSON,
            (outcome as ExternalJsonImportOutcome.Failure).error.code,
        )
    }

    @Test
    fun `canonical revision requires re verification before submit`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val requests = mutableListOf<ProjectionRequest>()
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                        requests += request
                        return ProjectionSubmission.Success("remote-receipt")
                    }
                },
            ),
            now = { "2026-08-27T00:00:00Z" },
        )
        val envelope = (import(restaurantJson(), "local-revision").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val started = orchestrator.start("ingestion-revision", "local-revision", envelope, evidence) as IngestionStartResult.Success
        orchestrator.markUserVerified("ingestion-revision", envelope, evidence)
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection("ingestion-revision", IngestionProjection.PRICETRACE_RECEIPT, envelope).status,
        )

        val revisedEnvelope = envelope.copy(
            classificationHints = envelope.classificationHints + ("cashos.category_hint" to "updated"),
        )
        val revised = orchestrator.reviseCanonicalDraft("ingestion-revision", revisedEnvelope) as IngestionStartResult.Success
        assertEquals(2L, revised.session.revisionSeq)
        assertEquals(null, revised.session.verifiedCanonicalFingerprint)
        assertEquals(ProjectionStatus.PENDING, revised.session.projections.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.status)
        assertEquals(null, revised.session.projections.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.idempotencyKey)

        val blocked = orchestrator.submitProjection("ingestion-revision", IngestionProjection.PRICETRACE_RECEIPT, revisedEnvelope)
        assertEquals(ProjectionStatus.BLOCKED, blocked.status)
        assertEquals("projection_not_reverified", blocked.lastError)
        assertEquals(1, requests.size)

        orchestrator.markUserVerified("ingestion-revision", revisedEnvelope, evidence)
        val retried = orchestrator.submitProjection("ingestion-revision", IngestionProjection.PRICETRACE_RECEIPT, revisedEnvelope)
        assertEquals(ProjectionStatus.UPLOADED, retried.status)
        assertEquals(2, requests.size)
        assertFalse(requests[0].idempotencyKey == requests[1].idempotencyKey)
        assertEquals(2L, requests[1].revisionSeq)
        assertEquals(revised.session.canonicalFingerprint, requests[1].canonicalFingerprint)
        assertEquals(started.session.ingestionId, revised.session.ingestionId)
    }
    private fun import(
        value: String,
        localId: String,
        workflow: OcrWorkflowType? = null,
    ) = when (val outcome = ExternalJsonImporter().import(value, localId, workflow)) {
        is ExternalJsonImportOutcome.Success -> outcome.result
        is ExternalJsonImportOutcome.Failure -> error(outcome.error)
    }

    private fun header(mode: String, extra: String = "") = """
        {$extra"schema_version":"yeonsik-ocr.v1","mode":"$mode",
        "source":{"producer":"chatgpt","source_files":[],"user_text":null},
        "merchant_candidate":null,"receipt":null,"nutrition":[],
        "classification_hints":{"cashos":{}},"links":[],"projection_targets":[],
        "review":{"status":"ready","blocking_issues":[],"warnings":[]}}
    """.trimIndent()

    private fun merchantJson(extra: String = "") = header("merchant", extra).replace(
        "\"merchant_candidate\":null", "\"merchant_candidate\":{\"name\":\"Test Mart\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
    )

    private fun restaurantJson() = header("restaurant")
        .replace(
            "\"merchant_candidate\":null",
            "\"merchant_candidate\":{\"name\":\"Test Restaurant\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
        )
        .replace("\"receipt\":null", "\"receipt\":$receiptJson")
        .replace(
            "\"nutrition\":[]",
            """"nutrition":[{"client_key":"food-1","kind":"restaurant_estimate","line_id":"line-1","menu_name":"Noodles","payload":null,"estimate":$estimateJson},{"client_key":"food-2","kind":"restaurant_estimate","line_id":"line-2","menu_name":"Egg","payload":null,"estimate":$estimateJson},{"client_key":"food-3","kind":"restaurant_estimate","line_id":"line-3","menu_name":"Kimchi","payload":null,"estimate":$estimateJson}]""",
        )
        .replace(
            "\"links\":[]",
            """"links":[{"receipt_line_id":"line-1","nutrition_client_key":"food-1"},{"receipt_line_id":"line-2","nutrition_client_key":"food-2"},{"receipt_line_id":"line-3","nutrition_client_key":"food-3"}]""",
        )

    private fun packagedJson() = header("packaged_product").replace("\"nutrition\":[]", "\"nutrition\":[{\"client_key\":\"label-1\",\"kind\":\"product_label\",\"line_id\":null,\"menu_name\":null,\"payload\":$nutritionJson,\"estimate\":null}]")

    private val receiptJson = """
        {"schema_version":"receipt.v2","document":{"id":"remote-receipt","type":"receipt","status":"final","issued_on":"2026-08-27","issued_at":null,"currency":"KRW","fulfillment":{"type":"dine_in","evidence":"printed"},"source":{"capture_method":"ocr","original_document_id":null,"source_images":[],"transcription_status":"user_verified","notes":[],"raw_text":null}},"merchant":{"name":"Test Restaurant","branch_name":null,"business_kind":"food_service","retail_channel":"regular","catalog_namespace":null,"merchant_id":null,"business_registration_number":null,"address":null,"phone":null},"line_items":[{"id":"line-1","type":"product","description":"Noodles","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":10000,"gross_amount_minor":10000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":10000,"confidence":"user_verified","tax_rate_percent":null,"food_service":{"role":"main","applies_to_line_id":null}},{"id":"line-2","type":"product","description":"Egg","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":2000,"gross_amount_minor":2000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":2000,"confidence":"user_verified","tax_rate_percent":null,"food_service":{"role":"option","applies_to_line_id":"line-1"}},{"id":"line-3","type":"product","description":"Kimchi","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":1000,"gross_amount_minor":1000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":1000,"confidence":"user_verified","tax_rate_percent":null,"food_service":{"role":"side","applies_to_line_id":null}}],"totals":{"items_gross_amount_minor":13000,"discount_amount_minor":0,"fee_amount_minor":0,"tax_amount_minor":0,"tip_amount_minor":0,"rounding_amount_minor":0,"grand_total_amount_minor":13000},"payments":[{"method":"card","amount_minor":13000,"status":"paid","reference":null}]}
    """.trimIndent()
    private val estimateJson = """{"estimated":true,"confidence":"medium","nutrients":{"calories_kcal":500.0,"protein_grams":20.0,"carbs_grams":70.0,"fat_grams":15.0,"sodium_mg":800.0,"saturated_fat_grams":3.0,"sugars_grams":5.0,"fiber_grams":null,"added_sugars_grams":null,"trans_fat_grams":null,"cholesterol_mg":null},"ranges":{"calories_kcal":{"min":400.0,"point":500.0,"max":600.0}}}"""
    private val nutritionJson = """{"schema_version":"fitness-nutrition-draft.v1","document_id":"remote-label","parser_version":"test","status":"user_verified","confirmed_at":"2026-01-01T00:00:00Z","name":"Test cereal","brand":"Brand","kind":"external_menu","category":"cereal","basis_amount":100.0,"basis_unit":"g","prep_state":"unspecified","cooking_method":"unspecified","nutrients":{"calories_kcal":380.0,"protein_grams":10.0,"carbs_grams":70.0,"fat_grams":5.0,"sodium_mg":100.0,"saturated_fat_grams":1.0,"sugars_grams":12.0,"fiber_grams":null,"added_sugars_grams":null,"trans_fat_grams":null,"cholesterol_mg":null},"source_type":"product_label_ocr","source_reference":"ocr-document:remote-label","source_version":"v1","data_version":2,"visibility":"private","parse_warnings":[],"evidence":{}}"""
}
