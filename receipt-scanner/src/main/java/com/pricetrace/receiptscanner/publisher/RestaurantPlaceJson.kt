package com.pricetrace.receiptscanner.publisher

import com.pricetrace.receiptscanner.domain.PlaceCandidateSource
import com.pricetrace.receiptscanner.domain.RestaurantPlaceCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

object RestaurantPlaceJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = true
    }

    fun encodeDirectoryRequest(query: String?, limit: Int = 5): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("p_query", query?.trim()?.takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
                put("p_limit", JsonPrimitive(limit.coerceIn(1, 100)))
            },
        )

    fun decodeDirectoryResponse(raw: String): List<RestaurantPlaceCandidate> {
        val entries = parseArray(raw, "get_restaurant_directory_v1")
        val candidateIds = linkedSetOf<String>()
        val candidates = mutableListOf<RestaurantPlaceCandidate>()
        entries.forEach { entryElement ->
            val entry = entryElement.jsonObject
            entry.optionalString("schemaVersion")?.let { schemaVersion ->
                require(schemaVersion == "restaurant-directory.v1" || schemaVersion == "restaurant-directory.v2") {
                    "Unsupported restaurant-directory schema: $schemaVersion"
                }
            }
            entry.optionalString("namespace")?.let { namespace ->
                require(namespace == "pricetrace") { "Unexpected restaurant-directory namespace" }
            }
            val restaurant = entry.requiredObject("restaurant")
            val restaurantId = requireUuid(restaurant.requiredString("id"), "restaurant.id")
            val displayName = restaurant.optionalString("brand")?.takeIf(String::isNotBlank)
                ?: restaurant.requiredString("legalName")
            val locations = entry.requiredArray("locations")
            locations.forEach { locationElement ->
                val location = locationElement.jsonObject
                val locationId = requireUuid(location.requiredString("id"), "location.id")
                val sourceNamespace = location.requiredString("sourceLabel")
                val sourceRestaurantCode = location.requiredString("sourceRestaurantCode")
                val branchName = location.optionalString("locationLabel")?.takeIf(String::isNotBlank)
                val sourceUrl = location.optionalString("sourceUrl")?.takeIf(String::isNotBlank)
                val candidateId = "pricetrace:$restaurantId:$locationId"
                require(candidateIds.add(candidateId)) { "Duplicate restaurant candidate id: $candidateId" }
                candidates += RestaurantPlaceCandidate(
                    id = candidateId,
                    source = PlaceCandidateSource.VERIFIED_DIRECTORY,
                    displayName = displayName,
                    restaurantId = restaurantId,
                    restaurantLocationId = locationId,
                    sourceNamespace = sourceNamespace,
                    branchName = branchName,
                    sourceLocationCode = sourceRestaurantCode,
                    detailUrl = sourceUrl,
                )
            }
        }
        return candidates
    }

    private fun parseArray(raw: String, contract: String): JsonArray = runCatching {
        json.parseToJsonElement(raw).jsonArray
    }.getOrElse { error("Invalid $contract JSON response") }

    private fun JsonObject.requiredString(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("$key must be a non-empty JSON string")

    private fun JsonObject.optionalString(key: String): String? = when (val value = get(key)) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("$key must be a JSON string or null")
    }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        get(key) as? JsonArray ?: error("$key must be a JSON array")

    private fun JsonObject.requiredObject(key: String): JsonObject =
        get(key) as? JsonObject ?: error("$key must be a JSON object")

    private fun requireUuid(value: String, label: String): String = runCatching {
        UUID.fromString(value.trim()).toString()
    }.getOrElse { error("$label must be a UUID") }
}
