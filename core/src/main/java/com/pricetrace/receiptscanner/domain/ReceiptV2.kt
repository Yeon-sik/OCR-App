package com.pricetrace.receiptscanner.domain

import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ReceiptV2(
    val schemaVersion: String = SCHEMA_VERSION,
    val document: ReceiptDocument,
    val merchant: ReceiptMerchant,
    val lineItems: List<ReceiptV2LineItem>,
    val totals: ReceiptV2Totals,
    val payments: List<ReceiptV2Payment>,
    val placeResolution: RestaurantPlaceResolution = RestaurantPlaceResolution.unresolved(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported schema version: $schemaVersion" }
    }

    companion object {
        const val SCHEMA_VERSION = "receipt.v2"
    }
}

data class ReceiptDocument(
    /** Upstream receipt.v2 document ID. External producers may legitimately leave this null. */
    val id: String?,
    /**
     * OCR App-only stable session identity. This is deliberately not part of receipt.v2 JSON and
     * must never be sent as receipt.document.id to an external service.
     */
    val localDocumentId: String? = null,
    val type: String = "receipt",
    val status: ReceiptStatus = ReceiptStatus.DRAFT,
    val issuedOn: String?,
    val issuedAt: String?,
    val currency: String?,
    val fulfillment: ReceiptFulfillment = ReceiptFulfillment(),
    val source: ReceiptSource,
)

/** Mirrors the business-agnostic fulfillment fact in PriceTrace receipt.v2. */
data class ReceiptFulfillment(
    val type: ReceiptFulfillmentType = ReceiptFulfillmentType.UNKNOWN,
    val evidence: ReceiptFulfillmentEvidence = ReceiptFulfillmentEvidence.UNKNOWN,
)

enum class ReceiptFulfillmentType(val wireValue: String) {
    DELIVERY("delivery"), TAKEOUT("takeout"), DINE_IN("dine_in"), UNKNOWN("unknown"),
}

enum class ReceiptFulfillmentEvidence(val wireValue: String) {
    PRINTED("printed"), USER_CONFIRMED("user_confirmed"), UNKNOWN("unknown"),
}

enum class FoodServiceRole(val wireValue: String) {
    MAIN("main"), OPTION("option"), SIDE("side"),
}

/** A separately priced menu line. Its amount is never folded into its parent main menu. */
data class ReceiptFoodService(
    val role: FoodServiceRole,
    val appliesToLineId: String? = null,
)

/** OCR-created or imported drafts must receive a separate local ID before they enter editable storage. */
fun ReceiptDocument.requireLocalDocumentId(): String = requireNotNull(localDocumentId ?: id) {
    "OCR App localDocumentId is required for local persistence and projection idempotency."
}

data class ReceiptSource(
    val captureMethod: String = "ocr",
    val originalDocumentId: String?,
    val sourceImages: List<String>,
    val transcriptionStatus: TranscriptionStatus,
    val notes: List<String> = emptyList(),
    val rawText: String?,
)

const val PURCHASE_LOCAL_TIME_NOTE_PREFIX = "purchase_local_time="
const val PARSER_VERSION_NOTE_PREFIX = "parser_version="

/** Local receipt time retained without inventing a UTC offset for receipt.v2 issued_at. */
fun ReceiptSource.purchaseLocalTime(): String? = notes
    .lastOrNull { it.startsWith(PURCHASE_LOCAL_TIME_NOTE_PREFIX) }
    ?.removePrefix(PURCHASE_LOCAL_TIME_NOTE_PREFIX)
    ?.takeIf(String::isNotBlank)

fun ReceiptSource.withPurchaseLocalTime(value: String?): ReceiptSource {
    val retainedNotes = notes.filterNot { it.startsWith(PURCHASE_LOCAL_TIME_NOTE_PREFIX) }
    val normalized = value?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
        runCatching {
            LocalTime.parse(raw, DateTimeFormatter.ISO_LOCAL_TIME)
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }.getOrNull()
    }
    return copy(
        notes = retainedNotes + listOfNotNull(normalized?.let { "$PURCHASE_LOCAL_TIME_NOTE_PREFIX$it" }),
    )
}

fun ReceiptSource.parserVersion(): String? = notes
    .lastOrNull { it.startsWith(PARSER_VERSION_NOTE_PREFIX) }
    ?.removePrefix(PARSER_VERSION_NOTE_PREFIX)
    ?.takeIf(String::isNotBlank)

fun ReceiptSource.withParserVersion(value: String): ReceiptSource {
    val retainedNotes = notes.filterNot { it.startsWith(PARSER_VERSION_NOTE_PREFIX) }
    val normalized = value.trim().takeIf(String::isNotBlank)
    return copy(
        notes = retainedNotes + listOfNotNull(normalized?.let { "$PARSER_VERSION_NOTE_PREFIX$it" }),
    )
}

data class ReceiptMerchant(
    val name: String?,
    val branchName: String?,
    val businessKind: BusinessKind = BusinessKind.UNKNOWN,
    val retailChannel: RetailChannel = RetailChannel.UNKNOWN,
    val catalogNamespace: String? = null,
    val merchantId: String? = null,
    val businessRegistrationNumber: String? = null,
    val address: String? = null,
    val phone: String? = null,
)

data class ReceiptV2LineItem(
    val id: String,
    val type: ReceiptLineType,
    val description: String?,
    val sourceLineReferences: List<String>,
    val identifiers: List<ReceiptIdentifier>,
    val quantity: ReceiptQuantity?,
    val unitPriceAmountMinor: Long?,
    val grossAmountMinor: Long?,
    val discountAmountMinor: Long?,
    val taxAmountMinor: Long?,
    val netAmountMinor: Long?,
    val confidence: ConfidenceLevel,
    val taxRatePercent: String?,
    val foodService: ReceiptFoodService? = null,
)

data class ReceiptV2Totals(
    val itemsGrossAmountMinor: Long?,
    val discountAmountMinor: Long?,
    val taxAmountMinor: Long?,
    val feeAmountMinor: Long?,
    val tipAmountMinor: Long?,
    val roundingAmountMinor: Long?,
    val grandTotalAmountMinor: Long?,
) {
    /** Compatibility accessor for pre-fulfillment review code; JSON uses items_gross_amount_minor only. */
    val subtotalAmountMinor: Long? get() = itemsGrossAmountMinor
}

data class ReceiptV2Payment(
    val method: String,
    val amountMinor: Long?,
    val status: String,
    val reference: String?,
)

fun ParsedReceipt.toReceiptV2(
    transcriptionStatus: TranscriptionStatus = TranscriptionStatus.PARSED,
    status: ReceiptStatus = ReceiptStatus.DRAFT,
    includePrivateRawText: Boolean = true,
): ReceiptV2 = ReceiptV2(
    document = ReceiptDocument(
        id = originalDocumentId.value,
        localDocumentId = documentId,
        status = status,
        issuedOn = issuedOn.value,
        issuedAt = issuedAt.value,
        currency = currency.value,
        fulfillment = ReceiptFulfillment(),
        source = ReceiptSource(
            originalDocumentId = originalDocumentId.value,
            sourceImages = sourceImages,
            transcriptionStatus = transcriptionStatus,
            notes = issuedTime.value?.let { listOf("$PURCHASE_LOCAL_TIME_NOTE_PREFIX$it") }.orEmpty(),
            rawText = rawText.takeIf { includePrivateRawText },
        ),
    ),
    merchant = ReceiptMerchant(
        name = merchantName.value,
        branchName = branchName.value,
        businessRegistrationNumber = businessRegistrationNumber.value,
        address = address.value,
        phone = phone.value,
    ),
    lineItems = lineItems.map { item ->
        ReceiptV2LineItem(
            id = item.id,
            type = item.type,
            description = item.description.value,
            sourceLineReferences = item.sourceLineReferences,
            identifiers = item.identifiers,
            quantity = item.quantity.value,
            unitPriceAmountMinor = item.unitPriceAmountMinor.value,
            grossAmountMinor = item.grossAmountMinor.value,
            discountAmountMinor = item.discountAmountMinor.value,
            taxAmountMinor = item.taxAmountMinor.value,
            netAmountMinor = item.netAmountMinor.value,
            confidence = item.confidence,
            taxRatePercent = item.taxRatePercent,
            foodService = null,
        )
    },
    totals = ReceiptV2Totals(
        itemsGrossAmountMinor = totals.subtotalAmountMinor.value,
        discountAmountMinor = totals.discountAmountMinor.value,
        taxAmountMinor = totals.taxAmountMinor.value,
        feeAmountMinor = totals.feeAmountMinor.value,
        tipAmountMinor = null,
        roundingAmountMinor = null,
        grandTotalAmountMinor = totals.grandTotalAmountMinor.value,
    ),
    payments = payments.map { payment ->
        ReceiptV2Payment(
            method = payment.method?.takeIf(String::isNotBlank) ?: "unknown",
            amountMinor = payment.amountMinor,
            status = "unknown",
            reference = null,
        )
    },
    placeResolution = placeResolution,
)
