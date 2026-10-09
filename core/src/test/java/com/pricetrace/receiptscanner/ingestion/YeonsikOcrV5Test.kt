package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import com.pricetrace.receiptscanner.review.ReviewViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class YeonsikOcrV5Test {
    private fun example(name: String = "yeonsik-ocr.v5.restaurant-purchase.example.json") = File("../examples/$name").readText()
    private fun envelope() = YeonsikOcrEnvelopeCodec.decode(example(), "v5-document")
    private fun evidence() = envelope().source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
    private val expected = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, IngestionProjection.CASHOS_TRANSACTION, IngestionProjection.FITNESS_NUTRITION)

    @Test fun paidPurchaseAndFoodPhotoWaitForLinkedPurchaseAuthorityAndRoundTrip() {
        val value = envelope()
        assertEquals(expected, CanonicalProjectionPlanner.plan(value).eligible)
        assertEquals(setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION), CanonicalProjectionPlanner.dependenciesFor(IngestionProjection.FITNESS_NUTRITION, value))
        assertTrue(IngestionEvidenceGate.evaluate(value, evidence()).isAllowed)
        assertEquals(YeonsikOcrV5Json.canonicalize(value), YeonsikOcrV5Json.canonicalize(YeonsikOcrEnvelopeCodec.decode(YeonsikOcrEnvelopeCodec.encode(value), "other-document")))
        assertEquals(14, Json.parseToJsonElement(YeonsikOcrEnvelopeCodec.encode(value)).jsonObject.size)
    }

    private fun withConsumption(value: YeonsikOcrEnvelope = envelope()): YeonsikOcrEnvelope = value.copy(
        source = value.source.copy(userText = "2026-10-09T12:00:00+09:00에 1 serving 섭취했습니다."),
        consumption = listOf(IngestionConsumption("consumption-1", consumedAt = "2026-10-09T12:00:00+09:00", items = listOf(IngestionConsumptionItem("food-1", 1.0, "serving", 1.0, "user_provided")))),
    )

    @Test fun explicitConsumptionAddsMealAndExternalAuthorityIsDiscarded() {
        val value = withConsumption()
        YeonsikOcrV5Json.validate(value)
        assertEquals(expected + IngestionProjection.FITNESS_MEAL, CanonicalProjectionPlanner.plan(value).eligible)
        assertEquals(setOf(IngestionProjection.FITNESS_NUTRITION), CanonicalProjectionPlanner.dependenciesFor(IngestionProjection.FITNESS_MEAL, value))
        val verified = value.copy(consumption = value.consumption.map { it.copy(status = ConsumptionVerificationStatus.USER_VERIFIED) }, review = value.review.copy(verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW))
        val persisted = YeonsikOcrEnvelopeCodec.encodePersisted(verified)
        val external = YeonsikOcrEnvelopeCodec.decode(persisted, "external")
        assertEquals(ConsumptionVerificationStatus.UNVERIFIED, external.consumption.single().status)
        assertEquals(VerificationBasis.SOURCE_EVIDENCE, external.review.verificationBasis)
        assertEquals(ConsumptionVerificationStatus.USER_VERIFIED, YeonsikOcrEnvelopeCodec.decode(persisted, "local", true).consumption.single().status)
        assertEquals(YeonsikOcrV5Json.canonicalize(value), YeonsikOcrV5Json.canonicalize(verified))
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(source = value.source.copy(userText = null))) }
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(source = value.source.copy(userText = "구매했습니다"))) }
    }

    @Test fun twoPurchaseLinesOnlyLinkedLineHasNutritionAndLinkEditingRevalidates() {
        val value = envelope()
        val record = value.purchaseRecords.single()
        val two = value.copy(purchaseRecords = listOf(record.copy(lineItems = record.lineItems + record.lineItems.single().copy(lineKey = "line-2", description = "다른 메뉴"))))
        YeonsikOcrV5Json.validate(two)
        assertEquals(1, two.nutrition.size)
        val review = ReviewViewModel.fromCanonical(two)
        assertEquals(1, review.rows.count { it.section == "구매 행과 영양 연결" })
        val controller = CanonicalReviewController(two)
        assertTrue(controller.updateField("links[food-1].purchase_line_key", "line-2"))
        assertEquals("line-2", controller.state.value.envelope.purchaseNutritionLinks.single().purchaseLineKey)
        assertFalse(controller.updateField("links[food-1].purchase_line_key", "missing"))
        assertEquals("line-2", controller.state.value.envelope.purchaseNutritionLinks.single().purchaseLineKey)
        assertTrue(controller.state.value.edits.any { it.fieldPath == "links[food-1].purchase_line_key" })
    }

    @Test fun danglingDuplicateConflictingLinksAndWrongEvidenceReject() {
        val value = envelope()
        val link = value.purchaseNutritionLinks.single()
        listOf(link.copy(purchaseLineKey = "missing"), link.copy(purchaseRecordClientKey = "missing"), link.copy(nutritionClientKey = "missing")).forEach {
            assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(purchaseNutritionLinks = listOf(it))) }
        }
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(purchaseNutritionLinks = listOf(link, link))) }
        val root = Json.parseToJsonElement(example()).jsonObject
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.decode(JsonObject(root + ("authority_uuid" to JsonPrimitive("fake"))).toString(), "test") }
        val item = value.nutrition.single() as IngestionNutrition.RestaurantEstimate
        val wrong = item.copy(estimate = item.estimate.copy(nutrientProvenance = item.estimate.nutrientProvenance.mapValues { it.value.copy(evidenceRefs = listOf("order-history-1")) }))
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(nutrition = listOf(wrong))) }
    }

    @Test fun nutritionAndPurchaseEvidenceGatesAreIndependentAndNeverInterchangeable() {
        val value = envelope()
        val food = evidence().filter { it.type == SourceAttachmentType.FOOD_PHOTO }
        val histories = evidence().filter { it.type != SourceAttachmentType.FOOD_PHOTO }
        assertTrue(IngestionEvidenceGate.evaluate(value, food, artifactKeys = setOf(IngestionArtifactKeys.nutrition("food-1"))).isAllowed)
        assertTrue(IngestionEvidenceGate.evaluate(value, histories, artifactKeys = setOf(IngestionArtifactKeys.purchaseRecord("purchase-1"))).isAllowed)
        assertFalse(IngestionEvidenceGate.evaluate(value, histories, artifactKeys = setOf(IngestionArtifactKeys.nutrition("food-1"))).isAllowed)
        assertFalse(IngestionEvidenceGate.evaluate(value, food, artifactKeys = setOf(IngestionArtifactKeys.purchaseRecord("purchase-1"))).isAllowed)
        assertFalse(IngestionEvidenceGate.evaluate(value, histories, verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW, explicitUserConfirmation = true).isAllowed)
        assertFalse(IngestionEvidenceGate.evaluate(value, evidence().map { it.copy(type = SourceAttachmentType.FOOD_PHOTO) }).isAllowed)
    }

    @Test fun purchaseOnlyAndReceiptFoodRemainOnExistingVersions() {
        assertEquals(YEONSIK_OCR_V4_SCHEMA, YeonsikOcrEnvelopeCodec.decode(example("yeonsik-ocr.v4.purchase.example.json"), "v4").schemaVersion)
        assertEquals(YEONSIK_OCR_V2_SCHEMA, YeonsikOcrEnvelopeCodec.decode(example("yeonsik-ocr.v2.restaurant.example.json"), "v2").schemaVersion)
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(envelope().copy(nutrition = emptyList(), purchaseNutritionLinks = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV4Json.validate(YeonsikOcrV5Json.purchaseEnvelope(envelope()).copy(nutrition = envelope().nutrition)) }
    }

    @Test fun unsettledStatesKeepV4EligibilityAndRetainNutritionFacts() {
        val value = envelope()
        listOf(PurchaseRecordStatus.PENDING, PurchaseRecordStatus.CANCELLED, PurchaseRecordStatus.REFUNDED).forEach { status ->
            val record = value.purchaseRecords.single().copy(status = status, payment = value.purchaseRecords.single().payment!!.copy(status = status.wireValue))
            val revised = value.copy(purchaseRecords = listOf(record))
            YeonsikOcrV5Json.validate(revised)
            val plan = CanonicalProjectionPlanner.plan(revised)
            val oldPlan = CanonicalProjectionPlanner.plan(YeonsikOcrV5Json.purchaseEnvelope(revised))
            assertEquals(oldPlan.eligible + IngestionProjection.FITNESS_NUTRITION, plan.eligible)
            assertEquals(value.nutrition, revised.nutrition)
        }
    }

    @Test fun missingAmbiguousOrWrongLineAuthorityNeverInfersIdentity() {
        val value = envelope()
        assertNull(PurchaseNutritionIdentity.exact(value, "food-1", """{"sources":[{"purchaseRecordClientKey":"purchase-1","lineResults":[{"lineKey":"line-1","observationCreated":true,"observationId":"opaque"}]}]}"""))
        assertNull(PurchaseNutritionIdentity.exact(value, "food-1", null))
        val line = """{"lineKey":"line-1","kind":"restaurant_purchase","sourceAcceptanceStatus":"accepted","observationCreated":true,"observationStatus":"created","observationType":"restaurant_menu_manual_observation","observationId":"55555555-5555-4555-8555-555555555555","authorityStatus":"exact","merchantResolutionStatus":"exact","menuResolutionStatus":"exact","authoritativeIds":{"restaurantId":"11111111-1111-4111-8111-111111111111","restaurantLocationId":"22222222-2222-4222-8222-222222222222","restaurantMenuId":"33333333-3333-4333-8333-333333333333","catalogProductId":"44444444-4444-4444-8444-444444444444"}}"""
        fun metadata(rows: String = line) = """{"sources":[{"purchaseRecordClientKey":"purchase-1","lineResults":[$rows]}]}"""
        assertNotNull(PurchaseNutritionIdentity.exact(value, "food-1", metadata()))
        assertNull(PurchaseNutritionIdentity.exact(value, "food-1", metadata(line.replace("line-1", "line-2"))))
        assertNull(PurchaseNutritionIdentity.exact(value, "food-1", metadata(line.replace("\"exact\"", "\"ambiguous\""))))
        assertNull(PurchaseNutritionIdentity.exact(value, "food-1", metadata("$line,$line")))
    }

    @Test fun sameBundleReplayDoesNotResubmitSuccessfulSiblings() = runBlocking {
        val calls = mutableListOf<IngestionProjection>()
        val useCase = CanonicalIngestionUseCase(InMemoryIngestionSessionStore(), expected.associateWith { projection -> object : IngestionProjectionSubmitter {
            override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                calls += projection
                return if (projection == IngestionProjection.FITNESS_NUTRITION) ProjectionSubmission.Failure("pricetrace_purchase_line_identity_metadata_missing", false, requiresReview = true, metadataJson = "[]")
                else ProjectionSubmission.Success("remote-${projection.wireValue}")
            }
        } })
        val imported = useCase.importJson(example(), "v5-document", "v5-session", evidence(), InputOrigin.EXTERNAL_JSON, "a".repeat(64)) as CanonicalImportResult.Success
        val confirmed = useCase.confirm("v5-session", imported.envelope, evidence())
        assertTrue(confirmed.result is IngestionStartResult.Success)
        useCase.submitSelected("v5-session", confirmed.envelope, expected)
        val replay = useCase.importJson(example(), "v5-document", "v5-session", evidence(), InputOrigin.EXTERNAL_JSON, "a".repeat(64)) as CanonicalImportResult.Success
        assertTrue(replay.startResult is IngestionStartResult.Duplicate)
        useCase.submitSelected("v5-session", confirmed.envelope, expected)
        assertEquals(1, calls.count { it == IngestionProjection.PRICETRACE_PRICE_OBSERVATION })
        assertEquals(1, calls.count { it == IngestionProjection.CASHOS_TRANSACTION })
    }

    @Test fun ineligibleLinkedPurchaseDoesNotPermanentlyBlockPrivateNutrition() = runBlocking {
        val original = envelope()
        val record = original.purchaseRecords.single()
        val value = original.copy(purchaseRecords = listOf(record.copy(lineItems = record.lineItems.map {
            it.copy(quantity = 1.5, unitPriceAmountKrw = null)
        })))
        YeonsikOcrV5Json.validate(value)
        assertFalse(record.copy(lineItems = value.purchaseRecords.single().lineItems).priceTraceSubmissionEligible)
        assertTrue(CanonicalProjectionPlanner.dependenciesFor(IngestionProjection.FITNESS_NUTRITION, value).isEmpty())
        var imports = 0
        val useCase = CanonicalIngestionUseCase(InMemoryIngestionSessionStore(), mapOf(
            IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    imports++
                    assertFalse(request.dependencyMetadataJson.containsKey(IngestionProjection.PRICETRACE_PRICE_OBSERVATION))
                    return ProjectionSubmission.Failure("identity_pending", false, requiresReview = true)
                }
            },
        ))
        val imported = useCase.importJson(YeonsikOcrEnvelopeCodec.encode(value), "v5-document", "private-session", evidence()) as CanonicalImportResult.Success
        val confirmed = useCase.confirm("private-session", imported.envelope, evidence())
        useCase.submitSelected("private-session", confirmed.envelope, setOf(IngestionProjection.FITNESS_NUTRITION))
        assertEquals(1, imports)
    }

    @Test fun selectingFitnessRecoversLegacyPurchaseCheckpointBeforePrivateImportAndNeverReingests() = runBlocking {
        val calls = mutableListOf<String>()
        val legacy = """{"sources":[{"purchaseRecordClientKey":"purchase-1","purchaseSourceId":"55555555-5555-4555-8555-555555555555","lineResults":[{"lineKey":"line-1"}]}]}"""
        val recovered = sourceOnlyAuthority()
        val purchase = object : IngestionProjectionSubmitter, OcrProjectionResponseReader {
            override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                calls += "purchase"
                return ProjectionSubmission.Success("purchase-id", legacy)
            }
            override suspend fun readAcceptedProjection(request: ProjectionRequest): ProjectionSubmission {
                calls += "owner_getter"
                assertEquals(legacy, request.previousMetadataJson)
                return ProjectionSubmission.Success("purchase-id", recovered, primaryUploaded = false,
                    primaryPendingReason = "price_observation_not_created")
            }
        }
        val fitness = object : IngestionProjectionSubmitter {
            override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                calls += "private_nutrition"
                assertEquals(recovered, request.dependencyMetadataJson[IngestionProjection.PRICETRACE_PRICE_OBSERVATION])
                assertNull(PurchaseNutritionIdentity.exact(request.envelope!!, "food-1", recovered))
                return ProjectionSubmission.Failure("identity_pending", false, requiresReview = true)
            }
        }
        val useCase = CanonicalIngestionUseCase(InMemoryIngestionSessionStore(), mapOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to purchase, IngestionProjection.FITNESS_NUTRITION to fitness,
        ))
        val imported = useCase.importJson(example(), "v5-document", "recovery-session", evidence()) as CanonicalImportResult.Success
        val confirmed = useCase.confirm("recovery-session", imported.envelope, evidence())
        useCase.submitSelected("recovery-session", confirmed.envelope, setOf(IngestionProjection.FITNESS_NUTRITION))
        assertEquals(listOf("purchase", "owner_getter", "private_nutrition"), calls)
        assertFalse(YeonsikOcrEnvelopeCodec.encode(confirmed.envelope).contains("55555555-5555-4555-8555-555555555555"))
    }

    private fun sourceOnlyAuthority() = """{"sources":[{"purchaseRecordClientKey":"purchase-1","purchaseSourceId":"55555555-5555-4555-8555-555555555555","sourceSaved":true,"sourceAcceptanceStatus":"accepted","lineAuthorityVersion":"purchase-line-authority.v1","lineResults":[{"lineKey":"line-1","kind":"restaurant_purchase","sourceAcceptanceStatus":"accepted","observationCreated":false,"observationStatus":"not_created","authorityStatus":"unresolved","merchantResolutionStatus":"unresolved","menuResolutionStatus":"unresolved","authoritativeIds":null}]}]}"""
}
