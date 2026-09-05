package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft

const val YEONSIK_OCR_SCHEMA = "yeonsik-ocr.v1"
const val YEONSIK_OCR_V2_SCHEMA = "yeonsik-ocr.v2"
object IngestionArtifactKeys {
    const val RECEIPT = "receipt"
    const val MERCHANT_CANDIDATE = "merchant_candidate"
    const val CASHOS_HINTS = "cashos_hints"
    const val CONSUMPTION = "consumption"
    const val PRODUCT_CANDIDATE = "product_candidate"

    fun nutrition(clientKey: String): String = "nutrition:$clientKey"
    fun consumption(clientKey: String): String = "$CONSUMPTION:$clientKey"
    fun productCandidate(clientKey: String): String = "$PRODUCT_CANDIDATE:$clientKey"
}

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
    PRODUCT_PHOTO("product_photo"),
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
    val sourceNamespace: String? = null,
    val sourceLocationCode: String? = null,
    val businessKind: com.pricetrace.receiptscanner.domain.BusinessKind = com.pricetrace.receiptscanner.domain.BusinessKind.UNKNOWN,
)

/** A fact-only product observation. PriceTrace identity is resolved after this leaves OCR-App. */
data class ProductCandidateEvidence(
    val sourceAttachmentIds: List<String>,
    val source: String? = null,
    val sourceType: String = "product_photo",
    val sourceRef: String? = null,
    val field: String = "product_name",
    val observedValue: String? = null,
    val contentHash: String? = null,
)

data class ProductCandidate(
    val clientKey: String,
    val productName: String,
    val brandOrManufacturer: String? = null,
    val specification: String? = null,
    val contentAmount: Double? = null,
    val contentUnit: String? = null,
    val packageCount: Int? = null,
    val variant: String? = null,
    val barcode: String? = null,
    val ean: String? = null,
    val upc: String? = null,
    val evidence: List<ProductCandidateEvidence> = emptyList(),
    val brand: String? = null,
    val manufacturer: String? = null,
    val candidateType: String = "retail_product",
    val sourceVersion: String? = null,
) {
    val effectiveBrand: String?
        get() = brand ?: brandOrManufacturer

    init {
        require(clientKey.isNotBlank() && productName.isNotBlank())
        require(productName.length <= 300)
        require(candidateType in setOf("retail_product", "complimentary_side", "meal_component_estimate"))
        listOf(
            brandOrManufacturer,
            specification,
            contentUnit,
            variant,
            barcode,
            ean,
            upc,
            sourceVersion,
        ).forEach { value ->
            require(value == null || value.isNotBlank()) { "product candidate text facts must not be blank" }
        }
        require(evidence.isNotEmpty()) { "product candidate evidence is required" }
        evidence.forEach { item ->
            require(item.sourceAttachmentIds.isNotEmpty()) { "product candidate evidence requires a source" }
            require(item.sourceAttachmentIds.all(String::isNotBlank)) {
                "product candidate evidence source IDs must be non-empty"
            }
            require(item.sourceType in PRODUCT_EVIDENCE_SOURCE_TYPES && item.field.isNotBlank()) {
                "product candidate evidence source type and field are required"
            }
            require(item.source == null || item.source.isNotBlank()) {
                "product candidate evidence source must be non-empty"
            }
            require(item.sourceRef == null || item.sourceRef.isNotBlank()) {
                "product candidate evidence source reference must be non-empty"
            }
            require(item.contentHash == null || item.contentHash.matches(Regex("^sha256:[a-f0-9]{64}$"))) {
                "product candidate evidence content hash must be sha256"
            }
        }
        require((contentAmount == null) == (contentUnit == null)) {
            "content amount and unit must be provided together"
        }
        require(contentAmount == null || contentAmount.isFinite() && contentAmount > 0)
        require(contentUnit == null || contentUnit in setOf("g", "ml", "each"))
        require(packageCount == null || packageCount > 0)
        validateIdentifier("barcode", barcode, setOf(8, 12, 13, 14))
        validateIdentifier("ean", ean, setOf(8, 13))
        validateIdentifier("upc", upc, setOf(12))
    }

    private fun validateIdentifier(name: String, value: String?, lengths: Set<Int>) {
        if (value == null) return
        val normalized = value.filterNot { it == ' ' || it == '-' }
        require(normalized.all(Char::isDigit) && normalized.length in lengths) {
            "$name must contain a supported numeric identifier"
        }
    }

    private companion object {
        val PRODUCT_EVIDENCE_SOURCE_TYPES = setOf(
            "product_photo",
            "package_label",
            "receipt",
            "official_listing",
            "manufacturer",
            "user_statement",
            "ocr",
        )
    }
}

data class NutritionNutrientProvenance(
    val valueStatus: String,
    val sourceType: String,
    val evidenceRefs: List<String>,
)

data class NutritionRange(
    val min: Double? = null,
    val point: Double? = null,
    val max: Double? = null,
)

data class RestaurantNutritionEstimate(
    val nutrients: Map<NutritionField, Double?>,
    val estimated: Boolean,
    /** Legacy envelopes may use labels; canonical publication requires confidenceScore. */
    val confidence: String,

    val ranges: Map<NutritionField, NutritionRange> = emptyMap(),
    val nutrientProvenance: Map<NutritionField, NutritionNutrientProvenance> = emptyMap(),
    val confidenceScore: Double? = null,
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

    /**
     * A meal component inferred from food evidence. It may be absent from the receipt, in which
     * case lineId is null and it must never be routed to PriceTrace RestaurantMenu.
     */
    data class MealComponentEstimate(
        override val clientKey: String,
        override val lineId: String? = null,
        val menuName: String,
        val estimate: RestaurantNutritionEstimate,
        val reference: MealComponentReference? = null,
    ) : IngestionNutrition
}

data class MealComponentReference(
    val restaurantName: String? = null,
    val branchName: String? = null,
    /** This is deliberately nullable in v2 input; OCR/ChatGPT cannot assert a PriceTrace UUID. */
    val restaurantMenuId: String? = null,
) {
    init {
        require(restaurantMenuId == null) {
            "meal component input cannot assert a PriceTrace restaurant_menu_id"
        }
    }
}

data class IngestionLink(
    val receiptLineId: String,
    val nutritionClientKey: String,
)

enum class ConsumptionVerificationStatus(val wireValue: String) {
    UNVERIFIED("unverified"),
    USER_VERIFIED("user_verified"),
    ;

    companion object {
        fun fromWireValue(value: String): ConsumptionVerificationStatus = entries.firstOrNull { it.wireValue == value }
            ?: error("Unsupported consumption verification status: $value")
    }
}

/** Explicit evidence that a nutrition artifact was actually consumed. */
data class IngestionConsumptionItem(
    val nutritionClientKey: String,
    val amount: Double,
    val unit: String,
    val confidence: Double,
) {
    init {
        require(nutritionClientKey.isNotBlank())
        require(amount.isFinite() && amount > 0)
        require(unit.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class IngestionConsumption(
    val clientKey: String,
    /** Kept for v1 compatibility; v2 derives this set from items. */
    val nutritionClientKeys: Set<String> = emptySet(),
    val consumedAt: String? = null,
    val status: ConsumptionVerificationStatus = ConsumptionVerificationStatus.UNVERIFIED,
    val items: List<IngestionConsumptionItem> = emptyList(),
) {
    init {
        require(clientKey.isNotBlank())
        if (items.isNotEmpty()) {
            require(nutritionClientKeys.isEmpty() || nutritionClientKeys == items.map { it.nutritionClientKey }.toSet()) {
                "consumption nutrition keys must match item keys"
            }
        }
    }

    val effectiveNutritionClientKeys: Set<String>
        get() = if (items.isNotEmpty()) items.map { it.nutritionClientKey }.toSet() else nutritionClientKeys
}

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
    val consumption: List<IngestionConsumption> = emptyList(),
    val classificationHints: Map<String, String?> = emptyMap(),
    val links: List<IngestionLink> = emptyList(),
    /** Requested destinations; mode is only a validated producer hint, never routing authority. */
    val targets: Set<IngestionProjection> = emptySet(),
    val review: IngestionReview = IngestionReview(IngestionReviewStatus.NEEDS_REVIEW),
    /** v1 is the source-compatible default. v2 persistence must retain this discriminator. */
    val productCandidates: List<ProductCandidate> = emptyList(),
    val schemaVersion: String = YEONSIK_OCR_SCHEMA,
)

enum class IngestionProjection(val wireValue: String) {
    PRICETRACE_RECEIPT("pricetrace_receipt"),
    PRICETRACE_PRICE_OBSERVATION("pricetrace_price_observation"),
    PRICETRACE_MERCHANT_CANDIDATE("pricetrace_merchant_candidate"),
    PRICETRACE_PRODUCT_CANDIDATE("pricetrace_product_candidate"),
    FITNESS_NUTRITION("fitness_nutrition"),
    FITNESS_MEAL("fitness_meal"),
    FITNESS_PRODUCT_NUTRITION_LINK("fitness_product_nutrition_link"),
    CASHOS_RECEIPT("cashos_receipt"),
    ;

    companion object {
        fun fromWireValue(value: String): IngestionProjection = entries.firstOrNull { it.wireValue == value }
            ?: error("Unsupported projection target: $value")

        /** Naming aliases for callers that describe the same Fitness-owned link differently. */
        val CATALOG_PRODUCT_NUTRITION_LINK: IngestionProjection
            get() = FITNESS_PRODUCT_NUTRITION_LINK
        val FITNESS_NUTRITION_PRODUCT_LINK: IngestionProjection
            get() = FITNESS_PRODUCT_NUTRITION_LINK
    }
}

enum class ProjectionStatus(val wireValue: String) {
    PENDING("pending"),
    BLOCKED("blocked"),
    UPLOADED("uploaded"),
    FAILED("failed"),
    DISABLED("disabled"),
    ;

    companion object {
        fun fromPersisted(value: String): ProjectionStatus = when (value) {
            "ready", "user_verified" -> PENDING
            "submitted" -> UPLOADED
            else -> entries.firstOrNull { it.wireValue == value } ?: error("Unsupported projection status: $value")
        }
    }
}

data class ProjectionState(
    val projection: IngestionProjection,
    val status: ProjectionStatus = ProjectionStatus.PENDING,
    val idempotencyKey: String? = null,
    val remoteId: String? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val updatedAt: String,
    /** Sanitized response metadata, e.g. CashOS ledger/category/account resolution. */
    val metadataJson: String? = null,
    /** Domain revision for this projection; independent from the envelope revision. */
    val projectionRevisionSeq: Long = 1,
    /** Payload identity associated with the projection idempotency key/revision. */
    val projectionPayloadFingerprint: String? = null,
)

data class IngestionSession(
    val ingestionId: String,
    val localDocumentId: String,
    val envelopeStorageKey: String,
    /** Fingerprint of the currently persisted canonical revision. */
    val canonicalFingerprint: String,
    val reviewStatus: IngestionReviewStatus,
    val createdAt: String,
    val updatedAt: String,
    val projections: List<ProjectionState>,
    val attachments: List<LocalEvidence> = emptyList(),
    val revisionSeq: Long = 1,
    val verifiedCanonicalFingerprint: String? = null,
    val verifiedAt: String? = null,
    /** Fingerprints of the artifacts explicitly reviewed by the user in this revision. */
    val verifiedArtifactFingerprints: Map<String, String> = emptyMap(),
    /** Immutable import identity; it does not change when the user edits the draft. */
    val importFingerprint: String = canonicalFingerprint,
)
