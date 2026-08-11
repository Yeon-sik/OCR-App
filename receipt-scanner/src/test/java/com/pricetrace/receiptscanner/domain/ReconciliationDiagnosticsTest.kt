package com.pricetrace.receiptscanner.domain

import com.pricetrace.receiptscanner.SyntheticFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationDiagnosticsTest {
    @Test
    fun `a balanced receipt produces no hypotheses`() {
        val diagnosis = ReconciliationDiagnostics.analyze(SyntheticFixtures.verifiedCandidate())

        assertTrue(diagnosis.reconciliation.isBalanced)
        assertTrue(diagnosis.hypotheses.isEmpty())
    }

    @Test
    fun `quantity times unit price outranks every other explanation of the same gap`() {
        val receipt = SyntheticFixtures.verifiedCandidate().let { base ->
            base.copy(
                lineItems = listOf(
                    base.lineItems.single().copy(
                        quantity = ReceiptQuantity("3", QuantityUnit.EACH),
                        unitPriceAmountMinor = 1_000,
                        grossAmountMinor = 1_000,
                        netAmountMinor = 1_000,
                    ),
                ),
                totals = base.totals.copy(grandTotalAmountMinor = 3_000),
            )
        }

        val top = ReconciliationDiagnostics.analyze(receipt).hypotheses.first()

        assertEquals(ReconciliationHypothesisCode.LINE_CONSERVATION_MISMATCH, top.code)
        assertEquals(
            ReconciliationSuggestion.SetLineNetAmount("line_fixture_001", 3_000),
            top.suggestion,
        )
    }

    @Test
    fun `a confusable digit in one row amount is offered as the closing value`() {
        // 8,000 printed, 3,000 recognized: the gap is only explainable by that row.
        val receipt = SyntheticFixtures.verifiedCandidate().let { base ->
            base.copy(
                lineItems = listOf(
                    base.lineItems.single().copy(
                        quantity = null,
                        unitPriceAmountMinor = null,
                        grossAmountMinor = 3_000,
                        netAmountMinor = 3_000,
                    ),
                ),
                totals = base.totals.copy(grandTotalAmountMinor = 8_000),
            )
        }

        val hypotheses = ReconciliationDiagnostics.analyze(receipt).hypotheses

        val misread = hypotheses.first { it.code == ReconciliationHypothesisCode.LINE_AMOUNT_MISREAD }
        assertEquals(ReconciliationSuggestion.SetLineNetAmount("line_fixture_001", 8_000), misread.suggestion)
        assertTrue(hypotheses.indexOf(misread) < hypotheses.indexOfFirst { it.code == ReconciliationHypothesisCode.MISSING_LINE })
    }

    @Test
    fun `an unrelated gap never produces a misread candidate`() {
        val receipt = SyntheticFixtures.verifiedCandidate(grandTotal = 4_731, netAmount = 1_000)

        val hypotheses = ReconciliationDiagnostics.analyze(receipt).hypotheses

        assertTrue(hypotheses.none { it.code == ReconciliationHypothesisCode.LINE_AMOUNT_MISREAD })
        assertEquals(ReconciliationHypothesisCode.MISSING_LINE, hypotheses.single().code)
        assertEquals(
            ReconciliationSuggestion.AddLineItem(ReceiptLineType.PRODUCT, 3_731),
            hypotheses.single().suggestion,
        )
    }

    @Test
    fun `a duplicated row is offered for removal instead of an amount change`() {
        val receipt = SyntheticFixtures.verifiedCandidate().let { base ->
            val item = base.lineItems.single()
            base.copy(
                lineItems = listOf(
                    item,
                    item.copy(id = "line_fixture_002", sourceLineReferences = listOf("ocr_line_2")),
                ),
                totals = base.totals.copy(grandTotalAmountMinor = 1_000),
            )
        }

        val duplicates = ReconciliationDiagnostics.analyze(receipt).hypotheses
            .filter { it.code == ReconciliationHypothesisCode.DUPLICATE_LINE }

        assertEquals(2, duplicates.size)
        assertEquals(
            setOf(
                ReconciliationSuggestion.RemoveLineItem("line_fixture_001"),
                ReconciliationSuggestion.RemoveLineItem("line_fixture_002"),
            ),
            duplicates.mapNotNull { it.suggestion }.toSet(),
        )
    }

    @Test
    fun `a missing amount blocks the comparison and is reported first`() {
        val receipt = SyntheticFixtures.verifiedCandidate().let { base ->
            base.copy(
                lineItems = listOf(
                    base.lineItems.single().copy(grossAmountMinor = null, netAmountMinor = null),
                ),
            )
        }

        val diagnosis = ReconciliationDiagnostics.analyze(receipt)

        assertNull(diagnosis.reconciliation.differenceMinor)
        assertEquals(ReconciliationHypothesisCode.UNRESOLVED_LINE_AMOUNT, diagnosis.hypotheses.single().code)
        // Quantity and unit price were printed, so the row amount can be offered without guessing.
        assertEquals(
            ReconciliationSuggestion.SetLineNetAmount("line_fixture_001", 1_000),
            diagnosis.hypotheses.single().suggestion,
        )
    }

    @Test
    fun `a gap equal to the printed discount points at the missing adjustment row`() {
        val receipt = SyntheticFixtures.verifiedCandidate().let { base ->
            base.copy(
                totals = base.totals.copy(discountAmountMinor = -500, grandTotalAmountMinor = 500),
            )
        }

        val adjustment = ReconciliationDiagnostics.analyze(receipt).hypotheses
            .single { it.code == ReconciliationHypothesisCode.ADJUSTMENT_ROW_MISSING }

        assertEquals(ReconciliationSuggestion.AddLineItem(ReceiptLineType.DISCOUNT, -500), adjustment.suggestion)
    }
}
