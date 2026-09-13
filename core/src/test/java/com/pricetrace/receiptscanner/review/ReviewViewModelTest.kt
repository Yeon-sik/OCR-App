package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.ReceiptDocument
import com.pricetrace.receiptscanner.domain.ReceiptMerchant
import com.pricetrace.receiptscanner.domain.ReceiptSource
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2Totals
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.NutritionNutrientProvenance
import com.pricetrace.receiptscanner.ingestion.PurchaseKind
import com.pricetrace.receiptscanner.ingestion.PurchaseRecord
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordEvidence
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordLine
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordPayment
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordStatus
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordTotals
import com.pricetrace.receiptscanner.ingestion.RestaurantNutritionEstimate
import com.pricetrace.receiptscanner.ingestion.SourceAttachment
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V4_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewViewModelTest {
    @Test
    fun destinationTokensAreCentralizedAndStable() {
        assertEquals("#22C55E", DestinationColorTokens.PRICE_TRACE)
        assertEquals("#FACC15", DestinationColorTokens.CASH_OS)
        assertEquals("#7DD3FC", DestinationColorTokens.FITNESS)
        assertEquals(DestinationColorTokens.PRICE_TRACE, ReviewDestination.PRICE_TRACE.colorHex)
    }

    @Test
    fun canonicalRowsUsePlannerInsteadOfProjectionTargetHints() {
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.RESTAURANT,
            source = IngestionSource(producer = "test", sourceFiles = emptyList()),
            receipt = receipt(),
            // Deliberately empty: the planner, not this hint, supplies PT/Cash destinations.
            targets = emptySet(),
        )

        val model = ReviewViewModel.fromCanonical(envelope)
        val pt = model.destinations.single { it.destination == ReviewDestination.PRICE_TRACE }
        val cash = model.destinations.single { it.destination == ReviewDestination.CASH_OS }
        val fitness = model.destinations.single { it.destination == ReviewDestination.FITNESS }

        assertEquals(ReviewDestinationStatus.PLANNED, pt.status)
        assertEquals(ReviewDestinationStatus.PLANNED, cash.status)
        assertEquals(ReviewDestinationStatus.NOT_APPLICABLE, fitness.status)
        assertTrue(model.rows.any { it.item == "판매처명" })
    }

    @Test
    fun rowDestinationsReflectSelectedProjections() {
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.RESTAURANT,
            source = IngestionSource(producer = "test", sourceFiles = emptyList()),
            receipt = receipt(),
        )

        val merchantRow = ReviewViewModel.fromCanonical(
            envelope,
            selectedProjections = setOf(IngestionProjection.PRICETRACE_RECEIPT),
        ).rows.single { it.item == "판매처명" }

        assertEquals(
            listOf(ReviewDestination.PRICE_TRACE, ReviewDestination.CASH_OS),
            merchantRow.destinations.map { it.destination },
        )
        assertEquals(
            ReviewDestinationStatus.PLANNED,
            merchantRow.destinations.single { it.destination == ReviewDestination.PRICE_TRACE }.status,
        )
        assertEquals(
            ReviewDestinationStatus.UNSELECTED,
            merchantRow.destinations.single { it.destination == ReviewDestination.CASH_OS }.status,
        )
    }

    @Test
    fun nutritionEvidenceRefsResolveToBoundSourceFileOnly() {
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.RESTAURANT,
            source = IngestionSource(
                producer = "test",
                sourceFiles = listOf(
                    SourceAttachment("food-1", SourceAttachmentType.FOOD_PHOTO),
                    SourceAttachment("food-2", SourceAttachmentType.FOOD_PHOTO),
                ),
            ),
            nutrition = listOf(
                IngestionNutrition.RestaurantEstimate(
                    clientKey = "nutrition-1",
                    lineId = "line-1",
                    menuName = "비빔밥",
                    estimate = RestaurantNutritionEstimate(
                        nutrients = mapOf(NutritionField.CALORIES_KCAL to 500.0),
                        estimated = true,
                        confidence = "medium",
                        nutrientProvenance = mapOf(
                            NutritionField.CALORIES_KCAL to NutritionNutrientProvenance(
                                valueStatus = "estimated",
                                sourceType = "food_image_estimate",
                                evidenceRefs = listOf("food-1/calories_kcal"),
                            ),
                        ),
                        confidenceScore = 0.8,
                    ),
                ),
            ),
        )

        val row = ReviewViewModel.fromCanonical(
            envelope,
            evidence = listOf(
                LocalEvidence("food-1", SourceAttachmentType.FOOD_PHOTO, fileReadable = true),
                LocalEvidence("food-2", SourceAttachmentType.FOOD_PHOTO, fileReadable = true),
            ),
        ).rows.single { it.section == "영양" }

        assertEquals(listOf("food-1"), row.evidence.single().sourceIds)
    }

    @Test
    fun productLabelUsesDocumentIdFallbackForBoundNutritionLabel() {
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.PACKAGED_PRODUCT,
            source = IngestionSource(
                producer = "test",
                sourceFiles = listOf(
                    SourceAttachment("nutrition-doc", SourceAttachmentType.NUTRITION_LABEL),
                    SourceAttachment("other-nutrition", SourceAttachmentType.NUTRITION_LABEL),
                ),
            ),
            nutrition = listOf(
                IngestionNutrition.ProductLabel(
                    clientKey = "nutrition-doc",
                    draft = NutritionLabelDraft(documentId = "nutrition-doc"),
                ),
            ),
        )

        val row = ReviewViewModel.fromCanonical(
            envelope,
            evidence = listOf(
                LocalEvidence("nutrition-doc", SourceAttachmentType.NUTRITION_LABEL, fileReadable = true),
                LocalEvidence("other-nutrition", SourceAttachmentType.NUTRITION_LABEL, fileReadable = true),
            ),
        ).rows.single { it.section == "영양" }

        assertEquals(ReviewEvidenceKind.NUTRITION_LABEL, row.evidence.single().kind)
        assertEquals(listOf("nutrition-doc"), row.evidence.single().sourceIds)
    }

    @Test
    fun v4PurchaseRowsUseFieldAndLineScopedEvidenceAndSeparateAmounts() {
        val purchase = PurchaseRecord(
            clientKey = "purchase-1",
            platform = "플랫폼",
            seller = "판매자",
            purchaseKind = PurchaseKind.RETAIL,
            orderedOn = "2026-09-13",
            orderedAt = "2026-09-13T09:00:00+09:00",
            paidOn = "2026-09-13",
            paidAt = "2026-09-13T09:01:00+09:00",
            status = PurchaseRecordStatus.PAID,
            totals = PurchaseRecordTotals(grandTotalAmountKrw = 1000, paidAmountKrw = 1000),
            payment = PurchaseRecordPayment(method = "card", provider = "카드사", status = "paid"),
            lineItems = listOf(
                PurchaseRecordLine(lineKey = "line-1", description = "상품", quantity = 1.0, netAmountKrw = 1000),
            ),
            evidence = listOf(
                PurchaseRecordEvidence("order_history", listOf("order-1"), field = "platform", observedValue = "플랫폼"),
                PurchaseRecordEvidence("payment_history", listOf("payment-1"), field = "seller", observedValue = "판매자"),
                PurchaseRecordEvidence("order_history", listOf("order-1"), field = "grand_total_amount_krw", observedValue = "11000"),
                PurchaseRecordEvidence("order_history", listOf("order-2"), field = "grand_total_amount_krw", observedValue = "12000"),
                PurchaseRecordEvidence("payment_history", listOf("payment-1"), field = "paid_amount_krw", observedValue = "1000"),
                PurchaseRecordEvidence("payment_history", listOf("payment-1"), field = "payment.method", observedValue = "card"),
                PurchaseRecordEvidence("payment_history", listOf("payment-1"), field = "payment.provider", observedValue = "카드사"),
                PurchaseRecordEvidence("payment_history", listOf("payment-1"), field = "payment.status", observedValue = "결제완료"),
                PurchaseRecordEvidence("order_history", listOf("order-1"), sourceRef = "item-a", field = "description", observedValue = "상품"),
                PurchaseRecordEvidence("user_statement", sourceRef = "screen-region-7", field = "quantity", observedValue = "1.0"),
            ),
            confidence = 0.9,
        )
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.PURCHASE,
            schemaVersion = YEONSIK_OCR_V4_SCHEMA,
            source = IngestionSource(
                producer = "test",
                userText = "상품 1개",
                sourceFiles = listOf(
                    SourceAttachment("order-1", SourceAttachmentType.ORDER_HISTORY),
                    SourceAttachment("order-2", SourceAttachmentType.ORDER_HISTORY),
                    SourceAttachment("payment-1", SourceAttachmentType.PAYMENT_HISTORY),
                ),
            ),
            purchaseRecords = listOf(purchase),
        )

        val rows = ReviewViewModel.fromCanonical(
            envelope,
            evidence = listOf(
                LocalEvidence("order-1", SourceAttachmentType.ORDER_HISTORY, fileReadable = true),
                LocalEvidence("order-2", SourceAttachmentType.ORDER_HISTORY, fileReadable = true),
                LocalEvidence("payment-1", SourceAttachmentType.PAYMENT_HISTORY, fileReadable = true),
            ),
        ).rows

        assertEquals(listOf(ReviewEvidenceKind.ORDER_HISTORY), rows.single { it.item == "플랫폼" }.evidence.map { it.kind })
        assertEquals(listOf(ReviewEvidenceKind.PAYMENT_HISTORY), rows.single { it.item == "판매자" }.evidence.map { it.kind })
        assertEquals(listOf(ReviewEvidenceKind.PAYMENT_HISTORY), rows.single { it.item == "결제수단" }.evidence.map { it.kind })
        assertEquals(listOf(ReviewEvidenceKind.PAYMENT_HISTORY), rows.single { it.item == "결제 제공자" }.evidence.map { it.kind })
        assertEquals(listOf(ReviewEvidenceKind.PAYMENT_HISTORY), rows.single { it.item == "결제상태" }.evidence.map { it.kind })
        assertEquals("1000 KRW", rows.single { it.item == "총 주문금액" }.value)
        assertEquals("1000 KRW", rows.single { it.item == "실제 결제금액" }.value)
        assertEquals(listOf("order-1", "order-2"), rows.single { it.item == "총 주문금액" }.evidence.single().sourceIds)
        assertEquals(
            setOf(ReviewEvidenceKind.ORDER_HISTORY, ReviewEvidenceKind.USER_INPUT),
            rows.single { it.item == "상품" }.evidence.map { it.kind }.toSet(),
        )

        val ambiguousPurchase = purchase.copy(
            lineItems = listOf(
                purchase.lineItems.single(),
                PurchaseRecordLine(lineKey = "line-2", description = "상품", quantity = 2.0, netAmountKrw = 2000),
            ),
            evidence = listOf(
                PurchaseRecordEvidence(
                    "user_statement",
                    sourceRef = "screen-region-7",
                    field = "description",
                    observedValue = "상품",
                ),
            ),
        )
        val ambiguousRows = ReviewViewModel.fromCanonical(
            envelope.copy(purchaseRecords = listOf(ambiguousPurchase)),
            evidence = listOf(
                LocalEvidence("order-1", SourceAttachmentType.ORDER_HISTORY, fileReadable = true),
                LocalEvidence("order-2", SourceAttachmentType.ORDER_HISTORY, fileReadable = true),
                LocalEvidence("payment-1", SourceAttachmentType.PAYMENT_HISTORY, fileReadable = true),
            ),
        ).rows.filter { it.section == "구매 상품" }
        assertTrue(ambiguousRows.all { it.evidence.isEmpty() })
    }

    private fun receipt() = ReceiptV2(
        document = ReceiptDocument(
            id = null,
            localDocumentId = "review-test",
            issuedOn = "2026-09-13",
            issuedAt = null,
            currency = "KRW",
            source = ReceiptSource(
                originalDocumentId = null,
                sourceImages = emptyList(),
                transcriptionStatus = TranscriptionStatus.PARSED,
                rawText = null,
            ),
        ),
        merchant = ReceiptMerchant(name = "테스트 마트", branchName = null),
        lineItems = emptyList(),
        totals = ReceiptV2Totals(
            itemsGrossAmountMinor = 1000,
            discountAmountMinor = null,
            taxAmountMinor = null,
            feeAmountMinor = null,
            tipAmountMinor = null,
            roundingAmountMinor = null,
            grandTotalAmountMinor = 1000,
        ),
        payments = emptyList(),
    )
}
