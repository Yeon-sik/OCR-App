package com.pricetrace.receiptscanner.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal

const val CASHOS_RECEIPT_INGEST_V3 = "cashos.receipt-ingest.v3"
private val CASHOS_LINE_TYPES = setOf("product", "service", "discount", "fee", "tax", "tip", "refund", "rounding", "other")

data class CashOsReceiptIngestV3Item(
    val receiptItemId: String,
    val descriptionSnapshot: String,
    val menuName: String? = null,
    val quantity: String? = null,
    val unit: String? = null,
    val unitPriceKrw: Long? = null,
    val grossAmountKrw: Long? = null,
    val discountAmountKrw: Long? = null,
    val taxAmountKrw: Long? = null,
    val netAmountKrw: Long,
    val lineType: String,
    val restaurantMenuId: String? = null,
    val catalogProductId: String? = null,
    val priceTraceCatalogProductId: String? = null,
    val priceTraceProductId: String? = null,
    val priceTraceStoreProductId: String? = null,
    val priceTraceIdentity: JsonObject? = null,
    val nutritionFoodId: String? = null,
) {
    init {
        require(receiptItemId.isNotBlank() && receiptItemId.length <= 200)
        require(descriptionSnapshot.isNotBlank() && descriptionSnapshot.length <= 500)
        quantity?.let {
            require(it.length <= 32 && BigDecimal(it).signum() > 0) { "quantity must be positive" }
        }
        unit?.let { require(it.isNotBlank() && it.length <= 50) }
        listOf(unitPriceKrw, grossAmountKrw, discountAmountKrw, taxAmountKrw).forEach {
            it?.let { value -> require(value >= 0) { "CashOS nullable amount must be non-negative" } }
        }
        require(lineType in CASHOS_LINE_TYPES)
    }
}

data class CashOsReceiptIngestV3Payload(
    val idempotencyKey: String,
    val documentId: String,
    val receiptRevision: String,
    val revisionSeq: Long,
    val receiptFingerprint: String,
    val merchantName: String,
    val branchName: String?,
    val purchaseLocalDate: String,
    val purchaseLocalTime: String?,
    val grandTotalAmountKrw: Long,
    val priceTraceStoreId: String,
    val items: List<CashOsReceiptIngestV3Item>,
    val restaurantId: String? = null,
    val restaurantLocationId: String? = null,
    /** Full PriceTrace authority response; nested in items for compatibility with the v3 RPC. */
    val priceTraceIdentity: JsonObject? = null,
    val categoryHint: String? = null,
    val paymentMethodHint: String? = null,
    val institutionHint: String? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200)
        require(documentId.isNotBlank() && documentId.length <= 200)
        require(receiptRevision.isNotBlank() && receiptRevision.length <= 100)
        require(revisionSeq > 0)
        require(receiptFingerprint.matches(Regex("[0-9a-fA-F]{64}")))
        require(merchantName.isNotBlank() && merchantName.length <= 500)
        require(branchName == null || branchName.length <= 500)
        require(grandTotalAmountKrw >= 0)
        require(
            priceTraceStoreId.isNotBlank() &&
                priceTraceStoreId == priceTraceStoreId.trim() &&
                priceTraceStoreId.length <= 200 &&
                !priceTraceStoreId.startsWith("http://", ignoreCase = true) &&
                !priceTraceStoreId.startsWith("https://", ignoreCase = true),
        ) { "priceTraceStoreId must be an opaque identity" }
        require(items.isNotEmpty() && items.size <= 200)
    }

    fun toRpcJson(): String = CashOsReceiptIngestV3Json.encode(this)
}

data class CashOsReceiptIngestV3Response(
    val ledgerEntryId: String,
    val receiptId: String,
    val replayed: Boolean,
    val itemCount: Int,
    val categoryId: String?,
    val accountId: String?,
    val categoryResolution: String,
    val accountResolution: String,
    val accountCandidateIds: List<String>,
)

object CashOsReceiptIngestV3Json {
    private val json = Json { explicitNulls = true; ignoreUnknownKeys = false }

    fun encode(payload: CashOsReceiptIngestV3Payload): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("p_contract_version", JsonPrimitive(CASHOS_RECEIPT_INGEST_V3))
            put("p_idempotency_key", JsonPrimitive(payload.idempotencyKey))
            put("p_document_id", JsonPrimitive(payload.documentId))
            put("p_receipt_revision", JsonPrimitive(payload.receiptRevision))
            put("p_revision_seq", JsonPrimitive(payload.revisionSeq))
            put("p_receipt_fingerprint", JsonPrimitive(payload.receiptFingerprint.lowercase()))
            put("p_merchant_name", JsonPrimitive(payload.merchantName))
            put("p_branch_name", payload.branchName?.let(::JsonPrimitive) ?: JsonNull)
            put("p_restaurant_id", payload.restaurantId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_restaurant_location_id", payload.restaurantLocationId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_purchase_local_date", JsonPrimitive(payload.purchaseLocalDate))
            put("p_purchase_local_time", payload.purchaseLocalTime?.let(::JsonPrimitive) ?: JsonNull)
            put("p_grand_total_amount_krw", JsonPrimitive(payload.grandTotalAmountKrw))
            put("p_price_trace_store_id", JsonPrimitive(payload.priceTraceStoreId))
            put("p_category_hint", payload.categoryHint?.let(::JsonPrimitive) ?: JsonNull)
            put("p_payment_method_hint", payload.paymentMethodHint?.let(::JsonPrimitive) ?: JsonNull)
            put("p_institution_hint", payload.institutionHint?.let(::JsonPrimitive) ?: JsonNull)
            put("p_category_id", payload.categoryId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_account_id", payload.accountId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_items", buildJsonArray {
                payload.items.forEach { item ->
                    add(buildJsonObject {
                        put("receipt_item_id", JsonPrimitive(item.receiptItemId))
                        put("description_snapshot", JsonPrimitive(item.descriptionSnapshot))
                        put("menu_name", item.menuName?.let(::JsonPrimitive) ?: JsonNull)
                        put("quantity", item.quantity?.let(::JsonPrimitive) ?: JsonNull)
                        put("unit", item.unit?.let(::JsonPrimitive) ?: JsonNull)
                        put("unit_price_krw", item.unitPriceKrw?.let(::JsonPrimitive) ?: JsonNull)
                        put("gross_amount_krw", item.grossAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                        put("discount_amount_krw", item.discountAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                        put("tax_amount_krw", item.taxAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                        put("net_amount_krw", JsonPrimitive(item.netAmountKrw))
                        put("line_type", JsonPrimitive(item.lineType))
                        put("restaurant_menu_id", item.restaurantMenuId?.let(::JsonPrimitive) ?: JsonNull)
                        put("catalog_product_id", item.catalogProductId?.let(::JsonPrimitive) ?: JsonNull)
                        put("pricetrace_catalog_product_id", item.priceTraceCatalogProductId?.let(::JsonPrimitive) ?: JsonNull)
                        if (item.priceTraceProductId != null) {
                            put("pricetrace_product_id", JsonPrimitive(item.priceTraceProductId))
                        }
                        if (item.priceTraceStoreProductId != null) {
                            put("pricetrace_store_product_id", JsonPrimitive(item.priceTraceStoreProductId))
                        }
                        if (item.priceTraceIdentity != null || payload.priceTraceIdentity != null) {
                            put("pricetrace_identity", item.priceTraceIdentity ?: payload.priceTraceIdentity ?: JsonNull)
                        }
                        put("nutrition_food_id", item.nutritionFoodId?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            })
        },
    )

    fun decodeResponse(value: String): CashOsReceiptIngestV3Response {
        val element = json.parseToJsonElement(value)
        val row = when (element) {
            is JsonObject -> element
            else -> element.jsonArray.single().jsonObject
        }
        fun requiredString(key: String): String = (row[key] as? JsonPrimitive)?.content
            ?.takeIf(String::isNotBlank) ?: error("Missing CashOS v3 response field: $key")
        fun nullableString(key: String): String? = when (val item = row[key]) {
            null, JsonNull -> null
            else -> (item as? JsonPrimitive)?.content ?: error("Invalid CashOS v3 response field: $key")
        }
        fun requiredBoolean(key: String): Boolean = (row[key] as? JsonPrimitive)?.content
            ?.toBooleanStrictOrNull() ?: error("Missing CashOS v3 response field: $key")
        fun requiredInt(key: String): Int = (row[key] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: error("Missing CashOS v3 response field: $key")
        val candidates = row["account_candidate_ids"]?.let { element ->
            element.jsonArray.map { (it as? JsonPrimitive)?.content ?: error("Invalid account candidate") }
        } ?: emptyList()
        return CashOsReceiptIngestV3Response(
            ledgerEntryId = requiredString("ledger_entry_id"),
            receiptId = requiredString("receipt_id"),
            replayed = requiredBoolean("replayed"),
            itemCount = requiredInt("item_count"),
            categoryId = nullableString("category_id"),
            accountId = nullableString("account_id"),
            categoryResolution = requiredString("category_resolution"),
            accountResolution = requiredString("account_resolution"),
            accountCandidateIds = candidates,
        )
    }

}
