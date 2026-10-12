package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.serialization.json.*

data class VerifiedReceiptProductNutritionPayload(
    val canonicalImportId: String,
    val nutritionFoodId: String,
    val identity: JsonObject,
) {
    fun toRpcJson(): String = buildJsonObject {
        put("p_canonical_import_id", JsonPrimitive(canonicalImportId))
        put("p_nutrition_food_id", JsonPrimitive(nutritionFoodId))
        put("p_pricetrace_identity", identity)
        put("p_user_verified", JsonPrimitive(true))
    }.toString()
}

sealed interface VerifiedReceiptProductNutritionOutcome {
    data class Success(val id: String, val rawResponse: String) : VerifiedReceiptProductNutritionOutcome
    data class Failure(val reason: NutritionGatewayFailure, val message: String? = null) : VerifiedReceiptProductNutritionOutcome
}

/** Exact source-line binding after Core's durable receipt/nutrition approval gate. */
object VerifiedReceiptProductNutrition {
    fun identity(request: ProjectionRequest, item: IngestionNutrition.ProductLabel): JsonObject {
        val envelope = requireNotNull(request.envelope)
        val receipt = requireNotNull(envelope.receipt)
        val authority = requireNotNull(request.resolvedIdentity?.priceTrace) { "pricetrace_identity_missing" }
        val eligible = receipt.lineItems.filter { it.isNormalPriceObservationCandidate() }
        val explicit = item.lineId?.takeIf(String::isNotBlank)
        val sourceLineId = explicit ?: eligible.singleOrNull()?.id?.takeIf {
            envelope.nutrition.filterIsInstance<IngestionNutrition.ProductLabel>().size == 1
        } ?: error("receipt_nutrition_binding_requires_ocr_review:${item.clientKey}")
        require(eligible.any { it.id == sourceLineId }) { "receipt_nutrition_source_line_invalid:$sourceLineId" }
        val line = requireNotNull(authority.lineFor(sourceLineId)) { "receipt_product_identity_missing:$sourceLineId" }
        require(!authority.storeId.isNullOrBlank() && !line.productId.isNullOrBlank() &&
            !line.storeProductId.isNullOrBlank() && !line.receiptItemId.isNullOrBlank()) {
            "receipt_product_identity_incomplete:$sourceLineId"
        }
        return buildJsonObject {
            put("schema_version", JsonPrimitive("verified-receipt-product.v1"))
            put("namespace", JsonPrimitive("pricetrace"))
            put("receipt_id", JsonPrimitive(authority.receiptId))
            put("store_id", JsonPrimitive(authority.storeId!!))
            put("product_id", JsonPrimitive(line.productId!!))
            put("store_product_id", JsonPrimitive(line.storeProductId!!))
            put("receipt_item_id", JsonPrimitive(line.receiptItemId!!))
            put("source_line_id", JsonPrimitive(sourceLineId))
            put("binding_basis", JsonPrimitive(if (explicit == null) "single_verified_product" else "explicit_line"))
        }
    }
}
