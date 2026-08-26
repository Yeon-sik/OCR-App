package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.CanonicalDraft
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
    fun `restaurant envelope keeps receipt links and estimates separate`() {
        val result = import(restaurantJson(), "local-restaurant")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        assertEquals(1, envelope.receipt?.lineItems?.size)
        assertTrue(envelope.nutrition.single() is IngestionNutrition.RestaurantEstimate)
        assertEquals("line-1", envelope.links.single().receiptLineId)
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
                    return if (projection == IngestionProjection.FITNESS && calls[projection] == 1) {
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
        val price = orchestrator.submitProjection("ingestion-1", IngestionProjection.PRICETRACE, envelope)
        val fitness = orchestrator.submitProjection("ingestion-1", IngestionProjection.FITNESS, envelope)
        assertEquals(ProjectionStatus.SUBMITTED, price.status)
        assertEquals(ProjectionStatus.FAILED, fitness.status)
        val retried = orchestrator.retryFailed("ingestion-1", envelope)
        assertEquals(ProjectionStatus.SUBMITTED, retried.single().status)
        assertEquals(1, calls[IngestionProjection.PRICETRACE])
        assertEquals(2, calls[IngestionProjection.FITNESS])
    }

    private fun import(value: String, localId: String) = when (val outcome = ExternalJsonImporter().import(
        value, localId, when {
            value.contains("\"mode\":\"merchant\"") -> OcrWorkflowType.PRICE_TRACE_MERCHANT
            value.contains("\"mode\":\"restaurant\"") -> OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT
            else -> OcrWorkflowType.FITNESS_NUTRITION
        },
    )) {
        is ExternalJsonImportOutcome.Success -> outcome.result
        is ExternalJsonImportOutcome.Failure -> error(outcome.error)
    }

    private fun header(mode: String, extra: String = "") = """
        {$extra"schema_version":"yeonsik-ocr.v1","mode":"$mode",
        "source":{"producer":"chatgpt","source_files":[],"user_text":null},
        "merchant_candidate":null,"receipt":null,"nutrition":[],
        "classification_hints":{"cashos":{}},"links":[],
        "review":{"status":"ready","blocking_issues":[],"warnings":[]}}
    """.trimIndent()

    private fun merchantJson(extra: String = "") = header("merchant", extra).replace(
        "\"merchant_candidate\":null", "\"merchant_candidate\":{\"name\":\"Test Mart\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
    )

    private fun restaurantJson() = header("restaurant").replace(
        "\"merchant_candidate\":null", "\"merchant_candidate\":{\"name\":\"Test Restaurant\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
    ).replace("\"receipt\":null", "\"receipt\":$receiptJson").replace("\"nutrition\":[]", "\"nutrition\":[{\"client_key\":\"food-1\",\"kind\":\"restaurant_estimate\",\"line_id\":\"line-1\",\"menu_name\":\"Noodles\",\"payload\":null,\"estimate\":$estimateJson}]").replace("\"links\":[]", "\"links\":[{\"receipt_line_id\":\"line-1\",\"nutrition_client_key\":\"food-1\"}]")

    private fun packagedJson() = header("packaged_product").replace("\"nutrition\":[]", "\"nutrition\":[{\"client_key\":\"label-1\",\"kind\":\"product_label\",\"line_id\":null,\"menu_name\":null,\"payload\":$nutritionJson,\"estimate\":null}]")

    private val receiptJson = """{"schema_version":"receipt.v2","document":{"id":"remote-receipt","type":"receipt","status":"final","issued_on":"2026-08-27","issued_at":null,"currency":"KRW","source":{"capture_method":"ocr","original_document_id":null,"source_images":[],"transcription_status":"user_verified","notes":[],"raw_text":null}},"merchant":{"name":"Test Restaurant","branch_name":null,"business_kind":"food_service","retail_channel":"regular","catalog_namespace":null,"merchant_id":null,"business_registration_number":null,"address":null,"phone":null},"line_items":[{"id":"line-1","type":"product","description":"Noodles","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":10000,"gross_amount_minor":10000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":10000,"confidence":"user_verified","tax_rate_percent":null}],"totals":{"subtotal_amount_minor":10000,"discount_amount_minor":0,"fee_amount_minor":0,"tax_amount_minor":0,"grand_total_amount_minor":10000},"payments":[]}"""
    private val estimateJson = """{"estimated":true,"confidence":"medium","nutrients":{"calories_kcal":500.0,"protein_grams":20.0,"carbs_grams":70.0,"fat_grams":15.0,"sodium_mg":800.0,"saturated_fat_grams":3.0,"sugars_grams":5.0,"fiber_grams":null,"added_sugars_grams":null,"trans_fat_grams":null,"cholesterol_mg":null},"ranges":{"calories_kcal":{"min":400.0,"point":500.0,"max":600.0}}}"""
    private val nutritionJson = """{"schema_version":"fitness-nutrition-draft.v1","document_id":"remote-label","parser_version":"test","status":"user_verified","confirmed_at":"2026-01-01T00:00:00Z","name":"Test cereal","brand":"Brand","kind":"external_menu","category":"cereal","basis_amount":100.0,"basis_unit":"g","prep_state":"unspecified","cooking_method":"unspecified","nutrients":{"calories_kcal":380.0,"protein_grams":10.0,"carbs_grams":70.0,"fat_grams":5.0,"sodium_mg":100.0,"saturated_fat_grams":1.0,"sugars_grams":12.0,"fiber_grams":null,"added_sugars_grams":null,"trans_fat_grams":null,"cholesterol_mg":null},"source_type":"product_label_ocr","source_reference":"ocr-document:remote-label","source_version":"v1","data_version":2,"visibility":"private","parse_warnings":[],"evidence":{}}"""
}
