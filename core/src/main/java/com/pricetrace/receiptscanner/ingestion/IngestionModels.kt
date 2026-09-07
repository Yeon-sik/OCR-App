package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

const val YEONSIK_OCR_SCHEMA = "yeonsik-ocr.v1"
const val YEONSIK_OCR_V2_SCHEMA = "yeonsik-ocr.v2"
const val YEONSIK_OCR_V3_SCHEMA = "yeonsik-ocr.v3"
object IngestionArtifactKeys {
    const val RECEIPT = "receipt"
    const val MERCHANT_CANDIDATE = "merchant_candidate"
    const val CASHOS_HINTS = "cashos_hints"
    const val CONSUMPTION = "consumption"
    const val PRODUCT_CANDIDATE = "product_candidate"
    const val PRICE_OBSERVATION = "price_observation"

    fun nutrition(clientKey: String): String = "nutrition:$clientKey"
    fun consumption(clientKey: String): String = "$CONSUMPTION:$clientKey"
    fun productCandidate(clientKey: String): String = "$PRODUCT_CANDIDATE:$clientKey"
    fun priceObservation(clientKey: String): String = "$PRICE_OBSERVATION:$clientKey"
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

/** A barcode observation from the OCR Project contract. Its value remains a source fact. */
data class ProductCandidateBarcode(
    val type: String,
    val value: String,
) {
    init {
        require(type.isNotBlank()) { "product candidate barcode type is required" }
        require(value.isNotBlank()) { "product candidate barcode value is required" }
        val normalized = value.filterNot { it == ' ' || it == '-' }
        require(normalized.all(Char::isDigit) && normalized.length in SUPPORTED_LENGTHS) {
            "product candidate barcode must contain a supported numeric identifier"
        }
    }

    /** Project v2 calls this field `scheme`; the internal name remains source-compatible. */
    val scheme: String
        get() = type

    private companion object {
        val SUPPORTED_LENGTHS = setOf(8, 12, 13, 14)
    }
}

data class ProductCandidate(
    val clientKey: String,
    val productName: String,
    val brand: String? = null,
    val manufacturer: String? = null,
    val specification: String? = null,
    val contentAmount: Double? = null,
    val contentUnit: String? = null,
    val packageCount: Int? = null,
    val variant: String? = null,
    val barcodes: List<ProductCandidateBarcode> = emptyList(),
    val evidence: List<ProductCandidateEvidence> = emptyList(),
    val candidateType: String = "retail_product",
    val sourceVersion: String? = null,
    /** The Project-owned source references retained while the candidate is a local fact draft. */
    val sourceAttachmentIds: List<String> = emptyList(),
    val confidence: Double = 1.0,
    /** A separately observed sub-brand fact; PriceTrace, not OCR-App, resolves identity. */
    val subBrand: String? = null,
) {
    init {
        require(clientKey.isNotBlank() && productName.isNotBlank())
        require(productName.length <= 300)
        require(candidateType in setOf("retail_product", "complimentary_side", "meal_component_estimate"))
        require(sourceAttachmentIds.distinct().size == sourceAttachmentIds.size) {
            "product candidate source attachment IDs must be unique"
        }
        require(sourceAttachmentIds.all(String::isNotBlank)) {
            "product candidate source attachment IDs must be non-empty"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "product candidate confidence must be between 0 and 1"
        }
        listOf(
            brand,
            manufacturer,
            specification,
            contentUnit,
            variant,
            subBrand,
            sourceVersion,
        ).forEach { value ->
            require(value == null || value.isNotBlank()) { "product candidate text facts must not be blank" }
        }
        require(barcodes.distinct().size == barcodes.size) { "product candidate barcodes must be unique" }
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
    }

    /** Falls back to legacy evidence for candidates loaded from the pre-v2 internal shape. */
    val effectiveSourceAttachmentIds: List<String>
        get() = sourceAttachmentIds.ifEmpty {
            evidence.flatMap { it.sourceAttachmentIds }.distinct()
        }

    /** Wire-contract names used by yeonsik-ocr.v3 callers. */
    val brandName: String?
        get() = brand
    val subBrandName: String?
        get() = subBrand
    val manufacturerName: String?
        get() = manufacturer
    val variantName: String?
        get() = variant

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

/** Verification authority is deliberately separate from producer-supplied review metadata. */
enum class VerificationBasis(val wireValue: String) {
    SOURCE_EVIDENCE("SOURCE_EVIDENCE"),
    MANUAL_CANONICAL_REVIEW("MANUAL_CANONICAL_REVIEW"),
    ;

    companion object {
        fun fromWireValue(value: String): VerificationBasis = entries.firstOrNull {
            it.name == value || it.wireValue == value || it.wireValue.equals(value, ignoreCase = true)
        } ?: error("Unsupported verification basis: $value")
    }
}

enum class StandalonePriceObservationKind(val wireValue: String) {
    RETAIL_PURCHASE("retail_purchase"),
    RESTAURANT_PURCHASE("restaurant_purchase"),
    ;

    companion object {
        fun fromWireValue(value: String): StandalonePriceObservationKind = entries.firstOrNull {
            it.wireValue == value
        } ?: error("Unsupported standalone price observation kind: $value")
    }
}

/** A price fact independent of receipt.v2 and never routed to CashOS. */
data class StandalonePriceObservation(
    val clientKey: String,
    val kind: StandalonePriceObservationKind,
    val productClientKey: String? = null,
    val itemName: String? = null,
    val observedOn: String? = null,
    val observedAt: String? = null,
    val quantity: Double,
    val unitPrice: Long,
    val gross: Long,
    val discount: Long,
    val net: Long,
    val evidence: List<String>,
    val confidence: Double,
) {
    init {
        require(clientKey.isNotBlank()) { "price observation client_key is required" }
        require((observedOn != null) || (observedAt != null)) {
            "price observation observed_on or observed_at is required"
        }
        observedOn?.let { require(runCatching { LocalDate.parse(it) }.isSuccess) { "invalid observed_on" } }
        observedAt?.let { require(runCatching { OffsetDateTime.parse(it) }.isSuccess) { "invalid observed_at" } }
        if (observedOn != null && observedAt != null) {
            val observedDate = LocalDate.parse(observedOn)
            val timestampDate = OffsetDateTime.parse(observedAt).toInstant()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
            require(observedDate == timestampDate) {
                "price observation observed_on and observed_at must refer to the same UTC date"
            }
        }
        require(quantity.isFinite() && quantity > 0.0) { "price observation quantity must be positive" }
        require(quantity % 1.0 == 0.0) { "price observation quantity must be an integer" }
        require(unitPrice >= 0 && gross >= 0 && discount >= 0 && net >= 0) {
            "price observation amounts must be non-negative"
        }
        require(gross >= discount) { "price observation discount cannot exceed gross" }
        require(
            BigDecimal(quantity.toString()).multiply(BigDecimal.valueOf(unitPrice))
                .compareTo(BigDecimal.valueOf(net)) == 0,
        ) {
            "quantity times unit_price must equal net"
        }
        require(gross - discount == net) { "gross minus discount must equal net" }
        require(evidence.isNotEmpty() && evidence.all(String::isNotBlank)) {
            "price observation evidence is required"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "price observation confidence must be between 0 and 1"
        }
        when (kind) {
            StandalonePriceObservationKind.RETAIL_PURCHASE -> {
                require(!productClientKey.isNullOrBlank()) { "retail price observation requires product_client_key" }
                require(itemName == null) { "retail price observation cannot use item_name" }
            }
            StandalonePriceObservationKind.RESTAURANT_PURCHASE -> {
                require(!itemName.isNullOrBlank()) { "restaurant price observation requires item_name" }
                require(productClientKey == null) { "restaurant price observation cannot use product_client_key" }
            }
        }
    }

    /** Compatibility aliases for callers that use the wire spelling. */
    val unit_price: Long get() = unitPrice
    val observed_on: String? get() = observedOn
    val observed_at: String? get() = observedAt
}

typealias CanonicalPriceObservation = StandalonePriceObservation

sealed interface IngestionNutrition {
    val clientKey: String
    val lineId: String?

    data class ProductLabel(
        override val clientKey: String,
        val draft: NutritionLabelDraft,
        override val lineId: String? = null,
        /** Fitness v3 category hierarchy; each entry remains a source classification fact. */
        val productLabelHierarchy: List<String> = emptyList(),
    ) : IngestionNutrition

    data class RestaurantEstimate(
        override val clientKey: String,
        override val lineId: String,
        val menuName: String,
        val estimate: RestaurantNutritionEstimate,
    ) : IngestionNutrition

    /** Paid restaurant menu nutrition linked to a standalone restaurant price observation. */
    data class RestaurantMenuEstimate(
        override val clientKey: String,
        override val lineId: String? = null,
        val menuName: String,
        val priceObservationClientKey: String,
        val estimate: RestaurantNutritionEstimate,
    ) : IngestionNutrition {
        init {
            require(priceObservationClientKey.isNotBlank()) {
                "restaurant menu estimate requires a price observation link"
            }
        }
    }

    /**
     * A meal component inferred from food evidence. It may be absent from the receipt, in which
     * case lineId is null and it must never be routed to PriceTrace RestaurantMenu.
     */
    data class MealComponentEstimate(
        override val clientKey: String,
        override val lineId: String? = null,
        val menuName: String,
        val estimate: RestaurantNutritionEstimate,
        val componentRole: String = "complimentary_side",
        val reference: MealComponentReference? = null,
    ) : IngestionNutrition {
        init {
            require(componentRole == "complimentary_side") {
                "meal component role must be complimentary_side"
            }
        }
    }
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
    val amount: Double?,
    val unit: String?,
    val confidence: Double,
    val amountStatus: String = "estimated",
) {
    init {
        require(nutritionClientKey.isNotBlank())
        require(amount == null || amount.isFinite() && amount > 0)
        require(unit == null || unit.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(amountStatus in CONSUMPTION_AMOUNT_STATUSES) {
            "unsupported consumption amount_status: $amountStatus"
        }
    }
}

val CONSUMPTION_AMOUNT_STATUSES = setOf("user_provided", "observed", "estimated", "unknown")

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

    /** Values are sufficient for a Fitness Meal draft, but local verification is still separate. */
    fun isCompleteForFitnessMeal(): Boolean = consumedAt?.let { value ->
        runCatching { java.time.OffsetDateTime.parse(value) }.isSuccess
    } == true && items.isNotEmpty() && items.all { item ->
        item.amount != null && item.unit?.isNotBlank() == true && item.amountStatus != "unknown"
    }
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
    /** Local authority state; external JSON values are discarded at import. */
    val verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
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
    val priceObservations: List<StandalonePriceObservation> = emptyList(),
) {
    init {
        require(priceObservations.map { it.clientKey }.distinct().size == priceObservations.size) {
            "price observation client_key values must be unique"
        }
        require(priceObservations.isEmpty() || schemaVersion == YEONSIK_OCR_V3_SCHEMA) {
            "standalone price observations require yeonsik-ocr.v3"
        }
        require(receipt == null || priceObservations.isEmpty()) {
            "standalone price observations cannot be combined with receipt"
        }
    }
}

enum class IngestionProjection(val wireValue: String) {
    PRICETRACE_RECEIPT("pricetrace_receipt"),
    PRICETRACE_PRICE_OBSERVATION("pricetrace_price_observation"),
    PRICETRACE_MERCHANT_CANDIDATE("pricetrace_merchant_candidate"),
    PRICETRACE_PRODUCT_CANDIDATE("pricetrace_product_candidate"),
    FITNESS_NUTRITION("fitness_nutrition"),
    FITNESS_MEAL("fitness_meal"),
    /** Fitness-owned persistence projection; the v2 wire contract names its target by sink. */
    FITNESS_PRODUCT_NUTRITION_LINK("pricetrace_product_nutrition_link"),
    CASHOS_RECEIPT("cashos_receipt"),
    ;

    companion object {
        fun fromWireValue(value: String): IngestionProjection = when (value) {
            "fitness_product_nutrition_link",
            "catalog_product_nutrition_link",
            FITNESS_PRODUCT_NUTRITION_LINK.wireValue -> FITNESS_PRODUCT_NUTRITION_LINK
            else -> entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported projection target: $value")
        }

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
