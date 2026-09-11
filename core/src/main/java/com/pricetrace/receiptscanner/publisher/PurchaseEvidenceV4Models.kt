package com.pricetrace.receiptscanner.publisher

import com.pricetrace.receiptscanner.ingestion.PurchaseRecord
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordLine
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordKind
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV4Json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal

/** Exact RPC seams confirmed from the current PriceTrace/CashOS V4 contracts. */
data class PriceTracePurchaseObservationV4Contract(
    val contractVersion: String = "purchase-price.v4",
    val rpcPath: String = "/rest/v1/rpc/ingest_verified_purchase_price_observation_v1",
) {
    init {
        require(contractVersion == "purchase-price.v4")
        require(rpcPath == "/rest/v1/rpc/ingest_verified_purchase_price_observation_v1")
    }
}

data class CashOsTransactionV4Contract(
    val contractVersion: String = "cashos.transaction-ingest.v4",
    val rpcPath: String = "/rest/v1/rpc/finance_ingest_transaction_v4",
) {
    init {
        require(contractVersion == "cashos.transaction-ingest.v4")
        require(rpcPath == "/rest/v1/rpc/finance_ingest_transaction_v4")
    }
}

data class PriceTracePurchaseObservationV4Payload(
    val contract: PriceTracePurchaseObservationV4Contract,
    val purchaseRecord: PurchaseRecord,
    val verificationBasis: VerificationBasis,
) {
    init {
        require(purchaseRecord.priceObservationEligible) {
            "PriceTrace V4 requires a non-payment purchase with an order/payment date"
        }
    }

    fun toJson(): JsonObject = PriceTracePurchaseObservationV4Json.toJson(this)
}

data class PriceTracePurchaseObservationV4Response(
    val purchaseSourceId: String,
    val observationIds: List<String>,
    val replayed: Boolean,
    val deduplicated: Boolean,
    val raw: JsonObject,
)

object PriceTracePurchaseObservationV4Json {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    fun encode(payload: PriceTracePurchaseObservationV4Payload): String =
        json.encodeToString(JsonObject.serializer(), payload.toJson())

    fun toJson(payload: PriceTracePurchaseObservationV4Payload): JsonObject {
        val record = payload.purchaseRecord
        val seller = effectiveSeller(record)
        return buildJsonObject {
            put("schema_version", JsonPrimitive("purchase-price-observation.v4"))
            put("contract_version", JsonPrimitive("purchase-price.v4"))
            put("source_app", JsonPrimitive("pricetrace_ocr_app"))
            record.sourceVersion?.let { put("source_version", JsonPrimitive(it)) }
            put("kind", JsonPrimitive(record.kind.toPriceTraceKind()))
            put("verification_basis", JsonPrimitive(payload.verificationBasis.toPriceTraceValue()))
            put("transcription_status", JsonPrimitive("user_verified"))
            put("platform", buildJsonObject {
                put("name", JsonPrimitive(record.platform))
                put("code", record.platformCode?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("seller", seller?.let { sellerName ->
                buildJsonObject {
                    put("seller_name", JsonPrimitive(sellerName))
                    put("branch_name", record.sellerBranchName?.let(::JsonPrimitive) ?: JsonNull)
                    put("source_namespace", record.sellerSourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
                    put("source_code", record.sellerSourceCode?.let(::JsonPrimitive) ?: JsonNull)
                    put("business_kind", record.sellerBusinessKind?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull)
            put("order", buildJsonObject {
                put("order_reference", record.orderReference?.let(::JsonPrimitive) ?: JsonNull)
                put("status", JsonPrimitive(record.status.wireValue))
                put("currency", JsonPrimitive(record.currency))
                put("ordered_on", record.orderedOn?.let(::JsonPrimitive) ?: JsonNull)
                put("ordered_at", record.orderedAt?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("payment", buildJsonObject {
                put("status", JsonPrimitive(record.payment?.status ?: "unknown"))
                put("method", JsonPrimitive(record.payment?.method ?: "unknown"))
                put("paid_on", record.paidOn?.let(::JsonPrimitive) ?: JsonNull)
                put("paid_at", record.paidAt?.let(::JsonPrimitive) ?: JsonNull)
                put("total_price", record.totals.cashOsAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("items_subtotal", record.totals.subtotalAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("shipping_fee", record.totals.shippingAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("discount", record.totals.discountAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("items", JsonArray(record.lineItems.mapIndexed { index, line ->
                priceTraceLineJson(line, record, index)
            }))
        }
    }

    fun decodeResponse(value: String): PriceTracePurchaseObservationV4Response {
        val row = asRow(value)
        val sourceId = row.requiredString("purchaseSourceId")
        val observationIds = (row["observationIds"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error("PriceTrace V4 observationIds must contain strings")
        } ?: emptyList()
        val replayed = row.requiredBooleanOrDefault("replayed", false)
        val deduplicated = row.requiredBooleanOrDefault("deduplicated", false)
        return PriceTracePurchaseObservationV4Response(
            purchaseSourceId = sourceId,
            observationIds = observationIds,
            replayed = replayed,
            deduplicated = deduplicated,
            raw = row,
        )
    }

    private fun priceTraceLineJson(line: PurchaseRecordLine, record: PurchaseRecord, index: Int): JsonObject {
        val quantity = line.quantity?.let {
            require(it.isFinite() && it > 0.0 && it % 1.0 == 0.0) {
                "PriceTrace V4 quantity must be a positive integer"
            }
            JsonPrimitive(it.toLong())
        } ?: JsonNull
        val productName = line.description?.takeIf(String::isNotBlank)
            ?: error("PriceTrace V4 line product_name is required")
        val sellerOverrides = record.lineItems.mapNotNull { it.sellerOverride }.distinct()
        require(sellerOverrides.isEmpty() || sellerOverrides.all {
            effectiveSeller(record)?.equals(it, ignoreCase = true) == true
        }) {
            "PriceTrace V4 cannot represent conflicting line seller overrides"
        }
        return buildJsonObject {
            put("line_key", JsonPrimitive(line.lineKey ?: "line-${index + 1}"))
            put("product", buildJsonObject {
                line.productClientKey?.let { put("client_key", JsonPrimitive(it)) }
                put("product_name", JsonPrimitive(productName))
                put("merchant_sku", line.merchantSku?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("option_text", line.optionText?.let(::JsonPrimitive) ?: JsonNull)
            put("price_status", JsonPrimitive(line.priceStatus))
            put("quantity", quantity)
            put("unit_price", line.unitPriceAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            put("gross_price", line.grossAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            put("discount", line.discountAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            put("net_price", line.netAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun effectiveSeller(record: PurchaseRecord): String? {
        val overrides = record.lineItems.mapNotNull { it.sellerOverride }.distinct()
        val seller = record.seller ?: overrides.singleOrNull()
        require(overrides.isEmpty() || overrides.all {
            seller?.equals(it, ignoreCase = true) == true
        }) {
            "PurchaseRecord has multiple seller overrides that PriceTrace V4 cannot represent"
        }
        return seller
    }

    private fun PurchaseRecordKind.toPriceTraceKind(): String = when (this) {
        PurchaseRecordKind.RETAIL -> "retail_purchase"
        PurchaseRecordKind.RESTAURANT -> "restaurant_purchase"
        PurchaseRecordKind.PAYMENT_ONLY -> error("payment-only record cannot create PriceTrace observation")
    }

    private fun VerificationBasis.toPriceTraceValue(): String = when (this) {
        VerificationBasis.SOURCE_EVIDENCE -> "source_evidence"
        VerificationBasis.MANUAL_CANONICAL_REVIEW -> "manual_canonical_review"
    }
}

data class CashOsTransactionV4Item(
    val transactionItemId: String,
    val lineOrdinal: Int,
    val descriptionSnapshot: String,
    val quantity: String? = null,
    val unit: String? = null,
    val unitPriceKrw: Long? = null,
    val grossAmountKrw: Long? = null,
    val discountAmountKrw: Long? = null,
    val feeAmountKrw: Long? = null,
    val netAmountKrw: Long? = null,
    val lineType: String = "product",
    val priceTraceStoreProductId: String? = null,
    val priceTraceCatalogProductId: String? = null,
) {
    init {
        require(transactionItemId.isNotBlank() && transactionItemId.length <= 200)
        require(lineOrdinal > 0 && lineOrdinal <= 100_000)
        require(descriptionSnapshot.isNotBlank() && descriptionSnapshot.length <= 500)
        require(quantity == null || quantity.matches(Regex("^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?$")))
        require(quantity == null || BigDecimal(quantity).signum() > 0)
        require(unit == null || unit.isNotBlank() && unit.length <= 50)
        listOf(unitPriceKrw, grossAmountKrw, discountAmountKrw, feeAmountKrw, netAmountKrw)
            .forEach { require(it == null || it >= 0) }
        require(lineType in setOf("product", "shipping", "discount", "fee", "tax", "refund", "other"))
    }
}

data class CashOsTransactionV4Payload(
    val contract: CashOsTransactionV4Contract,
    val idempotencyKey: String,
    val documentId: String,
    val transactionRevision: String,
    val revisionSeq: Long,
    val transactionFingerprint: String,
    val platform: String,
    val seller: String?,
    val orderedLocalDate: String?,
    val paidLocalDate: String?,
    val grandTotalAmountKrw: Long,
    val grossAmountKrw: Long?,
    val discountAmountKrw: Long?,
    val feeAmountKrw: Long?,
    val paymentMethodHint: String?,
    val accountHint: String?,
    val institutionHint: String?,
    val categoryHint: String?,
    val categoryId: String? = null,
    val accountId: String? = null,
    val priceTraceStoreId: String? = null,
    val items: List<CashOsTransactionV4Item> = emptyList(),
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200)
        require(documentId.isNotBlank() && documentId.length <= 200)
        require(transactionRevision.isNotBlank() && transactionRevision.length <= 100)
        require(revisionSeq > 0)
        require(transactionFingerprint.matches(Regex("^[0-9a-fA-F]{64}$")))
        require(platform.isNotBlank() && platform.length <= 200)
        require(seller == null || seller.isNotBlank() && seller.length <= 500)
        require(orderedLocalDate != null || paidLocalDate != null)
        require(grandTotalAmountKrw >= 0)
        listOf(grossAmountKrw, discountAmountKrw, feeAmountKrw).forEach {
            require(it == null || it >= 0)
        }
        require(items.size <= 200)
    }

    fun toRpcJson(): String = CashOsTransactionV4Json.encode(this)
}

data class CashOsTransactionV4Response(
    val ledgerEntryId: String,
    val transactionId: String,
    val replayed: Boolean,
    val itemCount: Int,
    val categoryId: String?,
    val accountId: String?,
    val categoryResolution: String,
    val accountResolution: String,
    val accountCandidateIds: List<String>,
    val raw: JsonObject,
)

object CashOsTransactionV4Json {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    fun encode(payload: CashOsTransactionV4Payload): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("p_contract_version", JsonPrimitive(payload.contract.contractVersion))
            put("p_idempotency_key", JsonPrimitive(payload.idempotencyKey))
            put("p_document_id", JsonPrimitive(payload.documentId))
            put("p_transaction_revision", JsonPrimitive(payload.transactionRevision))
            put("p_revision_seq", JsonPrimitive(payload.revisionSeq))
            put("p_transaction_fingerprint", JsonPrimitive(payload.transactionFingerprint.lowercase()))
            put("p_platform", JsonPrimitive(payload.platform))
            put("p_seller", payload.seller?.let(::JsonPrimitive) ?: JsonNull)
            put("p_ordered_local_date", payload.orderedLocalDate?.let(::JsonPrimitive) ?: JsonNull)
            put("p_paid_local_date", payload.paidLocalDate?.let(::JsonPrimitive) ?: JsonNull)
            put("p_grand_total_amount_krw", JsonPrimitive(payload.grandTotalAmountKrw))
            put("p_gross_amount_krw", payload.grossAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            put("p_discount_amount_krw", payload.discountAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            put("p_fee_amount_krw", payload.feeAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            put("p_payment_method_hint", payload.paymentMethodHint?.let(::JsonPrimitive) ?: JsonNull)
            put("p_account_hint", payload.accountHint?.let(::JsonPrimitive) ?: JsonNull)
            put("p_institution_hint", payload.institutionHint?.let(::JsonPrimitive) ?: JsonNull)
            put("p_category_hint", payload.categoryHint?.let(::JsonPrimitive) ?: JsonNull)
            put("p_category_id", payload.categoryId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_account_id", payload.accountId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_price_trace_store_id", payload.priceTraceStoreId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_items", JsonArray(payload.items.map { item ->
                buildJsonObject {
                    put("transaction_item_id", JsonPrimitive(item.transactionItemId))
                    put("line_ordinal", JsonPrimitive(item.lineOrdinal))
                    put("description_snapshot", JsonPrimitive(item.descriptionSnapshot))
                    put("quantity", item.quantity?.let(::JsonPrimitive) ?: JsonNull)
                    put("unit", item.unit?.let(::JsonPrimitive) ?: JsonNull)
                    put("unit_price_krw", item.unitPriceKrw?.let(::JsonPrimitive) ?: JsonNull)
                    put("gross_amount_krw", item.grossAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                    put("discount_amount_krw", item.discountAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                    put("fee_amount_krw", item.feeAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                    put("net_amount_krw", item.netAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                    put("line_type", JsonPrimitive(item.lineType))
                    put("price_trace_store_product_id", item.priceTraceStoreProductId?.let(::JsonPrimitive) ?: JsonNull)
                    put("price_trace_catalog_product_id", item.priceTraceCatalogProductId?.let(::JsonPrimitive) ?: JsonNull)
                }
            }))
        },
    )

    fun decodeResponse(value: String): CashOsTransactionV4Response {
        val row = asRow(value)
        fun requiredString(key: String): String = row.requiredString(key)
        fun requiredBoolean(key: String): Boolean = (row[key] as? JsonPrimitive)?.contentOrNull
            ?.toBooleanStrictOrNull() ?: error("CashOS V4 response field $key must be boolean")
        fun requiredInt(key: String): Int = (row[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: error("CashOS V4 response field $key must be an integer")
        fun nullableString(key: String): String? = when (val item = row[key]) {
            null, JsonNull -> null
            else -> (item as? JsonPrimitive)?.contentOrNull
                ?: error("CashOS V4 response field $key must be string or null")
        }
        val candidates = when (val item = row["account_candidate_ids"]) {
            null, JsonNull -> emptyList()
            else -> item.jsonArray.map {
                (it as? JsonPrimitive)?.contentOrNull
                    ?: error("CashOS V4 account_candidate_ids must contain strings")
            }
        }
        return CashOsTransactionV4Response(
            ledgerEntryId = requiredString("ledger_entry_id"),
            transactionId = requiredString("transaction_id"),
            replayed = requiredBoolean("replayed"),
            itemCount = requiredInt("item_count"),
            categoryId = nullableString("category_id"),
            accountId = nullableString("account_id"),
            categoryResolution = requiredString("category_resolution"),
            accountResolution = requiredString("account_resolution"),
            accountCandidateIds = candidates,
            raw = row,
        )
    }
}

private fun asRow(value: String): JsonObject {
    val element = Json.parseToJsonElement(value)
    return when (element) {
        is JsonObject -> element
        else -> element.jsonArray.single().jsonObject
    }
}

private fun JsonObject.requiredString(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("Missing response field: $key")

private fun JsonObject.requiredBooleanOrDefault(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default
