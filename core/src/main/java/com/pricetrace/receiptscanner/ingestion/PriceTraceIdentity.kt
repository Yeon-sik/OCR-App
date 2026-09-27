package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

data class PriceTraceIdentity(
    val receiptId: String,
    val storeId: String? = null,
    val restaurantId: String? = null,
    val restaurantLocationId: String? = null,
    val merchantResolutionStatus: String? = null,
    val merchantCandidateId: String? = null,
    val lines: List<PriceTraceLineIdentity> = emptyList(),
) {
    fun lineFor(receiptItemId: String): PriceTraceLineIdentity? = lines.firstOrNull { line ->
        line.receiptItemId == receiptItemId || line.sourceLineId == receiptItemId
    }
}

data class PriceTraceLineIdentity(
    val sourceLineId: String? = null,
    val lineOrdinal: Int? = null,
    val receiptItemId: String? = null,
    val productId: String? = null,
    val storeProductId: String? = null,
    val catalogProductId: String? = null,
    val restaurantMenuId: String? = null,
    val resolutionStatus: String? = null,
)

/** Exact server-issued identity for one Restaurant/Menu Nutrition item. */
data class PriceTraceRestaurantMenuIdentity(
    val restaurantId: String,
    val restaurantLocationId: String,
    val restaurantMenuId: String,
    val catalogProductId: String,
) {
    fun toNutritionIdentity(restaurantName: String, menuName: String): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive("dining-out-identity.v1"))
        put("namespace", JsonPrimitive("pricetrace"))
        put("restaurant_id", JsonPrimitive(restaurantId))
        put("restaurant_location_id", JsonPrimitive(restaurantLocationId))
        put("restaurant_menu_id", JsonPrimitive(restaurantMenuId))
        put("catalog_product_id", JsonPrimitive(catalogProductId))
        put("restaurant_name", JsonPrimitive(restaurantName))
        put("menu_name", JsonPrimitive(menuName))
    }
}

data class ProjectionIdentity(
    val priceTrace: PriceTraceIdentity? = null,
    /** Product identity returned by PriceTrace after candidate review/resolution. */
    val productCandidates: Map<String, PriceTraceProductIdentity> = emptyMap(),
) {
    val priceTraceIdentity: PriceTraceIdentity?
        get() = priceTrace
}

data class PriceTraceProductIdentity(
    val candidateClientKey: String,
    val catalogProductId: String,
    val productRevision: String? = null,
)

object PriceTraceIdentityJson {
    fun decode(response: JsonObject): PriceTraceIdentity {
        val receiptId = response.stringField("receiptId", "receipt_id")
            ?: error("PriceTrace response is missing receiptId")
        val lines = (response["lines"] as? JsonArray).orEmpty().map { element ->
            val row = element.jsonObject
            PriceTraceLineIdentity(
                sourceLineId = row.stringField("sourceLineId", "source_line_id"),
                lineOrdinal = row.intField("lineOrdinal", "line_ordinal"),
                receiptItemId = row.stringField("receiptItemId", "receipt_item_id"),
                productId = row.stringField("productId", "product_id"),
                storeProductId = row.stringField("storeProductId", "store_product_id"),
                catalogProductId = row.stringField("catalogProductId", "catalog_product_id"),
                restaurantMenuId = row.stringField("restaurantMenuId", "restaurant_menu_id"),
                resolutionStatus = row.stringField("resolutionStatus", "resolution_status"),
            )
        }
        return PriceTraceIdentity(
            receiptId = receiptId,
            storeId = response.stringField("storeId", "store_id"),
            restaurantId = response.stringField("restaurantId", "restaurant_id"),
            restaurantLocationId = response.stringField("restaurantLocationId", "restaurant_location_id"),
            merchantResolutionStatus = response.stringField("merchantResolutionStatus", "merchant_resolution_status"),
            merchantCandidateId = response.stringField("merchantCandidateId", "merchant_candidate_id"),
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
        put("merchantResolutionStatus", identity.merchantResolutionStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("merchantCandidateId", identity.merchantCandidateId?.let(::JsonPrimitive) ?: JsonNull)
        put("lines", JsonArray(identity.lines.map { line ->
            buildJsonObject {
                put("sourceLineId", line.sourceLineId?.let(::JsonPrimitive) ?: JsonNull)
                put("lineOrdinal", line.lineOrdinal?.let(::JsonPrimitive) ?: JsonNull)
                put("receiptItemId", line.receiptItemId?.let(::JsonPrimitive) ?: JsonNull)
                put("productId", line.productId?.let(::JsonPrimitive) ?: JsonNull)
                put("storeProductId", line.storeProductId?.let(::JsonPrimitive) ?: JsonNull)
                put("catalogProductId", line.catalogProductId?.let(::JsonPrimitive) ?: JsonNull)
                put("restaurantMenuId", line.restaurantMenuId?.let(::JsonPrimitive) ?: JsonNull)
                put("resolutionStatus", line.resolutionStatus?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
    }

    /** Receipt item IDs are never fallback keys here: PT source line identity must match exactly. */
    fun exactRestaurantMenuForSourceLine(
        identity: PriceTraceIdentity,
        sourceLineId: String,
    ): PriceTraceRestaurantMenuIdentity? {
        if (!identity.merchantResolutionStatus.equals("exact", ignoreCase = true)) return null
        val line = identity.lines.filter { it.sourceLineId == sourceLineId }.singleOrNull() ?: return null
        if (!line.resolutionStatus.equals("resolved", ignoreCase = true)) return null
        return restaurantMenuIdentity(
            restaurantId = identity.restaurantId,
            restaurantLocationId = identity.restaurantLocationId,
            restaurantMenuId = line.restaurantMenuId,
            catalogProductId = line.catalogProductId,
        )
    }

    /** A successful standalone PT response is the authority for receipt-free exact menu IDs. */
    fun exactRestaurantMenuFromStandaloneResponse(response: JsonObject): PriceTraceRestaurantMenuIdentity? {
        if (!response.stringField("kind").equals("restaurant_purchase", ignoreCase = true)) return null
        val status = response.stringField("merchantResolutionStatus", "merchant_resolution_status")
            ?: response.stringField("resolutionStatus", "resolution_status")
        if (status != null && !status.equals("exact", ignoreCase = true) &&
            !status.equals("resolved", ignoreCase = true)
        ) return null
        val ocrResolutionStatus = (response["ocrResolution"] as? JsonObject)
            ?.stringField("status")
            ?: (response["ocr_resolution"] as? JsonObject)?.stringField("status")
        if (ocrResolutionStatus != null &&
            !ocrResolutionStatus.equals("exact", ignoreCase = true) &&
            !ocrResolutionStatus.equals("resolved", ignoreCase = true)
        ) return null
        val ids = response["authoritativeIds"] as? JsonObject
            ?: response["authoritative_ids"] as? JsonObject
            ?: return null
        return restaurantMenuIdentity(
            restaurantId = ids.stringField("restaurantId", "restaurant_id"),
            restaurantLocationId = ids.stringField("restaurantLocationId", "restaurant_location_id"),
            restaurantMenuId = ids.stringField("restaurantMenuId", "restaurant_menu_id"),
            catalogProductId = ids.stringField("catalogProductId", "catalog_product_id"),
        )
    }

    /** Detects PriceTrace's OCR-owned ambiguity statuses without interpreting candidate names. */
    fun requiresOcrReview(response: JsonObject): Boolean {
        if (response.hasOcrReviewStatus()) return true
        val lines = response["lines"] as? JsonArray
        if (lines.orEmpty().any { (it as? JsonObject)?.hasOcrReviewStatus() == true }) return true
        val observations = response["observations"] as? JsonArray
        return observations.orEmpty().any { value ->
            val observation = value as? JsonObject ?: return@any false
            observation.stringField("kind") == "restaurant_purchase" && observation.hasOcrReviewStatus()
        }
    }

    private fun JsonObject.hasOcrReviewStatus(): Boolean {
        val statuses = listOfNotNull(
            stringField("merchantResolutionStatus", "merchant_resolution_status"),
            stringField("resolutionStatus", "resolution_status"),
            stringField("status"),
        )
        if (statuses.any { status ->
                listOf("needs_ocr_resolution", "needs_user_selection", "ambiguous")
                    .any { it.equals(status, ignoreCase = true) }
            }
        ) return true
        val nested = (this["ocrResolution"] as? JsonObject)
            ?: (this["ocr_resolution"] as? JsonObject)
        return nested?.hasOcrReviewStatus() == true
    }

    private fun restaurantMenuIdentity(
        restaurantId: String?,
        restaurantLocationId: String?,
        restaurantMenuId: String?,
        catalogProductId: String?,
    ): PriceTraceRestaurantMenuIdentity? {
        if (listOf(restaurantId, restaurantLocationId, restaurantMenuId, catalogProductId).any { it.isNullOrBlank() }) {
            return null
        }
        return PriceTraceRestaurantMenuIdentity(
            restaurantId = requireNotNull(restaurantId),
            restaurantLocationId = requireNotNull(restaurantLocationId),
            restaurantMenuId = requireNotNull(restaurantMenuId),
            catalogProductId = requireNotNull(catalogProductId),
        )
    }

    private fun JsonObject.stringField(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    private fun JsonObject.intField(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.intOrNull
    }
}

/** Parses only server-returned product resolution metadata; it never reads IDs from OCR input. */
object PriceTraceProductIdentityJson {
    fun decode(response: JsonObject): Map<String, PriceTraceProductIdentity> {
        val elements = sequenceOf("products", "productCandidates", "product_candidates")
            .mapNotNull { key -> response[key] as? JsonArray }
            .firstOrNull()
            ?.toList()
            ?: if (response.stringField("clientKey", "client_key") != null) listOf(response) else emptyList()
        require(elements.isNotEmpty()) { "PriceTrace product response is missing products" }
        return elements.map { element ->
            val row = element.jsonObject
            val identity = PriceTraceProductIdentity(
                candidateClientKey = row.stringField("clientKey", "client_key")
                    ?: error("PriceTrace product response is missing clientKey"),
                catalogProductId = row.stringField("catalogProductId", "catalog_product_id")
                    ?: error("PriceTrace product response is missing catalogProductId"),
                productRevision = row.stringField("productRevision", "product_revision", "revision"),
            )
            identity.candidateClientKey to identity
        }.toMap().also { identities ->
            require(identities.size == elements.size) { "PriceTrace product client keys must be unique" }
        }
    }

    fun tryDecode(response: String?): Map<String, PriceTraceProductIdentity> = response?.let { value ->
        runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject
            decode(root)
        }.getOrDefault(emptyMap())
    }.orEmpty()

    private fun JsonObject.stringField(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}
