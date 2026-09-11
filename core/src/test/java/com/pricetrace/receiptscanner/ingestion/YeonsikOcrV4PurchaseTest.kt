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
                sourceType = "order_history",
                transactionStatus = "confirmed",
                paymentStatus = "paid",
                orderReference = record.orderReference,
                paymentReference = null,
                orderedAt = record.orderedAt,
                paidAt = record.paidAt,
                timestampProvenance = "source_timestamp_offset",
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
    fun `order history product candidate is verified before its PriceTrace purchase observation`() = runBlocking {
        val candidate = ProductCandidate(
            clientKey = "coupang-product-new",
            productName = "새 생수",
            sourceAttachmentIds = listOf("order-history-1"),
            evidence = listOf(
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "product_name",
                    observedValue = "새 생수",
                ),
            ),
            confidence = 0.97,
        )
        val envelope = purchaseEnvelope(
            record = orderRecord(
                lineItems = listOf(
                    PurchaseRecordLine(
                        productClientKey = candidate.clientKey,
                        description = "새 생수",
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
                        sourceAttachmentIds = listOf("order-history-1"),
                        field = "platform",
                        observedValue = "쿠팡",
                    ),
                    PurchaseRecordEvidence(
                        sourceType = "order_history",
                        sourceAttachmentIds = listOf("order-history-1"),
                        field = "total_price",
                        observedValue = "1100",
                    ),
                ),
            ),
            productCandidates = listOf(candidate),
            sourceFiles = listOf(
                SourceAttachment("order-history-1", SourceAttachmentType.ORDER_HISTORY),
            ),
        )
        val localEvidence = listOf(
            LocalEvidence("order-history-1", SourceAttachmentType.ORDER_HISTORY, fileReadable = true),
        )
        val decoded = YeonsikOcrV4Json.decode(
            YeonsikOcrV4Json.encode(envelope),
            "v4-order-history-candidate",
        )
        assertEquals(candidate.clientKey, decoded.productCandidates.single().clientKey)
        assertEquals("order_history", decoded.productCandidates.single().evidence.single().sourceType)
        assertTrue(IngestionEvidenceGate.evaluate(decoded, localEvidence).isAllowed)

        val plan = CanonicalProjectionPlanner.plan(decoded)
        assertTrue(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE in plan.eligible)
        assertTrue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in plan.eligible)
        assertTrue(IngestionProjection.CASHOS_TRANSACTION in plan.eligible)
        assertTrue(
            IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE in
                plan.dependencies.getValue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        )

        val calls = mutableListOf<ProjectionRequest>()
        val submitter = object : IngestionProjectionSubmitter {
            override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                calls += request
                return ProjectionSubmission.Success(
                    remoteId = request.projection.wireValue,
                    metadataJson = """{"accepted":true}""",
                )
            }
        }
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to submitter,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to submitter,
                IngestionProjection.CASHOS_TRANSACTION to submitter,
            ),
            now = { "2026-09-11T12:00:00Z" },
        )
        assertTrue(
            orchestrator.start(
                ingestionId = "v4-order-history-ingestion",
                localDocumentId = "v4-order-history-document",
                envelope = decoded,
                evidence = localEvidence,
            ) is IngestionStartResult.Success,
        )
        assertTrue(
            orchestrator.markPurchaseRecordsVerified(
                ingestionId = "v4-order-history-ingestion",
                envelope = decoded,
                evidence = localEvidence,
            ) is IngestionStartResult.Success,
        )
        assertTrue(
            orchestrator.markProductCandidatesVerified(
                ingestionId = "v4-order-history-ingestion",
                envelope = decoded,
                evidence = localEvidence,
            ) is IngestionStartResult.Success,
        )
        orchestrator.submitAllReadyProjections("v4-order-history-ingestion", decoded)

        val candidateIndex = calls.indexOfFirst {
            it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE
        }
        val priceIndex = calls.indexOfFirst {
            it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION
        }
        assertTrue(candidateIndex >= 0)
        assertTrue(priceIndex > candidateIndex)
        assertTrue(
            calls[priceIndex].dependencyMetadataJson.containsKey(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
            ),
        )
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
    fun `unsettled purchase states never create normal observation or confirmed CashOS expense`() {
        listOf(
            PurchaseRecordStatus.CANCELLED to "cancelled",
            PurchaseRecordStatus.REFUNDED to "refunded",
            PurchaseRecordStatus.PENDING to "pending",
            PurchaseRecordStatus.UNKNOWN to "unknown",
        ).forEach { (status, paymentStatus) ->
            val record = orderRecord(
                status = status,
                payment = PurchaseRecordPayment(method = "card", status = paymentStatus),
            )
            assertFalse(record.priceObservationEligible)
            assertFalse(record.cashOsTransactionEligible)
            assertEquals(PurchaseRecordKind.RETAIL, record.kind)
        }

        val orderedOnly = orderRecord(
            status = PurchaseRecordStatus.ORDERED,
            payment = null,
        )
        assertFalse(orderedOnly.priceObservationEligible)
        assertFalse(orderedOnly.cashOsTransactionEligible)
    }

    @Test
    fun `timestamp-only purchase derives routing dates without rewriting source timestamps`() {
        val record = orderRecord(
            orderedOn = null,
            orderedAt = "2026-09-11T09:00:00+09:00",
            paidOn = null,
            paidAt = "2026-09-11T09:01:00+09:00",
        )

        assertEquals("2026-09-11", record.effectiveOrderedOn)
        assertEquals("2026-09-11", record.effectivePaidOn)
        val payload = PriceTracePurchaseObservationV4Json.toJson(
            PriceTracePurchaseObservationV4Payload(
                contract = PriceTracePurchaseObservationV4Contract(),
                purchaseRecord = record,
                verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
            ),
        )
        val order = payload["order"]!!.jsonObject
        val payment = payload["payment"]!!.jsonObject
        assertEquals("2026-09-11", order["ordered_on"]?.jsonPrimitive?.content)
        assertEquals("2026-09-11T09:00:00+09:00", order["ordered_at"]?.jsonPrimitive?.content)
        assertEquals("2026-09-11", payment["paid_on"]?.jsonPrimitive?.content)
        assertEquals("2026-09-11T09:01:00+09:00", payment["paid_at"]?.jsonPrimitive?.content)
        val decoded = YeonsikOcrV4Json.decode(
            YeonsikOcrV4Json.encode(
                purchaseEnvelope(record),
            ),
            "v4-timestamp-only",
        ).purchaseRecords.single()
        assertEquals(null, decoded.orderedOn)
        assertEquals(null, decoded.paidOn)
        assertEquals(record.orderedAt, decoded.orderedAt)
        assertEquals(record.paidAt, decoded.paidAt)
    }

    @Test
    fun `multiple seller overrides stay representable per PriceTrace line`() {
        val record = orderRecord(
            seller = null,
            lineItems = listOf(
                PurchaseRecordLine(
                    productClientKey = "product-a",
                    description = "상품 A",
                    sellerOverride = "판매자 A",
                    quantity = 1.0,
                    unitPriceAmountKrw = 500,
                    grossAmountKrw = 500,
                    netAmountKrw = 500,
                ),
                PurchaseRecordLine(
                    productClientKey = "product-b",
                    description = "상품 B",
                    sellerOverride = "판매자 B",
                    quantity = 1.0,
                    unitPriceAmountKrw = 600,
                    grossAmountKrw = 600,
                    netAmountKrw = 600,
                ),
            ),
        )
        val items = PriceTracePurchaseObservationV4Json.toJson(
            PriceTracePurchaseObservationV4Payload(
                contract = PriceTracePurchaseObservationV4Contract(),
                purchaseRecord = record,
                verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
            ),
        )["items"]!!.jsonArray

        assertEquals(JsonPrimitive("판매자 A"), items[0].jsonObject["seller"]!!.jsonObject["seller_name"])
        assertEquals(JsonPrimitive("판매자 B"), items[1].jsonObject["seller"]!!.jsonObject["seller_name"])
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
    fun v4AllowsEmptyCandidatesAndMixedCandidateEvidenceConflict() {
        val emptyCandidates = YeonsikOcrV4Json.decode(
            YeonsikOcrV4Json.encode(purchaseEnvelope(orderRecord())),
            "v4-empty-candidates",
        )
        assertTrue(emptyCandidates.productCandidates.isEmpty())

        val candidate = ProductCandidate(
            clientKey = "mixed-candidate",
            productName = "주문 상품",
            sourceAttachmentIds = listOf("order-history-1"),
            evidence = listOf(
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("order-history-1"),
                    sourceType = "order_history",
                    sourceRef = "order-history-1",
                    field = "product_name",
                    observedValue = "주문 상품",
                ),
                ProductCandidateEvidence(
                    sourceType = "user_statement",
                    sourceRef = "user-statement:manual",
                    field = "product_name",
                    observedValue = "다른 상품",
                ),
            ),
            confidence = 0.8,
        )
        val envelope = purchaseEnvelope(
            record = orderRecord(
                lineItems = listOf(
                    PurchaseRecordLine(
                        productClientKey = candidate.clientKey,
                        description = "주문 상품",
                        quantity = 2.0,
                        unitPriceAmountKrw = 550,
                        grossAmountKrw = 1100,
                        discountAmountKrw = 0,
                        netAmountKrw = 1100,
                    ),
                ),
            ),
            productCandidates = listOf(candidate),
            userText = "사용자가 다른 상품이라고 정정함",
        )
        val decoded = YeonsikOcrV4Json.decode(
            YeonsikOcrV4Json.encode(envelope),
            "v4-mixed-candidate-evidence",
        )
        assertEquals(IngestionReviewStatus.CONFLICT, decoded.review.status)
        assertTrue(
            decoded.review.blockingIssues.any {
                it == "product_candidate_conflict:mixed-candidate:product_name"
            },
        )
        assertTrue(decoded.productCandidates.single().evidence.any {
            it.sourceType == "order_history"
        })
        assertTrue(decoded.productCandidates.single().evidence.any {
            it.sourceType == "user_statement"
        })
        assertTrue(decoded.productCandidates.single().evidence
            .first { it.sourceType == "user_statement" }
            .sourceRef
            ?.equals("user-statement:manual") == true)
        val gate = IngestionEvidenceGate.evaluate(
            envelope = decoded,
            evidence = listOf(
                LocalEvidence("order-history-1", SourceAttachmentType.ORDER_HISTORY, fileReadable = true),
            ),
        )
        assertFalse(gate.isAllowed)
        assertTrue(
            gate.blockingIssues.any {
                it == "product_candidate_conflict:mixed-candidate:product_name"
            },
        )
    }

    @Test
    fun `restaurant purchase uses restaurant PriceTrace kind without inventing timestamps`() {
        val record = orderRecord(
            seller = "테스트 식당",
            purchaseKind = PurchaseKind.RESTAURANT,
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
        productCandidates: List<ProductCandidate> = emptyList(),
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
        productCandidates = productCandidates,
        purchaseRecords = listOf(record),
    )

    private fun orderRecord(
        seller: String? = "공식 판매자",
        purchaseKind: PurchaseKind = PurchaseKind.RETAIL,
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
        status: PurchaseRecordStatus = PurchaseRecordStatus.PAID,
        payment: PurchaseRecordPayment? = PurchaseRecordPayment(method = "card", status = "paid"),
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
        purchaseKind = purchaseKind,
        orderedOn = orderedOn,
        orderedAt = orderedAt,
        paidOn = paidOn,
        paidAt = paidAt,
        status = status,
        totals = PurchaseRecordTotals(
            subtotalAmountKrw = 1100,
            discountAmountKrw = 0,
            shippingAmountKrw = 0,
            taxAmountKrw = 0,
            grandTotalAmountKrw = 1100,
            paidAmountKrw = 1100,
        ),
        payment = payment,
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
