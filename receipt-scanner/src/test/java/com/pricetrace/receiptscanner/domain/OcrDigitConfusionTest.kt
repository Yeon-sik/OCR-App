package com.pricetrace.receiptscanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrDigitConfusionTest {
    @Test
    fun `shape confusions are separated from arbitrary substitutions`() {
        assertEquals(MisreadKind.CONFUSABLE_DIGIT, OcrDigitConfusion.classify(3_000, 8_000))
        assertEquals(MisreadKind.CONFUSABLE_DIGIT, OcrDigitConfusion.classify(1_500, 1_600))
        assertEquals(MisreadKind.SUBSTITUTED_DIGIT, OcrDigitConfusion.classify(1_500, 1_700))
        assertEquals(MisreadKind.SUBSTITUTED_DIGIT, OcrDigitConfusion.classify(1_000, 2_000))
    }

    @Test
    fun `lost and added digits are recognized in both directions`() {
        assertEquals(MisreadKind.DROPPED_DIGIT, OcrDigitConfusion.classify(1_000, 10_000))
        assertEquals(MisreadKind.INSERTED_DIGIT, OcrDigitConfusion.classify(10_000, 1_000))
        assertEquals(MisreadKind.DROPPED_DIGIT, OcrDigitConfusion.classify(900, 3_900))
    }

    @Test
    fun `neighbouring digits swapped are reported as a transposition`() {
        assertEquals(MisreadKind.TRANSPOSED_DIGITS, OcrDigitConfusion.classify(12_500, 21_500))
    }

    @Test
    fun `unrelated amounts and sign changes are never called a misreading`() {
        assertEquals(MisreadKind.NONE, OcrDigitConfusion.classify(1_000, 4_731))
        assertEquals(MisreadKind.NONE, OcrDigitConfusion.classify(1_000, -1_000))
        assertEquals(MisreadKind.NONE, OcrDigitConfusion.classify(1_000, 1_000))
        assertEquals(MisreadKind.NONE, OcrDigitConfusion.classify(1_000, 123_456))
    }

    @Test
    fun `negative discount amounts are compared on their digits`() {
        assertEquals(MisreadKind.CONFUSABLE_DIGIT, OcrDigitConfusion.classify(-500, -600))
    }
}
