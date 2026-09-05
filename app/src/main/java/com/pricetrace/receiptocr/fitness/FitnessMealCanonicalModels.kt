package com.pricetrace.receiptocr.fitness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

/** One exact NutritionFood reference plus the user's observed consumed amount. */
data class FitnessMealItemPayload(
    val nutritionFoodId: String,
    val clientKey: String,
    val consumedAmount: Double,
    val consumedUnit: String,
    val confidence: Double,
    val sourceProvenance: JsonObject,
    val priceTraceIdentity: JsonObject? = null,
) {
    init {
        require(nutritionFoodId.isNotBlank())
        require(clientKey.isNotBlank())
        require(consumedAmount.isFinite() && consumedAmount > 0)
        require(consumedUnit.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(sourceProvenance.isNotEmpty())
    }
}

/** Payload for Fitness's canonical Meal RPC; eatenAt is the actual meal time, not capture time. */
data class FitnessMealCanonicalPayload(
    val idempotencyKey: String,
    val eatenAt: String,
    val items: List<FitnessMealItemPayload>,
    val source: JsonObject,
    val priceTraceIdentity: JsonObject? = null,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(eatenAt.isNotBlank())
        OffsetDateTime.parse(eatenAt)
        require(items.isNotEmpty())
        require(source.isNotEmpty())
    }

    fun toRpcJson(): String = FitnessMealCanonicalJson.encode(this)
}

data class FitnessMealImportResponse(
    val mealImportId: String,
    val mealRecordId: String,
    val idempotentReplay: Boolean,
    val eatenAt: String,
    val recordDate: String,
    val itemCount: Int,
    val nutritionFoodIds: List<String>,
    val contractVersion: String,
)

object FitnessMealCanonicalJson {
    private val json = Json { explicitNulls = true; ignoreUnknownKeys = false }

    fun encode(payload: FitnessMealCanonicalPayload): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("p_idempotency_key", JsonPrimitive(payload.idempotencyKey))
            put("p_eaten_at", JsonPrimitive(payload.eatenAt))
            put("p_items", JsonArray(payload.items.map(::itemJson)))
            put("p_source", payload.source)
            put("p_pricetrace_identity", payload.priceTraceIdentity ?: JsonNull)
        },
    )

    fun decodeResponse(value: String): FitnessMealImportResponse {
        val root = json.parseToJsonElement(value)
        val row = when (root) {
            is JsonObject -> root
            else -> root.jsonArray.single().jsonObject
        }
        fun requiredString(key: String): String = (row[key] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("Missing Fitness Meal response field: $key")
        fun requiredBoolean(key: String): Boolean = (row[key] as? JsonPrimitive)?.contentOrNull
            ?.toBooleanStrictOrNull() ?: error("Invalid Fitness Meal response boolean: $key")
        fun requiredInt(key: String): Int = (row[key] as? JsonPrimitive)?.intOrNull
            ?: error("Invalid Fitness Meal response integer: $key")
        val nutritionFoodIds = (row["nutrition_food_ids"] as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error("Invalid Fitness Meal nutrition_food_ids")
        } ?: error("Missing Fitness Meal response field: nutrition_food_ids")
        return FitnessMealImportResponse(
            mealImportId = requiredString("meal_import_id"),
            mealRecordId = requiredString("meal_record_id"),
            idempotentReplay = requiredBoolean("idempotent_replay"),
            eatenAt = requiredString("eaten_at"),
            recordDate = requiredString("record_date"),
            itemCount = requiredInt("item_count").also { require(it > 0) },
            nutritionFoodIds = nutritionFoodIds,
            contractVersion = requiredString("contract_version"),
        )
    }

    private fun itemJson(item: FitnessMealItemPayload): JsonObject = buildJsonObject {
        put("nutrition_food_id", JsonPrimitive(item.nutritionFoodId))
        put("client_key", JsonPrimitive(item.clientKey))
        put("consumed_amount", JsonPrimitive(item.consumedAmount))
        put("consumed_unit", JsonPrimitive(item.consumedUnit))
        put("confidence", JsonPrimitive(item.confidence))
        put("source_provenance", item.sourceProvenance)
        item.priceTraceIdentity?.let { put("pricetrace_identity", it) }
    }
}
