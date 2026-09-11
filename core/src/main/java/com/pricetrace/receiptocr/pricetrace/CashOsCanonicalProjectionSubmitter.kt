package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.domain.purchaseLocalTime
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentity
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import com.pricetrace.receiptscanner.ingestion.PurchaseRecord
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordLine
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordStatus
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV4Json
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Item
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Payload
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Response
import com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Item
import com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Payload
import com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Response
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.OffsetDateTime
import java.math.BigDecimal

/** Maps a verified OCR receipt to CashOS's authenticated v3 RPC only. */
class CashOsCanonicalProjectionSubmitter(
    private val gateway: CashOsReceiptGateway,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        if (request.projection == IngestionProjection.CASHOS_TRANSACTION) {
            return submitTransactionV4(request)
        }
        if (request.projection != IngestionProjection.CASHOS_RECEIPT) {
            return ProjectionSubmission.Failure("unsupported_cashos_projection", retryable = false)
        }
        val envelope = request.envelope
            ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        val receipt = envelope.receipt
            ?: return ProjectionSubmission.Failure("receipt_artifact_missing", retryable = false)
        val localDocumentId = request.localDocumentId
            ?: return ProjectionSubmission.Failure("local_document_id_missing", retryable = false)
        val priceTraceIdentity = request.resolvedIdentity?.priceTrace
            ?: return ProjectionSubmission.Failure("pricetrace_identity_missing", retryable = false)
        val priceTraceStoreId = priceTraceIdentity.storeId
            ?.takeIf { it.isNotBlank() && it == it.trim() }
            ?: return ProjectionSubmission.Failure("pricetrace_store_id_missing", retryable = false)

        return try {
            val payload = toPayload(request, receipt, localDocumentId, priceTraceIdentity, priceTraceStoreId)
            when (val result = gateway.ingestVerifiedReceiptV3(payload)) {
                is PriceObservationReadOutcome.Success -> ProjectionSubmission.Success(
                    remoteId = result.value.receiptId,
                    metadataJson = responseMetadata(result.value),
                )
                is PriceObservationReadOutcome.Failure -> ProjectionSubmission.Failure(
                    message = result.message ?: result.kind.name,
                    retryable = result.kind.retryable,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            ProjectionSubmission.Failure(error.message ?: "cashos_contract_invalid", retryable = false)
        } catch (error: Exception) {
            ProjectionSubmission.Failure(error.message ?: "cashos_projection_failed", retryable = true)
        }
    }

    private suspend fun submitTransactionV4(request: ProjectionRequest): ProjectionSubmission {
        val envelope = request.envelope
            ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        val localDocumentId = request.localDocumentId
            ?: return ProjectionSubmission.Failure("local_document_id_missing", retryable = false)
        val records = envelope.purchaseRecords.filter { it.cashOsTransactionEligible }
        if (records.isEmpty()) {
            return ProjectionSubmission.Failure("cashos_transaction_artifact_missing", retryable = false)
        }
        return try {
            val responses = records.map { record ->
                val recordJson = YeonsikOcrV4Json.encodePurchaseRecord(record).toString()
                val recordIdempotencyKey = StableIds.sha256(
                    request.idempotencyKey + "|purchase_record=" + record.clientKey,
                )
                val recordFingerprint = StableIds.sha256("cashos-transaction-source|" + recordJson)
                val recordRevision = StableIds.sha256("cashos-transaction-revision|" + recordJson)
                val recordDocumentId = if (records.size == 1) {
                    localDocumentId
                } else {
                    StableIds.sha256(
                        "cashos-transaction-document|" + localDocumentId + "|" + record.clientKey,
                    )
                }
                val payload = toTransactionPayload(
                    request = request,
                    record = record,
                    idempotencyKey = recordIdempotencyKey,
                    documentId = recordDocumentId,
                    transactionRevision = recordRevision,
                    transactionFingerprint = recordFingerprint,
                    categoryHint = envelope.classificationHints["cashos.category_hint"],
                    paymentMethodHint = record.payment?.method
                        ?: envelope.classificationHints["cashos.payment_method_hint"],
                    institutionHint = envelope.classificationHints["cashos.institution_hint"],
                )
                when (val result = gateway.ingestTransactionV4(payload)) {
                    is PriceObservationReadOutcome.Success -> result.value
                    is PriceObservationReadOutcome.Failure -> return ProjectionSubmission.Failure(
                        message = result.message ?: result.kind.name,
                        retryable = result.kind.retryable,
                    )
                }
            }
            val metadata = buildJsonObject {
                put("schemaVersion", JsonPrimitive("cashos.transaction-ingest.v4"))
                put("transactions", JsonArray(responses.map(CashOsTransactionV4Response::raw)))
                put("postingStates", JsonArray(responses.map {
                    JsonPrimitive(it.postingState)
                }))
            }.toString()
            ProjectionSubmission.Success(
                remoteId = responses.first().transactionId,
                metadataJson = metadata,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            ProjectionSubmission.Failure(error.message ?: "cashos_transaction_contract_invalid", retryable = false)
        } catch (error: Exception) {
            ProjectionSubmission.Failure(error.message ?: "cashos_transaction_failed", retryable = true)
        }
    }

    private fun toTransactionPayload(
        request: ProjectionRequest,
        record: PurchaseRecord,
        idempotencyKey: String,
        documentId: String,
        transactionRevision: String,
        transactionFingerprint: String,
        categoryHint: String?,
        paymentMethodHint: String?,
        institutionHint: String?,
    ): CashOsTransactionV4Payload {
        val grandTotal = record.totals.cashOsAmountKrw
            ?: error("cashos_transaction_grand_total_missing")
        val gross = record.totals.subtotalAmountKrw
        val discount = record.totals.discountAmountKrw
        val shipping = record.totals.shippingAmountKrw
        val fee = shipping?.takeIf {
            gross == null || discount == null || gross - discount + it == grandTotal
        }
        val sellerOverrides = record.lineItems.mapNotNull(PurchaseRecordLine::sellerOverride).distinct()
        val seller = record.seller ?: sellerOverrides.singleOrNull()
        return CashOsTransactionV4Payload(
            contract = com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Contract(),
            idempotencyKey = idempotencyKey,
            documentId = documentId,
            transactionRevision = transactionRevision,
            revisionSeq = request.revisionSeq,
            transactionFingerprint = transactionFingerprint,
            sourceType = record.cashOsSourceType(),
            transactionStatus = record.cashOsTransactionStatus(),
            paymentStatus = record.cashOsPaymentStatus(),
            orderReference = record.orderReference,
            paymentReference = null,
            orderedAt = record.orderedAt,
            paidAt = record.paidAt,
            timestampProvenance = record.cashOsTimestampProvenance(),
            platform = record.platform,
            seller = seller,
            orderedLocalDate = record.effectiveOrderedOn,
            paidLocalDate = record.effectivePaidOn,
            grandTotalAmountKrw = grandTotal,
            grossAmountKrw = gross,
            discountAmountKrw = discount,
            feeAmountKrw = fee,
            paymentMethodHint = paymentMethodHint,
            accountHint = null,
            institutionHint = institutionHint,
            categoryHint = categoryHint,
            items = record.lineItems.mapIndexed(::toTransactionItem),
        )
    }

    private fun toTransactionItem(index: Int, line: PurchaseRecordLine): CashOsTransactionV4Item {
        val description = line.description?.takeIf(String::isNotBlank)
            ?: error("cashos_transaction_line_description_missing")
        val quantity = line.quantity?.let {
            BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()
        }
        return CashOsTransactionV4Item(
            transactionItemId = line.lineKey ?: "line-" + (index + 1),
            lineOrdinal = index + 1,
            descriptionSnapshot = description,
            seller = line.sellerOverride,
            sourceItemReference = line.lineKey,
            quantity = quantity,
            unit = null,
            unitPriceKrw = line.unitPriceAmountKrw,
            grossAmountKrw = line.grossAmountKrw,
            discountAmountKrw = line.discountAmountKrw,
            feeAmountKrw = null,
            netAmountKrw = line.netAmountKrw,
            lineType = "product",
        )
    }

    private fun PurchaseRecord.cashOsSourceType(): String = when {
        evidence.any { it.sourceType == "payment_history" } -> "payment_history"
        evidence.any { it.sourceType == "order_history" } -> "order_history"
        evidence.any { it.sourceType == "user_statement" } -> "user_statement"
        else -> error("cashos_transaction_source_type_missing")
    }

    private fun PurchaseRecord.cashOsTransactionStatus(): String = when {
        lineItems.isEmpty() -> "not_applicable"
        payment?.status == "paid" -> "confirmed"
        status == PurchaseRecordStatus.ORDERED -> "ordered"
        status == PurchaseRecordStatus.PENDING -> "pending"
        status == PurchaseRecordStatus.PAID -> "confirmed"
        status == PurchaseRecordStatus.SHIPPED ||
            status == PurchaseRecordStatus.DELIVERED -> "completed"
        status == PurchaseRecordStatus.CANCELLED -> "cancelled"
        status == PurchaseRecordStatus.REFUNDED -> "refunded"
        else -> "unknown"
    }

    private fun PurchaseRecord.cashOsPaymentStatus(): String = payment?.status ?: when (status) {
        PurchaseRecordStatus.ORDERED -> "unpaid"
        PurchaseRecordStatus.PENDING -> "pending"
        PurchaseRecordStatus.PAID -> "paid"
        PurchaseRecordStatus.CANCELLED -> "cancelled"
        PurchaseRecordStatus.REFUNDED -> "refunded"
        PurchaseRecordStatus.SHIPPED,
        PurchaseRecordStatus.DELIVERED,
        PurchaseRecordStatus.UNKNOWN -> "unknown"
    }

    private fun PurchaseRecord.cashOsTimestampProvenance(): String? =
        if (orderedAt != null || paidAt != null) "source_timestamp_offset" else null

    private fun toPayload(
        request: ProjectionRequest,
        receipt: ReceiptV2,
        localDocumentId: String,
        priceTraceIdentity: PriceTraceIdentity,
        priceTraceStoreId: String,
    ): CashOsReceiptIngestV3Payload {
        val merchantName = receipt.merchant.name?.trim()?.takeIf(String::isNotEmpty)
            ?: error("cashos_merchant_name_missing")
        val purchaseDate = receipt.document.issuedOn?.trim()?.takeIf(String::isNotEmpty)
            ?: receipt.document.issuedAt?.let { value ->
                runCatching { OffsetDateTime.parse(value).toLocalDate().toString() }.getOrNull()
            }
            ?: error("cashos_purchase_date_missing")
        val grandTotal = receipt.totals.grandTotalAmountMinor
            ?: error("cashos_grand_total_missing")
        require(grandTotal >= 0) { "cashos_grand_total_must_be_non_negative" }
        return CashOsReceiptIngestV3Payload(
            idempotencyKey = request.idempotencyKey,
            documentId = localDocumentId,
            receiptRevision = ReceiptV2Json.revisionHash(receipt),
            revisionSeq = request.revisionSeq,
            receiptFingerprint = ReceiptV2Json.revisionHash(receipt),
            merchantName = merchantName,
            branchName = receipt.merchant.branchName,
            purchaseLocalDate = purchaseDate,
            purchaseLocalTime = receipt.document.source.purchaseLocalTime()
                ?: receipt.document.issuedAt?.let(::parseLocalTime),
            grandTotalAmountKrw = grandTotal,
            priceTraceStoreId = priceTraceStoreId,
            restaurantId = priceTraceIdentity.restaurantId,
            restaurantLocationId = priceTraceIdentity.restaurantLocationId,
            priceTraceIdentity = PriceTraceIdentityJson.encode(priceTraceIdentity),
            categoryHint = request.envelope?.classificationHints?.get("cashos.category_hint"),
            paymentMethodHint = request.envelope?.classificationHints?.get("cashos.payment_method_hint")
                ?: receipt.payments.firstOrNull()?.method,
            institutionHint = request.envelope?.classificationHints?.get("cashos.institution_hint"),
            items = receipt.lineItems.map { item -> toItem(item, priceTraceIdentity) },
        )
    }

    private fun toItem(item: ReceiptV2LineItem, priceTraceIdentity: PriceTraceIdentity): CashOsReceiptIngestV3Item {
        val description = item.description?.trim()?.takeIf(String::isNotEmpty)
            ?: error("cashos_line_description_missing:${item.id}")
        val net = item.netAmountMinor ?: error("cashos_line_net_missing:${item.id}")
        val lineIdentity = priceTraceIdentity.lineFor(item.id)
        return CashOsReceiptIngestV3Item(
            receiptItemId = item.id,
            descriptionSnapshot = description,
            menuName = description,
            quantity = item.quantity?.value,
            unit = item.quantity?.wireUnit?.takeUnless { it.equals("unknown", ignoreCase = true) },
            unitPriceKrw = item.unitPriceAmountMinor,
            grossAmountKrw = item.grossAmountMinor,
            discountAmountKrw = item.discountAmountMinor,
            taxAmountKrw = item.taxAmountMinor,
            netAmountKrw = net,
            lineType = item.type.wireValue,
            restaurantMenuId = lineIdentity?.restaurantMenuId,
            catalogProductId = lineIdentity?.catalogProductId,
            priceTraceCatalogProductId = lineIdentity?.catalogProductId,
            priceTraceProductId = lineIdentity?.productId,
            priceTraceStoreProductId = lineIdentity?.storeProductId,
            priceTraceIdentity = PriceTraceIdentityJson.encode(
                priceTraceIdentity.copy(lines = lineIdentity?.let(::listOf).orEmpty()),
            ),
        )
    }

    private fun responseMetadata(response: CashOsReceiptIngestV3Response): String = buildJsonObject {
        put("ledger_entry_id", JsonPrimitive(response.ledgerEntryId))
        put("receipt_id", JsonPrimitive(response.receiptId))
        put("replayed", JsonPrimitive(response.replayed))
        put("item_count", JsonPrimitive(response.itemCount))
        put("category_id", response.categoryId?.let(::JsonPrimitive) ?: JsonNull)
        put("account_id", response.accountId?.let(::JsonPrimitive) ?: JsonNull)
        put("category_resolution", JsonPrimitive(response.categoryResolution))
        put("account_resolution", JsonPrimitive(response.accountResolution))
        put("account_candidate_ids", JsonArray(response.accountCandidateIds.map(::JsonPrimitive)))
    }.toString()
    private fun parseLocalTime(value: String): String? = runCatching {
        OffsetDateTime.parse(value).toLocalTime().toString()
    }.getOrNull()

}
