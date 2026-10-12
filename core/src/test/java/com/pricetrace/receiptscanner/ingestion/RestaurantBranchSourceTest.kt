package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Json
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Contract
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Payload
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RestaurantBranchSourceTest {
    private val examples = listOf(
        "yeonsik-ocr.v2.restaurant.example.json",
        "yeonsik-ocr.v4.purchase.example.json",
        "yeonsik-ocr.v5.restaurant-purchase.example.json",
    )

    private fun envelope(name: String = examples.last()) =
        YeonsikOcrEnvelopeCodec.decode(File("../examples/$name").readText(), "branch-document")

    @Test
    fun v4AndV5PreserveSellerBranchThroughCanonicalAndPriceTracePayload() {
        examples.drop(1).forEach { name ->
            val original = envelope(name)
            val value = original.copy(purchaseRecords = original.purchaseRecords.map {
                it.copy(seller = "맛단-맛있는 다이어트식단", sellerBranchName = "영등포점")
            })
            val decoded = YeonsikOcrEnvelopeCodec.decode(YeonsikOcrEnvelopeCodec.encode(value), "round-trip")
            val record = decoded.purchaseRecords.single()
            assertEquals("맛단-맛있는 다이어트식단", record.seller)
            assertEquals("영등포점", record.sellerBranchName)
            assertEquals(value.purchaseRecords, decoded.purchaseRecords)
            val payload = PriceTracePurchaseObservationV4Json.toJson(
                PriceTracePurchaseObservationV4Payload(PriceTracePurchaseObservationV4Contract(), record, VerificationBasis.SOURCE_EVIDENCE),
            )
            val seller = payload.getValue("seller").jsonObject
            assertEquals(JsonPrimitive(record.seller), seller["seller_name"])
            assertEquals(JsonPrimitive("영등포점"), seller["branch_name"])
            // Omitted line seller inherits this exact record snapshot at the existing receiver.
            assertFalse(payload.getValue("items").jsonArray.single().jsonObject.containsKey("seller"))
        }
    }

    @Test
    fun v2V4V5UnknownBranchesRemainValidAndRoundTripAsNull() {
        examples.forEach { name ->
            val original = envelope(name)
            val value = original.copy(
                receipt = original.receipt?.let { it.copy(merchant = it.merchant.copy(branchName = null)) },
                merchantCandidate = original.merchantCandidate?.copy(branchName = null),
                purchaseRecords = original.purchaseRecords.map { it.copy(sellerBranchName = null) },
            )
            val decoded = YeonsikOcrEnvelopeCodec.decode(YeonsikOcrEnvelopeCodec.encode(value), "null-branch")
            assertEquals(YeonsikOcrEnvelopeCodec.canonicalize(value), YeonsikOcrEnvelopeCodec.canonicalize(decoded))
            assertNull(decoded.receipt?.merchant?.branchName)
            assertNull(decoded.merchantCandidate?.branchName)
            assertTrue(decoded.purchaseRecords.all { it.sellerBranchName == null })
        }
    }

    @Test
    fun effectiveSellerBranchRequiresTheExactLocalLineAndNeverInheritsAcrossOverrides() {
        val value = envelope()
        val record = value.purchaseRecords.single()
        val key = value.nutrition.single().clientKey
        assertEquals(record.seller, PurchaseNutritionIdentity.sellerName(value, key))
        assertEquals("영등포점", PurchaseNutritionIdentity.sellerBranchName(value, key))
        listOf(record.seller!!, "별도 식당 강남점").forEach { override ->
            val revised = value.copy(purchaseRecords = listOf(record.copy(
                lineItems = record.lineItems.map { it.copy(sellerOverride = override) },
            )))
            assertEquals(override, PurchaseNutritionIdentity.sellerName(revised, key))
            assertNull(PurchaseNutritionIdentity.sellerBranchName(revised, key))
        }
        val link = value.purchaseNutritionLinks.single()
        listOf(
            emptyList(), listOf(link.copy(purchaseLineKey = "missing")),
            listOf(link.copy(purchaseRecordClientKey = "missing")), listOf(link, link),
        ).forEach { links ->
            val revised = value.copy(purchaseNutritionLinks = links)
            assertNull(PurchaseNutritionIdentity.sellerName(revised, key))
            assertNull(PurchaseNutritionIdentity.sellerBranchName(revised, key))
        }
    }

    @Test
    fun branchReviewEditsChangeFingerprintRevisionAndInvalidateVerification() = runBlocking {
        examples.forEach { name ->
            val store = InMemoryIngestionSessionStore()
            val useCase = CanonicalIngestionUseCase(store)
            val imported = useCase.importJson(File("../examples/$name").readText(), "branch-document", "branch-session")
                as CanonicalImportResult.Success
            val persisted = requireNotNull(useCase.session("branch-session"))
            store.save(persisted.copy(
                reviewStatus = IngestionReviewStatus.READY,
                verifiedCanonicalFingerprint = persisted.canonicalFingerprint,
                verifiedAt = "2026-10-11T12:00:00+09:00",
            ))
            val controller = CanonicalReviewController(imported.envelope)
            val field = if (imported.envelope.receipt != null) "receipt.merchant.branch_name"
                else "purchase_records[purchase-1].seller_branch_name"
            assertTrue(controller.editableFields().any { it.path == field })
            assertTrue(controller.updateField(field, "새 지점"))
            val result = useCase.reviseCanonicalDraft("branch-session", controller.state.value.envelope)
                as IngestionStartResult.Success
            assertEquals(2L, result.session.revisionSeq)
            assertNotEquals(persisted.canonicalFingerprint, result.session.canonicalFingerprint)
            assertNull(result.session.verifiedCanonicalFingerprint)
            assertEquals(IngestionReviewStatus.NEEDS_REVIEW, result.session.reviewStatus)
            val revised = YeonsikOcrEnvelopeCodec.decode(
                YeonsikOcrEnvelopeCodec.encode(controller.state.value.envelope), "revised",
            )
            assertEquals("새 지점", revised.receipt?.merchant?.branchName ?: revised.purchaseRecords.single().sellerBranchName)
        }
    }

    @Test
    fun sourceBranchEditsInvalidateNutritionArtifactAndAdvanceNutritionAndMealCheckpoints() = runBlocking {
        val fixtures = listOf(examples.first(), "yeonsik-ocr.v2.restaurant-food-photo.example.json",
            "yeonsik-ocr.v3.restaurant.example.json", examples.last())
        fixtures.forEach { name ->
            val decoded = envelope(name)
            val withNutrition = if (decoded.schemaVersion != YEONSIK_OCR_V3_SCHEMA) decoded else {
                val foodSource = envelope("yeonsik-ocr.v2.restaurant-food-photo.example.json")
                val estimate = foodSource.nutrition.single() as IngestionNutrition.RestaurantEstimate
                decoded.copy(
                    source = decoded.source.copy(sourceFiles = decoded.source.sourceFiles + foodSource.source.sourceFiles),
                    nutrition = listOf(IngestionNutrition.RestaurantMenuEstimate(
                        clientKey = estimate.clientKey,
                        menuName = estimate.menuName,
                        priceObservationClientKey = decoded.priceObservations.single().clientKey,
                        estimate = estimate.estimate,
                    )),
                )
            }
            val value = withNutrition.copy(
                source = withNutrition.source.copy(userText = "2026-10-11T12:00:00+09:00 1 serving 섭취"),
                consumption = listOf(IngestionConsumption("meal-branch", consumedAt = "2026-10-11T12:00:00+09:00",
                    items = withNutrition.nutrition.map {
                        IngestionConsumptionItem(it.clientKey, 1.0, "serving", 1.0, "user_provided")
                    }, status = ConsumptionVerificationStatus.USER_VERIFIED)),
            )
            val store = InMemoryIngestionSessionStore()
            val orchestrator = IngestionOrchestrator(store, submitters = emptyMap())
            val evidence = value.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
            assertTrue(orchestrator.start("branch-session", "branch-document", value, evidence) is IngestionStartResult.Success)
            val verified = orchestrator.markNutritionVerified("branch-session", value, evidence)
            assertTrue("$name: $verified", verified is IngestionStartResult.Success)
            val before = requireNotNull(store.get("branch-session"))
            val controller = CanonicalReviewController(value)
            val path = when {
                value.receipt != null -> "receipt.merchant.branch_name"
                value.schemaVersion == YEONSIK_OCR_V5_SCHEMA -> "purchase_records[purchase-1].seller_branch_name"
                else -> "merchant_candidate.branch_name"
            }
            assertTrue(controller.updateField(path, "새 지점"))
            val after = (orchestrator.reviseCanonicalDraft("branch-session", controller.state.value.envelope)
                as IngestionStartResult.Success).session
            value.nutrition.filter { it is IngestionNutrition.RestaurantEstimate || it is IngestionNutrition.RestaurantMenuEstimate }
                .forEach { item ->
                    val key = IngestionArtifactKeys.nutrition(item.clientKey)
                    assertTrue(before.verifiedArtifactFingerprints.containsKey(key))
                    assertFalse(after.verifiedArtifactFingerprints.containsKey(key))
                }
            listOf(IngestionProjection.FITNESS_NUTRITION, IngestionProjection.FITNESS_MEAL).forEach { projection ->
                val old = before.projections.single { it.projection == projection }
                val next = after.projections.single { it.projection == projection }
                assertNotEquals(name, ProjectionStatus.DISABLED, old.status)
                assertNotEquals(name, old.projectionPayloadFingerprint, next.projectionPayloadFingerprint)
                assertEquals(old.projectionRevisionSeq + 1, next.projectionRevisionSeq)
            }
        }
    }

    @Test
    fun lineSellerOverrideKeepsNutritionVerificationIndependentOfRecordBranch() = runBlocking {
        val original = envelope()
        val value = original.copy(purchaseRecords = original.purchaseRecords.map { record ->
            record.copy(lineItems = record.lineItems.map { it.copy(sellerOverride = record.seller) })
        })
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(store, submitters = emptyMap())
        val evidence = value.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
        assertTrue(orchestrator.start("override-session", "branch-document", value, evidence) is IngestionStartResult.Success)
        assertTrue(orchestrator.markNutritionVerified("override-session", value, evidence) is IngestionStartResult.Success)
        val before = requireNotNull(store.get("override-session"))
        val changed = value.copy(purchaseRecords = value.purchaseRecords.map { it.copy(sellerBranchName = "새 지점") })
        val after = (orchestrator.reviseCanonicalDraft("override-session", changed) as IngestionStartResult.Success).session
        val key = IngestionArtifactKeys.nutrition("food-1")
        assertNotEquals(before.canonicalFingerprint, after.canonicalFingerprint)
        assertEquals(before.verifiedArtifactFingerprints[key], after.verifiedArtifactFingerprints[key])
        assertEquals(before.projections.single { it.projection == IngestionProjection.FITNESS_NUTRITION }.projectionPayloadFingerprint,
            after.projections.single { it.projection == IngestionProjection.FITNESS_NUTRITION }.projectionPayloadFingerprint)
    }

    @Test
    fun namesContainingDependencyDelimitersCannotHideBranchRemovalFromNutritionVerification() = runBlocking {
        val source = envelope(examples.first())
        val items = listOf(
            source.nutrition.filterIsInstance<IngestionNutrition.RestaurantEstimate>().single(),
            source.nutrition.filterIsInstance<IngestionNutrition.MealComponentEstimate>().single().copy(reference = null),
        )
        items.forEach { item ->
            val receipt = requireNotNull(source.receipt)
            val branch = if (item is IngestionNutrition.MealComponentEstimate) "branch|branch_name=<null>" else "branch"
            val misleadingName = if (item is IngestionNutrition.MealComponentEstimate) receipt.merchant.name + "|branch_name=branch"
                else receipt.merchant.name + "|branch_name=" + JsonPrimitive(branch)
            val value = source.copy(
                receipt = receipt.copy(merchant = receipt.merchant.copy(branchName = branch)),
                nutrition = listOf(item),
                consumption = source.consumption.map { it.copy(nutritionClientKeys = emptySet(),
                    items = it.items.filter { consumed -> consumed.nutritionClientKey == item.clientKey }) },
                links = source.links.filter { it.nutritionClientKey == item.clientKey },
            )
            val store = InMemoryIngestionSessionStore()
            val orchestrator = IngestionOrchestrator(store, submitters = emptyMap())
            val evidence = value.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
            val started = orchestrator.start("delimiters", "branch-document", value, evidence)
            assertTrue(started.toString(), started is IngestionStartResult.Success)
            assertTrue(orchestrator.markNutritionVerified("delimiters", value, evidence) is IngestionStartResult.Success)
            val changed = value.copy(receipt = value.receipt!!.copy(
                merchant = value.receipt.merchant.copy(name = misleadingName, branchName = null),
            ))
            val after = (orchestrator.reviseCanonicalDraft("delimiters", changed) as IngestionStartResult.Success).session
            assertFalse(after.verifiedArtifactFingerprints.containsKey(IngestionArtifactKeys.nutrition(item.clientKey)))
        }
    }

    @Test
    fun componentOnlyMealTracksMerchantBranchEvenWhenNutritionUsesAnotherReference() = runBlocking {
        val original = envelope(examples.first())
        val component = original.nutrition.filterIsInstance<IngestionNutrition.MealComponentEstimate>().single()
        val value = original.copy(nutrition = listOf(component), links = emptyList(),
            consumption = original.consumption.map { it.copy(nutritionClientKeys = emptySet(),
                items = it.items.filter { item -> item.nutritionClientKey == component.clientKey },
                status = ConsumptionVerificationStatus.USER_VERIFIED) })
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(store, submitters = emptyMap())
        val evidence = value.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
        assertTrue(orchestrator.start("component-session", "branch-document", value, evidence) is IngestionStartResult.Success)
        assertTrue(orchestrator.markNutritionVerified("component-session", value, evidence) is IngestionStartResult.Success)
        val before = requireNotNull(store.get("component-session"))
        val receipt = requireNotNull(value.receipt)
        val changed = value.copy(receipt = receipt.copy(merchant = receipt.merchant.copy(branchName = "새 지점")))
        val after = (orchestrator.reviseCanonicalDraft("component-session", changed) as IngestionStartResult.Success).session
        val nutritionKey = IngestionArtifactKeys.nutrition(component.clientKey)
        assertEquals(before.verifiedArtifactFingerprints[nutritionKey], after.verifiedArtifactFingerprints[nutritionKey])
        val oldMeal = before.projections.single { it.projection == IngestionProjection.FITNESS_MEAL }
        val newMeal = after.projections.single { it.projection == IngestionProjection.FITNESS_MEAL }
        assertNotEquals(ProjectionStatus.DISABLED, oldMeal.status)
        assertNotEquals(oldMeal.projectionPayloadFingerprint, newMeal.projectionPayloadFingerprint)
        assertEquals(oldMeal.projectionRevisionSeq + 1, newMeal.projectionRevisionSeq)
    }
}
