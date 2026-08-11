package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.QuantityUnit
import com.pricetrace.receiptscanner.domain.ReceiptDocument
import com.pricetrace.receiptscanner.domain.ReceiptIdentifier
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptMerchant
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.ReceiptSource
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.ReceiptV2Payment
import com.pricetrace.receiptscanner.domain.ReceiptV2Totals
import com.pricetrace.receiptscanner.domain.RetailChannel
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

object ReceiptV2Json {
    private val compactJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }
    private val prettyJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
    }

    fun encodeCanonical(receipt: ReceiptV2): String = compactJson.encodeToString(
        JsonElement.serializer(),
        canonicalize(receipt.toJsonElement()),
    )

    fun encodePretty(receipt: ReceiptV2): String = prettyJson.encodeToString(
        JsonElement.serializer(),
        receipt.toJsonElement(),
    )

    /** Full record of one row, so a deletion stays recoverable from the review history alone. */
    fun encodeCanonicalLineItem(item: ReceiptV2LineItem): String = compactJson.encodeToString(
        JsonElement.serializer(),
        canonicalize(item.toJsonElement()),
    )

    fun revisionHash(receipt: ReceiptV2): String = StableIds.sha256(encodeCanonical(receipt))

    fun idempotencyKey(receipt: ReceiptV2): String = "receipt:${receipt.document.id}:${revisionHash(receipt)}"

    fun decode(value: String): ReceiptV2 {
        val root = compactJson.parseToJsonElement(value).jsonObject
        root.requireOnlyKeys("schema_version", "document", "merchant", "line_items", "totals", "payments")
        val schemaVersion = root.requiredString("schema_version")
        val document = root.requiredObject("document").toDocument()
        val merchant = root.requiredObject("merchant").toMerchant()
        val lineItems = root.requiredArray("line_items").map { it.jsonObject.toLineItem() }
        val totals = root.requiredObject("totals").toTotals()
        val payments = root.requiredArray("payments").map { it.jsonObject.toPayment() }
        return ReceiptV2(schemaVersion, document, merchant, lineItems, totals, payments)
    }

    private fun ReceiptV2.toJsonElement() = objectOf(
        "schema_version" to JsonPrimitive(schemaVersion),
        "document" to document.toJsonElement(),
        "merchant" to merchant.toJsonElement(),
        "line_items" to JsonArray(lineItems.map { item -> item.toJsonElement() }),
        "totals" to totals.toJsonElement(),
        "payments" to JsonArray(payments.map { payment -> payment.toJsonElement() }),
    )

    private fun ReceiptDocument.toJsonElement() = objectOf(
        "id" to JsonPrimitive(id),
        "type" to JsonPrimitive(type),
        "status" to JsonPrimitive(status.wireValue),
        "issued_on" to issuedOn.jsonStringOrNull(),
        "issued_at" to issuedAt.jsonStringOrNull(),
        "currency" to currency.jsonStringOrNull(),
        "source" to source.toJsonElement(),
    )

    private fun ReceiptSource.toJsonElement() = objectOf(
        "capture_method" to JsonPrimitive(captureMethod),
        "original_document_id" to originalDocumentId.jsonStringOrNull(),
        "source_images" to JsonArray(sourceImages.map(::JsonPrimitive)),
        "transcription_status" to JsonPrimitive(transcriptionStatus.wireValue),
        "notes" to JsonArray(notes.map(::JsonPrimitive)),
        "raw_text" to rawText.jsonStringOrNull(),
    )

    private fun ReceiptMerchant.toJsonElement() = objectOf(
        "name" to name.jsonStringOrNull(),
        "branch_name" to branchName.jsonStringOrNull(),
        "business_kind" to JsonPrimitive(businessKind.wireValue),
        "retail_channel" to JsonPrimitive(retailChannel.wireValue),
        "catalog_namespace" to catalogNamespace.jsonStringOrNull(),
        "merchant_id" to merchantId.jsonStringOrNull(),
        "business_registration_number" to businessRegistrationNumber.jsonStringOrNull(),
        "address" to address.jsonStringOrNull(),
        "phone" to phone.jsonStringOrNull(),
    )

    private fun ReceiptV2LineItem.toJsonElement() = objectOf(
        "id" to JsonPrimitive(id),
        "type" to JsonPrimitive(type.wireValue),
        "description" to description.jsonStringOrNull(),
        "source_line_references" to JsonArray(sourceLineReferences.map(::JsonPrimitive)),
        "identifiers" to JsonArray(identifiers.map { identifier ->
            objectOf(
                "scheme" to JsonPrimitive(identifier.scheme),
                "value" to JsonPrimitive(identifier.value),
            )
        }),
        "quantity" to quantity?.let { value ->
            objectOf(
                "value" to JsonPrimitive(BigDecimal(value.value)),
                "unit" to JsonPrimitive(value.unit.wireValue),
            )
        }.orJsonNull(),
        "unit_price_amount_minor" to unitPriceAmountMinor.jsonLongOrNull(),
        "gross_amount_minor" to grossAmountMinor.jsonLongOrNull(),
        "discount_amount_minor" to discountAmountMinor.jsonLongOrNull(),
        "tax_amount_minor" to taxAmountMinor.jsonLongOrNull(),
        "net_amount_minor" to netAmountMinor.jsonLongOrNull(),
        "confidence" to JsonPrimitive(confidence.wireValue),
        "tax_rate_percent" to taxRatePercent?.let { JsonPrimitive(BigDecimal(it)) }.orJsonNull(),
    )

    private fun ReceiptV2Totals.toJsonElement() = objectOf(
        "subtotal_amount_minor" to subtotalAmountMinor.jsonLongOrNull(),
        "discount_amount_minor" to discountAmountMinor.jsonLongOrNull(),
        "tax_amount_minor" to taxAmountMinor.jsonLongOrNull(),
        "fee_amount_minor" to feeAmountMinor.jsonLongOrNull(),
        "grand_total_amount_minor" to grandTotalAmountMinor.jsonLongOrNull(),
    )

    private fun ReceiptV2Payment.toJsonElement() = objectOf(
        "method" to method.jsonStringOrNull(),
        "amount_minor" to amountMinor.jsonLongOrNull(),
        "source_line_references" to JsonArray(sourceLineReferences.map(::JsonPrimitive)),
    )

    private fun JsonObject.toDocument(): ReceiptDocument {
        requireOnlyKeys("id", "type", "status", "issued_on", "issued_at", "currency", "source")
        return ReceiptDocument(
            id = requiredString("id"),
            type = requiredString("type"),
            status = enumValue(requiredString("status"), ReceiptStatus.entries, ReceiptStatus::wireValue),
            issuedOn = nullableString("issued_on"),
            issuedAt = nullableString("issued_at"),
            currency = nullableString("currency"),
            source = requiredObject("source").toSource(),
        )
    }

    private fun JsonObject.toSource(): ReceiptSource {
        requireOnlyKeys(
            "capture_method",
            "original_document_id",
            "source_images",
            "transcription_status",
            "notes",
            "raw_text",
        )
        return ReceiptSource(
            captureMethod = requiredString("capture_method"),
            originalDocumentId = nullableString("original_document_id"),
            sourceImages = requiredArray("source_images").map { it.jsonPrimitive.content },
            transcriptionStatus = enumValue(
                requiredString("transcription_status"),
                TranscriptionStatus.entries,
                TranscriptionStatus::wireValue,
            ),
            notes = requiredArray("notes").map { it.jsonPrimitive.content },
            rawText = nullableString("raw_text"),
        )
    }

    private fun JsonObject.toMerchant(): ReceiptMerchant {
        requireOnlyKeys(
            "name",
            "branch_name",
            "business_kind",
            "retail_channel",
            "catalog_namespace",
            "merchant_id",
            "business_registration_number",
            "address",
            "phone",
        )
        return ReceiptMerchant(
            name = nullableString("name"),
            branchName = nullableString("branch_name"),
            businessKind = enumValue(requiredString("business_kind"), BusinessKind.entries, BusinessKind::wireValue),
            retailChannel = enumValue(requiredString("retail_channel"), RetailChannel.entries, RetailChannel::wireValue),
            catalogNamespace = nullableString("catalog_namespace"),
            merchantId = nullableString("merchant_id"),
            businessRegistrationNumber = nullableString("business_registration_number"),
            address = nullableString("address"),
            phone = nullableString("phone"),
        )
    }

    private fun JsonObject.toLineItem(): ReceiptV2LineItem {
        requireOnlyKeys(
            "id",
            "type",
            "description",
            "source_line_references",
            "identifiers",
            "quantity",
            "unit_price_amount_minor",
            "gross_amount_minor",
            "discount_amount_minor",
            "tax_amount_minor",
            "net_amount_minor",
            "confidence",
            "tax_rate_percent",
        )
        return ReceiptV2LineItem(
            id = requiredString("id"),
            type = enumValue(requiredString("type"), ReceiptLineType.entries, ReceiptLineType::wireValue),
            description = nullableString("description"),
            sourceLineReferences = requiredArray("source_line_references").map { it.jsonPrimitive.content },
            identifiers = requiredArray("identifiers").map { identifier ->
                identifier.jsonObject.let { value ->
                    value.requireOnlyKeys("scheme", "value")
                    ReceiptIdentifier(value.requiredString("scheme"), value.requiredString("value"))
                }
            },
            quantity = optionalObject("quantity")?.let { quantity ->
                quantity.requireOnlyKeys("value", "unit")
                ReceiptQuantity(
                    value = quantity.requiredNumberText("value"),
                    unit = enumValue(quantity.requiredString("unit"), QuantityUnit.entries, QuantityUnit::wireValue),
                )
            },
            unitPriceAmountMinor = nullableLong("unit_price_amount_minor"),
            grossAmountMinor = nullableLong("gross_amount_minor"),
            discountAmountMinor = nullableLong("discount_amount_minor"),
            taxAmountMinor = nullableLong("tax_amount_minor"),
            netAmountMinor = nullableLong("net_amount_minor"),
            confidence = enumValue(requiredString("confidence"), ConfidenceLevel.entries, ConfidenceLevel::wireValue),
            taxRatePercent = nullableNumberText("tax_rate_percent"),
        )
    }

    private fun JsonObject.toTotals(): ReceiptV2Totals {
        requireOnlyKeys(
            "subtotal_amount_minor",
            "discount_amount_minor",
            "tax_amount_minor",
            "fee_amount_minor",
            "grand_total_amount_minor",
        )
        return ReceiptV2Totals(
            subtotalAmountMinor = nullableLong("subtotal_amount_minor"),
            discountAmountMinor = nullableLong("discount_amount_minor"),
            taxAmountMinor = nullableLong("tax_amount_minor"),
            feeAmountMinor = nullableLong("fee_amount_minor"),
            grandTotalAmountMinor = nullableLong("grand_total_amount_minor"),
        )
    }

    private fun JsonObject.toPayment(): ReceiptV2Payment {
        requireOnlyKeys("method", "amount_minor", "source_line_references")
        return ReceiptV2Payment(
            method = nullableString("method"),
            amountMinor = nullableLong("amount_minor"),
            sourceLineReferences = requiredArray("source_line_references").map { it.jsonPrimitive.content },
        )
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { entry -> entry.key }
                .associate { (key, value) -> key to canonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun objectOf(vararg values: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*values))
    private fun String?.jsonStringOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Long?.jsonLongOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonElement?.orJsonNull(): JsonElement = this ?: JsonNull

    private fun JsonObject.requireOnlyKeys(vararg keys: String) {
        val expected = keys.toSet()
        require(this.keys == expected) {
            "Unexpected or missing keys. Expected=$expected actual=${this.keys}"
        }
    }

    private fun JsonObject.requiredString(key: String): String {
        require(containsKey(key)) { "Missing key: $key" }
        val primitive = requireNotNull(get(key)).jsonPrimitive
        require(primitive.isString) { "Expected string: $key" }
        return primitive.content
    }

    private fun JsonObject.nullableString(key: String): String? {
        require(containsKey(key)) { "Missing key: $key" }
        val value = get(key)
        if (value == JsonNull) return null
        val primitive = requireNotNull(value).jsonPrimitive
        require(primitive.isString) { "Expected string or null: $key" }
        return primitive.content
    }

    private fun JsonObject.requiredNumberText(key: String): String {
        require(containsKey(key)) { "Missing key: $key" }
        val primitive = requireNotNull(get(key)).jsonPrimitive
        require(!primitive.isString) { "Expected number: $key" }
        BigDecimal(primitive.content)
        return primitive.content
    }

    private fun JsonObject.nullableNumberText(key: String): String? {
        require(containsKey(key)) { "Missing key: $key" }
        if (get(key) == JsonNull) return null
        return requiredNumberText(key)
    }

    private fun JsonObject.nullableLong(key: String): Long? {
        require(containsKey(key)) { "Missing key: $key" }
        if (get(key) == JsonNull) return null
        val primitive = requireNotNull(get(key)).jsonPrimitive
        require(!primitive.isString) { "Expected integer: $key" }
        return requireNotNull(primitive.longOrNull) { "Expected signed 64-bit integer: $key" }
    }

    private fun JsonObject.requiredObject(key: String): JsonObject {
        require(containsKey(key)) { "Missing key: $key" }
        return requireNotNull(get(key)).jsonObject
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? {
        require(containsKey(key)) { "Missing key: $key" }
        if (get(key) == JsonNull) return null
        return requireNotNull(get(key)).jsonObject
    }

    private fun JsonObject.requiredArray(key: String): JsonArray {
        require(containsKey(key)) { "Missing key: $key" }
        return requireNotNull(get(key)).jsonArray
    }

    private fun <T> enumValue(value: String, entries: List<T>, wireValue: (T) -> String): T =
        requireNotNull(entries.firstOrNull { wireValue(it) == value }) { "Unsupported enum value: $value" }
}
