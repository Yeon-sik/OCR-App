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
const val YEONSIK_OCR_V4_SCHEMA = "yeonsik-ocr.v4"

private val CANONICAL_EVIDENCE_SOURCE_TYPES = setOf(
    "product_photo",
    "package_label",
    "receipt",
    "order_history",
    "official_listing",
    "manufacturer",
    "user_statement",
    "ocr",
)
private val ATTACHMENT_BACKED_EVIDENCE_SOURCE_TYPES = setOf(
    "product_photo",
    "package_label",
    "receipt",
    "order_history",
    "ocr",
)
private val PRICE_OBSERVATION_EVIDENCE_SOURCE_TYPES = setOf(
    "product_photo",
    "menu_photo",
    "food_photo",
    "user_statement",
)
private val PRICE_OBSERVATION_ATTACHMENT_BACKED_EVIDENCE_SOURCE_TYPES = setOf(
    "product_photo",
    "menu_photo",
    "food_photo",
)
private val PURCHASE_RECORD_EVIDENCE_SOURCE_TYPES = setOf(
    "order_history",
    "payment_history",
    "user_statement",
)
private val PURCHASE_PAYMENT_STATUS_VALUES = setOf("pending", "paid", "cancelled", "refunded", "unknown")
private val PURCHASE_PAYMENT_METHOD_VALUES = setOf(
    "card",
    "bank_transfer",
    "mobile_payment",
    "points",
    "mixed",
    "unknown",
)
private val PURCHASE_SELLER_BUSINESS_KINDS = setOf(
    "retail",
    "food_service",
    "transport",
    "accommodation",
    "healthcare",
    "professional_service",
    "utility",
    "government",
    "financial",
    "marketplace",
    "other",
    "unknown",
)
object IngestionArtifactKeys {
    const val RECEIPT = "receipt"
    const val MERCHANT_CANDIDATE = "merchant_candidate"
    const val CASHOS_HINTS = "cashos_hints"
    const val CONSUMPTION = "consumption"
    const val PRODUCT_CANDIDATE = "product_candidate"
    const val PRICE_OBSERVATION = "price_observation"
    const val PURCHASE_RECORD = "purchase_record"

    fun nutrition(clientKey: String): String = "nutrition:$clientKey"
    fun consumption(clientKey: String): String = "$CONSUMPTION:$clientKey"
    fun productCandidate(clientKey: String): String = "$PRODUCT_CANDIDATE:$clientKey"
    fun priceObservation(clientKey: String): String = "$PRICE_OBSERVATION:$clientKey"
    fun purchaseRecord(clientKey: String): String = "$PURCHASE_RECORD:$clientKey"
}

enum class IngestionMode(val wireValue: String) {
    MERCHANT("merchant"),
    RESTAURANT("restaurant"),
    PACKAGED_PRODUCT("packaged_product"),
    PURCHASE("purchase"),
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
    ORDER_HISTORY("order_history"),
    PAYMENT_HISTORY("payment_history"),
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
    val sourceAttachmentIds: List<String> = emptyList(),
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
    /** A real source product code, if one was actually observed. Never use clientKey here. */
    val merchantSku: String? = null,
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
            merchantSku,
            sourceVersion,
        ).forEach { value ->
            require(value == null || value.isNotBlank()) { "product candidate text facts must not be blank" }
        }
        require(barcodes.distinct().size == barcodes.size) { "product candidate barcodes must be unique" }
        require(evidence.isNotEmpty()) { "product candidate evidence is required" }
        evidence.forEach { item ->
            require(item.sourceAttachmentIds.all(String::isNotBlank)) {
                "product candidate evidence source IDs must be non-empty"
            }
            require(item.sourceType in CANONICAL_EVIDENCE_SOURCE_TYPES && item.field.isNotBlank()) {
                "product candidate evidence source type and field are required"
            }
            if (item.sourceType in ATTACHMENT_BACKED_EVIDENCE_SOURCE_TYPES) {
                require(item.sourceAttachmentIds.isNotEmpty()) {
                    "attachment-backed product candidate evidence requires source attachment IDs"
                }
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

data class StandalonePriceObservationQuantity(
    val value: Double,
    val unit: String,
) {
    init {
        require(value.isFinite() && value > 0.0) {
            "price observation quantity value must be positive"
        }
        require(value % 1.0 == 0.0) {
            "price observation quantity value must be an integer"
        }
        require(unit.isNotBlank()) { "price observation quantity unit is required" }
    }
}

data class StandalonePriceObservationEvidence(
    val sourceType: String,
    val sourceAttachmentIds: List<String>,
    val field: String,
    val observedValue: String? = null,
) {
    init {
        require(sourceType in PRICE_OBSERVATION_EVIDENCE_SOURCE_TYPES) {
            "unsupported price observation evidence source type"
        }
        require(sourceAttachmentIds.distinct().size == sourceAttachmentIds.size) {
            "price observation evidence source attachment IDs must be unique"
        }
        require(sourceAttachmentIds.all(String::isNotBlank)) {
            "price observation evidence source attachment IDs must be non-empty"
        }
        if (sourceType in PRICE_OBSERVATION_ATTACHMENT_BACKED_EVIDENCE_SOURCE_TYPES) {
            require(sourceAttachmentIds.isNotEmpty()) {
                "attachment-backed price observation evidence requires source attachment IDs"
            }
        }
        require(field.isNotBlank()) {
            "price observation evidence field is required"
        }
        require(observedValue == null || observedValue.isNotBlank()) {
            "price observation evidence observed_value must be non-empty when present"
        }
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
    val currency: String = "KRW",
    val quantity: StandalonePriceObservationQuantity? = null,
    val unitPriceAmountMinor: Long? = null,
    val grossAmountMinor: Long? = null,
    val discountAmountMinor: Long? = null,
    val netAmountMinor: Long? = null,
    val sourceAttachmentIds: List<String> = emptyList(),
    val evidence: List<StandalonePriceObservationEvidence>,
    val confidence: Double,
) {
    init {
        require(clientKey.isNotBlank()) { "price observation client_key is required" }
        require(currency == "KRW") { "price observation currency must be KRW" }
        require((observedOn != null) || (observedAt != null)) {
            "price observation observed_on or observed_at is required"
        }
        observedOn?.let { require(runCatching { LocalDate.parse(it) }.isSuccess) { "invalid observed_on" } }
        observedAt?.let { require(runCatching { OffsetDateTime.parse(it) }.isSuccess) { "invalid observed_at" } }
        if (observedOn != null && observedAt != null) {
            val observedDate = LocalDate.parse(observedOn)
            val timestampDate = OffsetDateTime.parse(observedAt).toLocalDate()
            require(observedDate == timestampDate) {
                "price observation observed_on and observed_at must refer to the same calendar date"
            }
        }
        require(sourceAttachmentIds.distinct().size == sourceAttachmentIds.size) {
            "price observation source attachment IDs must be unique"
        }
        require(sourceAttachmentIds.all(String::isNotBlank)) {
            "price observation source attachment IDs must be non-empty"
        }
        evidence.forEach { item ->
            require(item.sourceAttachmentIds.all { it in sourceAttachmentIds }) {
                "price observation evidence source attachment IDs must belong to the observation"
            }
        }
        val expectedQuantityUnit = when (kind) {
            StandalonePriceObservationKind.RETAIL_PURCHASE -> "each"
            StandalonePriceObservationKind.RESTAURANT_PURCHASE -> "serving"
        }
        require(quantity == null || quantity.unit == expectedQuantityUnit) {
            "price observation ${kind.wireValue} quantity unit must be $expectedQuantityUnit"
        }
        listOf(unitPriceAmountMinor, grossAmountMinor, discountAmountMinor, netAmountMinor).forEach { value ->
            require(value == null || value >= 0) { "price observation amounts must be non-negative" }
        }
        require(
            unitPriceAmountMinor != null ||
                grossAmountMinor != null ||
                discountAmountMinor != null ||
                netAmountMinor != null,
        ) {
            "price observation requires at least one observed price fact"
        }
        if (grossAmountMinor != null && discountAmountMinor != null) {
            require(discountAmountMinor <= grossAmountMinor) {
                "price observation discount cannot exceed gross"
            }
        }
        if (grossAmountMinor != null && netAmountMinor != null) {
            require(grossAmountMinor >= netAmountMinor) {
                "price observation gross cannot be below net"
            }
        }
        if (quantity != null && unitPriceAmountMinor != null && netAmountMinor != null) {
            require(
                java.math.BigDecimal(quantity.value.toString()).multiply(java.math.BigDecimal.valueOf(unitPriceAmountMinor))
                    .compareTo(java.math.BigDecimal.valueOf(netAmountMinor)) == 0,
            ) {
                "quantity.value times unit_price_amount_minor must equal net_amount_minor when all are known"
            }
        }
        if (grossAmountMinor != null && discountAmountMinor != null && netAmountMinor != null) {
            require(grossAmountMinor - discountAmountMinor == netAmountMinor) {
                "gross_amount_minor minus discount_amount_minor must equal net_amount_minor when all are known"
            }
        }
        require(evidence.isNotEmpty()) {
            "price observation evidence is required"
        }
        evidence.forEach { item ->
            require(item.sourceType in PRICE_OBSERVATION_EVIDENCE_SOURCE_TYPES)
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
}

typealias CanonicalPriceObservation = StandalonePriceObservation

enum class PurchaseRecordStatus(val wireValue: String) {
    UNKNOWN("unknown"),
    ORDERED("ordered"),
    PENDING("pending"),
    PAID("paid"),
    SHIPPED("shipped"),
    DELIVERED("delivered"),
    CANCELLED("cancelled"),
    REFUNDED("refunded"),
    ;

    companion object {
        fun fromWireValue(value: String): PurchaseRecordStatus = entries.firstOrNull {
            it.wireValue == value
        } ?: error("Unsupported purchase record status: $value")
    }
}

data class PurchaseRecordPayment(
    val method: String? = null,
    val provider: String? = null,
    val status: String? = null,
) {
    init {
        require(method != null || provider != null || status != null) {
            "purchase payment must contain at least one fact"
        }
        listOf(method, provider, status).forEach { value ->
            require(value == null || value.isNotBlank()) {
                "purchase payment facts must not be blank"
            }
            require(value == null || value.length <= 200) {
                "purchase payment facts are too long"
            }
        }
        require(status == null || status in PURCHASE_PAYMENT_STATUS_VALUES) {
            "purchase payment status is not supported by CashOS/PriceTrace V4"
        }
        require(method == null || method in PURCHASE_PAYMENT_METHOD_VALUES) {
            "purchase payment method is not supported by CashOS/PriceTrace V4"
        }
    }
}

data class PurchaseRecordTotals(
    val subtotalAmountKrw: Long? = null,
    val discountAmountKrw: Long? = null,
    val shippingAmountKrw: Long? = null,
    val taxAmountKrw: Long? = null,
    val grandTotalAmountKrw: Long? = null,
    val paidAmountKrw: Long? = null,
) {
    init {
        require(listOf(
            subtotalAmountKrw,
            discountAmountKrw,
            shippingAmountKrw,
            taxAmountKrw,
            grandTotalAmountKrw,
            paidAmountKrw,
        ).any { it != null }) { "purchase totals require at least one KRW fact" }
        listOf(
            subtotalAmountKrw,
            discountAmountKrw,
            shippingAmountKrw,
            taxAmountKrw,
            grandTotalAmountKrw,
            paidAmountKrw,
        ).forEach { value ->
            require(value == null || value >= 0) {
                "purchase totals must be non-negative KRW values"
            }
        }
        if (grandTotalAmountKrw != null && paidAmountKrw != null) {
            require(grandTotalAmountKrw == paidAmountKrw) {
                "grand_total_amount_krw and paid_amount_krw conflict"
            }
        }
        if (subtotalAmountKrw != null && discountAmountKrw != null &&
            shippingAmountKrw != null && taxAmountKrw != null && grandTotalAmountKrw != null
        ) {
            require(subtotalAmountKrw - discountAmountKrw + shippingAmountKrw + taxAmountKrw == grandTotalAmountKrw) {
                "purchase totals do not reconcile"
            }
        }
    }

    /** CashOS requires one authoritative transaction amount; no amount is invented here. */
    val cashOsAmountKrw: Long?
        get() = paidAmountKrw ?: grandTotalAmountKrw
}

data class PurchaseRecordLine(
    val lineKey: String? = null,
    val productClientKey: String? = null,
    val description: String? = null,
    val sellerOverride: String? = null,
    val optionText: String? = null,
    val priceStatus: String = "itemized",
    val merchantSku: String? = null,
    val quantity: Double? = null,
    val unitPriceAmountKrw: Long? = null,
    val grossAmountKrw: Long? = null,
    val discountAmountKrw: Long? = null,
    val netAmountKrw: Long? = null,
) {
    init {
        require(lineKey == null || lineKey.isNotBlank())
        require(lineKey == null || lineKey.length <= 200)
        require(!description.isNullOrBlank()) {
            "purchase line description is required for downstream evidence contracts"
        }
        require(description.length <= 500)
        require(productClientKey == null || productClientKey.isNotBlank())
        require(productClientKey == null || productClientKey.length <= 200)
        require(sellerOverride == null || sellerOverride.isNotBlank())
        require(sellerOverride == null || sellerOverride.length <= 500)
        require(optionText == null || optionText.isNotBlank())
        require(optionText == null || optionText.length <= 500)
        require(priceStatus in setOf("itemized", "ambiguous", "unknown")) {
            "purchase line price_status is invalid"
        }
        require(merchantSku == null || merchantSku.isNotBlank())
        require(merchantSku == null || merchantSku.length <= 300)
        require(merchantSku == null || merchantSku != productClientKey) {
            "merchant_sku cannot reuse product_client_key"
        }
        require(quantity == null || quantity.isFinite() && quantity > 0.0) {
            "purchase line quantity must be positive when present"
        }
        listOf(unitPriceAmountKrw, grossAmountKrw, discountAmountKrw, netAmountKrw).forEach { value ->
            require(value == null || value >= 0) {
                "purchase line price facts must be non-negative KRW values"
            }
        }
        if (grossAmountKrw != null && discountAmountKrw != null) {
            require(discountAmountKrw <= grossAmountKrw) {
                "purchase line discount cannot exceed gross"
            }
        }
        if (grossAmountKrw != null && netAmountKrw != null) {
            require(grossAmountKrw >= netAmountKrw) {
                "purchase line gross cannot be below net"
            }
        }
        if (quantity != null && unitPriceAmountKrw != null && netAmountKrw != null) {
            require(
                BigDecimal(quantity.toString())
                    .multiply(BigDecimal.valueOf(unitPriceAmountKrw))
                    .compareTo(BigDecimal.valueOf(netAmountKrw)) == 0,
            ) { "purchase line quantity times unit price must equal net" }
        }
        if (grossAmountKrw != null && discountAmountKrw != null && netAmountKrw != null) {
            require(grossAmountKrw - discountAmountKrw == netAmountKrw) {
                "purchase line gross minus discount must equal net"
            }
        }
    }
}

data class PurchaseRecordEvidence(
    val sourceType: String,
    val sourceAttachmentIds: List<String> = emptyList(),
    val sourceRef: String? = null,
    val field: String,
    val observedValue: String? = null,
) {
    init {
        require(sourceType in PURCHASE_RECORD_EVIDENCE_SOURCE_TYPES) {
            "unsupported purchase record evidence source type"
        }
        require(sourceAttachmentIds.distinct().size == sourceAttachmentIds.size) {
            "purchase record evidence attachment IDs must be unique"
        }
        require(sourceAttachmentIds.all(String::isNotBlank)) {
            "purchase record evidence attachment IDs must be non-empty"
        }
        if (sourceType in setOf("order_history", "payment_history")) {
            require(sourceAttachmentIds.isNotEmpty()) {
                "history evidence requires source attachment IDs"
            }
        }
        require(sourceRef == null || sourceRef.isNotBlank()) {
            "purchase record evidence source_ref must not be blank"
        }
        require(field.isNotBlank()) { "purchase record evidence field is required" }
        require(observedValue == null || observedValue.isNotBlank()) {
            "purchase record evidence observed_value must not be blank"
        }
    }
}

enum class PurchaseRecordKind {
    RETAIL,
    RESTAURANT,
    OTHER,
    UNKNOWN,
    PAYMENT_ONLY,
}

/** Explicit source fact. This is never inferred from line shape or product_client_key. */
enum class PurchaseKind(val wireValue: String) {
    RETAIL("retail"),
    RESTAURANT("restaurant"),
    OTHER("other"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String): PurchaseKind = entries.firstOrNull {
            it.wireValue == value
        } ?: error("Unsupported purchase_kind: $value")
    }
}

data class PurchaseRecord(
    val clientKey: String,
    val platform: String,
    val platformCode: String? = null,
    val seller: String? = null,
    val sellerBranchName: String? = null,
    val sellerSourceNamespace: String? = null,
    val sellerSourceCode: String? = null,
    val sellerBusinessKind: String? = null,
    val sourceVersion: String? = null,
    val orderReference: String? = null,
    val purchaseKind: PurchaseKind = PurchaseKind.UNKNOWN,
    val orderedOn: String? = null,
    val orderedAt: String? = null,
    val paidOn: String? = null,
    val paidAt: String? = null,
    val status: PurchaseRecordStatus = PurchaseRecordStatus.UNKNOWN,
    val currency: String = "KRW",
    val totals: PurchaseRecordTotals,
    val payment: PurchaseRecordPayment? = null,
    val lineItems: List<PurchaseRecordLine> = emptyList(),
    val evidence: List<PurchaseRecordEvidence>,
    val confidence: Double,
) {
    init {
        require(clientKey.isNotBlank()) { "purchase record client_key is required" }
        require(platform.isNotBlank()) { "purchase record platform is required" }
        require(platform.length <= 100)
        require(platformCode == null || platformCode.isNotBlank())
        require(platformCode == null || platformCode.length <= 100)
        require(seller == null || seller.isNotBlank())
        require(seller == null || seller.length <= 500)
        require(sellerBranchName == null || sellerBranchName.isNotBlank())
        require(sellerBranchName == null || sellerBranchName.length <= 300)
        require(sellerSourceNamespace == null || sellerSourceNamespace.isNotBlank())
        require(sellerSourceNamespace == null || sellerSourceNamespace.length <= 200)
        require(sellerSourceCode == null || sellerSourceCode.isNotBlank())
        require(sellerSourceCode == null || sellerSourceCode.length <= 300)
        require(sellerBusinessKind == null || sellerBusinessKind in PURCHASE_SELLER_BUSINESS_KINDS)
        require(sourceVersion == null || sourceVersion.isNotBlank())
        require(sourceVersion == null || sourceVersion.length <= 100)
        require(orderReference == null || orderReference.isNotBlank())
        require(orderReference == null || orderReference.length <= 200)
        require(seller == null || !platform.trim().equals(seller.trim(), ignoreCase = true)) {
            "purchase record platform and seller must be distinct"
        }
        require(seller == null || (sellerSourceNamespace == null) == (sellerSourceCode == null)) {
            "seller source namespace and code must be provided together"
        }
        require(seller != null || sellerBranchName == null)
        require(seller != null || sellerSourceNamespace == null)
        require(seller != null || sellerSourceCode == null)
        require(seller != null || sellerBusinessKind == null)
        require(currency == "KRW") { "purchase record currency must be KRW" }
        validateDate(orderedOn, "ordered_on")
        validateDate(orderedAt, "ordered_at", timestamp = true)
        validateDate(paidOn, "paid_on")
        validateDate(paidAt, "paid_at", timestamp = true)
        if (orderedOn != null && orderedAt != null) {
            require(LocalDate.parse(orderedOn) == OffsetDateTime.parse(orderedAt).toLocalDate()) {
                "ordered_on and ordered_at must refer to the same date"
            }
        }
        if (paidOn != null && paidAt != null) {
            require(LocalDate.parse(paidOn) == OffsetDateTime.parse(paidAt).toLocalDate()) {
                "paid_on and paid_at must refer to the same date"
            }
        }
        require(lineItems.size <= MAX_PURCHASE_RECORD_LINES) {
            "purchase record has too many lines; maximum is $MAX_PURCHASE_RECORD_LINES"
        }
        require(evidence.isNotEmpty()) { "purchase record evidence is required" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "purchase record confidence must be between 0 and 1"
        }
        lineItems.forEach { line ->
            require(line.sellerOverride == null ||
                !platform.trim().equals(line.sellerOverride.trim(), ignoreCase = true)) {
                "purchase line seller override must differ from platform"
            }
        }
    }

    val kind: PurchaseRecordKind
        get() = when {
            lineItems.isEmpty() -> PurchaseRecordKind.PAYMENT_ONLY
            purchaseKind == PurchaseKind.RETAIL -> PurchaseRecordKind.RETAIL
            purchaseKind == PurchaseKind.RESTAURANT -> PurchaseRecordKind.RESTAURANT
            purchaseKind == PurchaseKind.OTHER -> PurchaseRecordKind.OTHER
            else -> PurchaseRecordKind.UNKNOWN
        }

    /** Source storage can retain an unresolved or non-settled purchase without making an observation. */
    val priceTraceSourceEligible: Boolean
        get() = lineItems.isNotEmpty() && (effectiveOrderedOn != null || effectivePaidOn != null)

    /** Only a settled, explicitly classified retail/restaurant purchase may create a normal observation. */
    val priceObservationEligible: Boolean
        get() = priceTraceSourceEligible &&
            purchaseKind in setOf(PurchaseKind.RETAIL, PurchaseKind.RESTAURANT) &&
            isSettled && !hasBlockedState

    /** CashOS V4 currently creates only confirmed EXPENSE rows, so require payment settlement. */
    val cashOsTransactionEligible: Boolean
        get() = totals.cashOsAmountKrw != null &&
            (effectivePaidOn != null || effectiveOrderedOn != null) &&
            isPaymentConfirmed && !hasBlockedState

    /** Derived only for routing; ordered_at/paid_at remain unchanged on the wire. */
    val effectiveOrderedOn: String?
        get() = orderedOn ?: orderedAt?.let {
            runCatching { OffsetDateTime.parse(it).toLocalDate().toString() }.getOrNull()
        }

    /** Derived only for routing; ordered_at/paid_at remain unchanged on the wire. */
    val effectivePaidOn: String?
        get() = paidOn ?: paidAt?.let {
            runCatching { OffsetDateTime.parse(it).toLocalDate().toString() }.getOrNull()
        }

    private val isSettled: Boolean
        get() = status in setOf(
            PurchaseRecordStatus.PAID,
            PurchaseRecordStatus.SHIPPED,
            PurchaseRecordStatus.DELIVERED,
        ) || payment?.status == "paid"

    private val isPaymentConfirmed: Boolean
        get() = status == PurchaseRecordStatus.PAID || payment?.status == "paid"

    private val hasBlockedState: Boolean
        get() = status in setOf(
            PurchaseRecordStatus.PENDING,
            PurchaseRecordStatus.CANCELLED,
            PurchaseRecordStatus.REFUNDED,
            PurchaseRecordStatus.UNKNOWN,
        ) || payment?.status in setOf("pending", "cancelled", "refunded", "unknown")

    private fun validateDate(value: String?, key: String, timestamp: Boolean = false) {
        value ?: return
        require(value.isNotBlank()) { "$key must not be blank" }
        if (timestamp) {
            require(runCatching { OffsetDateTime.parse(value) }.isSuccess) { "$key must be an ISO timestamp" }
        } else {
            require(runCatching { LocalDate.parse(value) }.isSuccess) { "$key must be an ISO date" }
        }
    }
}

const val MAX_PURCHASE_RECORD_LINES = 100

fun PurchaseRecord.conflictFields(): Set<String> = evidence
    .filter { !it.observedValue.isNullOrBlank() }
    .groupBy { it.field }
    .mapValues { (_, facts) -> facts.map { it.observedValue!!.trim() }.distinct() }
    .filterValues { it.size > 1 }
    .keys

fun ProductCandidate.conflictFields(): Set<String> = evidence
    .filter { !it.observedValue.isNullOrBlank() }
    .groupBy { it.field }
    .mapValues { (_, facts) -> facts.map { it.observedValue!!.trim() }.distinct() }
    .filterValues { it.size > 1 }
    .keys

sealed interface IngestionNutrition {
    val clientKey: String
    val lineId: String?

    data class ProductLabel(
        override val clientKey: String,
        val draft: NutritionLabelDraft,
        override val lineId: String? = null,
        /** Local relation to the Product Candidate carrying the observed hierarchy facts. */
        val productClientKey: String? = null,
    ) : IngestionNutrition {
        init {
            require(productClientKey == null || productClientKey.isNotBlank()) {
                "product label product_client_key must be non-empty when provided"
            }
        }
    }

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
    CONFLICT("conflict"),
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
    val purchaseRecords: List<PurchaseRecord> = emptyList(),
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
        require(purchaseRecords.map { it.clientKey }.distinct().size == purchaseRecords.size) {
            "purchase record client_key values must be unique"
        }
        require(purchaseRecords.isEmpty() || schemaVersion == YEONSIK_OCR_V4_SCHEMA) {
            "purchase records require yeonsik-ocr.v4"
        }
        require(purchaseRecords.isEmpty() || mode == IngestionMode.PURCHASE) {
            "purchase records require purchase mode"
        }
        require(purchaseRecords.isEmpty() || receipt == null) {
            "purchase records cannot be combined with receipt"
        }
        require(purchaseRecords.isEmpty() || priceObservations.isEmpty()) {
            "purchase records cannot be combined with legacy price observations"
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
    CASHOS_TRANSACTION("cashos_transaction"),
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
    /** Non-null only for a .yeonsik bundle; separates identical canonical JSON with different evidence. */
    val bundleFingerprint: String? = null,
)
