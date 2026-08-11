package com.pricetrace.receiptscanner.correction

import com.pricetrace.receiptscanner.SyntheticFixtures
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.ReceiptV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptCorrectionPolicyTest {
    private val ocrDocument = SyntheticFixtures.ocrDocument()

    @Test
    fun `request contains only line item evidence instead of full receipt OCR`() {
        val request = ReceiptCorrectionRequestFactory.create(
            correctionReceipt(),
            ocrDocument,
        )

        assertEquals(listOf("line_fixture_001"), request.targets.map { it.lineItemId })
        assertEquals(listOf("ocr_line_4"), request.evidenceLines.map { it.id })
        assertFalse(request.evidenceLines.any { it.text == ocrDocument.rawText })
    }

    @Test
    fun `request excludes sensitive evidence and user verified rows`() {
        val sensitiveRequest = ReceiptCorrectionRequestFactory.create(
            SyntheticFixtures.verifiedCandidate(sourceReferences = listOf("ocr_line_1")),
            ocrDocument,
        )
        val verifiedReceipt = correctionReceipt().copy(
            lineItems = correctionReceipt().lineItems.map { it.copy(confidence = ConfidenceLevel.USER_VERIFIED) },
        )
        val verifiedRequest = ReceiptCorrectionRequestFactory.create(verifiedReceipt, ocrDocument)

        assertTrue(sensitiveRequest.targets.isEmpty())
        assertTrue(sensitiveRequest.evidenceLines.isEmpty())
        assertTrue(verifiedRequest.targets.isEmpty())
    }

    @Test
    fun `supported description correction with matching evidence is accepted`() {
        val candidate = candidate(
            fieldPath = "line_items[line_fixture_001].description",
            oldValue = "합성 상품",
            proposedValue = "합성상품",
        )

        assertNull(
            ReceiptCorrectionPolicy.rejectionReason(
                correctionReceipt(),
                ocrDocument,
                candidate,
            ),
        )
    }

    @Test
    fun `candidate cannot change totals or cite an unrelated source line`() {
        val receipt = correctionReceipt()
        val totalCandidate = candidate(
            fieldPath = "totals.grand_total_amount_minor",
            oldValue = "1000",
            proposedValue = "9000",
        )
        val unrelatedCandidate = candidate(
            fieldPath = "line_items[line_fixture_001].description",
            oldValue = "합성 상품",
            proposedValue = "다른 상품",
            sourceLineIds = listOf("ocr_line_2"),
        )

        assertEquals(
            ReceiptCorrectionRejectionReason.UNSUPPORTED_FIELD,
            ReceiptCorrectionPolicy.rejectionReason(receipt, ocrDocument, totalCandidate),
        )
        assertEquals(
            ReceiptCorrectionRejectionReason.UNRELATED_SOURCE_EVIDENCE,
            ReceiptCorrectionPolicy.rejectionReason(receipt, ocrDocument, unrelatedCandidate),
        )
    }

    @Test
    fun `stale old value and arithmetic breaking quantity are rejected`() {
        val receipt = correctionReceipt().copy(
            lineItems = correctionReceipt().lineItems.map {
                it.copy(quantity = ReceiptQuantity("2"), unitPriceAmountMinor = 500, netAmountMinor = 1_000)
            },
        )
        val stale = candidate(
            fieldPath = "line_items[line_fixture_001].quantity",
            oldValue = "1",
            proposedValue = "2",
        )
        val breaksConservation = candidate(
            fieldPath = "line_items[line_fixture_001].quantity",
            oldValue = "2",
            proposedValue = "3",
        )

        assertEquals(
            ReceiptCorrectionRejectionReason.STALE_OLD_VALUE,
            ReceiptCorrectionPolicy.rejectionReason(receipt, ocrDocument, stale),
        )
        assertEquals(
            ReceiptCorrectionRejectionReason.AMOUNT_CONSERVATION_FAILED,
            ReceiptCorrectionPolicy.rejectionReason(receipt, ocrDocument, breaksConservation),
        )
    }

    @Test
    fun `duplicate field suggestions keep only the first valid candidate`() {
        val first = candidate(
            fieldPath = "line_items[line_fixture_001].description",
            oldValue = "합성 상품",
            proposedValue = "합성상품",
        )
        val second = first.copy(id = "candidate_2", proposedValue = "두번째 상품")

        val result = ReceiptCorrectionPolicy.validateBatch(
            correctionReceipt(),
            ocrDocument,
            listOf(first, second),
        )

        assertEquals(listOf(first), result.accepted)
        assertEquals(ReceiptCorrectionRejectionReason.DUPLICATE_FIELD, result.rejected.single().reason)
    }

    @Test
    fun `prompt prohibits unsupported mutations and contains no receipt raw text`() {
        val request = ReceiptCorrectionRequestFactory.create(
            correctionReceipt(),
            ocrDocument,
        )
        val prompt = ReceiptCorrectionPrompt.build(request)

        assertTrue(prompt.contains("Never add or remove a line item"))
        assertTrue(prompt.contains("line_items[<lineItemId>].description"))
        assertFalse(prompt.contains(ocrDocument.rawText))
    }

    @Test
    fun `every cited source line must belong to the corrected row`() {
        val candidate = candidate(
            fieldPath = "line_items[line_fixture_001].description",
            oldValue = "합성 상품",
            proposedValue = "합성상품",
            sourceLineIds = listOf("ocr_line_4", "ocr_line_2"),
        )

        assertEquals(
            ReceiptCorrectionRejectionReason.UNRELATED_SOURCE_EVIDENCE,
            ReceiptCorrectionPolicy.rejectionReason(correctionReceipt(), ocrDocument, candidate),
        )
    }

    private fun correctionReceipt(): ReceiptV2 = SyntheticFixtures.verifiedCandidate(
        sourceReferences = listOf("ocr_line_4"),
    )

    private fun candidate(
        fieldPath: String,
        oldValue: String?,
        proposedValue: String,
        sourceLineIds: List<String> = listOf("ocr_line_4"),
    ) = ReceiptCorrectionCandidate(
        id = "candidate_1",
        fieldPath = fieldPath,
        oldValue = oldValue,
        proposedValue = proposedValue,
        sourceLineIds = sourceLineIds,
        confidencePercent = 85,
        reason = "합성 근거",
        providerId = "test",
        model = "test-model",
        promptVersion = ReceiptCorrectionPrompt.VERSION,
    )
}
