package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.domain.ReceiptFoodService
import com.pricetrace.receiptscanner.domain.ReceiptFulfillment
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentEvidence
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentType
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
import java.time.LocalDate
import java.time.OffsetDateTime

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

    fun idempotencyKey(receipt: ReceiptV2): String = "receipt:${receipt.document.id ?: "unassigned"}:${revisionHash(receipt)}"

    fun decode(value: String): ReceiptV2 {
        val root = compactJson.parseToJsonElement(value).jsonObject
        root.requireOnlyKeys("schema_version", "document", "merchant", "line_items", "totals", "payments")
        val schemaVersion = root.requiredString("schema_version")
        val document = root.requiredObject("document").toDocument()
        val merchant = root.requiredObject("merchant").toMerchant()
        val lineItems = root.requiredArray("line_items").map { it.jsonObject.toLineItem() }
        val totals = root.requiredObject("totals").toTotals()
        val payments = root.requiredArray("payments").map { it.jsonObject.toPayment() }
        return ReceiptV2(schemaVersion, document, merchant, lineItems, totals, payments).also(::validateFoodServiceLinks)
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
        "id" to id.jsonStringOrNull(),
        "type" to JsonPrimitive(type),
        "status" to JsonPrimitive(status.wireValue),
        "issued_on" to issuedOn.jsonStringOrNull(),
        "issued_at" to issuedAt.jsonStringOrNull(),
        "currency" to currency.jsonStringOrNull(),
        "fulfillment" to fulfillment.toJsonElement(),
        "source" to source.toJsonElement(),
    )

    private fun ReceiptFulfillment.toJsonElement() = objectOf(
        "type" to JsonPrimitive(type.wireValue),
        "evidence" to JsonPrimitive(evidence.wireValue),
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
        "food_service" to foodService?.toJsonElement().orJsonNull(),
    )

    private fun ReceiptFoodService.toJsonElement() = objectOf(
        "role" to JsonPrimitive(role.wireValue),
        "applies_to_line_id" to appliesToLineId.jsonStringOrNull(),
    )

    private fun ReceiptV2Totals.toJsonElement() = objectOf(
        "items_gross_amount_minor" to itemsGrossAmountMinor.jsonLongOrNull(),
        "discount_amount_minor" to discountAmountMinor.jsonLongOrNull(),
        "tax_amount_minor" to taxAmountMinor.jsonLongOrNull(),
        "fee_amount_minor" to feeAmountMinor.jsonLongOrNull(),
        "tip_amount_minor" to tipAmountMinor.jsonLongOrNull(),
        "rounding_amount_minor" to roundingAmountMinor.jsonLongOrNull(),
        "grand_total_amount_minor" to grandTotalAmountMinor.jsonLongOrNull(),
    )

    private fun ReceiptV2Payment.toJsonElement() = objectOf(
        "method" to JsonPrimitive(method),
        "amount_minor" to amountMinor.jsonLongOrNull(),
        "status" to JsonPrimitive(status),
        "reference" to reference.jsonStringOrNull(),
    )

    private fun JsonObject.toDocument(): ReceiptDocument {
        requireOneOfKeySets(
            setOf("id", "type", "status", "issued_on", "issued_at", "currency", "fulfillment", "source"),
            setOf("id", "type", "status", "issued_on", "issued_at", "currency", "source"),
        )
        return ReceiptDocument(
            id = nullableNonEmptyString("id"),
            type = requiredEnumString("type", DOCUMENT_TYPES),
            status = enumValue(requiredString("status"), ReceiptStatus.entries, ReceiptStatus::wireValue),
            issuedOn = nullableIsoDate("issued_on"),
            issuedAt = nullableIsoOffsetDateTime("issued_at"),
            currency = nullableCurrencyCode("currency"),
            fulfillment = if (containsKey("fulfillment")) requiredObject("fulfillment").toFulfillment() else ReceiptFulfillment(),
            source = requiredObject("source").toSource(),
        )
    }

    private fun JsonObject.toFulfillment(): ReceiptFulfillment {
        requireOnlyKeys("type", "evidence")
        return ReceiptFulfillment(
            type = enumValue(requiredString("type"), ReceiptFulfillmentType.entries, ReceiptFulfillmentType::wireValue),
            evidence = enumValue(requiredString("evidence"), ReceiptFulfillmentEvidence.entries, ReceiptFulfillmentEvidence::wireValue),
        )
    }

    private fun JsonObject.toSource(): ReceiptSource {
        requireKeysWithOptional(
            required = setOf("capture_method", "original_document_id", "source_images", "transcription_status", "notes"),
            optional = setOf("raw_text"),
        )
        return ReceiptSource(
            captureMethod = requiredEnumString("capture_method", CAPTURE_METHODS),
            originalDocumentId = nullableNonEmptyString("original_document_id"),
            sourceImages = requiredArray("source_images").map { it.jsonPrimitive.content },
            transcriptionStatus = enumValue(
                requiredString("transcription_status"),
                TranscriptionStatus.entries,
                TranscriptionStatus::wireValue,
            ),
            notes = requiredArray("notes").map { it.jsonPrimitive.content },
            rawText = if (containsKey("raw_text")) nullableString("raw_text") else null,
        )
    }
    private fun JsonObject.toMerchant(): ReceiptMerchant {
        requireKeysWithOptional(
            required = setOf("name", "branch_name", "merchant_id", "business_registration_number", "address", "phone"),
            optional = setOf("business_kind", "retail_channel", "catalog_namespace"),
        )
        return ReceiptMerchant(
            name = nullableNonEmptyString("name"),
            branchName = nullableNonEmptyString("branch_name"),
            businessKind = if (containsKey("business_kind")) enumValue(requiredString("business_kind"), BusinessKind.entries, BusinessKind::wireValue) else BusinessKind.UNKNOWN,
            retailChannel = if (containsKey("retail_channel")) enumValue(requiredString("retail_channel"), RetailChannel.entries, RetailChannel::wireValue) else RetailChannel.UNKNOWN,
            catalogNamespace = if (containsKey("catalog_namespace")) nullableTrimmedNonEmptyString("catalog_namespace") else null,
            merchantId = nullableNonEmptyString("merchant_id"),
            businessRegistrationNumber = nullableNonEmptyString("business_registration_number"),
            address = nullableNonEmptyString("address"),
            phone = nullableNonEmptyString("phone"),
        )
    }
    private fun JsonObject.toLineItem(): ReceiptV2LineItem {
        requireKeysWithOptional(
            required = setOf(
                "id", "type", "description", "source_line_references", "identifiers", "quantity",
                "unit_price_amount_minor", "gross_amount_minor", "discount_amount_minor", "tax_amount_minor",
                "net_amount_minor", "confidence", "tax_rate_percent",
            ),
            optional = setOf("food_service"),
        )
        return ReceiptV2LineItem(
            id = requiredString("id"),
            type = enumValue(requiredString("type"), ReceiptLineType.entries, ReceiptLineType::wireValue),
            description = nullableNonEmptyString("description"),
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
                    value = quantity.requiredPositiveNumberText("value"),
                    unit = enumValue(quantity.requiredString("unit"), QuantityUnit.entries, QuantityUnit::wireValue),
                )
            },
            unitPriceAmountMinor = nullableNonNegativeLong("unit_price_amount_minor"),
            grossAmountMinor = nullableNonNegativeLong("gross_amount_minor"),
            discountAmountMinor = nullableNonNegativeLong("discount_amount_minor"),
            taxAmountMinor = nullableNonNegativeLong("tax_amount_minor"),
            netAmountMinor = nullableLong("net_amount_minor"),
            confidence = enumValue(requiredString("confidence"), ConfidenceLevel.entries, ConfidenceLevel::wireValue),
            taxRatePercent = nullableNonNegativeNumberText("tax_rate_percent"),
            foodService = if (containsKey("food_service")) optionalObject("food_service")?.toFoodService() else null,
        )
    }
    private fun JsonObject.toFoodService(): ReceiptFoodService {
        requireOnlyKeys("role", "applies_to_line_id")
        return ReceiptFoodService(
            role = enumValue(requiredString("role"), FoodServiceRole.entries, FoodServiceRole::wireValue),
            appliesToLineId = nullableNonEmptyString("applies_to_line_id"),
        )
    }

    private fun JsonObject.toTotals(): ReceiptV2Totals {
        requireOnlyKeys(
            "items_gross_amount_minor", "discount_amount_minor", "tax_amount_minor", "fee_amount_minor",
            "tip_amount_minor", "rounding_amount_minor", "grand_total_amount_minor",
        )
        return ReceiptV2Totals(
            itemsGrossAmountMinor = nullableNonNegativeLong("items_gross_amount_minor"),
            discountAmountMinor = nullableNonNegativeLong("discount_amount_minor"),
            taxAmountMinor = nullableNonNegativeLong("tax_amount_minor"),
            feeAmountMinor = nullableLong("fee_amount_minor"),
            tipAmountMinor = nullableLong("tip_amount_minor"),
            roundingAmountMinor = nullableLong("rounding_amount_minor"),
            grandTotalAmountMinor = nullableLong("grand_total_amount_minor"),
        )
    }

    private fun JsonObject.toPayment(): ReceiptV2Payment {
        requireOnlyKeys("method", "amount_minor", "status", "reference")
        return ReceiptV2Payment(
            method = requiredEnumString("method", PAYMENT_METHODS),
            amountMinor = nullableLong("amount_minor"),
            status = requiredEnumString("status", PAYMENT_STATUSES),
            reference = nullableNonEmptyString("reference"),
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

    private fun JsonObject.requireOneOfKeySets(vararg expectedSets: Set<String>) {
        require(expectedSets.any { this.keys == it }) {
            "Unexpected or missing keys. Expected one of=$expectedSets actual=${this.keys}"
        }
    }

    private fun JsonObject.requireKeysWithOptional(required: Set<String>, optional: Set<String> = emptySet()) {
        require(this.keys.containsAll(required) && this.keys subtract (required + optional) == emptySet<String>()) {
            "Unexpected or missing keys. Required=$required optional=$optional actual=${this.keys}"
        }
    }

    private fun JsonObject.requireOnlyKeys(vararg keys: String) {
        val expected = keys.toSet()
        require(this.keys == expected) {
            "Unexpected or missing keys. Expected=$expected actual=${this.keys}"
        }
    }

    private fun JsonObject.nullableNonEmptyString(key: String): String? =
        nullableString(key)?.also { require(it.isNotEmpty()) { "$key must not be empty" } }

    private fun JsonObject.nullableTrimmedNonEmptyString(key: String): String? =
        nullableString(key)?.trim()?.also { require(it.isNotEmpty()) { "$key must not be empty" } }

    private fun JsonObject.nullableIsoDate(key: String): String? =
        nullableNonEmptyString(key)?.also {
            require(runCatching { LocalDate.parse(it) }.isSuccess) { "$key must be an ISO date" }
        }

    private fun JsonObject.nullableIsoOffsetDateTime(key: String): String? =
        nullableNonEmptyString(key)?.also {
            require(runCatching { OffsetDateTime.parse(it) }.isSuccess) { "$key must be an offset datetime" }
        }

    private fun JsonObject.nullableCurrencyCode(key: String): String? =
        nullableNonEmptyString(key)?.also {
            require(it.matches(Regex("[A-Z]{3}"))) { "$key must be an uppercase ISO currency code" }
        }
    private fun JsonObject.requiredString(key: String): String {
        require(containsKey(key)) { "Missing key: $key" }
        val primitive = requireNotNull(get(key)).jsonPrimitive
        require(primitive.isString) { "Expected string: $key" }
        require(primitive.content.isNotEmpty()) { "Expected a non-empty string: $key" }
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

    private fun JsonObject.requiredPositiveNumberText(key: String): String =
        requiredNumberText(key).also { require(BigDecimal(it) > BigDecimal.ZERO) { "$key must be greater than zero" } }

    private fun JsonObject.nullableNonNegativeNumberText(key: String): String? =
        nullableNumberText(key)?.also { require(BigDecimal(it) >= BigDecimal.ZERO) { "$key must be non-negative" } }

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

    private fun JsonObject.nullableNonNegativeLong(key: String): Long? =
        nullableLong(key)?.also { require(it >= 0) { "$key must be non-negative" } }

    private fun JsonObject.requiredEnumString(key: String, allowed: Set<String>): String =
        requiredString(key).also { require(it in allowed) { "Unsupported enum value for $key: $it" } }

    private fun validateFoodServiceLinks(receipt: ReceiptV2) {
        val byId = receipt.lineItems.associateBy { it.id }
        receipt.lineItems.forEachIndexed { index, line ->
            val foodService = line.foodService ?: return@forEachIndexed
            require(receipt.merchant.businessKind == BusinessKind.FOOD_SERVICE) {
                "line_items[$index].food_service requires merchant.business_kind=food_service"
            }
            require(line.type == ReceiptLineType.PRODUCT) {
                "line_items[$index].food_service requires a product line"
            }
            if (foodService.role != FoodServiceRole.OPTION) {
                require(foodService.appliesToLineId == null) {
                    "only food_service option lines may reference applies_to_line_id"
                }
            }
            foodService.appliesToLineId?.let { parentId ->
                val parent = byId[parentId]
                require(parent != null && parent.id != line.id && parent.foodService?.role == FoodServiceRole.MAIN) {
                    "food_service option must reference another main line in the same receipt"
                }
            }
        }
    }

    private fun <T> enumValue(value: String, entries: List<T>, wireValue: (T) -> String): T =
        requireNotNull(entries.firstOrNull { wireValue(it) == value }) { "Unsupported enum value: $value" }

    private val DOCUMENT_TYPES = setOf("receipt", "invoice", "order_confirmation", "credit_note", "statement", "voucher", "other")
    private val CAPTURE_METHODS = setOf("pos_export", "e_receipt", "ocr", "manual_transcription", "manual_entry", "unknown")
    private val PAYMENT_METHODS = setOf("cash", "card", "bank_transfer", "mobile_payment", "gift_card", "points", "mixed", "unknown")
    private val PAYMENT_STATUSES = setOf("authorized", "paid", "refunded", "voided", "unknown")
}
