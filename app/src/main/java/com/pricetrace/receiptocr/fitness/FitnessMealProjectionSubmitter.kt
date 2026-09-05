package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

/** Routes only v2 item-level consumption to Fitness's canonical Meal RPC. */
internal class FitnessMealProjectionSubmitter(
    private val gateway: NutritionSupabaseGateway,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        if (request.projection != IngestionProjection.FITNESS_MEAL) {
            return ProjectionSubmission.Failure("unsupported_fitness_meal_projection", retryable = false)
        }
        val envelope = request.envelope
            ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        if (envelope.schemaVersion != YEONSIK_OCR_V2_SCHEMA) {
            return ProjectionSubmission.Failure("fitness_meal_requires_yeonsik_ocr_v2", retryable = false)
        }
        val localDocumentId = request.localDocumentId
            ?: return ProjectionSubmission.Failure("local_document_id_missing", retryable = false)
        if (envelope.consumption.isEmpty()) {
            return ProjectionSubmission.Failure("consumption_artifact_missing", retryable = false)
        }
        if (envelope.consumption.any { it.items.isEmpty() }) {
            return ProjectionSubmission.Failure("consumption_items_required", retryable = false)
        }
        val eatenAt = envelope.consumption.mapNotNull { it.consumedAt }.distinct().singleOrNull()
            ?: return ProjectionSubmission.Failure("one_actual_consumed_at_required", retryable = false)
        runCatching { OffsetDateTime.parse(eatenAt) }.getOrElse {
            return ProjectionSubmission.Failure("consumed_at_invalid", retryable = false)
        }

        val nutritionRows = parseNutritionRows(request.dependencyMetadataJson[IngestionProjection.FITNESS_NUTRITION])
            ?: return ProjectionSubmission.Failure("fitness_nutrition_metadata_invalid", retryable = true)
        if (nutritionRows.size != envelope.nutrition.size) {
            return ProjectionSubmission.Failure("fitness_nutrition_metadata_missing", retryable = true)
        }
        val foodIds = envelope.nutrition.mapIndexed { index, item ->
            item.clientKey to nutritionRows[index].nutritionFoodId()
        }.toMap()
        if (foodIds.values.any { it == null }) {
            return ProjectionSubmission.Failure("nutrition_food_id_missing", retryable = true)
        }

        val mealItems = try {
            envelope.consumption.flatMap { consumption ->
                consumption.items.map { item ->
                    val nutrition = envelope.nutrition.singleOrNull { it.clientKey == item.nutritionClientKey }
                        ?: throw IllegalArgumentException("nutrition_client_key_missing:${item.nutritionClientKey}")
                    val sourceProvenance = sourceProvenance(
                        localDocumentId = localDocumentId,
                        consumptionClientKey = consumption.clientKey,
                        nutritionClientKey = item.nutritionClientKey,
                        consumedAt = eatenAt,
                        nutrition = nutrition,
                    )
                    FitnessMealItemPayload(
                        nutritionFoodId = requireNotNull(foodIds[item.nutritionClientKey]),
                        clientKey = item.nutritionClientKey,
                        consumedAmount = item.amount,
                        consumedUnit = item.unit,
                        confidence = item.confidence,
                        sourceProvenance = sourceProvenance,
                        priceTraceIdentity = if (nutrition is IngestionNutrition.MealComponentEstimate) {
                            null
                        } else {
                            request.resolvedIdentity?.priceTrace?.let(PriceTraceIdentityJson::encode)
                        },
                    )
                }
            }
        } catch (error: IllegalArgumentException) {
            return ProjectionSubmission.Failure(error.message ?: "fitness_meal_payload_invalid", retryable = false)
        }

        val source = buildJsonObject {
            put("source_app", JsonPrimitive("ocr-app"))
            put("schema_version", JsonPrimitive(YEONSIK_OCR_V2_SCHEMA))
            put("meal_kind", JsonPrimitive(if (envelope.mode == com.pricetrace.receiptscanner.ingestion.IngestionMode.RESTAURANT) "dining_out" else "food"))
            put("menu", JsonPrimitive(mealTitle(envelope)))
            put("source_document_ref", JsonPrimitive(localDocumentId))
            put("consumed_at", JsonPrimitive(eatenAt))
            envelope.receipt?.merchant?.name?.let { put("store_name", JsonPrimitive(it)) }
            envelope.receipt?.merchant?.branchName?.let { put("branch_name", JsonPrimitive(it)) }
        }
        val payload = try {
            val paidOrReceiptBackedItem = envelope.consumption
                .flatMap { it.items }
                .mapNotNull { item -> envelope.nutrition.singleOrNull { it.clientKey == item.nutritionClientKey } }
                .any { it !is IngestionNutrition.MealComponentEstimate }
            FitnessMealCanonicalPayload(
                idempotencyKey = StableIds.sha256("${request.idempotencyKey}|fitness-meal|$eatenAt"),
                eatenAt = eatenAt,
                items = mealItems,
                source = source,
                // A meal containing only free/estimated components must not carry a
                // PriceTrace RestaurantMenu identity. Paid receipt-backed items may retain
                // the receipt provenance at the item/top-level boundary.
                priceTraceIdentity = if (paidOrReceiptBackedItem) {
                    request.resolvedIdentity?.priceTrace?.let(PriceTraceIdentityJson::encode)
                } else {
                    null
                },
            )
        } catch (error: IllegalArgumentException) {
            return ProjectionSubmission.Failure(error.message ?: "fitness_meal_payload_invalid", retryable = false)
        }

        return when (val result = gateway.importVerifiedMeal(payload)) {
            is NutritionMealImportOutcome.Success -> ProjectionSubmission.Success(
                remoteId = result.response.mealRecordId,
                metadataJson = result.rawResponse,
            )
            is NutritionMealImportOutcome.Failure -> ProjectionSubmission.Failure(
                message = result.message ?: result.reason.name,
                retryable = result.reason.isRetryable(),
            )
        }
    }

    private fun parseNutritionRows(value: String?): List<JsonObject>? = value?.let {
        runCatching { Json.parseToJsonElement(it).jsonArray.map { element -> element.jsonObject } }.getOrNull()
    }

    private fun JsonObject.nutritionFoodId(): String? = this["nutrition_food_id"]
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)

    private fun sourceProvenance(
        localDocumentId: String,
        consumptionClientKey: String,
        nutritionClientKey: String,
        consumedAt: String,
        nutrition: IngestionNutrition,
    ): JsonObject = buildJsonObject {
        put("source_app", JsonPrimitive("ocr-app"))
        put("schema_version", JsonPrimitive(YEONSIK_OCR_V2_SCHEMA))
        put("source_document_ref", JsonPrimitive(localDocumentId))
        put("consumption_client_key", JsonPrimitive(consumptionClientKey))
        put("nutrition_client_key", JsonPrimitive(nutritionClientKey))
        put("consumed_at", JsonPrimitive(consumedAt))
        put("nutrition_kind", JsonPrimitive(nutritionKind(nutrition)))
    }

    private fun nutritionKind(item: IngestionNutrition): String = when (item) {
        is IngestionNutrition.ProductLabel -> "product_label"
        is IngestionNutrition.RestaurantEstimate -> "restaurant_estimate"
        is IngestionNutrition.MealComponentEstimate -> "meal_component_estimate"
    }

    private fun mealTitle(envelope: com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope): String =
        envelope.receipt?.merchant?.name?.takeIf(String::isNotBlank)
            ?: envelope.nutrition.filterIsInstance<IngestionNutrition.ProductLabel>()
                .firstOrNull()?.draft?.productName?.takeIf(String::isNotBlank)
            ?: envelope.nutrition.firstOrNull()?.let {
                when (it) {
                    is IngestionNutrition.ProductLabel -> it.draft.productName
                    is IngestionNutrition.RestaurantEstimate -> it.menuName
                    is IngestionNutrition.MealComponentEstimate -> it.menuName
                }
            }
            ?: "OCR Meal"

    private fun NutritionGatewayFailure.isRetryable(): Boolean = when (this) {
        NutritionGatewayFailure.NETWORK,
        NutritionGatewayFailure.RATE_LIMITED,
        NutritionGatewayFailure.SERVER -> true
        NutritionGatewayFailure.NOT_CONFIGURED,
        NutritionGatewayFailure.AUTHENTICATION,
        NutritionGatewayFailure.CONTRACT,
        NutritionGatewayFailure.CONFLICT -> false
    }

}
