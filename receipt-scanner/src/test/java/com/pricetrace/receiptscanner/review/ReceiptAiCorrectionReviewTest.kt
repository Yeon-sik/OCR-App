package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.SyntheticFixtures
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionCandidate
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionPrompt
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptAiCorrectionReviewTest {
    @Test
    fun `accepted AI suggestion stays draft and records model provenance`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-11T10:00:00+09:00" },
        )
        val candidate = candidate(oldValue = "합성 상품", proposedValue = "합성상품")

        assertTrue(controller.applyCorrectionSuggestion(candidate))

        val state = controller.state.value
        assertEquals("합성상품", state.receipt.lineItems.single().description)
        assertEquals(ConfidenceLevel.LOW, state.receipt.lineItems.single().confidence)
        assertTrue(requireNotNull(state.edits.single().provenanceJson).contains("\"ai_suggestion_accepted\":true"))
        assertTrue(requireNotNull(state.edits.single().provenanceJson).contains("gemini-test"))
    }

    @Test
    fun `stale AI suggestion cannot overwrite a later reviewer edit`() {
        val controller = ReceiptReviewController(SyntheticFixtures.verifiedCandidate())
        controller.updateLineDescription("line_fixture_001", "사용자 수정")

        assertFalse(
            controller.applyCorrectionSuggestion(
                candidate(oldValue = "합성 상품", proposedValue = "AI 수정"),
            ),
        )
        assertEquals("사용자 수정", controller.state.value.receipt.lineItems.single().description)
    }

    private fun candidate(oldValue: String, proposedValue: String) = ReceiptCorrectionCandidate(
        id = "candidate_1",
        fieldPath = "line_items[line_fixture_001].description",
        oldValue = oldValue,
        proposedValue = proposedValue,
        sourceLineIds = listOf("ocr_line_1"),
        confidencePercent = 90,
        reason = "OCR 한 글자 교정",
        providerId = "gemini-test",
        model = "gemini-test-model",
        promptVersion = ReceiptCorrectionPrompt.VERSION,
    )
}
