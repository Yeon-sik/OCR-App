package com.pricetrace.receiptscanner.domain

import com.pricetrace.receiptscanner.SyntheticFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptValidationTest {
    @Test
    fun `complete balanced candidate can be marked user verified`() {
        val result = ReceiptValidator.validateForUserVerification(SyntheticFixtures.verifiedCandidate())
        assertTrue(result.canMarkUserVerified)
        assertTrue(result.reconciliation.isBalanced)
        assertTrue(ReceiptValidator.markUserVerified(SyntheticFixtures.verifiedCandidate()).isSuccess)
    }

    @Test
    fun `missing required values and conservation mismatch block verification`() {
        val invalid = SyntheticFixtures.verifiedCandidate().copy(
            merchant = ReceiptMerchant(name = null, branchName = null),
            lineItems = SyntheticFixtures.verifiedCandidate().lineItems.map {
                it.copy(quantity = null, netAmountMinor = 900)
            },
        )
        val result = ReceiptValidator.validateForUserVerification(invalid)
        assertFalse(result.canMarkUserVerified)
        assertTrue(result.issues.any { it.code == ValidationCode.MERCHANT_MISSING })
        assertTrue(result.issues.any { it.code == ValidationCode.ITEM_QUANTITY_MISSING })
    }

    @Test
    fun `explicit reason only waives total reconciliation mismatch`() {
        val mismatch = SyntheticFixtures.verifiedCandidate(grandTotal = 900, netAmount = 1000)
        assertFalse(ReceiptValidator.validateForUserVerification(mismatch).canMarkUserVerified)
        assertTrue(
            ReceiptValidator.validateForUserVerification(mismatch, "영수증 인쇄 반올림 확인").canMarkUserVerified,
        )
    }

    @Test
    fun `reconciliation overflow is reported as uncomputable instead of crashing`() {
        val first = SyntheticFixtures.verifiedCandidate().lineItems.single().copy(
            netAmountMinor = Long.MAX_VALUE,
        )
        val second = first.copy(
            id = "line_fixture_002",
            sourceLineReferences = listOf("ocr_line_2"),
            netAmountMinor = 1,
        )
        val receipt = SyntheticFixtures.verifiedCandidate().copy(lineItems = listOf(first, second))

        val result = ReceiptReconciler.reconcile(receipt)

        assertNull(result.lineNetTotalMinor)
        assertNull(result.differenceMinor)
    }

    @Test
    fun `invalid manual date and zero quantity block while missing unit price is allowed`() {
        val base = SyntheticFixtures.verifiedCandidate()
        val invalid = base.copy(
            document = base.document.copy(issuedOn = "2026-02-30"),
            lineItems = base.lineItems.map {
                it.copy(quantity = ReceiptQuantity("0"), unitPriceAmountMinor = null)
            },
        )

        val result = ReceiptValidator.validateForUserVerification(invalid, "합계는 원본과 대조함")

        assertTrue(result.issues.any { it.code == ValidationCode.PURCHASE_DATE_INVALID })
        assertTrue(result.issues.any { it.code == ValidationCode.ITEM_QUANTITY_INVALID })
        assertTrue(result.issues.none { it.code == ValidationCode.ITEM_UNIT_PRICE_MISSING })
    }

    @Test
    fun `a user transcribed row is flagged but does not block verification`() {
        val base = SyntheticFixtures.verifiedCandidate()
        val transcribed = base.copy(
            lineItems = base.lineItems.map {
                it.copy(sourceLineReferences = listOf(userEnteredSourceReference(it.id)))
            },
        )

        val result = ReceiptValidator.validateForUserVerification(transcribed)

        assertTrue(result.canMarkUserVerified)
        val issue = result.issues.single { it.code == ValidationCode.ITEM_WITHOUT_OCR_EVIDENCE }
        assertEquals(ValidationSeverity.WARNING, issue.severity)
    }

    @Test
    fun `a row with no reference at all still blocks verification`() {
        val base = SyntheticFixtures.verifiedCandidate()
        val orphaned = base.copy(lineItems = base.lineItems.map { it.copy(sourceLineReferences = emptyList()) })

        val result = ReceiptValidator.validateForUserVerification(orphaned)

        assertFalse(result.canMarkUserVerified)
        assertTrue(result.issues.any { it.code == ValidationCode.SOURCE_REFERENCE_MISSING })
    }

    @Test
    fun `progress separates rows needing attention from settled ones`() {
        val base = SyntheticFixtures.verifiedCandidate()
        val settled = base.lineItems.single()
        val lowConfidence = settled.copy(
            id = "line_fixture_002",
            sourceLineReferences = listOf(userEnteredSourceReference("line_fixture_002")),
            confidence = ConfidenceLevel.LOW,
        )
        val receipt = base.copy(
            lineItems = listOf(settled, lowConfidence),
            totals = base.totals.copy(grandTotalAmountMinor = 2_000),
        )

        val progress = ReceiptReviewProgress.of(
            receipt,
            ReceiptValidator.validateForUserVerification(receipt),
        )

        assertEquals(listOf("line_fixture_002"), progress.attentionLineItemIds)
        assertEquals(1, progress.settledLineItemCount)
        assertEquals(0.5, progress.lineCompletionRatio, 0.0001)
        assertEquals(1, progress.userEnteredLineItemCount)
        assertTrue(progress.canMarkUserVerified)
        assertTrue(progress.isBalanced)
    }

    @Test
    fun `empty line list cannot be waived with a reconciliation reason`() {
        val empty = SyntheticFixtures.verifiedCandidate().copy(lineItems = emptyList())

        val result = ReceiptValidator.validateForUserVerification(empty, "행을 읽을 수 없음")

        assertFalse(result.canMarkUserVerified)
        assertTrue(result.issues.any { it.code == ValidationCode.ITEMS_MISSING })
    }
}
