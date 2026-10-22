package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopReviewBulkApplyTest {
    private suspend fun controller(): DesktopIngestionController {
        val store = DesktopSessionStore(Files.createTempDirectory("review-bulk"))
        return DesktopIngestionController(store, DesktopProjectionBundle(
            DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("none.env")))).also {
            it.importJson(File("../examples/yeonsik-ocr.v4.purchase.example.json").readText())
        }
    }
    private val quantity = "purchase_records[purchase-1].line_items[0].quantity"
    private val description = "purchase_records[purchase-1].line_items[0].description"
    private val price = "purchase_records[purchase-1].line_items[0].unit_price_amount_krw"
    private val option = "purchase_records[purchase-1].line_items[0].option_items.new_option"

    @Test fun mixedValidAndInvalidInputsDoNotPartiallyPersistAndCanBeRetried() = runBlocking {
        val c = controller()
        val original = c.state.value.canonicalJson
        val edits = c.state.value.reviewEdits
        c.updateReviewDraft(description, "일괄 수정 상품")
        c.updateReviewDraft(quantity, "bad-number")
        c.updateReviewDraft(price, "also-bad")
        c.applyAllReviewDrafts()
        assertEquals(original, c.state.value.canonicalJson)
        assertEquals(edits, c.state.value.reviewEdits)
        assertEquals(3, c.state.value.reviewDrafts.size)
        assertTrue(c.state.value.reviewFieldErrors.keys.containsAll(listOf(quantity, price)))
        c.updateReviewDraft(quantity, "4")
        c.updateReviewDraft(price, "275")
        c.applyAllReviewDrafts()
        assertTrue(c.state.value.error, c.state.value.reviewDrafts.isEmpty())
        assertTrue(c.state.value.reviewFieldErrors.isEmpty())
        assertEquals("일괄 수정 상품", c.state.value.envelope!!.purchaseRecords.single().lineItems.single().description)
        assertEquals(4.0, com.pricetrace.receiptscanner.review.CanonicalFieldRegistry.value(c.state.value.envelope!!, quantity)!!.toDouble(), 0.0)
    }

    @Test fun oneUndoAndRedoCoverTheEntireAppliedBatch() = runBlocking {
        val c = controller()
        val original = c.state.value.envelope!!
        c.verify(VerificationBasis.MANUAL_CANONICAL_REVIEW)
        c.updateReviewDraft(description, "한 번에 적용")
        c.updateReviewDraft(quantity, "4")
        c.updateReviewDraft(price, "275")
        c.applyAllReviewDrafts()
        assertNull(c.state.value.session!!.verifiedCanonicalFingerprint)
        val revised = c.state.value.envelope!!
        c.reviseStructuredReview { it.undo() }
        assertEquals(original.purchaseRecords, c.state.value.envelope!!.purchaseRecords)
        c.reviseStructuredReview { it.redo() }
        assertEquals(revised.purchaseRecords, c.state.value.envelope!!.purchaseRecords)
    }

    @Test fun pendingOptionCreationAndFieldChangesAreOneAtomicBatch() = runBlocking {
        val c = controller()
        val original = c.state.value.canonicalJson
        c.updateReviewDraft(option, "새 옵션")
        c.updateReviewDraft(quantity, "invalid")
        c.applyAllReviewDrafts()
        assertEquals(original, c.state.value.canonicalJson)
        c.updateReviewDraft(quantity, "2")
        c.applyAllReviewDrafts()
        assertTrue(c.state.value.reviewDrafts.isEmpty())
        assertEquals("새 옵션", c.state.value.envelope!!.purchaseRecords.single().lineItems.single().optionItems.last().description)
    }

    @Test fun discardAllClearsRejectedInputsWithoutChangingStoredFacts() = runBlocking {
        val c = controller()
        val original = c.state.value.canonicalJson
        c.updateReviewDraft(quantity, "invalid")
        c.applyAllReviewDrafts()
        c.discardAllReviewDrafts()
        assertTrue(c.state.value.reviewDrafts.isEmpty())
        assertTrue(c.state.value.reviewFieldErrors.isEmpty())
        assertEquals(original, c.state.value.canonicalJson)
    }

    @Test fun coupledTotalsAndLineAmountsValidateOnlyAfterAllValuesAreStaged() = runBlocking {
        val c = controller()
        val prefix = "purchase_records[purchase-1]"
        mapOf(
            "$prefix.totals.subtotal_amount_krw" to "1000",
            "$prefix.totals.grand_total_amount_krw" to "1000",
            "$prefix.totals.paid_amount_krw" to "1000",
            price to "500",
            "$prefix.line_items[0].gross_amount_krw" to "1000",
            "$prefix.line_items[0].net_amount_krw" to "1000",
        ).forEach { (path, value) -> c.updateReviewDraft(path, value) }
        c.applyAllReviewDrafts()
        assertTrue(c.state.value.error, c.state.value.reviewDrafts.isEmpty())
        assertEquals(1000L, c.state.value.envelope!!.purchaseRecords.single().totals.paidAmountKrw)
        assertEquals(500L, c.state.value.envelope!!.purchaseRecords.single().lineItems.single().unitPriceAmountKrw)
    }
}
