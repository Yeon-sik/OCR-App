package com.pricetrace.receiptscanner.domain

import com.pricetrace.receiptscanner.SyntheticFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAccuracyCalculatorTest {
    @Test
    fun `field paths map to the groups a reviewer reasons about`() {
        assertEquals(ReviewFieldGroup.MERCHANT_ADDRESS, ReviewFieldGroup.of("merchant.address"))
        assertEquals(ReviewFieldGroup.LINE_DESCRIPTION, ReviewFieldGroup.of("line_items[line_1].description"))
        assertEquals(ReviewFieldGroup.LINE_QUANTITY, ReviewFieldGroup.of("line_items[line_1].quantity"))
        assertEquals(ReviewFieldGroup.LINE_STRUCTURE, ReviewFieldGroup.of("line_items[line_1]"))
        assertEquals(ReviewFieldGroup.OTHER_TOTALS, ReviewFieldGroup.of("totals.tax_amount_minor"))
        assertEquals(ReviewFieldGroup.PAYMENT, ReviewFieldGroup.of("payments[0].method"))
        assertNull(ReviewFieldGroup.of("review.reconciliation_reason"))
    }

    @Test
    fun `a corrected address counts once and is measured in characters`() {
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = withAddress("서울특별시 강남구 테헤란로 1"),
                    corrections = listOf(
                        correction("merchant.address", "서울특별시 강남구 테헤란로 l", "서울특별시 강남구 테헤란로 1"),
                    ),
                ),
            ),
        )

        val address = summary.field(ReviewFieldGroup.MERCHANT_ADDRESS)
        assertEquals(1, address.observedCount)
        assertEquals(1, address.misreadCount)
        assertEquals(1.0, requireNotNull(address.errorRate), 0.0001)
        assertEquals(1.0, requireNotNull(address.averageEditDistance), 0.0001)
    }

    @Test
    fun `an edit that was undone is not counted as a recognition error`() {
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = withAddress("서울 강남"),
                    corrections = listOf(
                        correction("merchant.address", "서울 강남", "오타", editedAt = "2026-08-09T10:00:00+09:00"),
                        correction("merchant.address", "오타", "서울 강남", editedAt = "2026-08-09T10:00:01+09:00"),
                    ),
                ),
            ),
        )

        assertEquals(0, summary.field(ReviewFieldGroup.MERCHANT_ADDRESS).correctedCount)
        assertEquals(0.0, summary.correctionsPerReceipt, 0.0001)
    }

    @Test
    fun `retyping one field several times counts as a single error`() {
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = withAddress("서울 강남구"),
                    corrections = listOf(
                        correction("merchant.address", "서울 강남ㄱ", "서울 강남", editedAt = "2026-08-09T10:00:00+09:00"),
                        correction("merchant.address", "서울 강남", "서울 강남구", editedAt = "2026-08-09T10:00:05+09:00"),
                    ),
                ),
            ),
        )

        assertEquals(1, summary.field(ReviewFieldGroup.MERCHANT_ADDRESS).misreadCount)
    }

    @Test
    fun `missed values, misread values and invented values are separated`() {
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = SyntheticFixtures.verifiedCandidate(),
                    corrections = listOf(
                        // OCR produced nothing for the branch, so the reviewer supplied it.
                        correction("merchant.branch_name", null, "서울점"),
                        // OCR invented a phone number that was not printed.
                        correction("merchant.phone", "02-000-0000", null),
                        correction("line_items[line_fixture_001].quantity", "2", "1"),
                    ),
                ),
            ),
        )

        assertEquals(1, summary.field(ReviewFieldGroup.MERCHANT_BRANCH).missedCount)
        val phone = summary.field(ReviewFieldGroup.PHONE)
        assertEquals(1, phone.spuriousCount)
        // The invented value is missing from the confirmed receipt, so it is added back as an attempt.
        assertEquals(1, phone.observedCount)
        assertEquals(1, summary.field(ReviewFieldGroup.LINE_QUANTITY).misreadCount)
        assertEquals(3.0, summary.correctionsPerReceipt, 0.0001)
    }

    @Test
    fun `line fields are measured against the number of rows, not receipts`() {
        val base = SyntheticFixtures.verifiedCandidate()
        val twoRows = base.copy(
            lineItems = base.lineItems + base.lineItems.single().copy(
                id = "line_fixture_002",
                sourceLineReferences = listOf("ocr_line_2"),
            ),
        )
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = twoRows,
                    corrections = listOf(
                        correction("line_items[line_fixture_001].description", "합성 상풍", "합성 상품"),
                    ),
                ),
            ),
        )

        val description = summary.field(ReviewFieldGroup.LINE_DESCRIPTION)
        assertEquals(2, description.observedCount)
        assertEquals(0.5, requireNotNull(description.errorRate), 0.0001)
    }

    @Test
    fun `a deleted row is an attempt the recognizer should not have made`() {
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = SyntheticFixtures.verifiedCandidate(),
                    corrections = listOf(
                        correction("line_items[line_removed]", "{\"id\":\"line_removed\"}", null),
                    ),
                ),
            ),
        )

        val structure = summary.field(ReviewFieldGroup.LINE_STRUCTURE)
        assertEquals(1, structure.spuriousCount)
        // One surviving row plus the row that should never have existed.
        assertEquals(2, structure.observedCount)
    }

    @Test
    fun `review duration uses the median and skips receipts without a recorded start`() {
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = SyntheticFixtures.verifiedCandidate(),
                    ocrCompletedAt = "2026-08-09T10:00:00+09:00",
                    reviewedAt = "2026-08-09T10:02:00+09:00",
                ),
                sample(
                    receipt = SyntheticFixtures.verifiedCandidate(),
                    ocrCompletedAt = "2026-08-09T11:00:00+09:00",
                    reviewedAt = "2026-08-09T11:08:00+09:00",
                ),
                sample(receipt = SyntheticFixtures.verifiedCandidate(), ocrCompletedAt = null),
            ),
        )

        assertEquals(2, summary.timedSampleCount)
        assertEquals(300L, summary.medianReviewSeconds)
        assertEquals(3, summary.sampleCount)
    }

    @Test
    fun `fields are ranked by how much review work they caused`() {
        val base = SyntheticFixtures.verifiedCandidate()
        val rows = (1..4).map { index ->
            base.lineItems.single().copy(id = "line_$index", sourceLineReferences = listOf("ocr_line_$index"))
        }
        val summary = ReviewAccuracyCalculator.summarize(
            listOf(
                sample(
                    receipt = withAddress("서울 강남").copy(lineItems = rows),
                    corrections = rows.map { row ->
                        correction("line_items[${row.id}].description", "틀림", "맞음")
                    } + correction("merchant.address", "서울 강남구", "서울 강남"),
                ),
            ),
        )

        assertEquals(ReviewFieldGroup.LINE_DESCRIPTION, summary.worstFields.first().group)
        assertTrue(summary.worstFields.any { it.group == ReviewFieldGroup.MERCHANT_ADDRESS })
    }

    private fun ReviewAccuracySummary.field(group: ReviewFieldGroup): ReviewFieldAccuracy =
        fields.single { it.group == group }

    private fun withAddress(address: String): ReceiptV2 = SyntheticFixtures.verifiedCandidate().let { base ->
        base.copy(merchant = base.merchant.copy(address = address))
    }

    private fun correction(
        fieldPath: String,
        previousValue: String?,
        newValue: String?,
        editedAt: String = "2026-08-09T10:00:00+09:00",
    ) = FieldCorrection(fieldPath, previousValue, newValue, editedAt)

    private fun sample(
        receipt: ReceiptV2,
        corrections: List<FieldCorrection> = emptyList(),
        ocrCompletedAt: String? = "2026-08-09T10:00:00+09:00",
        reviewedAt: String? = "2026-08-09T10:05:00+09:00",
    ) = ReviewedReceiptSample(
        documentId = receipt.document.id,
        parserVersion = "generic-parser.v15",
        receipt = receipt,
        corrections = corrections,
        ocrCompletedAt = ocrCompletedAt,
        reviewedAt = reviewedAt,
    )
}
