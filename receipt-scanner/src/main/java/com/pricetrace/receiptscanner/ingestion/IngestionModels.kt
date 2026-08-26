package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft

const val YEONSIK_OCR_SCHEMA = "yeonsik-ocr.v1"

enum class IngestionMode(val wireValue: String) {
    MERCHANT("merchant"),
    RESTAURANT("restaurant"),
    PACKAGED_PRODUCT("packaged_product"),
    ;

    companion object {
        fun fromWireValue(value: String): IngestionMode = entries.firstOrNull { it.wireValue == value }
            ?: error("Unsupported ingestion mode: $value")
    }
}

enum class SourceAttachmentType(val wireValue: String) {
    RECEIPT("receipt"),
    NUTRITION_LABEL("nutrition_label"),
    FOOD_PHOTO("food_photo"),
    MENU_PHOTO("menu_photo"),
    ;

    companion object {
        fun fromWireValue(value: String): SourceAttachmentType = entries.firstOrNull { it.wireValue == value }
            ?: error("Unsupported source attachment type: $value")
    }
}

data class SourceAttachment(
    val id: String,
    val type: SourceAttachmentType,
    /** A logical producer reference only; it is never treated as a local file path. */
    val label: String? = null,
)

data class IngestionSource(
    val producer: String,
    val sourceFiles: List<SourceAttachment>,
    val userText: String? = null,
)

data class MerchantCandidate(
    val name: String,
    val branchName: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val businessRegistrationNumber: String? = null,
    val sourceAttachmentIds: List<String> = emptyList(),
)

data class NutritionRange(
    val min: Double? = null,
    val point: Double? = null,
    val max: Double? = null,
)

data class RestaurantNutritionEstimate(
    val nutrients: Map<NutritionField, Double?>,
    val estimated: Boolean,
    val confidence: String,
    val ranges: Map<NutritionField, NutritionRange> = emptyMap(),
)

sealed interface IngestionNutrition {
    val clientKey: String
    val lineId: String?

    data class ProductLabel(
        override val clientKey: String,
        val draft: NutritionLabelDraft,
        override val lineId: String? = null,
    ) : IngestionNutrition

    data class RestaurantEstimate(
        override val clientKey: String,
        override val lineId: String,
        val menuName: String,
        val estimate: RestaurantNutritionEstimate,
    ) : IngestionNutrition
}

data class IngestionLink(
    val receiptLineId: String,
    val nutritionClientKey: String,
)

enum class IngestionReviewStatus(val wireValue: String) {
    READY("ready"),
    NEEDS_REVIEW("needs_review"),
    BLOCKED("blocked"),
}

data class IngestionReview(
    val status: IngestionReviewStatus,
    val blockingIssues: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Sanitized, local-draft representation of yeonsik-ocr.v1. */
data class YeonsikOcrEnvelope(
    val mode: IngestionMode,
    val source: IngestionSource,
    val merchantCandidate: MerchantCandidate? = null,
    val receipt: ReceiptV2? = null,
    val nutrition: List<IngestionNutrition> = emptyList(),
    val classificationHints: Map<String, String?> = emptyMap(),
    val links: List<IngestionLink> = emptyList(),
    val review: IngestionReview = IngestionReview(IngestionReviewStatus.NEEDS_REVIEW),
)

enum class IngestionProjection(val wireValue: String) {
    PRICETRACE("pricetrace"),
    FITNESS("fitness"),
    CASHOS("cashos"),
}

enum class ProjectionStatus(val wireValue: String) {
    PENDING("pending"),
    READY("ready"),
    USER_VERIFIED("user_verified"),
    SUBMITTED("submitted"),
    FAILED("failed"),
    DISABLED("disabled"),
}

data class ProjectionState(
    val projection: IngestionProjection,
    val status: ProjectionStatus = ProjectionStatus.PENDING,
    val idempotencyKey: String? = null,
    val remoteId: String? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val updatedAt: String,
)

data class IngestionSession(
    val ingestionId: String,
    val localDocumentId: String,
    val envelopeStorageKey: String,
    val canonicalFingerprint: String,
    val reviewStatus: IngestionReviewStatus,
    val createdAt: String,
    val updatedAt: String,
    val projections: List<ProjectionState>,
    val attachments: List<LocalEvidence> = emptyList(),
)
