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
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported schema version: $schemaVersion" }
    }

    companion object {
        const val SCHEMA_VERSION = "receipt.v2"
    }
}

data class ReceiptDocument(
    val id: String,
    val type: String = "receipt",
    val status: ReceiptStatus = ReceiptStatus.DRAFT,
    val issuedOn: String?,
    val issuedAt: String?,
    val currency: String?,
    val source: ReceiptSource,
) {
    init {
        require(type == "receipt") { "Receipt document type must be receipt" }
    }
}

data class ReceiptSource(
    val captureMethod: String = "ocr",
    val originalDocumentId: String?,
    val sourceImages: List<String>,
    val transcriptionStatus: TranscriptionStatus,
    val notes: List<String> = emptyList(),
    val rawText: String?,
) {
    init {
        require(captureMethod == "ocr") { "Receipt capture method must be ocr" }
    }
}

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
)

data class ReceiptV2Totals(
    val subtotalAmountMinor: Long?,
    val discountAmountMinor: Long?,
    val taxAmountMinor: Long?,
    val feeAmountMinor: Long?,
    val grandTotalAmountMinor: Long?,
)

data class ReceiptV2Payment(
    val method: String?,
    val amountMinor: Long?,
    val sourceLineReferences: List<String>,
)

fun ParsedReceipt.toReceiptV2(
    transcriptionStatus: TranscriptionStatus = TranscriptionStatus.PARSED,
    status: ReceiptStatus = ReceiptStatus.DRAFT,
    includePrivateRawText: Boolean = true,
): ReceiptV2 = ReceiptV2(
    document = ReceiptDocument(
        id = documentId,
        status = status,
        issuedOn = issuedOn.value,
        issuedAt = issuedAt.value,
        currency = currency.value,
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
        )
    },
    totals = ReceiptV2Totals(
        subtotalAmountMinor = totals.subtotalAmountMinor.value,
        discountAmountMinor = totals.discountAmountMinor.value,
        taxAmountMinor = totals.taxAmountMinor.value,
        feeAmountMinor = totals.feeAmountMinor.value,
        grandTotalAmountMinor = totals.grandTotalAmountMinor.value,
    ),
    payments = payments.map { payment ->
        ReceiptV2Payment(
            method = payment.method,
            amountMinor = payment.amountMinor,
            sourceLineReferences = payment.sourceLineReferences,
        )
    },
)
