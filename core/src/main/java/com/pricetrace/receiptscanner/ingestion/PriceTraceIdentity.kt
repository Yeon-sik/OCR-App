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

/** OCR-owned follow-up instructions returned by PriceTrace; these values are never user authority. */
data class PriceTraceOcrResolution(
    val status: String,
    val resolutionId: String,
    val reasonCode: String?,
    val requiredSourceFacts: List<String>,
)

data class PriceTraceLineOcrResolution(
    val sourceLineId: String,
    val resolution: PriceTraceOcrResolution,
)

data class PriceTraceStandaloneOcrResolution(
    val priceObservationClientKey: String,
    val resolution: PriceTraceOcrResolution,
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
    fun ocrResolution(metadataJson: String?): PriceTraceOcrResolution? = metadataJson?.let { value ->
        runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject
            root.ocrResolution()
        }.getOrNull()
    }

    fun receiptMenuOcrResolutions(metadataJson: String?): List<PriceTraceLineOcrResolution> = metadataJson?.let { value ->
        runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject
            (root["lines"] as? JsonArray).orEmpty().mapNotNull { element ->
                val line = element as? JsonObject ?: return@mapNotNull null
                val sourceLineId = line.stringField("sourceLineId", "source_line_id")
                    ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val resolution = line.ocrResolution() ?: return@mapNotNull null
                if (!resolution.status.equals("needs_ocr_resolution", ignoreCase = true)) return@mapNotNull null
                PriceTraceLineOcrResolution(sourceLineId, resolution)
            }
        }.getOrNull()
    }.orEmpty()

    fun standaloneMenuOcrResolutions(metadataJson: String?): List<PriceTraceStandaloneOcrResolution> = metadataJson?.let { value ->
        runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject
            (root["observations"] as? JsonArray).orEmpty().mapNotNull { element ->
                val observation = element as? JsonObject ?: return@mapNotNull null
                val clientKey = observation.stringField("priceObservationClientKey", "price_observation_client_key")
                    ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val response = observation["response"] as? JsonObject ?: return@mapNotNull null
                val resolution = response.ocrResolution() ?: return@mapNotNull null
                if (!resolution.status.equals("needs_ocr_resolution", ignoreCase = true)) return@mapNotNull null
                PriceTraceStandaloneOcrResolution(clientKey, resolution)
            }
        }.getOrNull()
    }.orEmpty()

    fun hasPendingOcrResolution(metadataJson: String?): Boolean {
        if (ocrResolution(metadataJson)?.status.equals("needs_ocr_resolution", ignoreCase = true)) return true
        if (receiptMenuOcrResolutions(metadataJson).isNotEmpty()) return true
        if (standaloneMenuOcrResolutions(metadataJson).isNotEmpty()) return true
        val root = metadataJson?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        return root?.let(::requiresOcrReview) == true
    }

    fun merchantResolutionIsExact(response: JsonObject): Boolean {
        val merchantStatus = response.stringField("merchantResolutionStatus", "merchant_resolution_status")
            ?: return false
        if (!merchantStatus.equals("exact", ignoreCase = true) &&
            !merchantStatus.equals("resolved", ignoreCase = true)
        ) return false
        val nested = (response["ocrResolution"] as? JsonObject)
            ?: (response["ocr_resolution"] as? JsonObject)
        if (nested != null && !nested.stringField("status").equals("resolved", ignoreCase = true)) return false
        return true
    }

    /** Resolution RPC responses must explicitly close the PT-issued OCR resolution. */
    fun ocrResolutionIsResolved(response: JsonObject): Boolean {
        val nested = (response["ocrResolution"] as? JsonObject)
            ?: (response["ocr_resolution"] as? JsonObject)
            ?: return false
        return nested.stringField("status").equals("resolved", ignoreCase = true)
    }

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
        val authorityStatus = response.stringField("authorityStatus", "authority_status")
        if (authorityStatus != null && !authorityStatus.equals("exact", ignoreCase = true)) return null
        val merchantStatus = response.stringField("merchantResolutionStatus", "merchant_resolution_status")
            ?: return null
        if (!merchantStatus.equals("exact", ignoreCase = true) &&
            !merchantStatus.equals("resolved", ignoreCase = true)
        ) return null
        val menuStatus = response.stringField("menuResolutionStatus", "menu_resolution_status") ?: return null
        if (!menuStatus.equals("resolved", ignoreCase = true) &&
            !menuStatus.equals("exact", ignoreCase = true)
        ) return null
        val ocrResolution = (response["ocrResolution"] as? JsonObject)
            ?: (response["ocr_resolution"] as? JsonObject)
        if (ocrResolution != null) {
            val ocrResolutionStatus = ocrResolution.stringField("status") ?: return null
            if (!ocrResolutionStatus.equals("resolved", ignoreCase = true)) return null
        }
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
            val serverResponse = (observation["response"] as? JsonObject) ?: observation
            serverResponse.stringField("kind") == "restaurant_purchase" && serverResponse.hasOcrReviewStatus()
        }
    }

    /** A restaurant observation without explicit exact authority remains publication-pending. */
    fun standaloneRestaurantIdentityNeedsReview(response: JsonObject): Boolean {
        val observations = response["observations"] as? JsonArray ?: return false
        return observations.orEmpty().any { value ->
            val row = value as? JsonObject ?: return@any false
            val serverResponse = (row["response"] as? JsonObject) ?: row
            serverResponse.stringField("kind").equals("restaurant_purchase", ignoreCase = true) &&
                exactRestaurantMenuFromStandaloneResponse(serverResponse) == null
        }
    }

    private fun JsonObject.ocrResolution(): PriceTraceOcrResolution? {
        val resolution = (this["ocrResolution"] as? JsonObject)
            ?: (this["ocr_resolution"] as? JsonObject)
            ?: return null
        val status = resolution.stringField("status") ?: return null
        val resolutionId = resolution.stringField("resolutionId", "resolution_id") ?: return null
        return PriceTraceOcrResolution(
            status = status,
            resolutionId = resolutionId,
            reasonCode = resolution.stringField("reasonCode", "reason_code"),
            requiredSourceFacts = (resolution["requiredSourceFacts"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) },
        )
    }

    private fun JsonObject.hasOcrReviewStatus(): Boolean {
        val statuses = listOfNotNull(
            stringField("merchantResolutionStatus", "merchant_resolution_status"),
            stringField("resolutionStatus", "resolution_status"),
            stringField("status"),
        )
        if (statuses.any { status ->
                listOf("needs_ocr_resolution", "needs_user_selection", "ambiguous", "unresolved_catalog")
                    .any { it.equals(status, ignoreCase = true) }
            }
        ) return true
        val nested = (this["ocrResolution"] as? JsonObject)
            ?: (this["ocr_resolution"] as? JsonObject)
        if (nested?.hasOcrReviewStatus() == true) return true
        return (this["response"] as? JsonObject)?.hasOcrReviewStatus() == true
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
