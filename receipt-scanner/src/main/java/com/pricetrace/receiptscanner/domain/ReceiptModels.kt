package com.pricetrace.receiptscanner.domain

data class ReceiptPage(
    val id: String,
    val documentId: String,
    val storageKey: String,
    val sha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val pageIndex: Int,
    val createdAt: String,
    val revision: Int = 1,
)

data class FieldProvenance(
    val sourcePageId: String,
    val ocrLineId: String,
    val boundingBox: BoundingBox?,
    val rawText: String,
    val parserRuleId: String,
    val confidence: Float?,
    val userModified: Boolean = false,
)

data class ParsedField<T>(
    val value: T?,
    val provenance: List<FieldProvenance> = emptyList(),
)

enum class ReceiptLineType(val wireValue: String) {
    PRODUCT("product"),
    SERVICE("service"),
    DISCOUNT("discount"),
    FEE("fee"),
    TAX("tax"),
    TIP("tip"),
    REFUND("refund"),
    ROUNDING("rounding"),
    OTHER("other"),
}

enum class ConfidenceLevel(val wireValue: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    USER_VERIFIED("user_verified"),
}

enum class ReceiptStatus(val wireValue: String) {
    DRAFT("draft"),
    FINAL("final"),
    VOIDED("voided"),
    REFUNDED("refunded"),
    UNKNOWN("unknown"),
}

enum class TranscriptionStatus(val wireValue: String) {
    UNPROCESSED("unprocessed"),
    PARSED("parsed"),
    VERIFIED("verified"),
    USER_VERIFIED("user_verified"),
    UNKNOWN("unknown"),
}

enum class BusinessKind(val wireValue: String) {
    RETAIL("retail"),
    FOOD_SERVICE("food_service"),
    TRANSPORT("transport"),
    ACCOMMODATION("accommodation"),
    HEALTHCARE("healthcare"),
    PROFESSIONAL_SERVICE("professional_service"),
    UTILITY("utility"),
    GOVERNMENT("government"),
    FINANCIAL("financial"),
    MARKETPLACE("marketplace"),
    OTHER("other"),
    UNKNOWN("unknown"),
}

enum class RetailChannel(val wireValue: String) {
    PX("px"),
    REGULAR("regular"),
    UNKNOWN("unknown"),
}

enum class QuantityUnit(val wireValue: String) {
    EACH("each"),
    GRAM("g"),
    KILOGRAM("kg"),
    MILLILITER("ml"),
    LITER("l"),
    UNKNOWN("unknown"),
}

data class ReceiptIdentifier(
    val scheme: String,
    val value: String,
)

data class ReceiptQuantity(
    /** Exact decimal text. It is emitted as a JSON number, never through Double. */
    val value: String,
    val unit: QuantityUnit = QuantityUnit.EACH,
)

data class ParsedLineItem(
    val id: String,
    val type: ReceiptLineType,
    val description: ParsedField<String>,
    val sourceLineReferences: List<String>,
    val identifiers: List<ReceiptIdentifier> = emptyList(),
    val quantity: ParsedField<ReceiptQuantity> = ParsedField(null),
    val unitPriceAmountMinor: ParsedField<Long> = ParsedField(null),
    val grossAmountMinor: ParsedField<Long> = ParsedField(null),
    val discountAmountMinor: ParsedField<Long> = ParsedField(null),
    val taxAmountMinor: ParsedField<Long> = ParsedField(null),
    val netAmountMinor: ParsedField<Long> = ParsedField(null),
    val confidence: ConfidenceLevel = ConfidenceLevel.LOW,
    val taxRatePercent: String? = null,
)

data class ParsedTotals(
    val subtotalAmountMinor: ParsedField<Long> = ParsedField(null),
    val discountAmountMinor: ParsedField<Long> = ParsedField(null),
    val taxAmountMinor: ParsedField<Long> = ParsedField(null),
    val feeAmountMinor: ParsedField<Long> = ParsedField(null),
    val grandTotalAmountMinor: ParsedField<Long> = ParsedField(null),
)

data class ParsedPayment(
    val method: String?,
    val amountMinor: Long?,
    val sourceLineReferences: List<String>,
)

data class ParsedReceipt(
    val documentId: String,
    val sourceImages: List<String>,
    val rawText: String?,
    val merchantName: ParsedField<String> = ParsedField(null),
    val branchName: ParsedField<String> = ParsedField(null),
    val businessRegistrationNumber: ParsedField<String> = ParsedField(null),
    val address: ParsedField<String> = ParsedField(null),
    val phone: ParsedField<String> = ParsedField(null),
    val issuedOn: ParsedField<String> = ParsedField(null),
    val issuedTime: ParsedField<String> = ParsedField(null),
    val issuedAt: ParsedField<String> = ParsedField(null),
    val originalDocumentId: ParsedField<String> = ParsedField(null),
    val currency: ParsedField<String> = ParsedField(null),
    val lineItems: List<ParsedLineItem> = emptyList(),
    val totals: ParsedTotals = ParsedTotals(),
    val payments: List<ParsedPayment> = emptyList(),
)
