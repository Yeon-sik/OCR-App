package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Contract
import com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Item
import com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Json
import com.pricetrace.receiptscanner.publisher.CashOsTransactionV4Payload
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Contract
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Json
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Payload
import com.pricetrace.receiptscanner.input.InputOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class YeonsikOcrV4PurchaseTest {
    @Test
    fun `coupang seller and optional line key map to exact PriceTrace and CashOS V4 shapes`() {
        val envelope = purchaseEnvelope(orderRecord(seller = "공식 판매자"))
        val roundTripped = YeonsikOcrV4Json.decode(
            YeonsikOcrV4Json.encode(envelope),
            "v4-coupang",
        )
        val record = roundTripped.purchaseRecords.single()

        assertEquals(IngestionMode.PURCHASE, roundTripped.mode)
        assertEquals("쿠팡", record.platform)
        assertEquals("공식 판매자", record.seller)
        assertEquals(null, record.lineItems.single().lineKey)
        assertEquals(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, CanonicalProjectionPlanner
            .plan(roundTripped).eligible.single { it == IngestionProjection.PRICETRACE_PRICE_OBSERVATION })

        val priceJson = PriceTracePurchaseObservationV4Json.toJson(
            PriceTracePurchaseObservationV4Payload(
                contract = PriceTracePurchaseObservationV4Contract(),
                purchaseRecord = record,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            ),
        )
        assertEquals("retail_purchase", priceJson["kind"]?.jsonPrimitive?.content)
        assertEquals("공식 판매자", priceJson["seller"]?.jsonObject?.get("seller_name")?.jsonPrimitive?.content)
        assertEquals("line-1", priceJson["items"]!!.jsonArray.single().jsonObject["line_key"]?.jsonPrimitive?.content)
        assertEquals("product-client-1", priceJson["items"]!!.jsonArray.single().jsonObject["product"]
            ?.jsonObject?.get("client_key")?.jsonPrimitive?.content)

        val cashJson = Json.parseToJsonElement(
            CashOsTransactionV4Payload(
                contract = CashOsTransactionV4Contract(),
                idempotencyKey = "cashos-key",
                documentId = "v4-coupang",
                transactionRevision = "revision-1",
                revisionSeq = 1,
                transactionFingerprint = "a".repeat(64),
                platform = record.platform,
                seller = record.seller,
                orderedLocalDate = record.orderedOn,
                paidLocalDate = record.paidOn,
                grandTotalAmountKrw = 1100,
                grossAmountKrw = 1100,
                discountAmountKrw = 0,
                feeAmountKrw = null,
                paymentMethodHint = "card",
                accountHint = null,
                institutionHint = null,
                categoryHint = "shopping",
                items = listOf(
                    CashOsTransactionV4Item(
                        transactionItemId = "line-1",
                        lineOrdinal = 1,
                        descriptionSnapshot = "생수",
                        quantity = "2",
                        unitPriceKrw = 550,
                        grossAmountKrw = 1100,
                        netAmountKrw = 1100,
                    ),
                ),
            ).toRpcJson(),
        ).jsonObject
        assertEquals("cashos.transaction-ingest.v4", cashJson["p_contract_version"]?.jsonPrimitive?.content)
        assertEquals("공식 판매자", cashJson["p_seller"]?.jsonPrimitive?.content)
        assertEquals("2026-09-11", cashJson["p_paid_local_date"]?.jsonPrimitive?.content)
        assertEquals("line-1", cashJson["p_items"]!!.jsonArray.single().jsonObject["transaction_item_id"]
            ?.jsonPrimitive?.content)
    }

    @Test
    fun `seller absent is preserved and payment-only routes to CashOS only`() {
        val paymentOnly = orderRecord(
            seller = null,
            lineItems = emptyList(),
            orderedOn = null,
            paidOn = "2026-09-11",
            evidence = listOf(
                PurchaseRecordEvidence(
                    sourceType = "payment_history",
                    sourceAttachmentIds = listOf("payment-history-1"),
                    field = "total_price",
                    observedValue = "1100",
                ),
            ),
        )
        val envelope = purchaseEnvelope(
            record = paymentOnly,
            sourceFiles = listOf(SourceAttachment("payment-history-1", SourceAttachmentType.PAYMENT_HISTORY)),
        )
        val plan = CanonicalProjectionPlanner.plan(envelope)

        assertEquals(PurchaseRecordKind.PAYMENT_ONLY, paymentOnly.kind)
        assertTrue(IngestionProjection.CASHOS_TRANSACTION in plan.eligible)
        assertFalse(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in plan.eligible)
        assertFalse(IngestionProjection.FITNESS_NUTRITION in plan.eligible)
        assertFalse(IngestionProjection.FITNESS_MEAL in plan.eligible)

        val priceJson = PriceTracePurchaseObservationV4Json.toJsonOrNull(paymentOnly)
        assertEquals(null, priceJson)
        val encoded = YeonsikOcrV4Json.encode(envelope)
        val decoded = YeonsikOcrV4Json.decode(encoded, "v4-payment-only")
        assertEquals(null, decoded.purchaseRecords.single().seller)
        assertEquals(null, decoded.purchaseRecords.single().orderedOn)
        assertEquals("2026-09-11", decoded.purchaseRecords.single().paidOn)
    }

    @Test
    fun `user text is valid purchase evidence without a local attachment and confirm verifies record`() = runBlocking {
        val record = orderRecord(
            seller = null,
            evidence = listOf(
                PurchaseRecordEvidence(
                    sourceType = "user_statement",
                    field = "platform",
                    observedValue = "쿠팡",
                ),
            ),
        )
        val envelope = purchaseEnvelope(
            record = record,
            sourceFiles = emptyList(),
            userText = "쿠팡 주문내역에서 확인한 구매입니다.",
        )
        val gate = IngestionEvidenceGate.evaluate(
            envelope = envelope,
            evidence = emptyList(),
            inputOrigin = InputOrigin.EXTERNAL_JSON,
        )
        assertTrue(gate.isAllowed)

        val store = InMemoryIngestionSessionStore()
        val useCase = CanonicalIngestionUseCase(store = store)
        val imported = useCase.importJson(
            value = YeonsikOcrV4Json.encode(envelope),
            localDocumentId = "v4-user-text",
            ingestionId = "v4-user-text-ingestion",
        ) as CanonicalImportResult.Success
        val confirmation = useCase.confirm(
            ingestionId = imported.session.ingestionId,
            envelope = imported.envelope,
            evidence = emptyList(),
            verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        )

        assertTrue(confirmation.result is IngestionStartResult.Success)
        val verified = store.get(imported.session.ingestionId)!!
        assertTrue(verified.verifiedArtifactFingerprints.containsKey(IngestionArtifactKeys.purchaseRecord("purchase-1")))
        assertEquals(IngestionReviewStatus.READY, verified.reviewStatus)
    }

    @Test
    fun `image and text conflict becomes review conflict and cannot be verified automatically`() {
        val conflicting = orderRecord(
            seller = "공식 판매자",
            evidence = listOf(
                PurchaseRecordEvidence(
                    sourceType = "order_history",
                    sourceAttachmentIds = listOf("order-history-1"),
                    field = "platform",
                    observedValue = "쿠팡",
                ),
                PurchaseRecordEvidence(
                    sourceType = "payment_history",
                    sourceAttachmentIds = listOf("payment-history-1"),
                    field = "platform",
                    observedValue = "네이버",
                ),
            ),
        )
        val envelope = purchaseEnvelope(
            record = conflicting,
            sourceFiles = listOf(
                SourceAttachment("order-history-1", SourceAttachmentType.ORDER_HISTORY),
                SourceAttachment("payment-history-1", SourceAttachmentType.PAYMENT_HISTORY),
            ),
        )
        val decoded = YeonsikOcrV4Json.decode(YeonsikOcrV4Json.encode(envelope), "v4-conflict")
        assertEquals(IngestionReviewStatus.CONFLICT, decoded.review.status)
        assertTrue(decoded.review.blockingIssues.any { it == "purchase_evidence_conflict:purchase-1:platform" })

        val result = IngestionEvidenceGate.evaluate(
            envelope = decoded,
            evidence = listOf(
                LocalEvidence("order-history-1", SourceAttachmentType.ORDER_HISTORY, fileReadable = true),
                LocalEvidence("payment-history-1", SourceAttachmentType.PAYMENT_HISTORY, fileReadable = true),
            ),
        )
        assertFalse(result.isAllowed)
        assertTrue(result.blockingIssues.any { it == "purchase_evidence_conflict:purchase-1:platform" })
    }

    @Test
    fun `restaurant purchase uses restaurant PriceTrace kind without inventing timestamps`() {
        val record = orderRecord(
            seller = "테스트 식당",
            sellerBusinessKind = "food_service",
            sellerSourceNamespace = "pricetrace",
            sellerSourceCode = "menu-1",
            lineItems = listOf(
                PurchaseRecordLine(
                    description = "비빔밥",
                    sellerOverride = "테스트 식당",
                    quantity = 1.0,
                    unitPriceAmountKrw = 1100,
                    grossAmountKrw = 1100,
                    netAmountKrw = 1100,
                ),
            ),
            orderedAt = null,
            paidAt = null,
        )
        val payload = PriceTracePurchaseObservationV4Json.toJson(
            PriceTracePurchaseObservationV4Payload(
                contract = PriceTracePurchaseObservationV4Contract(),
                purchaseRecord = record,
                verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
            ),
        )
        assertEquals("restaurant_purchase", payload["kind"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive("2026-09-11"), payload["order"]!!.jsonObject["ordered_on"])
        assertTrue(payload["order"]!!.jsonObject["ordered_at"] is kotlinx.serialization.json.JsonNull)
        assertTrue(payload["payment"]!!.jsonObject["paid_at"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `v4 rejects legacy price observation mixing and v3 decoder remains separate`() {
        val envelope = purchaseEnvelope(orderRecord())
        val root = Json.parseToJsonElement(YeonsikOcrV4Json.encode(envelope)).jsonObject
        val mixed = JsonObject(root.toMutableMap().apply {
            put("price_observations", JsonArray(listOf(JsonObject(emptyMap()))))
        })
        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV4Json.decode(Json.encodeToString(JsonObject.serializer(), mixed), "v4-mixed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV3Json.decode(YeonsikOcrV4Json.encode(envelope), "v3-must-not-read-v4")
        }
    }

    private fun purchaseEnvelope(
        record: PurchaseRecord,
        sourceFiles: List<SourceAttachment> = listOf(
            SourceAttachment("order-history-1", SourceAttachmentType.ORDER_HISTORY),
            SourceAttachment("payment-history-1", SourceAttachmentType.PAYMENT_HISTORY),
        ),
        userText: String? = null,
    ): YeonsikOcrEnvelope = YeonsikOcrEnvelope(
        mode = IngestionMode.PURCHASE,
        source = IngestionSource("chatgpt", sourceFiles, userText),
        classificationHints = mapOf(
            "cashos.category_hint" to "shopping",
            "cashos.payment_method_hint" to "card",
        ),
        targets = setOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            IngestionProjection.CASHOS_TRANSACTION,
        ),
        schemaVersion = YEONSIK_OCR_V4_SCHEMA,
        purchaseRecords = listOf(record),
    )

    private fun orderRecord(
        seller: String? = "공식 판매자",
        sellerBusinessKind: String? = "retail",
        sellerSourceNamespace: String? = null,
        sellerSourceCode: String? = null,
        lineItems: List<PurchaseRecordLine> = listOf(
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
        orderedOn: String? = "2026-09-11",
        orderedAt: String? = "2026-09-11T09:00:00+09:00",
        paidOn: String? = "2026-09-11",
        paidAt: String? = "2026-09-11T09:01:00+09:00",
        evidence: List<PurchaseRecordEvidence> = listOf(
            PurchaseRecordEvidence(
                sourceType = "order_history",
                sourceAttachmentIds = listOf("order-history-1"),
                field = "platform",
                observedValue = "쿠팡",
            ),
            PurchaseRecordEvidence(
                sourceType = "payment_history",
                sourceAttachmentIds = listOf("payment-history-1"),
                field = "total_price",
                observedValue = "1100",
            ),
        ),
    ): PurchaseRecord = PurchaseRecord(
        clientKey = "purchase-1",
        platform = "쿠팡",
        platformCode = "coupang",
        seller = seller,
        sellerBusinessKind = seller?.let { sellerBusinessKind },
        sellerSourceNamespace = seller?.let { sellerSourceNamespace },
        sellerSourceCode = seller?.let { sellerSourceCode },
        orderedOn = orderedOn,
        orderedAt = orderedAt,
        paidOn = paidOn,
        paidAt = paidAt,
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
        lineItems = lineItems,
        evidence = evidence,
        confidence = 0.98,
    )
}

private fun PriceTracePurchaseObservationV4Json.toJsonOrNull(
    record: PurchaseRecord,
): JsonObject? = if (record.priceObservationEligible) {
    toJson(
        PriceTracePurchaseObservationV4Payload(
            contract = PriceTracePurchaseObservationV4Contract(),
            purchaseRecord = record,
            verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        ),
    )
} else {
    null
}
