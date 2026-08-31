package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.*
import com.pricetrace.receiptscanner.ingestion.*
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Item
import com.pricetrace.receiptscanner.publisher.CashOsReceiptIngestV3Payload
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitItem
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitPayload
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CashOsReceiptGatewayTest {
    @Test
    fun fetchCandidatesUsesAuthenticatedExpenseDateAndAmountQuery() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"id":"ledger-text-1","occurred_on":"2026-08-13","amount_krw":1590,"title":"Coffee","merchant_or_counterparty":"Cafe","status":"CONFIRMED"}]""",
            ),
        )
        val result = CashOsReceiptGateway(FakeStore(signedIn()), transport)
            .fetchLedgerCandidates("2026-08-13", 1590)

        val candidates = (result as PriceObservationReadOutcome.Success).value
        assertEquals("ledger-text-1", candidates.single().id)
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://cashos.example.com/rest/v1/finance_ledger_entries?select=id,occurred_on,amount_krw,title,merchant_or_counterparty,status&amount_krw=eq.1590&occurred_on=eq.2026-08-13&entry_type=in.(EXPENSE,FIXED_EXPENSE)&deleted_at=is.null&order=occurred_on.desc&limit=50",
            request.url,
        )
        assertEquals("cashos-publishable-key", request.headers["apikey"])
        assertEquals("Bearer access-token", request.headers["Authorization"])
    }

    @Test
    fun invalidCandidateDateOrAmountDoesNotMakeANetworkRequest() = runTest {
        val transport = QueueTransport()
        val gateway = CashOsReceiptGateway(FakeStore(signedIn()), transport)

        assertTrue(gateway.fetchLedgerCandidates("not-a-date", 1590) is PriceObservationReadOutcome.Failure)
        assertTrue(gateway.fetchLedgerCandidates("2026-08-13", -1) is PriceObservationReadOutcome.Failure)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun submitKeepsTextIdsAndSendsNumericQuantityToV2Rpc() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"receipt_id":"cashos-receipt-text-1","replayed":false,"item_count":1}]""",
            ),
        )
        val result = CashOsReceiptGateway(FakeStore(signedIn()), transport).submit(payload())

        val success = result as CashOsReceiptSubmitResult.Success
        assertEquals("cashos-receipt-text-1", success.response.receiptId)
        val request = transport.requests.single()
        assertEquals(
            "https://cashos.example.com/rest/v1/rpc/finance_attach_verified_receipt_v2",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals("ledger-text-1", body["p_ledger_entry_id"]?.jsonPrimitive?.content)
        val quantity = body["p_items"]?.jsonArray?.single()?.jsonObject?.get("quantity")?.jsonPrimitive
        assertEquals("1", quantity?.content)
        assertFalse(requireNotNull(quantity).isString)
    }

    @Test
    fun ingestVerifiedReceiptV3UsesCanonicalRpcAndPreservesSignedNullableFacts() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"ledger_entry_id":"ledger-1","receipt_id":"receipt-1","replayed":false,"item_count":4,"category_id":null,"account_id":"account-1","category_resolution":"rule","account_resolution":"hint","account_candidate_ids":["account-1"]}]""",
            ),
        )
        val result = CashOsReceiptGateway(FakeStore(signedIn()), transport)
            .ingestVerifiedReceiptV3(canonicalPayload())

        val success = result as PriceObservationReadOutcome.Success
        assertEquals("ledger-1", success.value.ledgerEntryId)
        assertEquals("receipt-1", success.value.receiptId)
        assertEquals(4, success.value.itemCount)
        val request = transport.requests.single()
        assertEquals(
            "https://cashos.example.com/rest/v1/rpc/finance_ingest_verified_receipt_v3",
            request.url,
        )
        assertEquals("Bearer access-token", request.headers["Authorization"])
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(
            setOf(
                "p_contract_version", "p_idempotency_key", "p_document_id", "p_receipt_revision",
                "p_revision_seq", "p_receipt_fingerprint", "p_merchant_name", "p_branch_name",
                "p_restaurant_id", "p_restaurant_location_id", "p_purchase_local_date", "p_purchase_local_time",
                "p_grand_total_amount_krw", "p_category_hint", "p_payment_method_hint", "p_institution_hint",
                "p_category_id", "p_account_id", "p_items",
            ),
            body.keys,
        )
        assertEquals("cashos.receipt-ingest.v3", body["p_contract_version"]?.jsonPrimitive?.content)
        assertEquals("ocr-local-session", body["p_document_id"]?.jsonPrimitive?.content)
        assertEquals(9, body["p_revision_seq"]?.jsonPrimitive?.intOrNull)
        assertEquals(45, body["p_grand_total_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertFalse(body.containsKey("p_ledger_entry_id"))

        val items = body["p_items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(JsonNull, items[0]["quantity"])
        assertEquals(JsonNull, items[0]["unit"])
        assertEquals(JsonNull, items[0]["unit_price_krw"])
        assertEquals(JsonNull, items[0]["gross_amount_krw"])
        assertEquals(JsonNull, items[0]["discount_amount_krw"])
        assertEquals(JsonNull, items[0]["tax_amount_krw"])
        assertEquals(100, items[0]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertEquals(-30, items[1]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertEquals(-20, items[2]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertEquals(-5, items[3]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun canonicalProjectionMapsUnknownFactsToNullAndPreservesSignedLineNet() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"ledger_entry_id":"ledger-1","receipt_id":"receipt-1","replayed":false,"item_count":4,"category_id":"category-1","account_id":"account-1","category_resolution":"rule","account_resolution":"resolved","account_candidate_ids":[]}]""",
            ),
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.MERCHANT,
            source = IngestionSource("ocr_app", emptyList()),
            classificationHints = mapOf("cashos.institution_hint" to "bank"),
            receipt = mapperReceipt(),
        )
        val request = ProjectionRequest(
            ingestionId = "ingestion:ocr-local-session",
            projection = IngestionProjection.CASHOS_RECEIPT,
            canonicalPayload = YeonsikOcrEnvelopeJson.encode(envelope),
            resolvedIdentity = emptyMap(),
            idempotencyKey = "projection-key",
            envelope = envelope,
            localDocumentId = "ocr-local-session",
            revisionSeq = 9,
            canonicalFingerprint = "b".repeat(64),
        )

        val result = CashOsCanonicalProjectionSubmitter(
            CashOsReceiptGateway(FakeStore(signedIn()), transport),
        ).submit(request)

        val success = result as ProjectionSubmission.Success
        assertEquals("receipt-1", success.remoteId)
        val body = Json.parseToJsonElement(requireNotNull(transport.requests.single().body)).jsonObject
        assertEquals("ocr-local-session", body["p_document_id"]?.jsonPrimitive?.content)
        assertEquals("bank", body["p_institution_hint"]?.jsonPrimitive?.content)
        assertEquals(9, body["p_revision_seq"]?.jsonPrimitive?.intOrNull)
        assertEquals(45, body["p_grand_total_amount_krw"]?.jsonPrimitive?.intOrNull)
        val items = body["p_items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("1", items[0]["quantity"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, items[0]["unit"])
        assertEquals(JsonNull, items[0]["unit_price_krw"])
        assertEquals(JsonNull, items[0]["gross_amount_krw"])
        assertEquals(JsonNull, items[0]["discount_amount_krw"])
        assertEquals(JsonNull, items[0]["tax_amount_krw"])
        assertEquals(100, items[0]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertEquals(-30, items[1]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertEquals(-20, items[2]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertEquals(-5, items[3]["net_amount_krw"]?.jsonPrimitive?.intOrNull)
        assertTrue(success.metadataJson.orEmpty().contains("ledger_entry_id"))
    }

    private fun mapperReceipt() = ReceiptV2(
        document = ReceiptDocument(
            id = null,
            localDocumentId = "ocr-local-session",
            status = ReceiptStatus.FINAL,
            issuedOn = "2026-08-13",
            issuedAt = null,
            currency = "KRW",
            source = ReceiptSource(
                originalDocumentId = null,
                sourceImages = listOf("private-image"),
                transcriptionStatus = TranscriptionStatus.USER_VERIFIED,
                rawText = "private",
            ),
        ),
        merchant = ReceiptMerchant(
            name = "Cafe",
            branchName = null,
            businessKind = BusinessKind.RETAIL,
            retailChannel = RetailChannel.REGULAR,
        ),
        lineItems = listOf(
            ReceiptV2LineItem("product-1", ReceiptLineType.PRODUCT, "Coffee", listOf("line-1"), emptyList(), ReceiptQuantity("1", QuantityUnit.UNKNOWN), null, null, null, null, 100, ConfidenceLevel.USER_VERIFIED, null),
            ReceiptV2LineItem("discount-1", ReceiptLineType.DISCOUNT, "Discount", listOf("line-2"), emptyList(), null, null, null, 30, null, -30, ConfidenceLevel.USER_VERIFIED, null),
            ReceiptV2LineItem("refund-1", ReceiptLineType.REFUND, "Refund", listOf("line-3"), emptyList(), null, null, null, null, null, -20, ConfidenceLevel.USER_VERIFIED, null),
            ReceiptV2LineItem("rounding-1", ReceiptLineType.ROUNDING, "Rounding", listOf("line-4"), emptyList(), null, null, null, null, null, -5, ConfidenceLevel.USER_VERIFIED, null),
        ),
        totals = ReceiptV2Totals(100, 30, 0, 0, 0, -5, 45),
        payments = emptyList(),
    )
    @Test
    fun v3RetryKeepsTheSameIdempotencyKeyAndRevisionSequence() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(503, "temporary"),
            PriceObservationHttpResponse(
                200,
                """[{"ledger_entry_id":"ledger-1","receipt_id":"receipt-1","replayed":true,"item_count":4,"category_id":null,"account_id":null,"category_resolution":"unresolved","account_resolution":"unresolved","account_candidate_ids":[]}]""",
            ),
        )
        val gateway = CashOsReceiptGateway(FakeStore(signedIn()), transport)
        val payload = canonicalPayload()

        val first = gateway.ingestVerifiedReceiptV3(payload)
        val second = gateway.ingestVerifiedReceiptV3(payload)

        assertEquals(PriceObservationFailureKind.SERVER, (first as PriceObservationReadOutcome.Failure).kind)
        assertTrue(second is PriceObservationReadOutcome.Success)
        assertEquals(2, transport.requests.size)
        val firstBody = Json.parseToJsonElement(requireNotNull(transport.requests[0].body)).jsonObject
        val secondBody = Json.parseToJsonElement(requireNotNull(transport.requests[1].body)).jsonObject
        assertEquals(firstBody["p_idempotency_key"], secondBody["p_idempotency_key"])
        assertEquals(firstBody["p_revision_seq"], secondBody["p_revision_seq"])
        assertEquals("ocr-local-session", secondBody["p_document_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun v3StaleRevisionConflictIsReturnedWithoutChangingThePayloadIdentity() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(409, """{"message":"source document identity is stale"}"""),
        )
        val result = CashOsReceiptGateway(FakeStore(signedIn()), transport)
            .ingestVerifiedReceiptV3(canonicalPayload())

        val failure = result as PriceObservationReadOutcome.Failure
        assertEquals(PriceObservationFailureKind.IDEMPOTENCY_MISMATCH, failure.kind)
        val body = Json.parseToJsonElement(requireNotNull(transport.requests.single().body)).jsonObject
        assertEquals("ocr-local-session", body["p_document_id"]?.jsonPrimitive?.content)
        assertEquals(9, body["p_revision_seq"]?.jsonPrimitive?.intOrNull)
        assertEquals("cashos-v3-idempotency-key", body["p_idempotency_key"]?.jsonPrimitive?.content)
    }
    private fun canonicalPayload() = CashOsReceiptIngestV3Payload(
        idempotencyKey = "cashos-v3-idempotency-key",
        documentId = "ocr-local-session",
        receiptRevision = "receipt-revision-9",
        revisionSeq = 9,
        receiptFingerprint = "b".repeat(64),
        merchantName = "Cafe",
        branchName = null,
        purchaseLocalDate = "2026-08-13",
        purchaseLocalTime = "12:34:00",
        grandTotalAmountKrw = 45,
        items = listOf(
            CashOsReceiptIngestV3Item(
                receiptItemId = "product-1",
                descriptionSnapshot = "Coffee",
                netAmountKrw = 100,
                lineType = "product",
            ),
            CashOsReceiptIngestV3Item(
                receiptItemId = "discount-1",
                descriptionSnapshot = "Discount",
                netAmountKrw = -30,
                lineType = "discount",
            ),
            CashOsReceiptIngestV3Item(
                receiptItemId = "refund-1",
                descriptionSnapshot = "Refund",
                netAmountKrw = -20,
                lineType = "refund",
            ),
            CashOsReceiptIngestV3Item(
                receiptItemId = "rounding-1",
                descriptionSnapshot = "Rounding",
                netAmountKrw = -5,
                lineType = "rounding",
            ),
        ),
    )
    private fun payload() = CashOsReceiptSubmitPayload(
        idempotencyKey = "cashos-idempotency-key",
        ledgerEntryId = "ledger-text-1",
        documentId = "ocr-local-1",
        receiptRevision = "revision-1",
        receiptFingerprint = "a".repeat(64),
        merchantName = "Cafe",
        branchName = null,
        purchaseLocalDate = "2026-08-13",
        purchaseLocalTime = null,
        totalAmountKrw = 1590,
        items = listOf(
            CashOsReceiptSubmitItem(
                receiptItemId = "line-1",
                descriptionSnapshot = "Coffee",
                quantity = "1",
                unit = "each",
                unitPriceKrw = 1590,
                totalPriceKrw = 1590,
                lineType = "product",
            ),
        ),
    )

    private fun signedIn() = CashOsSupabaseConfig(
        url = "https://cashos.example.com",
        publishableKey = "cashos-publishable-key",
        userId = "user-1",
        email = "user@example.com",
        accessToken = "access-token",
        refreshToken = "refresh-token",
    )

    private class QueueTransport(
        vararg responses: PriceObservationHttpResponse,
    ) : PriceObservationHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<PriceObservationHttpRequest>()

        override suspend fun execute(request: PriceObservationHttpRequest): PriceObservationHttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class FakeStore(initial: CashOsSupabaseConfig) : CashOsSupabaseStore {
        private var config = initial

        override fun read(): CashOsSupabaseConfig = config

        override fun saveConnection(url: String, publishableKey: String): Result<CashOsSupabaseConfig> =
            runCatching {
                config = config.copy(url = url, publishableKey = publishableKey)
                config
            }

        override fun saveSession(
            userId: String,
            email: String,
            accessToken: String,
            refreshToken: String,
        ): Result<CashOsSupabaseConfig> = runCatching {
            config = config.copy(
                userId = userId,
                email = email,
                accessToken = accessToken,
                refreshToken = refreshToken,
            )
            config
        }

        override fun clearSession(): Boolean {
            config = config.copy(userId = "", email = "", accessToken = "", refreshToken = "")
            return true
        }
    }
}
