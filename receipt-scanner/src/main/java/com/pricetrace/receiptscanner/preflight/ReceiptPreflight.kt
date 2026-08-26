package com.pricetrace.receiptscanner.preflight

import com.pricetrace.receiptscanner.correction.ReceiptEvidenceAssessment
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceVerdict
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.ReceiptValidationResult
import com.pricetrace.receiptscanner.ocr.OcrDocument

enum class ReceiptAiReviewStatus {
    NOT_REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
    UNAVAILABLE,
}

enum class ReceiptPreflightRoute {
    REQUEST_AI_REVIEW,
    READY_FOR_USER_VERIFICATION,
    MANUAL_REVIEW_REQUIRED,
    RECAPTURE_RECOMMENDED,
}

enum class ReceiptPreflightReason {
    AI_REVIEW_NOT_RUN,
    AI_REVIEW_IN_PROGRESS,
    AI_REVIEW_FAILED,
    AI_REVIEW_UNAVAILABLE,
    AI_EVIDENCE_PLAUSIBLE,
    AI_EVIDENCE_NEEDS_REVIEW,
    AI_FIELD_CHECKS_NEED_REVIEW,
    AI_EVIDENCE_INSUFFICIENT,
    AI_IMAGE_EVIDENCE_PARTIAL,
    AI_CORRECTIONS_AVAILABLE,
    AI_RESPONSE_CANDIDATES_REJECTED,
    OCR_EVIDENCE_MISSING,
    OCR_EVIDENCE_SPARSE,
    CAPTURE_RESOLUTION_LOW,
    BLOCKING_VALIDATION_ISSUES,
    TOTAL_RECONCILIATION_FAILED,
}

data class ReceiptPreflightDecision(
    val route: ReceiptPreflightRoute,
    val reasons: List<ReceiptPreflightReason>,
)

/**
 * Routes a parsed draft before field-by-field verification.
 *
 * A model verdict is only one signal. Recapture is recommended only for missing visual evidence,
 * never merely because a provider failed or a catalog lookup found no match.
 */
object ReceiptPreflightEvaluator {
    fun evaluate(
        pages: List<ReceiptPage>,
        ocrDocument: OcrDocument,
        validation: ReceiptValidationResult,
        aiStatus: ReceiptAiReviewStatus,
        assessment: ReceiptEvidenceAssessment? = null,
        aiImageEvidenceCoverageComplete: Boolean = false,
        acceptedCandidateCount: Int = 0,
        rejectedCandidateCount: Int = 0,
    ): ReceiptPreflightDecision {
        require(acceptedCandidateCount >= 0)
        require(rejectedCandidateCount >= 0)

        val ocrLineCount = ocrDocument.lines.size
        val ocrCharacterCount = ocrDocument.rawText.count { !it.isWhitespace() }
        val evidenceMissing = ocrLineCount == 0 || ocrCharacterCount < MINIMUM_EVIDENCE_CHARACTERS
        val evidenceSparse = ocrLineCount < MINIMUM_STABLE_LINE_COUNT ||
            ocrCharacterCount < MINIMUM_STABLE_CHARACTER_COUNT
        val lowResolution = pages.isEmpty() || pages.any { page ->
            minOf(page.width, page.height) < MINIMUM_SHORT_EDGE_PIXELS
        }

        if (evidenceMissing || (evidenceSparse && lowResolution)) {
            return ReceiptPreflightDecision(
                route = ReceiptPreflightRoute.RECAPTURE_RECOMMENDED,
                reasons = buildList {
                    add(
                        if (evidenceMissing) {
                            ReceiptPreflightReason.OCR_EVIDENCE_MISSING
                        } else {
                            ReceiptPreflightReason.OCR_EVIDENCE_SPARSE
                        },
                    )
                    if (lowResolution) add(ReceiptPreflightReason.CAPTURE_RESOLUTION_LOW)
                },
            )
        }

        return when (aiStatus) {
            ReceiptAiReviewStatus.NOT_REQUESTED -> ReceiptPreflightDecision(
                ReceiptPreflightRoute.REQUEST_AI_REVIEW,
                listOf(ReceiptPreflightReason.AI_REVIEW_NOT_RUN),
            )
            ReceiptAiReviewStatus.RUNNING -> ReceiptPreflightDecision(
                ReceiptPreflightRoute.REQUEST_AI_REVIEW,
                listOf(ReceiptPreflightReason.AI_REVIEW_IN_PROGRESS),
            )
            ReceiptAiReviewStatus.FAILED -> manualDecision(
                validation,
                evidenceSparse,
                listOf(ReceiptPreflightReason.AI_REVIEW_FAILED),
            )
            ReceiptAiReviewStatus.UNAVAILABLE -> manualDecision(
                validation,
                evidenceSparse,
                listOf(ReceiptPreflightReason.AI_REVIEW_UNAVAILABLE),
            )
            ReceiptAiReviewStatus.COMPLETED -> completedDecision(
                validation = validation,
                evidenceSparse = evidenceSparse,
                lowResolution = lowResolution,
                assessment = assessment,
                aiImageEvidenceCoverageComplete = aiImageEvidenceCoverageComplete,
                acceptedCandidateCount = acceptedCandidateCount,
                rejectedCandidateCount = rejectedCandidateCount,
            )
        }
    }

    private fun completedDecision(
        validation: ReceiptValidationResult,
        evidenceSparse: Boolean,
        lowResolution: Boolean,
        assessment: ReceiptEvidenceAssessment?,
        aiImageEvidenceCoverageComplete: Boolean,
        acceptedCandidateCount: Int,
        rejectedCandidateCount: Int,
    ): ReceiptPreflightDecision {
        if (assessment?.verdict == ReceiptEvidenceVerdict.INSUFFICIENT_EVIDENCE && evidenceSparse) {
            return ReceiptPreflightDecision(
                ReceiptPreflightRoute.RECAPTURE_RECOMMENDED,
                buildList {
                    add(ReceiptPreflightReason.AI_EVIDENCE_INSUFFICIENT)
                    add(ReceiptPreflightReason.OCR_EVIDENCE_SPARSE)
                    if (lowResolution) add(ReceiptPreflightReason.CAPTURE_RESOLUTION_LOW)
                },
            )
        }

        val reasons = buildList {
            when (assessment?.verdict) {
                ReceiptEvidenceVerdict.PLAUSIBLE -> add(ReceiptPreflightReason.AI_EVIDENCE_PLAUSIBLE)
                ReceiptEvidenceVerdict.NEEDS_REVIEW -> add(ReceiptPreflightReason.AI_EVIDENCE_NEEDS_REVIEW)
                ReceiptEvidenceVerdict.INSUFFICIENT_EVIDENCE, null ->
                    add(ReceiptPreflightReason.AI_EVIDENCE_INSUFFICIENT)
            }
            if (acceptedCandidateCount > 0) add(ReceiptPreflightReason.AI_CORRECTIONS_AVAILABLE)
            if (assessment?.requiresFieldReview == true) add(ReceiptPreflightReason.AI_FIELD_CHECKS_NEED_REVIEW)
            if (rejectedCandidateCount > 0) add(ReceiptPreflightReason.AI_RESPONSE_CANDIDATES_REJECTED)
            if (!aiImageEvidenceCoverageComplete) add(ReceiptPreflightReason.AI_IMAGE_EVIDENCE_PARTIAL)
            if (evidenceSparse) add(ReceiptPreflightReason.OCR_EVIDENCE_SPARSE)
            addValidationReasons(validation)
        }.distinct()

        val ready = assessment?.verdict == ReceiptEvidenceVerdict.PLAUSIBLE &&
            !assessment.requiresFieldReview &&
            acceptedCandidateCount == 0 &&
            rejectedCandidateCount == 0 &&
            aiImageEvidenceCoverageComplete &&
            !evidenceSparse &&
            validation.canMarkUserVerified &&
            validation.reconciliation.isBalanced

        return ReceiptPreflightDecision(
            route = if (ready) {
                ReceiptPreflightRoute.READY_FOR_USER_VERIFICATION
            } else {
                ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED
            },
            reasons = reasons,
        )
    }

    private fun manualDecision(
        validation: ReceiptValidationResult,
        evidenceSparse: Boolean,
        initialReasons: List<ReceiptPreflightReason>,
    ): ReceiptPreflightDecision = ReceiptPreflightDecision(
        ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED,
        buildList {
            addAll(initialReasons)
            if (evidenceSparse) add(ReceiptPreflightReason.OCR_EVIDENCE_SPARSE)
            addValidationReasons(validation)
        }.distinct(),
    )

    private fun MutableList<ReceiptPreflightReason>.addValidationReasons(validation: ReceiptValidationResult) {
        if (!validation.canMarkUserVerified) add(ReceiptPreflightReason.BLOCKING_VALIDATION_ISSUES)
        if (!validation.reconciliation.isBalanced) add(ReceiptPreflightReason.TOTAL_RECONCILIATION_FAILED)
    }

    private const val MINIMUM_EVIDENCE_CHARACTERS = 8
    private const val MINIMUM_STABLE_CHARACTER_COUNT = 24
    private const val MINIMUM_STABLE_LINE_COUNT = 3
    private const val MINIMUM_SHORT_EDGE_PIXELS = 720
}
