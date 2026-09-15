package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.ReceiptBenefitKind
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.ingestion.CanonicalImportResult
import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanonicalReviewControllerTest {
    @Test
    fun `review event checkbox undo redo and reload preserve source-faithful edits`() {
        val original = restaurantEnvelope("review-controller")
        val sourceBinding = original.source
        val controller = CanonicalReviewController(original, now = { "2026-09-15T12:00:00+09:00" })
        val originalFingerprint = controller.state.value.canonicalFingerprint

        assertTrue(controller.setRestaurantReviewEvent("line-1", true))
        assertEquals(
            ReceiptBenefitKind.REVIEW_EVENT,
            controller.state.value.envelope.receipt!!.lineItems.single().foodService!!.benefitKind,
        )
        assertEquals(10_000L, controller.state.value.envelope.receipt!!.lineItems.single().netAmountMinor)
        assertNotEquals(originalFingerprint, controller.state.value.canonicalFingerprint)
        assertEquals(sourceBinding, controller.state.value.envelope.source)

        assertTrue(controller.undo())
        assertNull(controller.state.value.envelope.receipt!!.lineItems.single().foodService!!.benefitKind)
        assertTrue(controller.redo())
        assertEquals(
            ReceiptBenefitKind.REVIEW_EVENT,
            controller.state.value.envelope.receipt!!.lineItems.single().foodService!!.benefitKind,
        )

        assertTrue(controller.updateMerchantName("수정 식당"))
        assertEquals("수정 식당", controller.state.value.envelope.receipt!!.merchant.name)
        assertEquals("수정 식당", controller.state.value.envelope.merchantCandidate!!.name)

        val reloaded = CanonicalReviewController(
            controller.state.value.envelope,
            controller.state.value.edits,
            now = { "2026-09-15T12:00:01+09:00" },
        )
        assertEquals("수정 식당", reloaded.state.value.envelope.receipt!!.merchant.name)
        assertEquals(
            ReceiptBenefitKind.REVIEW_EVENT,
            reloaded.state.value.envelope.receipt!!.lineItems.single().foodService!!.benefitKind,
        )
        assertTrue(reloaded.state.value.edits.any { it.fieldPath.endsWith("benefit_kind") })
    }

    @Test
    fun `unchecking review event preserves a different benefit and invalid edit is not saved`() {
        val source = restaurantEnvelope("review-controller-benefit")
        val included = source.copy(
            receipt = source.receipt!!.copy(
                lineItems = source.receipt.lineItems.map { line ->
                    line.copy(foodService = line.foodService!!.copy(benefitKind = ReceiptBenefitKind.INCLUDED))
                },
            ),
        )
        val controller = CanonicalReviewController(included)
        assertTrue(controller.setRestaurantReviewEvent("line-1", false))
        assertEquals(
            ReceiptBenefitKind.INCLUDED,
            controller.state.value.envelope.receipt!!.lineItems.single().foodService!!.benefitKind,
        )

        val beforeInvalid = controller.state.value.envelope
        assertTrue(!controller.updateFoodServiceOptionParent("line-1", "missing-parent"))
        assertEquals(beforeInvalid, controller.state.value.envelope)
        assertTrue(controller.state.value.error!!.contains("option"))
    }

    @Test
    fun `core revision invalidates prior verification and recalculates v2`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val useCase = CanonicalIngestionUseCase(store)
        val imported = useCase.importJson(
            value = readExample(),
            localDocumentId = "revision-controller",
            ingestionId = "revision-controller-ingestion",
        ) as CanonicalImportResult.Success
        val persisted = requireNotNull(useCase.session("revision-controller-ingestion"))
        store.save(
            persisted.copy(
                reviewStatus = IngestionReviewStatus.READY,
                verifiedCanonicalFingerprint = persisted.canonicalFingerprint,
                verifiedAt = "2026-09-15T12:00:00+09:00",
            ),
        )

        val controller = CanonicalReviewController(imported.envelope)
        assertTrue(controller.updateMerchantName("수정 식당"))
        val result = useCase.reviseCanonicalDraft(
            ingestionId = "revision-controller-ingestion",
            envelope = controller.state.value.envelope,
        ) as IngestionStartResult.Success

        assertEquals(2L, result.session.revisionSeq)
        assertNotEquals(persisted.canonicalFingerprint, result.session.canonicalFingerprint)
        assertNull(result.session.verifiedCanonicalFingerprint)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, result.session.reviewStatus)
    }

    @Test
    fun `v2 domain validation rejects food service on a non-product line`() {
        val source = restaurantEnvelope("validator-food-service")
        val invalid = source.copy(
            receipt = source.receipt!!.copy(
                lineItems = source.receipt.lineItems.map { line ->
                    if (line.id == "line-1") line.copy(type = ReceiptLineType.SERVICE) else line
                },
            ),
        )

        val issues = com.pricetrace.receiptscanner.ingestion.CanonicalEnvelopeValidator.validate(invalid)

        assertTrue(issues.single().contains("food_service"))
    }

    private fun restaurantEnvelope(localDocumentId: String) =
        YeonsikOcrV2Json.decode(readExample(), localDocumentId)

    private fun readExample(): String = sequenceOf(
        File("examples", "yeonsik-ocr.v2.restaurant.example.json"),
        File("../examples", "yeonsik-ocr.v2.restaurant.example.json"),
    ).firstOrNull(File::isFile)?.readText() ?: error("restaurant example not found")
}
