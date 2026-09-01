package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class PriceTraceIdentity(
    val receiptId: String,
    val storeId: String? = null,
    val restaurantId: String? = null,
    val restaurantLocationId: String? = null,
    val lines: List<PriceTraceLineIdentity> = emptyList(),
) {
    fun lineFor(receiptItemId: String): PriceTraceLineIdentity? = lines.firstOrNull { line ->
        line.receiptItemId == receiptItemId || line.sourceLineId == receiptItemId
    }
}

data class PriceTraceLineIdentity(
    val sourceLineId: String? = null,
    val receiptItemId: String? = null,
    val productId: String? = null,
    val storeProductId: String? = null,
    val catalogProductId: String? = null,
    val restaurantMenuId: String? = null,
)

data class ProjectionIdentity(
    val priceTrace: PriceTraceIdentity? = null,
) {
    val priceTraceIdentity: PriceTraceIdentity?
        get() = priceTrace
}

object PriceTraceIdentityJson {
    fun decode(response: JsonObject): PriceTraceIdentity {
        val receiptId = response.stringField("receiptId", "receipt_id")
            ?: error("PriceTrace response is missing receiptId")
        val lines = (response["lines"] as? JsonArray).orEmpty().map { element ->
            val row = element.jsonObject
            PriceTraceLineIdentity(
                sourceLineId = row.stringField("sourceLineId", "source_line_id"),
                receiptItemId = row.stringField("receiptItemId", "receipt_item_id"),
                productId = row.stringField("productId", "product_id"),
                storeProductId = row.stringField("storeProductId", "store_product_id"),
                catalogProductId = row.stringField("catalogProductId", "catalog_product_id"),
                restaurantMenuId = row.stringField("restaurantMenuId", "restaurant_menu_id"),
            )
        }
        return PriceTraceIdentity(
            receiptId = receiptId,
            storeId = response.stringField("storeId", "store_id"),
            restaurantId = response.stringField("restaurantId", "restaurant_id"),
            restaurantLocationId = response.stringField("restaurantLocationId", "restaurant_location_id"),
            lines = lines,
        )
    }

    fun tryDecode(response: String?): PriceTraceIdentity? = response?.let { value ->
        runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(value)
            decode(root.jsonObject)
        }.getOrNull()
    }

    fun encode(identity: PriceTraceIdentity): JsonObject = buildJsonObject {
        put("receiptId", JsonPrimitive(identity.receiptId))
        put("storeId", identity.storeId?.let(::JsonPrimitive) ?: JsonNull)
        put("restaurantId", identity.restaurantId?.let(::JsonPrimitive) ?: JsonNull)
        put("restaurantLocationId", identity.restaurantLocationId?.let(::JsonPrimitive) ?: JsonNull)
        put("lines", JsonArray(identity.lines.map { line ->
            buildJsonObject {
                put("sourceLineId", line.sourceLineId?.let(::JsonPrimitive) ?: JsonNull)
                put("receiptItemId", line.receiptItemId?.let(::JsonPrimitive) ?: JsonNull)
                put("productId", line.productId?.let(::JsonPrimitive) ?: JsonNull)
                put("storeProductId", line.storeProductId?.let(::JsonPrimitive) ?: JsonNull)
                put("catalogProductId", line.catalogProductId?.let(::JsonPrimitive) ?: JsonNull)
                put("restaurantMenuId", line.restaurantMenuId?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
    }

    private fun JsonObject.stringField(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}
