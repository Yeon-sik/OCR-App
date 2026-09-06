package com.pricetrace.receiptscanner.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Minimal user-verified facts accepted by CashOS; images/raw OCR/payment data are excluded. */
data class CashOsReceiptSubmitItem(
    val receiptItemId: String,
    val descriptionSnapshot: String,
    val quantity: String,
    val unit: String,
    val unitPriceKrw: Int,
    val totalPriceKrw: Int,
    val lineType: String,
    val priceTraceCatalogProductId: String? = null,
    val nutritionFoodId: String? = null,
    val restaurantMenuId: String? = null,
    val menuName: String? = null,
    val catalogProductId: String? = null,
) {
    init {
        require(receiptItemId.isNotBlank())
        require(descriptionSnapshot.isNotBlank())
        require(quantity.toBigDecimalOrNull()?.signum() == 1)
        require(unit.isNotBlank())
        require(unitPriceKrw >= 0 && totalPriceKrw >= 0)
        require(lineType.isNotBlank())
    }
}

data class CashOsReceiptSubmitPayload(
    val idempotencyKey: String,
    val ledgerEntryId: String,
    val documentId: String,
    val receiptRevision: String,
    val receiptFingerprint: String,
    val merchantName: String,
    val branchName: String?,
    val purchaseLocalDate: String,
    val purchaseLocalTime: String?,
    val totalAmountKrw: Int,
    val items: List<CashOsReceiptSubmitItem>,
    val restaurantId: String? = null,
    val restaurantLocationId: String? = null,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(documentId.isNotBlank())
        require(receiptRevision.isNotBlank())
        require(receiptFingerprint.matches(Regex("[0-9a-fA-F]{64}")))
        require(merchantName.isNotBlank())
        require(totalAmountKrw >= 0)
        require(items.isNotEmpty())
    }

    fun toRpcJson(): String = CashOsReceiptJson.encode(this)
}

data class CashOsReceiptSubmitResponse(
    val receiptId: String,
    val replayed: Boolean,
    val itemCount: Int,
)

object CashOsReceiptJson {
    private val json = Json { explicitNulls = true; ignoreUnknownKeys = false }

    fun encode(payload: CashOsReceiptSubmitPayload): String = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("p_idempotency_key", JsonPrimitive(payload.idempotencyKey))
            put("p_ledger_entry_id", JsonPrimitive(payload.ledgerEntryId))
            put("p_document_id", JsonPrimitive(payload.documentId))
            put("p_receipt_revision", JsonPrimitive(payload.receiptRevision))
            put("p_receipt_fingerprint", JsonPrimitive(payload.receiptFingerprint))
            put("p_merchant_name", JsonPrimitive(payload.merchantName))
            put("p_branch_name", payload.branchName?.let(::JsonPrimitive) ?: JsonNull)
            put("p_restaurant_id", payload.restaurantId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_restaurant_location_id", payload.restaurantLocationId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_purchase_local_date", JsonPrimitive(payload.purchaseLocalDate))
            put("p_purchase_local_time", payload.purchaseLocalTime?.let(::JsonPrimitive) ?: JsonNull)
            put("p_total_amount_krw", JsonPrimitive(payload.totalAmountKrw))
            put("p_items", buildJsonArray {
                payload.items.forEach { item ->
                    add(buildJsonObject {
                        put("receipt_item_id", JsonPrimitive(item.receiptItemId))
                        put("description_snapshot", JsonPrimitive(item.descriptionSnapshot))
                        put("quantity", JsonPrimitive(item.quantity.toBigDecimal()))
                        put("unit", JsonPrimitive(item.unit))
                        put("unit_price_krw", JsonPrimitive(item.unitPriceKrw))
                        put("total_price_krw", JsonPrimitive(item.totalPriceKrw))
                        put("line_type", JsonPrimitive(item.lineType))
                        put("restaurant_menu_id", item.restaurantMenuId?.let(::JsonPrimitive) ?: JsonNull)
                        put("menu_name", item.menuName?.let(::JsonPrimitive) ?: JsonPrimitive(item.descriptionSnapshot))
                        put(
                            "catalog_product_id",
                            (item.catalogProductId ?: item.priceTraceCatalogProductId)
                                ?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put("pricetrace_catalog_product_id", item.priceTraceCatalogProductId?.let(::JsonPrimitive) ?: JsonNull)
                        put("nutrition_food_id", item.nutritionFoodId?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            })
        },
    )
}

interface CashOsReceiptSubmitter {
    suspend fun submit(payload: CashOsReceiptSubmitPayload): CashOsReceiptSubmitResult
}

sealed interface CashOsReceiptSubmitResult {
    data class Success(val response: CashOsReceiptSubmitResponse) : CashOsReceiptSubmitResult
    data class Failure(val kind: PriceObservationFailureKind, val message: String? = null) : CashOsReceiptSubmitResult
}
