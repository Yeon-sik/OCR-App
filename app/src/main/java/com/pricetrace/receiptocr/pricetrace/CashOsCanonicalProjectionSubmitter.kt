package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.purchaseLocalTime
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentity
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Item
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Payload
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Response
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.OffsetDateTime

/** Maps a verified OCR receipt to CashOS's authenticated v3 RPC only. */
internal class CashOsCanonicalProjectionSubmitter(
    private val gateway: CashOsReceiptGateway,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
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
