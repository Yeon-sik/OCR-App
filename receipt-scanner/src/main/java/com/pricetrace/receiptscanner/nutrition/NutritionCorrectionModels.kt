package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionProvider

data class NutritionCorrectionTarget(
    val fieldPath: String,
    val currentValue: String?,
    val sourceLineIds: List<String>,
)

data class NutritionCorrectionEvidenceLine(
    val id: String,
    val pageIndex: Int,
    val text: String,
)

data class NutritionCorrectionEvidenceImage(
    val id: String,
    val mimeType: String,
    val bytes: ByteArray,
    val sourceLineIds: List<String>,
)

data class NutritionCorrectionRequest(
    val documentId: String,
    val targets: List<NutritionCorrectionTarget>,
    val evidenceLines: List<NutritionCorrectionEvidenceLine>,
    val evidenceImages: List<NutritionCorrectionEvidenceImage> = emptyList(),
)

data class NutritionCorrectionCandidate(
    val id: String,
    val fieldPath: String,
    val oldValue: String?,
    val proposedValue: String,
    val sourceLineIds: List<String>,
    val confidencePercent: Int,
    val reason: String,
    val providerId: String,
    val model: String,
    val promptVersion: String,
)

enum class NutritionEvidenceVerdict(val wireValue: String) {
    PLAUSIBLE("plausible"),
    NEEDS_REVIEW("needs_review"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence"),
    ;

    companion object {
        fun fromWireValue(value: String?): NutritionEvidenceVerdict? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class NutritionFieldVerdict(val wireValue: String) {
    MATCHES_EVIDENCE("matches_evidence"),
    NEEDS_REVIEW("needs_review"),
    WRONG_FIELD_TYPE("wrong_field_type"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence"),
    ;

    companion object {
        fun fromWireValue(value: String?): NutritionFieldVerdict? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class NutritionFieldCheck(
    val fieldPath: String,
    val verdict: NutritionFieldVerdict,
    val sourceLineIds: List<String>,
    val reason: String,
)

data class NutritionEvidenceAssessment(
    val verdict: NutritionEvidenceVerdict,
    val fieldChecks: List<NutritionFieldCheck> = emptyList(),
) {
    val requiresFieldReview: Boolean
        get() = fieldChecks.any { it.verdict != NutritionFieldVerdict.MATCHES_EVIDENCE }
}

data class NutritionCorrectionBatch(
    val candidates: List<NutritionCorrectionCandidate>,
    val assessment: NutritionEvidenceAssessment,
    val providerId: String,
    val model: String,
    val promptVersion: String,
)

enum class NutritionCorrectionFailureReason {
    NOT_CONFIGURED,
    NO_ELIGIBLE_EVIDENCE,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    PROVIDER,
    INVALID_RESPONSE,
}

sealed interface NutritionCorrectionOutcome {
    data class Success(val batch: NutritionCorrectionBatch) : NutritionCorrectionOutcome
    data class Failure(
        val reason: NutritionCorrectionFailureReason,
        val safeDetail: String? = null,
    ) : NutritionCorrectionOutcome
}

interface NutritionCorrectionSuggester {
    val provider: ReceiptCorrectionProvider
    suspend fun suggest(request: NutritionCorrectionRequest): NutritionCorrectionOutcome
}

object NutritionCorrectionFieldPaths {
    const val PRODUCT_NAME = "product_name"
    const val BRAND = "brand"
    const val CATEGORY = "category"
    const val BASIS_AMOUNT = "basis_amount"
    const val BASIS_UNIT = "basis_unit"

    val metadata: List<String> = listOf(PRODUCT_NAME, BRAND, CATEGORY, BASIS_AMOUNT, BASIS_UNIT)

    fun isNutritionFieldPath(fieldPath: String): Boolean =
        fieldPath in metadata || NutritionField.fromWireKey(fieldPath) != null
}
