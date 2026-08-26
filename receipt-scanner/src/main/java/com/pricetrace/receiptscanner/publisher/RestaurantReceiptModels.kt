package com.pricetrace.receiptscanner.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.util.UUID

/** Minimal, user-verified restaurant receipt facts sent to PriceTrace. */
data class RestaurantReceiptSubmitItem(
    val lineId: String,
    val description: String,
    val quantity: Int,
    val unitPriceKrw: Int,
    val totalPriceKrw: Int,
    val lineType: String,
) {
    init {
        require(lineId.isNotBlank() && lineId.length <= 200) { "lineId must contain 1 to 200 characters" }
        require(description.isNotBlank() && description.length <= 500) {
            "description must contain 1 to 500 characters"
        }
        require(quantity > 0) { "quantity must be positive" }
        require(unitPriceKrw >= 0) { "unitPriceKrw must be non-negative" }
        require(totalPriceKrw == unitPriceKrw * quantity) {
            "totalPriceKrw must equal unitPriceKrw * quantity"
        }
        require(lineType.isNotBlank()) { "lineType must not be blank" }
    }
}

data class RestaurantReceiptSubmitPayload(
    val idempotencyKey: String,
    val documentId: String,
    val restaurantName: String,
    val branchName: String?,
    val observedOn: String,
    val totalPriceKrw: Int,
    val items: List<RestaurantReceiptSubmitItem>,
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200) {
            "idempotencyKey must contain 1 to 200 characters"
        }
        require(documentId.isNotBlank() && documentId.length <= 200) { "documentId must not be blank" }
        require(restaurantName.isNotBlank() && restaurantName.length <= 500) {
            "restaurantName must contain 1 to 500 characters"
        }
        require(branchName == null || branchName.length <= 500) { "branchName is too long" }
        require(runCatching { LocalDate.parse(observedOn) }.isSuccess) {
            "observedOn must be an ISO date"
        }
        require(totalPriceKrw >= 0) { "totalPriceKrw must be non-negative" }
        require(items.isNotEmpty()) { "at least one restaurant menu item is required" }
    }

    fun toRpcJson(): String = RestaurantReceiptJson.encodeSubmitPayload(this)
}

data class RestaurantReceiptSubmitResponse(
    val receiptId: String,
    val replayed: Boolean,
    val itemCount: Int,
)

object RestaurantReceiptJson {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun encodeSubmitPayload(payload: RestaurantReceiptSubmitPayload): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("p_idempotency_key", JsonPrimitive(payload.idempotencyKey))
                put("p_document_id", JsonPrimitive(payload.documentId))
                put("p_restaurant_name", JsonPrimitive(payload.restaurantName))
                put("p_branch_name", payload.branchName?.let(::JsonPrimitive) ?: JsonNull)
                put("p_observed_on", JsonPrimitive(payload.observedOn))
                put("p_total_price_krw", JsonPrimitive(payload.totalPriceKrw))
                put("p_items", buildJsonArray {
                    payload.items.forEach { item ->
                        add(buildJsonObject {
                            put("line_id", JsonPrimitive(item.lineId))
                            put("description", JsonPrimitive(item.description))
                            put("quantity", JsonPrimitive(item.quantity))
                            put("unit_price_krw", JsonPrimitive(item.unitPriceKrw))
                            put("total_price_krw", JsonPrimitive(item.totalPriceKrw))
                            put("line_type", JsonPrimitive(item.lineType))
                        })
                    }
                })
            },
        )

    fun decodeSubmitResponse(raw: String): RestaurantReceiptSubmitResponse {
        val rows = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { error("Invalid submit_restaurant_receipt_v1 JSON response") }
        require(rows.size == 1) { "submit_restaurant_receipt_v1 must return exactly one row" }
        val row = rows.single().jsonObject
        require(row.keys == responseKeys) {
            "Unexpected restaurant receipt response keys: ${row.keys.sorted()}"
        }
        return RestaurantReceiptSubmitResponse(
            receiptId = requireUuid(row.requiredString("receipt_id"), "receipt_id"),
            replayed = row.requiredBoolean("replayed"),
            itemCount = row.requiredLong("item_count").toInt().also { require(it > 0) },
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("$key must be a non-empty JSON string")

    private fun JsonObject.requiredBoolean(key: String): Boolean =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: error("$key must be a JSON boolean")

    private fun JsonObject.requiredLong(key: String): Long =
        (get(key) as? JsonPrimitive)?.longOrNull ?: error("$key must be a JSON integer")

    private val responseKeys = setOf("receipt_id", "replayed", "item_count")
}

private fun requireUuid(value: String, label: String): String = runCatching {
    UUID.fromString(value.trim()).toString()
}.getOrElse { error("$label must be a UUID") }
