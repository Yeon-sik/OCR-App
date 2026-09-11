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
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Contract
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Json
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Payload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.intOrNull
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
    fun standalonePriceObservationUsesDedicatedRpcAndKeepsCashOsOutOfThePayload() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(200, """{"observationId":"observation-1","replayed":false}"""),
        )
        val candidate = ProductCandidate(
            clientKey = "product-1",
            productName = "Test Drink",
            brand = "Test Brand",
            subBrand = "Test Sub Brand",
            manufacturer = "Test Foods",
            variant = "Zero",
            sourceAttachmentIds = listOf("product-photo-1"),
            evidence = listOf(
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("product-photo-1"),
                    sourceType = "product_photo",
                    sourceRef = "product-photo-1",
                    field = "product_name",
                    observedValue = "Test Drink",
                ),
            ),
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.PACKAGED_PRODUCT,
            source = IngestionSource(
                producer = "chatgpt",
                sourceFiles = listOf(SourceAttachment("product-photo-1", SourceAttachmentType.PRODUCT_PHOTO)),
            ),
            merchantCandidate = MerchantCandidate(
                name = "Test Mart",
                branchName = "Main",
                businessKind = BusinessKind.RETAIL,
                sourceNamespace = "demo",
                sourceLocationCode = "store-1",
            ),
            productCandidates = listOf(candidate),
            priceObservations = listOf(
                StandalonePriceObservation(
                    clientKey = "price-1",
                    kind = StandalonePriceObservationKind.RETAIL_PURCHASE,
                    productClientKey = "product-1",
                    observedOn = "2026-09-07",
                    quantity = StandalonePriceObservationQuantity(2.0, "each"),
                    unitPriceAmountMinor = 500,
                    grossAmountMinor = 1_000,
                    discountAmountMinor = 0,
                    netAmountMinor = 1_000,
                    sourceAttachmentIds = listOf("product-photo-1"),
                    evidence = listOf(
                        StandalonePriceObservationEvidence(
                            sourceType = "product_photo",
                            sourceAttachmentIds = listOf("product-photo-1"),
                            field = "net_amount_minor",
                            observedValue = "1000",
                        ),
                    ),
                    confidence = 0.9,
                ),
            ),
            review = IngestionReview(
                status = IngestionReviewStatus.READY,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            ),
            schemaVersion = YEONSIK_OCR_V3_SCHEMA,
        )

        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitStandalonePriceObservations("standalone-price-key", envelope)
        val success = result as PriceTraceCanonicalOutcome.Success
        assertEquals("observation-1", success.response["observations"]!!.jsonArray.single()
            .jsonObject["observationId"]?.jsonPrimitive?.content)

        val request = transport.requests.single()
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/ingest_verified_standalone_price_observation_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(setOf("p_idempotency_key", "p_observation"), body.keys)
        val sentIdempotencyKey = body["p_idempotency_key"]?.jsonPrimitive?.content
        assertTrue(!sentIdempotencyKey.isNullOrBlank())
        assertTrue(sentIdempotencyKey != "standalone-price-key")
        val observation = body["p_observation"]!!.jsonObject
        assertEquals("receipt-independent-price-observation.v3", observation["schema_version"]?.jsonPrimitive?.content)
        assertEquals("price-observation.v3", observation["contract_version"]?.jsonPrimitive?.content)
        assertEquals("retail_purchase", observation["kind"]?.jsonPrimitive?.content)
        assertEquals("manual_canonical_review", observation["verification_basis"]?.jsonPrimitive?.content)
        assertEquals("user_verified", observation["transcription_status"]?.jsonPrimitive?.content)
        assertEquals("KRW", observation["currency"]?.jsonPrimitive?.content)
        assertEquals("2", observation["quantity"]?.jsonPrimitive?.content)
        assertEquals("500", observation["unit_price"]?.jsonPrimitive?.content)
        assertEquals("1000", observation["gross_price"]?.jsonPrimitive?.content)
        assertEquals("0", observation["discount"]?.jsonPrimitive?.content)
        assertEquals("1000", observation["net_price"]?.jsonPrimitive?.content)
        assertFalse(observation.containsKey("unit_price_amount_minor"))
        assertFalse(observation.containsKey("gross_amount_minor"))
        assertFalse(observation.containsKey("source_attachment_ids"))
        assertFalse(observation.containsKey("evidence"))
        assertEquals("Test Mart", observation["merchant"]!!.jsonObject["merchant_name"]?.jsonPrimitive?.content)
        assertEquals("store-1", observation["merchant"]!!.jsonObject["source_code"]?.jsonPrimitive?.content)
        val product = observation["product"]!!.jsonObject
        assertEquals("product-1", product["product_client_key"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, product["merchant_sku"])
        assertEquals("Test Sub Brand", product["sub_brand"]?.jsonPrimitive?.content)
        assertEquals("Test Foods", product["manufacturer"]?.jsonPrimitive?.content)
        assertFalse(observation.containsKey("cashos"))
        assertFalse(observation.containsKey("user_verified"))
    }

    @Test
    fun purchasePriceObservationUsesTheConfirmedV4RpcAndDoesNotSendRawEvidence() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"purchaseSourceId":"purchase-source-1","observationIds":["observation-1"],"replayed":false,"deduplicated":false}]""",
            ),
        )
        val record = PurchaseRecord(
            clientKey = "purchase-1",
            platform = "쿠팡",
            platformCode = "coupang",
            seller = "공식 판매자",
            sellerBusinessKind = "retail",
            purchaseKind = PurchaseKind.RETAIL,
            orderedOn = "2026-09-11",
            paidOn = "2026-09-11",
            status = PurchaseRecordStatus.PAID,
            totals = PurchaseRecordTotals(
                subtotalAmountKrw = 1100,
                discountAmountKrw = 0,
                shippingAmountKrw = 0,
                taxAmountKrw = 0,
                grandTotalAmountKrw = 1100,
                paidAmountKrw = 1100,
            ),
            payment = PurchaseRecordPayment(method = "card", status = "paid"),
            lineItems = listOf(
                PurchaseRecordLine(
                    productClientKey = "product-client-1",
                    description = "생수",
                    quantity = 2.0,
                    unitPriceAmountKrw = 550,
                    grossAmountKrw = 1100,
                    discountAmountKrw = 0,
                    netAmountKrw = 1100,
                ),
            ),
            evidence = listOf(
                PurchaseRecordEvidence(
                    sourceType = "order_history",
                    sourceAttachmentIds = listOf("private-order-image"),
                    field = "seller",
                    observedValue = "공식 판매자",
                ),
            ),
            confidence = 0.98,
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.PURCHASE,
            source = IngestionSource(
                producer = "chatgpt",
                sourceFiles = listOf(SourceAttachment("private-order-image", SourceAttachmentType.ORDER_HISTORY)),
                userText = "private statement",
            ),
            targets = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
            review = IngestionReview(
                status = IngestionReviewStatus.READY,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            ),
            schemaVersion = YEONSIK_OCR_V4_SCHEMA,
            purchaseRecords = listOf(record),
        )

        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitPurchasePriceObservationsV4("purchase-request-key", envelope)
        val success = result as PriceTraceCanonicalOutcome.Success
        assertEquals("purchase-source-1", success.response["sources"]!!.jsonArray.single()
            .jsonObject["purchaseSourceId"]?.jsonPrimitive?.content)
        assertEquals(true, success.response["sourceSaved"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, success.response["observationCreated"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(1, success.response["observationCount"]?.jsonPrimitive?.intOrNull)

        val request = transport.requests.single()
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/ingest_verified_purchase_price_observation_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(setOf("p_idempotency_key", "p_purchase"), body.keys)
        assertTrue(body["p_idempotency_key"]!!.jsonPrimitive.content != "purchase-request-key")
        val purchase = body["p_purchase"]!!.jsonObject
        assertEquals("purchase-price-observation.v4", purchase["schema_version"]?.jsonPrimitive?.content)
        assertEquals("purchase-price.v4", purchase["contract_version"]?.jsonPrimitive?.content)
        assertEquals("retail_purchase", purchase["kind"]?.jsonPrimitive?.content)
        assertEquals("공식 판매자", purchase["seller"]!!.jsonObject["seller_name"]?.jsonPrimitive?.content)
        assertEquals("line-1", purchase["items"]!!.jsonArray.single().jsonObject["line_key"]?.jsonPrimitive?.content)
        assertFalse(purchase.containsKey("evidence"))
        assertFalse(purchase.toString().contains("private-order-image"))
        assertFalse(purchase.toString().contains("private statement"))
    }

    @Test
    fun purchaseSourceCanBeSavedWithoutClaimingPriceObservationWhenSellerIsUnknown() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"purchaseSourceId":"purchase-source-only","observationIds":[],"observationCreated":false,"replayed":false,"deduplicated":false}]""",
            ),
        )
        val record = PurchaseRecord(
            clientKey = "purchase-source-only",
            platform = "쿠팡",
            purchaseKind = PurchaseKind.RETAIL,
            orderedOn = "2026-09-11",
            paidOn = "2026-09-11",
            status = PurchaseRecordStatus.PAID,
            totals = PurchaseRecordTotals(
                subtotalAmountKrw = 1100,
                discountAmountKrw = 0,
                shippingAmountKrw = 0,
                taxAmountKrw = 0,
                grandTotalAmountKrw = 1100,
                paidAmountKrw = 1100,
            ),
            payment = PurchaseRecordPayment(method = "card", status = "paid"),
            lineItems = listOf(
                PurchaseRecordLine(
                    productClientKey = null,
                    description = "판매자 미상 상품",
                    quantity = 1.0,
                    unitPriceAmountKrw = 1100,
                    grossAmountKrw = 1100,
                    netAmountKrw = 1100,
                ),
            ),
            evidence = listOf(
                PurchaseRecordEvidence(
                    sourceType = "order_history",
                    sourceAttachmentIds = listOf("order-history-source-only"),
                    field = "platform",
                    observedValue = "쿠팡",
                ),
            ),
            confidence = 0.9,
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.PURCHASE,
            source = IngestionSource(
                producer = "chatgpt",
                sourceFiles = listOf(
                    SourceAttachment("order-history-source-only", SourceAttachmentType.ORDER_HISTORY),
                ),
            ),
            targets = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
            review = IngestionReview(
                status = IngestionReviewStatus.READY,
                verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
            ),
            schemaVersion = YEONSIK_OCR_V4_SCHEMA,
            purchaseRecords = listOf(record),
        )
        val request = ProjectionRequest(
            ingestionId = "source-only-ingestion",
            projection = IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            canonicalPayload = YeonsikOcrV4Json.encode(envelope),
            idempotencyKey = "source-only-key",
            envelope = envelope,
            localDocumentId = "source-only-document",
        )

        val result = PriceTraceCanonicalProjectionSubmitter(
            PriceTraceCanonicalGateway(FakeStore(signedIn()), transport),
        ).submit(request) as ProjectionSubmission.Success

        assertFalse(result.primaryUploaded)
        assertEquals("price_observation_not_created", result.primaryPendingReason)
        val metadata = Json.parseToJsonElement(requireNotNull(result.metadataJson)).jsonObject
        assertEquals(true, metadata["sourceSaved"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, metadata["observationCreated"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(0, metadata["observationCount"]?.jsonPrimitive?.intOrNull)
        val purchase = Json.parseToJsonElement(requireNotNull(transport.requests.single().body))
            .jsonObject["p_purchase"]!!.jsonObject
        assertEquals(JsonNull, purchase["seller"])
        assertEquals("retail", purchase["purchase_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun standaloneRetailObservationPreservesUnknownAmountsDateOnlyAndObservedSku() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(200, """{"observationId":"observation-unknowns","replayed":false}"""),
        )
        val candidate = ProductCandidate(
            clientKey = "product-unknowns",
            productName = "Observed Drink",
            brand = "Observed Brand",
            manufacturer = "Observed Foods",
            merchantSku = "SKU-OBSERVED",
            sourceAttachmentIds = listOf("product-photo-unknowns"),
            evidence = listOf(
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("product-photo-unknowns"),
                    sourceType = "product_photo",
                    sourceRef = "product-photo-unknowns",
                    field = "merchant_sku",
                    observedValue = "SKU-OBSERVED",
                ),
            ),
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.PACKAGED_PRODUCT,
            source = IngestionSource("chatgpt", emptyList()),
            merchantCandidate = MerchantCandidate(
                name = "Observed Mart",
                businessKind = BusinessKind.RETAIL,
            ),
            productCandidates = listOf(candidate),
            priceObservations = listOf(
                StandalonePriceObservation(
                    clientKey = "price-unknowns",
                    kind = StandalonePriceObservationKind.RETAIL_PURCHASE,
                    productClientKey = candidate.clientKey,
                    observedOn = "2026-09-07",
                    quantity = null,
                    unitPriceAmountMinor = 500,
                    grossAmountMinor = null,
                    discountAmountMinor = null,
                    netAmountMinor = null,
                    sourceAttachmentIds = listOf("product-photo-unknowns"),
                    evidence = listOf(
                        StandalonePriceObservationEvidence(
                            sourceType = "product_photo",
                            sourceAttachmentIds = listOf("product-photo-unknowns"),
                            field = "unit_price_amount_minor",
                            observedValue = "500",
                        ),
                    ),
                    confidence = 0.8,
                ),
            ),
            review = IngestionReview(
                status = IngestionReviewStatus.READY,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            ),
            schemaVersion = YEONSIK_OCR_V3_SCHEMA,
        )

        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitStandalonePriceObservations("unknown-price-key", envelope)
        assertTrue(result is PriceTraceCanonicalOutcome.Failure)
        assertEquals(PriceObservationFailureKind.CONTRACT, (result as PriceTraceCanonicalOutcome.Failure).kind)
        assertEquals("price_observation_net_amount_required", result.message)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun standaloneRestaurantObservationUsesItemAndRestaurantLocationContract() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(200, """{"observationId":"restaurant-observation-1","replayed":false}"""),
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.RESTAURANT,
            source = IngestionSource("chatgpt", emptyList()),
            merchantCandidate = MerchantCandidate(
                name = "Test Noodle House",
                branchName = "Main",
                businessKind = BusinessKind.FOOD_SERVICE,
                sourceNamespace = "demo",
                sourceLocationCode = "restaurant-location-1",
            ),
            priceObservations = listOf(
                StandalonePriceObservation(
                    clientKey = "restaurant-price-1",
                    kind = StandalonePriceObservationKind.RESTAURANT_PURCHASE,
                    itemName = "Noodles",
                    observedOn = "2026-09-07",
                    quantity = StandalonePriceObservationQuantity(1.0, "serving"),
                    unitPriceAmountMinor = 11_000,
                    grossAmountMinor = 11_000,
                    discountAmountMinor = 0,
                    netAmountMinor = 11_000,
                    sourceAttachmentIds = listOf("menu-photo-1"),
                    evidence = listOf(
                        StandalonePriceObservationEvidence(
                            sourceType = "menu_photo",
                            sourceAttachmentIds = listOf("menu-photo-1"),
                            field = "net_amount_minor",
                            observedValue = "11000",
                        ),
                    ),
                    confidence = 0.9,
                ),
            ),
            review = IngestionReview(
                status = IngestionReviewStatus.READY,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            ),
            schemaVersion = YEONSIK_OCR_V3_SCHEMA,
        )

        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitStandalonePriceObservations("restaurant-price-key", envelope)
        val success = result as PriceTraceCanonicalOutcome.Success
        assertEquals("restaurant-observation-1", success.response["observations"]!!.jsonArray.single()
            .jsonObject["observationId"]?.jsonPrimitive?.content)

        val request = transport.requests.single()
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        val observation = body["p_observation"]!!.jsonObject
        assertEquals("restaurant_purchase", observation["kind"]?.jsonPrimitive?.content)
        assertEquals("1", observation["quantity"]?.jsonPrimitive?.content)
        assertEquals("restaurant-location-1", observation["merchant"]!!.jsonObject["source_location_code"]?.jsonPrimitive?.content)
        assertFalse(observation["merchant"]!!.jsonObject.containsKey("source_code"))
        assertEquals("Noodles", observation["item"]!!.jsonObject["item_name"]?.jsonPrimitive?.content)
        assertFalse(observation.containsKey("product"))
        assertFalse(observation.containsKey("cashos"))
    }

    @Test
    fun productCandidateSendsObservableFactsAndAcceptsServerIdentityOnly() = runTest {
        val candidate = ProductCandidate(
            clientKey = "product-1",
            productName = "Test cereal",
            brand = "Test brand",
            manufacturer = "Test Foods",
            specification = "Original",
            contentAmount = 500.0,
            contentUnit = "g",
            packageCount = 1,
            variant = "Original",
            barcodes = listOf(ProductCandidateBarcode(type = "ean13", value = "8801234567890")),
            sourceVersion = "chatgpt-vision-v2",
            evidence = listOf(
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("product-photo-1"),
                    source = "product photo",
                    sourceType = "product_photo",
                    sourceRef = "product-photo-1",
                    field = "product_name",
                    observedValue = "Test cereal",
                ),
            ),
        )
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """{"schemaVersion":"product-candidate.v1","contract":"PRICETRACE_PRODUCT_CANDIDATE","outcome":"catalog_product_reused","catalogProductId":"$CATALOG_PRODUCT_ID","candidateId":"candidate-1","verificationStatus":"verified","productRevision":"sha256:${"c".repeat(64)}"}""",
            ),
        )
        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitProductCandidates("product-candidate-key", listOf(candidate))

        val success = result as PriceTraceCanonicalOutcome.Success
        val products = success.response["products"]!!.jsonArray.single().jsonObject
        assertEquals("product-1", products["clientKey"]?.jsonPrimitive?.content)
        assertEquals(CATALOG_PRODUCT_ID, products["catalogProductId"]?.jsonPrimitive?.content)
        val request = transport.requests.single()
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/submit_product_candidate_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        val sent = body["p_candidate"]!!.jsonObject
        assertEquals(setOf("p_idempotency_key", "p_candidate"), body.keys)
        assertEquals("PRICETRACE_PRODUCT_CANDIDATE", sent["schema_version"]?.jsonPrimitive?.content)
        assertEquals("product-candidate.v1", sent["contract_version"]?.jsonPrimitive?.content)
        assertEquals("product-1", sent["client_key"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, sent["sub_brand"])
        val identifier = sent["identifiers"]!!.jsonArray.single().jsonObject
        assertEquals("ean", identifier["scheme"]?.jsonPrimitive?.content)
        assertEquals("8801234567890", identifier["value"]?.jsonPrimitive?.content)
        assertFalse(sent.containsKey("catalog_product_id"))
        assertFalse(sent.containsKey("standard_product_id"))
        assertFalse(sent.containsKey("restaurant_menu_id"))
        assertFalse(sent.containsKey("user_verified"))
        assertFalse(requireNotNull(request.body).contains("access_token"))
    }

    @Test
    fun v4OrderHistoryProductCandidateUsesPriceTraceEvidenceVocabularyAndIdentifiers() = runTest {
        val candidate = ProductCandidate(
            clientKey = "order-history-product-1",
            productName = "Coupang cereal",
            brand = "Brand fact",
            subBrand = "Sub-brand fact",
            manufacturer = "Manufacturer fact",
            specification = "500 g",
            variant = "Original",
            barcodes = listOf(ProductCandidateBarcode(type = "ean13", value = "8801234567890")),
            evidence = listOf(
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "product_name",
                    observedValue = "Coupang cereal",
                ),
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "brand_name",
                    observedValue = "Brand fact",
                ),
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "manufacturer_name",
                    observedValue = "Manufacturer fact",
                ),
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "variant_name",
                    observedValue = "Original",
                ),
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "specification_text",
                    observedValue = "500 g",
                ),
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "barcodes",
                    observedValue = "ean13:8801234567890",
                ),
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "sub_brand_name",
                    observedValue = "Sub-brand fact",
                ),
            ),
        )
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """{"schemaVersion":"product-candidate.v1","contract":"PRICETRACE_PRODUCT_CANDIDATE","outcome":"pending_review","catalogProductId":null}""",
            ),
        )

        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitProductCandidates("order-history-product-key", listOf(candidate))
        assertTrue(result is PriceTraceCanonicalOutcome.Success)

        val body = Json.parseToJsonElement(requireNotNull(transport.requests.single().body)).jsonObject
        val sent = body["p_candidate"]!!.jsonObject
        assertEquals("Sub-brand fact", sent["sub_brand"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("product_name", "brand", "manufacturer", "variant", "specification", "sub_brand"),
            sent["evidence"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content },
        )
        assertFalse(sent.toString().contains("brand_name"))
        assertFalse(sent.toString().contains("manufacturer_name"))
        assertFalse(sent.toString().contains("variant_name"))
        assertFalse(sent.toString().contains("specification_text"))
        assertFalse(sent.toString().contains("barcodes"))
        assertFalse(sent.toString().contains("sub_brand_name"))
        val identifier = sent["identifiers"]!!.jsonArray.single().jsonObject
        assertEquals("ean", identifier["scheme"]?.jsonPrimitive?.content)
        assertEquals("8801234567890", identifier["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun textOnlyProductCandidateAndStandalonePricePreserveUserStatementEvidence() = runTest {
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """{"schemaVersion":"product-candidate.v1","contract":"PRICETRACE_PRODUCT_CANDIDATE","outcome":"pending_review","catalogProductId":null}""",
            ),
            PriceObservationHttpResponse(
                200,
                """{"observationId":"text-only-observation-1","replayed":false}""",
            ),
        )
        val envelope = textOnlyEnvelope()

        val productResult = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitProductCandidates("text-only-product-key", envelope.productCandidates)
        assertTrue(productResult is PriceTraceCanonicalOutcome.Success)

        val candidateRequest = transport.requests[0]
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/submit_product_candidate_v1",
            candidateRequest.url,
        )
        val candidateBody = Json.parseToJsonElement(requireNotNull(candidateRequest.body)).jsonObject
        val sentCandidate = candidateBody["p_candidate"]!!.jsonObject
        assertFalse(sentCandidate.containsKey("merchant_sku"))
        val sentEvidence = sentCandidate["evidence"]!!.jsonArray
        assertEquals(2, sentEvidence.size)
        assertTrue(sentEvidence.all {
            it.jsonObject["source_type"]?.jsonPrimitive?.content == "user_statement"
        })
        assertTrue(sentEvidence.all {
            it.jsonObject["source_ref"]?.jsonPrimitive?.content == "user-statement:purchase-20260910-1"
        })
        assertTrue(sentEvidence.all { it.jsonObject["content_hash"] == JsonNull })
        assertFalse(requireNotNull(candidateRequest.body).contains("product-photo"))

        val priceResult = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .submitStandalonePriceObservations("text-only-price-key", envelope)
        assertTrue(priceResult is PriceTraceCanonicalOutcome.Success)

        val priceBody = Json.parseToJsonElement(requireNotNull(transport.requests[1].body)).jsonObject
        val observation = priceBody["p_observation"]!!.jsonObject
        assertEquals("manual_canonical_review", observation["verification_basis"]?.jsonPrimitive?.content)
        assertEquals("KRW", observation["currency"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, observation["gross_price"])
        assertEquals(JsonNull, observation["discount"])
        assertEquals(JsonNull, observation["unit_price"])
        assertEquals("2900", observation["net_price"]?.jsonPrimitive?.content)
        assertFalse(observation.containsKey("source_attachment_ids"))
        assertFalse(observation.containsKey("evidence"))
        val product = observation["product"]!!.jsonObject
        assertEquals("product-brandx-chicken-20260910", product["product_client_key"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, product["merchant_sku"])
        assertEquals("브랜드X 닭가슴살", product["product_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun exactProductReadUsesCatalogScopedRpcAndRefreshesExpiredSession() = runTest {
        val revision = "sha256:" + "d".repeat(64)
        val transport = QueueTransport(
            PriceObservationHttpResponse(401, "JWT expired"),
            PriceObservationHttpResponse(
                200,
                """{"access_token":"access-2","refresh_token":"refresh-2","user":{"id":"user-1","email":"user@example.com"}}""",
            ),
            PriceObservationHttpResponse(
                200,
                productReadJson().replace("\"revision\":\"revision-1\"", "\"revision\":\"$revision\"") ,
            ),
        )

        val result = PriceTraceCanonicalGateway(FakeStore(signedIn()), transport)
            .readExactProductRevision(CATALOG_PRODUCT_ID)

        assertEquals(PriceTraceProductReadOutcome.Success(revision), result)
        assertEquals(3, transport.requests.size)
        val readBody = Json.parseToJsonElement(requireNotNull(transport.requests[2].body)).jsonObject
        assertEquals(CATALOG_PRODUCT_ID, readBody["p_catalog_product_id"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, readBody["p_query"])
        assertEquals("Bearer access-2", transport.requests[2].headers["Authorization"])
        assertTrue(transport.requests[1].url.contains("grant_type=refresh_token"))
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

    private fun textOnlyEnvelope(): YeonsikOcrEnvelope {
        val file = sequenceOf(
            java.io.File("examples", "yeonsik-ocr.v3.text-only-retail.example.json"),
            java.io.File("../examples", "yeonsik-ocr.v3.text-only-retail.example.json"),
        ).firstOrNull(java.io.File::isFile) ?: error("text-only retail example not found")
        return YeonsikOcrV3Json.decode(file.readText(), "gateway-text-only").copy(
            review = IngestionReview(
                status = IngestionReviewStatus.READY,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            ),
        )
    }

    private fun productReadJson() =
        """{"schemaVersion":"product-read.v1","namespace":"pricetrace","revision":"revision-1","products":[{"standardProduct":{"id":"$STANDARD_PRODUCT_ID","name":"Coffee","brand":null,"updatedAt":"2026-08-01T00:00:00Z"},"catalogProduct":{"id":"$CATALOG_PRODUCT_ID","name":"Coffee 500g","specificationText":"500g","contentAmount":500,"contentUnit":"g","packageCount":1,"referenceUnit":"g","listingReferenceUrl":null,"updatedAt":"2026-08-01T00:00:00Z"},"sellerProducts":[],"observations":[] }]}"""

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

    private companion object {
        const val CATALOG_PRODUCT_ID = "22222222-2222-4222-8222-222222222222"
        const val STANDARD_PRODUCT_ID = "44444444-4444-4444-8444-444444444444"
    }
}
