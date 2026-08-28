package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitItem
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitPayload
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
