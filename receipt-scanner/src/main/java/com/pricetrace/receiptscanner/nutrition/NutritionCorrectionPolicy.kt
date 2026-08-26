package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.ocr.OcrDocument
import java.math.BigDecimal

enum class NutritionCorrectionRejectionReason {
    DUPLICATE_FIELD,
    UNSUPPORTED_FIELD,
    STALE_OLD_VALUE,
    MISSING_SOURCE_EVIDENCE,
    UNKNOWN_SOURCE_EVIDENCE,
    UNRELATED_SOURCE_EVIDENCE,
    INVALID_VALUE,
}

data class RejectedNutritionCorrection(
    val candidate: NutritionCorrectionCandidate,
    val reason: NutritionCorrectionRejectionReason,
)

data class ValidatedNutritionCorrections(
    val accepted: List<NutritionCorrectionCandidate>,
    val rejected: List<RejectedNutritionCorrection>,
)

object NutritionCorrectionPolicy {
    fun validateBatch(
        draft: NutritionLabelDraft,
        request: NutritionCorrectionRequest,
        candidates: List<NutritionCorrectionCandidate>,
    ): ValidatedNutritionCorrections {
        val seenFields = mutableSetOf<String>()
        val accepted = mutableListOf<NutritionCorrectionCandidate>()
        val rejected = mutableListOf<RejectedNutritionCorrection>()
        candidates.forEach { candidate ->
            val reason = if (!seenFields.add(candidate.fieldPath)) {
                NutritionCorrectionRejectionReason.DUPLICATE_FIELD
            } else {
                rejectionReason(draft, request, candidate)
            }
            if (reason == null) accepted += candidate else rejected += RejectedNutritionCorrection(candidate, reason)
        }
        return ValidatedNutritionCorrections(accepted, rejected)
    }

    fun rejectionReason(
        draft: NutritionLabelDraft,
        request: NutritionCorrectionRequest,
        candidate: NutritionCorrectionCandidate,
    ): NutritionCorrectionRejectionReason? {
        if (!NutritionCorrectionFieldPaths.isNutritionFieldPath(candidate.fieldPath)) {
            return NutritionCorrectionRejectionReason.UNSUPPORTED_FIELD
        }
        val target = request.targets.firstOrNull { it.fieldPath == candidate.fieldPath }
            ?: return NutritionCorrectionRejectionReason.UNSUPPORTED_FIELD
        if (candidate.oldValue.normalized() != currentValue(draft, candidate.fieldPath).normalized()) {
            return NutritionCorrectionRejectionReason.STALE_OLD_VALUE
        }
        if (candidate.sourceLineIds.isEmpty()) {
            return NutritionCorrectionRejectionReason.MISSING_SOURCE_EVIDENCE
        }
        val knownSourceIds = request.evidenceLines.mapTo(mutableSetOf()) { it.id }
        if (candidate.sourceLineIds.any { it !in knownSourceIds }) {
            return NutritionCorrectionRejectionReason.UNKNOWN_SOURCE_EVIDENCE
        }
        if (candidate.sourceLineIds.any { it !in target.sourceLineIds }) {
            return NutritionCorrectionRejectionReason.UNRELATED_SOURCE_EVIDENCE
        }
        if (candidate.confidencePercent !in 0..100 || !candidate.proposedValue.isSafeCandidateValue()) {
            return NutritionCorrectionRejectionReason.INVALID_VALUE
        }
        if (!isValidProposedValue(candidate.fieldPath, candidate.proposedValue)) {
            return NutritionCorrectionRejectionReason.INVALID_VALUE
        }
        return null
    }

    fun currentValue(draft: NutritionLabelDraft, fieldPath: String): String? = when (fieldPath) {
        NutritionCorrectionFieldPaths.PRODUCT_NAME -> draft.productName
        NutritionCorrectionFieldPaths.BRAND -> draft.brand
        NutritionCorrectionFieldPaths.CATEGORY -> draft.category
        NutritionCorrectionFieldPaths.BASIS_AMOUNT -> draft.basisAmount?.canonicalNumber()
        NutritionCorrectionFieldPaths.BASIS_UNIT -> NutritionUnit.normalize(draft.basisUnit)
        else -> NutritionField.fromWireKey(fieldPath)?.let { draft.value(it)?.canonicalNumber() }
    }

    fun apply(draft: NutritionLabelDraft, candidate: NutritionCorrectionCandidate): NutritionLabelDraft? {
        if (rejectionReason(
                draft = draft,
                request = NutritionCorrectionRequest(
                    documentId = draft.documentId,
                    targets = listOf(
                        NutritionCorrectionTarget(
                            fieldPath = candidate.fieldPath,
                            currentValue = currentValue(draft, candidate.fieldPath),
                            sourceLineIds = candidate.sourceLineIds,
                        ),
                    ),
                    evidenceLines = candidate.sourceLineIds.map {
                        NutritionCorrectionEvidenceLine(it, 0, "candidate-evidence")
                    },
                ),
                candidate = candidate,
            ) != null
        ) {
            return null
        }
        return when (candidate.fieldPath) {
            NutritionCorrectionFieldPaths.PRODUCT_NAME -> draft.copy(
                productName = candidate.proposedValue.trim(),
                status = NutritionDraftStatus.PARSED,
                confirmedAt = null,
            )
            NutritionCorrectionFieldPaths.BRAND -> draft.copy(
                brand = candidate.proposedValue.trim().takeIf(String::isNotEmpty),
                status = NutritionDraftStatus.PARSED,
                confirmedAt = null,
            )
            NutritionCorrectionFieldPaths.CATEGORY -> draft.copy(
                category = candidate.proposedValue.trim().lowercase(),
                status = NutritionDraftStatus.PARSED,
                confirmedAt = null,
            )
            NutritionCorrectionFieldPaths.BASIS_AMOUNT -> draft.copy(
                basisAmount = candidate.proposedValue.toCanonicalDouble(),
                status = NutritionDraftStatus.PARSED,
                confirmedAt = null,
            )
            NutritionCorrectionFieldPaths.BASIS_UNIT -> draft.copy(
                basisUnit = NutritionUnit.normalize(candidate.proposedValue),
                status = NutritionDraftStatus.PARSED,
                confirmedAt = null,
            )
            else -> NutritionField.fromWireKey(candidate.fieldPath)?.let { field ->
                draft.withNutrient(field, candidate.proposedValue.toCanonicalDouble())
            }
        }
    }

    private fun isValidProposedValue(fieldPath: String, proposed: String): Boolean = when (fieldPath) {
        NutritionCorrectionFieldPaths.PRODUCT_NAME -> proposed.trim().length in 1..120
        NutritionCorrectionFieldPaths.BRAND -> proposed.trim().length in 1..120
        NutritionCorrectionFieldPaths.CATEGORY -> proposed.trim().lowercase() in NutritionContract.categories
        NutritionCorrectionFieldPaths.BASIS_AMOUNT -> proposed.toCanonicalDoubleOrNull()?.let { it > 0.0 } == true
        NutritionCorrectionFieldPaths.BASIS_UNIT -> NutritionUnit.normalize(proposed) in NutritionUnit.supported
        else -> NutritionField.fromWireKey(fieldPath) != null &&
            proposed.toCanonicalDoubleOrNull()?.let { it >= 0.0 } == true
    }

    private fun String.isSafeCandidateValue(): Boolean {
        val value = trim()
        return value.isNotEmpty() && value.length <= 160 && '\n' !in value && '\r' !in value
    }

    private fun String.toCanonicalDoubleOrNull(): Double? = runCatching {
        BigDecimal(trim().replace(",", "")).toDouble().takeIf(Double::isFinite)
    }.getOrNull()

    private fun String.toCanonicalDouble(): Double = requireNotNull(toCanonicalDoubleOrNull())

    private fun Double.canonicalNumber(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

    private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
