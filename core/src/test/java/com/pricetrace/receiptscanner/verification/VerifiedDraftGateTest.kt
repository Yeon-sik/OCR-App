package com.pricetrace.receiptscanner.verification

import com.pricetrace.receiptscanner.input.InputOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedDraftGateTest {
    @Test
    fun `external draft needs at least one local page`() {
        val result = VerifiedDraftGate.evaluate(InputOrigin.EXTERNAL_JSON, 0, false)
        assertEquals(VerifiedDraftGateFailure.SOURCE_IMAGE_REQUIRED, result.failure)
    }

    @Test
    fun `external draft needs every local page file to be readable`() {
        val result = VerifiedDraftGate.evaluate(InputOrigin.EXTERNAL_JSON, 1, false)
        assertEquals(VerifiedDraftGateFailure.SOURCE_IMAGE_UNREADABLE, result.failure)
    }

    @Test
    fun `external draft passes with readable local evidence`() {
        val result = VerifiedDraftGate.evaluate(InputOrigin.EXTERNAL_JSON, 1, true)
        assertTrue(result.isAllowed)
    }

    @Test
    fun `android OCR keeps existing gate policy`() {
        val result = VerifiedDraftGate.evaluate(InputOrigin.ANDROID_OCR, 0, false)
        assertTrue(result.isAllowed)
    }
}