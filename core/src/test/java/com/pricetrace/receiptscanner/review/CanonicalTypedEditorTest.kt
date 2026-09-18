package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordTotals
import com.pricetrace.receiptscanner.ingestion.PriceTraceV4SubmissionReason
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV4Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanonicalTypedEditorTest {
    @Test
    fun `typed v2 edits synchronize linked facts and reject structural fields`() {
        val imported = YeonsikOcrV2Json.decode(readExample("yeonsik-ocr.v2.restaurant.example.json"), "typed-v2")
        val original = imported.copy(review = imported.review.copy(status = IngestionReviewStatus.READY))
        val controller = CanonicalReviewController(original, now = { "2026-09-15T12:00:00+09:00" })

        assertTrue(controller.editableFields().none { it.path.endsWith(".sub_brand_name") || it.path.endsWith(".merchant_sku") })
        assertTrue(controller.editableFields().none { it.path.startsWith("merchant_candidate.") })

        assertTrue(controller.updateField("receipt.merchant.name", "수정 식당"))
        assertEquals("수정 식당", controller.state.value.envelope.receipt!!.merchant.name)
        assertEquals("수정 식당", controller.state.value.envelope.merchantCandidate!!.name)
        assertTrue(controller.state.value.edits.any { it.fieldPath == "merchant_candidate.name" })

        val originalConfidence = original.receipt!!.lineItems.single { it.id == "line-1" }.confidence
        assertTrue(controller.updateField("receipt.line_items[line-1].description", "우동"))
        assertEquals("우동", controller.state.value.envelope.receipt!!.lineItems.single().description)
        assertEquals(originalConfidence, controller.state.value.envelope.receipt!!.lineItems.single().confidence)
        val linkedNutrition = controller.state.value.envelope.nutrition.single { it.clientKey == "food-1" }
        assertEquals("우동", (linkedNutrition as IngestionNutrition.RestaurantEstimate).menuName)
        assertTrue(controller.state.value.edits.any { it.fieldPath == "nutrition[food-1].menu_name" })
        assertEquals(original.source.userText, controller.state.value.envelope.source.userText)
        assertTrue(controller.state.value.edits.all { it.valueType == CanonicalFieldType.TEXT })
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, controller.state.value.envelope.review.status)

        val beforeBlockedEdit = controller.state.value.envelope
        assertFalse(controller.updateField("merchant_candidate.name", "불일치 상점"))
        assertEquals(beforeBlockedEdit, controller.state.value.envelope)
        assertFalse(controller.updateField("source.user_text", "바꾸면 안 됨"))
        assertEquals(beforeBlockedEdit, controller.state.value.envelope)
        assertTrue(controller.state.value.fieldErrors.containsKey("source.user_text"))
    }

    @Test
    fun `typed v2 nullable quantity can be cleared without changing line authority`() {
        val original = YeonsikOcrV2Json.decode(readExample("yeonsik-ocr.v2.restaurant.example.json"), "typed-v2-clear")
        val originalLine = original.receipt!!.lineItems.single { it.id == "line-1" }
        val controller = CanonicalReviewController(original)

        assertTrue(controller.updateField("receipt.line_items[line-1].quantity", null))

        val editedLine = controller.state.value.envelope.receipt!!.lineItems.single { it.id == "line-1" }
        assertEquals(null, editedLine.quantity)
        assertEquals(originalLine.confidence, editedLine.confidence)
        assertEquals(null, controller.state.value.edits.single().newValue)
    }

    @Test
    fun `typed edit preserves existing blocking issues while requiring re-verification`() {
        val imported = YeonsikOcrV2Json.decode(readExample("yeonsik-ocr.v2.restaurant.example.json"), "typed-v2-blocking")
        val original = imported.copy(
            review = imported.review.copy(
                status = IngestionReviewStatus.READY,
                blockingIssues = listOf("evidence_conflict:merchant"),
            ),
        )
        val controller = CanonicalReviewController(original)

        assertTrue(controller.updateField("receipt.merchant.branch_name", "새 지점"))

        assertEquals(listOf("evidence_conflict:merchant"), controller.state.value.envelope.review.blockingIssues)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, controller.state.value.envelope.review.status)
    }

    @Test
    fun `typed v2 receipt free restaurant keeps merchant and menu edits independent`() {
        val original = YeonsikOcrV2Json.decode(
            readExample("yeonsik-ocr.v2.restaurant-food-photo.example.json"),
            "typed-v2-receipt-free",
        )
        val controller = CanonicalReviewController(original)

        assertTrue(controller.updateField("merchant_candidate.name", "새 식당"))
        assertTrue(controller.updateField("nutrition[food-photo-1].menu_name", "새 메뉴"))

        val edited = controller.state.value.envelope
        assertEquals("새 식당", edited.merchantCandidate?.name)
        assertEquals("새 메뉴", (edited.nutrition.single() as IngestionNutrition.RestaurantEstimate).menuName)
        assertEquals(original.source, edited.source)
        assertEquals(
            setOf("merchant_candidate.name", "nutrition[food-photo-1].menu_name"),
            controller.state.value.edits.map { it.fieldPath }.toSet(),
        )
    }

    @Test
    fun `typed v4 edits preserve purchase identity and enforce integer price trace limits`() {
       val original = YeonsikOcrV4Json.decode(readExample("yeonsik-ocr.v4.purchase.example.json"), "typed-v4")
        val editable = original.copy(
            purchaseRecords = original.purchaseRecords.map { record ->
                record.copy(lineItems = record.lineItems.map { line ->
                    line.copy(
                        unitPriceAmountKrw = null,
                        grossAmountKrw = null,
                        discountAmountKrw = null,
                        netAmountKrw = null,
                    )
                }, totals = PurchaseRecordTotals(grandTotalAmountKrw = 0L))
            },
        )
        val controller = CanonicalReviewController(editable, now = { "2026-09-15T12:00:00+09:00" })

        assertTrue(controller.editableFields().any { it.path == "purchase_records[purchase-1].platform" })
        val quantityField = controller.editableFields().single { it.path.endsWith(".line_items[0].quantity") }
        assertEquals(CanonicalFieldType.DECIMAL, quantityField.type)
        assertNull(quantityField.max)
        assertNull(controller.editableFields().single { it.path.endsWith(".totals.grand_total_amount_krw") }.max)
        assertTrue(controller.updateField("purchase_records[purchase-1].platform", "11번가"))
        val lineUpdated = controller.updateField("purchase_records[purchase-1].line_items[0].quantity", "0.5")
        assertTrue("line update failed: ${controller.state.value.error}", lineUpdated)
        val edited = controller.state.value.envelope.purchaseRecords.single()
        assertEquals("11번가", edited.platform)
        assertEquals(0.5, edited.lineItems.single().quantity)
        assertEquals(CanonicalFieldType.DECIMAL, controller.state.value.edits.last().valueType)
        assertEquals(original.source, controller.state.value.envelope.source)

        assertFalse(edited.priceTraceSubmissionEligible)
        assertTrue(edited.cashOsTransactionEligible)

        assertTrue(controller.updateField("purchase_records[purchase-1].line_items[0].quantity", "1"))
        assertTrue(controller.state.value.envelope.purchaseRecords.single().priceTraceSubmissionEligible)
        assertTrue(controller.updateField("purchase_records[purchase-1].totals.grand_total_amount_krw", "2147483648"))
        val largeValueEdited = controller.state.value.envelope.purchaseRecords.single()
        assertEquals(2147483648L, largeValueEdited.totals.grandTotalAmountKrw)
        assertFalse(largeValueEdited.priceTraceSubmissionEligible)
        assertEquals(PriceTraceV4SubmissionReason.PAYMENT_AMOUNT_INTEGER_RANGE_REQUIRED, largeValueEdited.priceTraceSubmissionReasonCode)
        assertTrue(largeValueEdited.cashOsTransactionEligible)
        assertFalse(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in controller.state.value.plan.eligible)
        assertTrue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in controller.state.value.plan.disabled)
        assertTrue(IngestionProjection.CASHOS_TRANSACTION in controller.state.value.plan.eligible)

        val beforeInvalid = controller.state.value.envelope
        assertFalse(controller.updateField("purchase_records[purchase-1].line_items[0].quantity", "0"))
        assertEquals(beforeInvalid, controller.state.value.envelope)
        assertFalse(controller.updateField("purchase_records[purchase-1].totals.grand_total_amount_krw", "-1"))
        assertEquals(beforeInvalid, controller.state.value.envelope)
        assertTrue(controller.state.value.fieldErrors.keys.any { it.contains("grand_total_amount_krw") })
        assertFalse(controller.updateField("purchase_records[purchase-1].purchase_kind", "not-a-kind"))
    }

    @Test
    fun `typed v4 seller line and product observations use the same allow list`() {
        val original = YeonsikOcrV4Json.decode(readExample("yeonsik-ocr.v4.purchase.example.json"), "typed-v4-seller")
        val controller = CanonicalReviewController(original)

        assertTrue(controller.updateField("purchase_records[purchase-1].seller", "새 판매자"))
        assertTrue(controller.updateField("purchase_records[purchase-1].line_items[0].description", "새 생수"))
        assertTrue(controller.updateField("product_candidates[product-client-1].product_name", "새 상품"))

        val edited = controller.state.value.envelope
        val record = edited.purchaseRecords.single()
        assertEquals("새 판매자", record.seller)
        assertEquals("새 생수", record.lineItems.single().description)
        assertEquals("새 상품", edited.productCandidates.single().productName)
        assertEquals(original.source, edited.source)
    }

    private fun readExample(name: String): String = sequenceOf(
        File("examples", name),
        File("../examples", name),
    ).firstOrNull(File::isFile)?.readText() ?: error("example not found: $name")
}
