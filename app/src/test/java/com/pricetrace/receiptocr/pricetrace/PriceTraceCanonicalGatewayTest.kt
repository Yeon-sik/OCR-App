package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptDocument
import com.pricetrace.receiptscanner.domain.ReceiptFulfillment
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentEvidence
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentType
import com.pricetrace.receiptscanner.domain.ReceiptIdentifier
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptMerchant
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.ReceiptSource
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.ReceiptV2Payment
import com.pricetrace.receiptscanner.domain.ReceiptV2Totals
import com.pricetrace.receiptscanner.domain.RetailChannel
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceTraceCanonicalGatewayTest {
    @Test
    fun verifiedReceiptRpcSanitizesPrivateEvidenceAndKeepsNullSourceId() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(200, """{"receiptId":"receipt-1"}"""),
        )
        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitVerifiedReceipt("pricetrace-idempotency-1", receipt())

        val success = result as PriceTraceCanonicalOutcome.Success
        assertEquals("receipt-1", success.response["receiptId"]?.jsonPrimitive?.content)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/submit_verified_receipt_v2",
            request.url,
        )
        assertEquals("price-trace-publishable-key", request.headers["apikey"])
        assertEquals("Bearer access-token", request.headers["Authorization"])

        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(setOf("p_idempotency_key", "p_receipt"), body.keys)
        assertEquals("pricetrace-idempotency-1", body["p_idempotency_key"]?.jsonPrimitive?.content)

        val sentReceipt = body["p_receipt"]!!.jsonObject
        val document = sentReceipt["document"]!!.jsonObject
        assertEquals(JsonNull, document["id"])
        assertFalse(document.containsKey("localDocumentId"))
        assertEquals("dine_in", document["fulfillment"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("printed", document["fulfillment"]!!.jsonObject["evidence"]?.jsonPrimitive?.content)

        val source = document["source"]!!.jsonObject
        assertEquals("source-document-1", source["original_document_id"]?.jsonPrimitive?.content)
        assertEquals(0, source["source_images"]!!.jsonArray.size)
        assertEquals(JsonNull, source["raw_text"])
        assertFalse(requireNotNull(request.body).contains("ocr-local-only"))

        val payment = sentReceipt["payments"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, payment["reference"])
        assertEquals("SKU-1", sentReceipt["line_items"]!!.jsonArray.single()
            .jsonObject["identifiers"]!!.jsonArray.single().jsonObject["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun merchantOnlyUsesMerchantCandidateRpc() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(200, """{"candidateId":"candidate-1"}"""),
        )
        val merchant = com.pricetrace.receiptscanner.ingestion.MerchantCandidate(
            name = "Test Mart",
            branchName = "Main",
            businessKind = BusinessKind.RETAIL,
            sourceNamespace = "naver",
            sourceLocationCode = "store-1",
        )

        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitMerchantCandidate("merchant-idempotency-1", merchant)

        val success = result as PriceTraceCanonicalOutcome.Success
        assertEquals("candidate-1", success.response["candidateId"]?.jsonPrimitive?.content)
        val request = transport.requests.single()
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/submit_merchant_identity_candidate_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(setOf("p_idempotency_key", "p_merchant", "p_user_verified"), body.keys)
        assertTrue(body["p_user_verified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("retail", body["p_merchant"]!!.jsonObject["business_kind"]?.jsonPrimitive?.content)
        assertEquals("naver", body["p_merchant"]!!.jsonObject["source_namespace"]?.jsonPrimitive?.content)
    }

    @Test
    fun canonicalProjectionSeparatesReceiptAndObservationResults() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """{"receiptId":"receipt-1","storeId":"store-1","restaurantId":"restaurant-1","restaurantLocationId":"location-1","observationIds":[],"lines":[{"sourceLineId":"line-1","receiptItemId":"item-1","productId":"product-1","storeProductId":"store-product-1","catalogProductId":"catalog-1","restaurantMenuId":"menu-1","observationId":null,"restaurantObservationId":null,"resolutionStatus":"unresolved_catalog"}]}""",
            ),
            PriceObservationHttpResponse(
                200,
                """{"receiptId":"receipt-1","observationIds":["observation-1"],"lines":[{"sourceLineId":"line-1","observationId":"observation-1","restaurantObservationId":null,"resolutionStatus":"resolved"}]}""",
            ),
            PriceObservationHttpResponse(
                200,
                """{"receiptId":"receipt-1","observationIds":[],"lines":[{"sourceLineId":"line-1","observationId":null,"restaurantObservationId":null,"resolutionStatus":"unresolved_catalog"}]}""",
            ),
        )
        val submitter = PriceTraceCanonicalProjectionSubmitter(
            PriceTraceCanonicalGateway(FakeStore(signedIn()), transport),
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.MERCHANT,
            source = IngestionSource("ocr_app", emptyList()),
            receipt = receipt(),
        )
        fun request(projection: IngestionProjection) = ProjectionRequest(
            ingestionId = "ingestion-1",
            projection = projection,
            canonicalPayload = YeonsikOcrEnvelopeJson.encode(envelope),
            resolvedIdentity = null,
            idempotencyKey = "projection-key-" + projection.wireValue,
            envelope = envelope,
            localDocumentId = "ocr-local-only",
            revisionSeq = 1,
            canonicalFingerprint = "a".repeat(64),
        )

        val receiptResult = submitter.submit(request(IngestionProjection.PRICETRACE_RECEIPT))
            as ProjectionSubmission.Success
        assertTrue(receiptResult.primaryUploaded)
        assertTrue(receiptResult.alsoUploaded.isEmpty())
        assertEquals(
            PriceTraceIdentity(
                receiptId = "receipt-1",
                storeId = "store-1",
                restaurantId = "restaurant-1",
                restaurantLocationId = "location-1",
                lines = listOf(
                    PriceTraceLineIdentity(
                        sourceLineId = "line-1",
                        receiptItemId = "item-1",
                        productId = "product-1",
                        storeProductId = "store-product-1",
                        catalogProductId = "catalog-1",
                        restaurantMenuId = "menu-1",
                    ),
                ),
            ),
            PriceTraceIdentityJson.tryDecode(receiptResult.metadataJson),
        )

        val completeObservationResult = submitter.submit(request(IngestionProjection.PRICETRACE_PRICE_OBSERVATION))
            as ProjectionSubmission.Success
        assertTrue(completeObservationResult.primaryUploaded)
        assertEquals(
            setOf(IngestionProjection.PRICETRACE_RECEIPT),
            completeObservationResult.alsoUploaded,
        )

        val incompleteObservationResult = submitter.submit(request(IngestionProjection.PRICETRACE_PRICE_OBSERVATION))
            as ProjectionSubmission.Success
        assertFalse(incompleteObservationResult.primaryUploaded)
        assertEquals(
            setOf(IngestionProjection.PRICETRACE_RECEIPT),
            incompleteObservationResult.alsoUploaded,
        )
        assertEquals("price_observation_incomplete", incompleteObservationResult.primaryPendingReason)
    }

    private fun receipt() = ReceiptV2(
        document = ReceiptDocument(
            id = null,
            localDocumentId = "ocr-local-only",
            type = "receipt",
            status = ReceiptStatus.FINAL,
            issuedOn = "2026-08-27",
            issuedAt = "2026-08-27T12:34:00+09:00",
            currency = "KRW",
            fulfillment = ReceiptFulfillment(
                type = ReceiptFulfillmentType.DINE_IN,
                evidence = ReceiptFulfillmentEvidence.PRINTED,
            ),
            source = ReceiptSource(
                captureMethod = "ocr",
                originalDocumentId = "source-document-1",
                sourceImages = listOf("private-image"),
                transcriptionStatus = TranscriptionStatus.USER_VERIFIED,
                notes = listOf("parser_version=test"),
                rawText = "private raw text",
            ),
        ),
        merchant = ReceiptMerchant(
            name = "Test Mart",
            branchName = "Main",
            businessKind = BusinessKind.RETAIL,
            retailChannel = RetailChannel.REGULAR,
            catalogNamespace = null,
            merchantId = null,
            businessRegistrationNumber = null,
            address = null,
            phone = null,
        ),
        lineItems = listOf(
            ReceiptV2LineItem(
                id = "line-1",
                type = ReceiptLineType.PRODUCT,
                description = "Coffee",
                sourceLineReferences = listOf("source-line-1"),
                identifiers = listOf(ReceiptIdentifier("merchant_sku", "SKU-1")),
                quantity = ReceiptQuantity("1"),
                unitPriceAmountMinor = 1590,
                grossAmountMinor = 1590,
                discountAmountMinor = 0,
                taxAmountMinor = 0,
                netAmountMinor = 1590,
                confidence = ConfidenceLevel.USER_VERIFIED,
                taxRatePercent = null,
            ),
        ),
        totals = ReceiptV2Totals(
            itemsGrossAmountMinor = 1590,
            discountAmountMinor = 0,
            taxAmountMinor = 0,
            feeAmountMinor = 0,
            tipAmountMinor = 0,
            roundingAmountMinor = 0,
            grandTotalAmountMinor = 1590,
        ),
        payments = listOf(
            ReceiptV2Payment(
                method = "card",
                amountMinor = 1590,
                status = "paid",
                reference = "private-payment-reference",
            ),
        ),
    )

    private fun signedIn() = PriceTraceSupabaseConfig(
        url = "https://pricetrace.example.com",
        publishableKey = "price-trace-publishable-key",
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

    private class FakeStore(initial: PriceTraceSupabaseConfig) : PriceTraceSupabaseStore {
        private var config = initial

        override fun read(): PriceTraceSupabaseConfig = config

        override fun saveConnection(url: String, publishableKey: String): Result<PriceTraceSupabaseConfig> =
            runCatching {
                config = config.copy(url = url, publishableKey = publishableKey)
                config
            }

        override fun saveSession(
            userId: String,
            email: String,
            accessToken: String,
            refreshToken: String,
        ): Result<PriceTraceSupabaseConfig> = runCatching {
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
