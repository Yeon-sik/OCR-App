package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.PurchaseNutritionIdentity
import kotlinx.serialization.json.*

/** Owner-scoped external reference, deliberately separate from global catalogue publication. */
data class NutritionPrivatePurchaseLinkPayload(
    val canonicalImportId: String,
    val nutritionFoodId: String,
    val nutritionClientKey: String,
    val identity: PurchaseNutritionIdentity.OwnerConfirmedIdentity,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("p_canonical_import_id", canonicalImportId)
        put("p_nutrition_food_id", nutritionFoodId)
        put("p_nutrition_client_key", nutritionClientKey)
        put("p_purchase_record_client_key", identity.purchaseRecordClientKey)
        put("p_purchase_line_key", identity.lineKey)
        put("p_purchase_source_id", identity.purchaseSourceId)
        put("p_restaurant_id", identity.restaurantId)
        put("p_restaurant_location_id", identity.restaurantLocationId)
        put("p_restaurant_menu_id", identity.restaurantMenuId)
    }

    fun requireMatches(response: JsonObject) {
        require(response["visibility"] == JsonPrimitive("private"))
        require(response["authority_status"] == JsonPrimitive("owner_confirmed"))
        require(response["linked"] == JsonPrimitive(true))
        toJson().forEach { (key, value) -> require(response[key.removePrefix("p_")] == value) }
    }
}

sealed interface NutritionPrivatePurchaseLinkOutcome {
    data class Success(val rawResponse: String) : NutritionPrivatePurchaseLinkOutcome
    data class Failure(val reason: NutritionGatewayFailure, val message: String? = null) : NutritionPrivatePurchaseLinkOutcome
}
