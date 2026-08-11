package com.pricetrace.receiptscanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptEvaluationTest {
    @Test
    fun `levenshtein distance counts insert delete and substitute`() {
        assertEquals(3, ReceiptEvaluationCalculator.levenshteinDistance("kitten", "sitting"))
        assertEquals(1, ReceiptEvaluationCalculator.levenshteinDistance("영수증", "영수즙"))
    }

    @Test
    fun `summary uses aggregate denominators instead of averaging per-sample ratios`() {
        val summary = ReceiptEvaluationCalculator.summarize(
            listOf(
                sample(expectedText = "abcd", recognizedText = "abxd", expectedLines = 2, parsedLines = 4, matched = 2),
                sample(expectedText = "ab", recognizedText = "ab", expectedLines = 1, parsedLines = 1, matched = 1),
            ),
        )

        assertEquals(1.0 / 6.0, summary.characterErrorRate!!, 0.0001)
        assertEquals(3.0 / 5.0, summary.linePrecision!!, 0.0001)
        assertEquals(1.0, summary.lineRecall!!, 0.0001)
        assertEquals(0.5, summary.automaticBoundaryDetectionRate, 0.0001)
    }

    @Test
    fun `undefined denominators remain null instead of claiming perfect accuracy`() {
        val summary = ReceiptEvaluationCalculator.summarize(
            listOf(
                sample(expectedText = "", recognizedText = "noise", expectedLines = 0, parsedLines = 0, matched = 0),
            ),
        )

        assertNull(summary.characterErrorRate)
        assertNull(summary.linePrecision)
        assertNull(summary.lineRecall)
        assertNull(summary.skuAccuracy)
    }

    private fun sample(
        expectedText: String,
        recognizedText: String,
        expectedLines: Int,
        parsedLines: Int,
        matched: Int,
    ) = ReceiptEvaluationSample(
        boundaryDetectedAutomatically = expectedLines > 1,
        boundaryCorrectedManually = false,
        expectedText = expectedText,
        recognizedText = recognizedText,
        expectedMerchant = "합성상점",
        parsedMerchant = "합성상점",
        expectedDate = "2026-08-03",
        parsedDate = "2026-08-03",
        expectedGrandTotalMinor = 1_000,
        parsedGrandTotalMinor = 1_000,
        expectedLineCount = expectedLines,
        parsedLineCount = parsedLines,
        matchedLineCount = matched,
        expectedSkuCount = 0,
        matchedSkuCount = 0,
        reconciliationSucceeded = true,
        processingTimeMillis = 500,
        userModifiedFieldCount = 1,
    )
}
