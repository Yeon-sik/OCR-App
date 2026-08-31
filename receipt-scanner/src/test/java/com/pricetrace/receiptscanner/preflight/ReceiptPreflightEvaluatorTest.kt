package com.pricetrace.receiptscanner.preflight

import com.pricetrace.receiptscanner.SyntheticFixtures
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceAssessment
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceVerdict
import com.pricetrace.receiptscanner.correction.ReceiptFieldCheck
import com.pricetrace.receiptscanner.correction.ReceiptFieldVerdict
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.ReceiptValidator
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPreflightEvaluatorTest {
    private val receipt = SyntheticFixtures.verifiedCandidate()
    private val validation = ReceiptValidator.validateForUserVerification(receipt)

    @Test
    fun `valid OCR asks for AI review before user verification`() {
        val decision = evaluate(aiStatus = ReceiptAiReviewStatus.NOT_REQUESTED)

        assertEquals(ReceiptPreflightRoute.REQUEST_AI_REVIEW, decision.route)
        assertEquals(listOf(ReceiptPreflightReason.AI_REVIEW_NOT_RUN), decision.reasons)
    }

    @Test
    fun `missing OCR evidence recommends recapture`() {
        val decision = evaluate(
            ocrDocument = OcrDocument("empty", "", emptyList(), OcrEngineInfo("test", "1")),
            aiStatus = ReceiptAiReviewStatus.NOT_REQUESTED,
        )

        assertEquals(ReceiptPreflightRoute.RECAPTURE_RECOMMENDED, decision.route)
        assertTrue(ReceiptPreflightReason.OCR_EVIDENCE_MISSING in decision.reasons)
    }

    @Test
    fun `provider failure with clear capture falls back to manual review instead of recapture`() {
        val decision = evaluate(aiStatus = ReceiptAiReviewStatus.FAILED)

        assertEquals(ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED, decision.route)
        assertTrue(ReceiptPreflightReason.AI_REVIEW_FAILED in decision.reasons)
    }

    @Test
    fun `plausible AI evidence and deterministic checks allow user verification request`() {
        val decision = evaluate(
            aiStatus = ReceiptAiReviewStatus.COMPLETED,
            assessment = ReceiptEvidenceAssessment(ReceiptEvidenceVerdict.PLAUSIBLE),
        )

        assertEquals(ReceiptPreflightRoute.READY_FOR_USER_VERIFICATION, decision.route)
        assertEquals(listOf(ReceiptPreflightReason.AI_EVIDENCE_PLAUSIBLE), decision.reasons)
    }

    @Test
    fun wrongMerchantFieldTypeRequiresManualReview() {
        val decision = evaluate(
            aiStatus = ReceiptAiReviewStatus.COMPLETED,
            assessment = ReceiptEvidenceAssessment(
                verdict = ReceiptEvidenceVerdict.PLAUSIBLE,
                merchantVerdict = ReceiptEvidenceVerdict.PLAUSIBLE,
                fieldChecks = listOf(
                    ReceiptFieldCheck(
                        fieldPath = "merchant.address",
                        verdict = ReceiptFieldVerdict.WRONG_FIELD_TYPE,
                        sourceLineIds = listOf("ocr_line_0"),
                        reason = "주소에 상호 형식이 들어갔습니다.",
                    ),
                ),
            ),
        )

        assertEquals(ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED, decision.route)
        assertTrue(ReceiptPreflightReason.AI_FIELD_CHECKS_NEED_REVIEW in decision.reasons)
    }

    @Test
    fun `AI correction candidates require manual review`() {
        val decision = evaluate(
            aiStatus = ReceiptAiReviewStatus.COMPLETED,
            assessment = ReceiptEvidenceAssessment(ReceiptEvidenceVerdict.NEEDS_REVIEW),
            acceptedCandidateCount = 2,
        )

        assertEquals(ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED, decision.route)
        assertTrue(ReceiptPreflightReason.AI_CORRECTIONS_AVAILABLE in decision.reasons)
    }

    @Test
    fun `plausible verdict with partial image coverage still requires manual review`() {
        val decision = evaluate(
            aiStatus = ReceiptAiReviewStatus.COMPLETED,
            assessment = ReceiptEvidenceAssessment(ReceiptEvidenceVerdict.PLAUSIBLE),
            aiImageEvidenceCoverageComplete = false,
        )

        assertEquals(ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED, decision.route)
        assertTrue(ReceiptPreflightReason.AI_IMAGE_EVIDENCE_PARTIAL in decision.reasons)
    }

    @Test
    fun `insufficient AI evidence plus sparse OCR recommends recapture`() {
        val sparse = SyntheticFixtures.ocrDocument().copy(rawText = "상품명 1 1000")
        val decision = evaluate(
            ocrDocument = sparse,
            aiStatus = ReceiptAiReviewStatus.COMPLETED,
            assessment = ReceiptEvidenceAssessment(ReceiptEvidenceVerdict.INSUFFICIENT_EVIDENCE),
        )

        assertEquals(ReceiptPreflightRoute.RECAPTURE_RECOMMENDED, decision.route)
        assertTrue(ReceiptPreflightReason.AI_EVIDENCE_INSUFFICIENT in decision.reasons)
    }

    private fun evaluate(
        ocrDocument: OcrDocument = SyntheticFixtures.ocrDocument(),
        aiStatus: ReceiptAiReviewStatus,
        assessment: ReceiptEvidenceAssessment? = null,
        aiImageEvidenceCoverageComplete: Boolean = true,
        acceptedCandidateCount: Int = 0,
    ) = ReceiptPreflightEvaluator.evaluate(
        pages = listOf(page()),
        ocrDocument = ocrDocument,
        validation = validation,
        aiStatus = aiStatus,
        assessment = assessment,
        aiImageEvidenceCoverageComplete = aiImageEvidenceCoverageComplete,
        acceptedCandidateCount = acceptedCandidateCount,
    )

    private fun page() = ReceiptPage(
        id = "page_fixture_001",
        documentId = requireNotNull(receipt.document.id),
        storageKey = "fixture/page.jpg",
        sha256 = "fixture",
        mimeType = "image/jpeg",
        width = 1_080,
        height = 2_400,
        pageIndex = 0,
        createdAt = "2026-08-20T00:00:00Z",
    )
}
