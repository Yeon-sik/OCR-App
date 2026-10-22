package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.*
import com.pricetrace.receiptscanner.review.PurchaseOptionListMutation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class DesktopReviewDraftTest {
    private fun controller(): DesktopIngestionController {
        val store = DesktopSessionStore(Files.createTempDirectory("review-drafts"))
        return DesktopIngestionController(store, DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("none.env"))))
    }
    private fun example(name: String) = File("../examples/$name").readText()

    @Test fun unappliedValueBlocksApprovalAndDeliveryAtTheControllerBoundary() = runBlocking {
        val c = controller()
        c.importJson(example("yeonsik-ocr.v3.text-only-retail.example.json"))
        val path = "product_candidates[product-brandx-chicken-20260910].product_name"
        c.updateReviewDraft(path, "미적용 상품명")
        c.verify(VerificationBasis.MANUAL_CANONICAL_REVIEW)
        assertNull(c.state.value.session!!.verifiedCanonicalFingerprint)
        assertTrue(c.state.value.error.orEmpty().contains("미적용"))
        c.submit()
        assertTrue(c.state.value.error.orEmpty().contains("미적용"))
        c.applyReviewDraft(path) { it.updateField(path, "미적용 상품명") }
        assertTrue(c.state.value.reviewDrafts.isEmpty())
        assertEquals("미적용 상품명", c.state.value.envelope!!.productCandidates.single().productName)
        assertTrue(c.state.value.reviewEdits.isNotEmpty())
        c.verify(VerificationBasis.MANUAL_CANONICAL_REVIEW)
        assertEquals(c.state.value.session!!.canonicalFingerprint, c.state.value.session!!.verifiedCanonicalFingerprint)
        c.updateReviewDraft(path, "확정 이후 미적용")
        assertNotNull(reviewConfirmationBlocker(c.state.value, false, true))
        c.submit()
        assertTrue(c.state.value.error.orEmpty().contains("미적용"))
    }

    @Test fun failedValidationKeepsDraftAndApplyingOneFieldKeepsAnotherPending() = runBlocking {
        val c = controller()
        c.importJson(example("yeonsik-ocr.v4.purchase.example.json"))
        val path = "purchase_records[purchase-1].line_items[0].quantity"
        val name = "purchase_records[purchase-1].line_items[0].description"
        c.updateReviewDraft(path, "not-a-number")
        c.updateReviewDraft(name, "수정 상품")
        c.applyReviewDraft(path) { it.updateField(path, "not-a-number") }
        assertEquals("not-a-number", c.state.value.reviewDrafts[path])
        assertTrue(c.state.value.reviewFieldErrors.isNotEmpty())
        c.applyReviewDraft(name) { it.updateField(name, "수정 상품") }
        assertFalse(c.state.value.reviewDrafts.containsKey(name))
        assertEquals("not-a-number", c.state.value.reviewDrafts[path])
    }

    @Test fun normalizedNoOpClearsOnlyAppliedDraftAndDraftSurvivesSessionRoundTrip() = runBlocking {
        val c = controller()
        c.importJson(example("yeonsik-ocr.v3.text-only-retail.example.json"))
        val id = c.state.value.ingestionId!!
        val path = "product_candidates[product-brandx-chicken-20260910].product_name"
        val original = c.state.value.envelope!!.productCandidates.single().productName
        c.updateReviewDraft(path, " $original ")
        c.applyReviewDraft(path) { it.updateField(path, " $original ") }
        assertTrue(c.state.value.reviewDrafts.isEmpty())
        c.updateReviewDraft(path, "다른 문서에서도 보존")
        c.importJson(example("yeonsik-ocr.v3.merchant.example.json"))
        assertTrue(c.state.value.reviewDrafts.isEmpty())
        c.load(id)
        assertEquals("다른 문서에서도 보존", c.state.value.reviewDrafts[path])
        c.discardReviewDraft(path)
        assertTrue(c.state.value.reviewDrafts.isEmpty())
        assertEquals(original, c.state.value.envelope!!.productCandidates.single().productName)
    }

    @Test fun newPurchaseOptionAlsoCountsAsUnappliedInput() = runBlocking {
        val c = controller()
        c.importJson(example("yeonsik-ocr.v4.purchase.example.json"))
        val path = "purchase_records[purchase-1].line_items[0].option_items.new_option"
        c.updateReviewDraft(path, "추가 옵션")
        assertNotNull(reviewConfirmationBlocker(c.state.value, false, true))
        c.applyReviewDraft(path) { it.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("추가 옵션")) }
        assertTrue(c.state.value.reviewDrafts.isEmpty())
        assertEquals("추가 옵션", c.state.value.envelope!!.purchaseRecords.single().lineItems.single().optionItems.last().description)
    }
}
