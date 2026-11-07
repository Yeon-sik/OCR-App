package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PurchaseItemReviewTest {
    private val productNamePath = "product_candidates[product-client-1].product_name"
    private val linePath = "purchase_records[purchase-1].line_items[0]"
    private val descriptionPath = "$linePath.description"

    private fun envelope() = YeonsikOcrV4Json.decode(
        File("../examples/yeonsik-ocr.v4.purchase.example.json").readText(), "purchase-item-review")

    private fun layout(envelope: YeonsikOcrEnvelope, attention: Set<String> = emptySet()): PurchaseItemReviewLayout =
        purchaseItemReviewLayout(canonicalReviewFieldGroups(envelope).visibleFields, envelope, attention)

    @Test fun linkedProductNameAndPurchaseAmountsAppearTogetherWithoutRepeatingTheName() {
        val source = envelope()
        val before = YeonsikOcrEnvelopeCodec.encodePersisted(source)
        val layout = layout(source)
        val item = layout.items.single()
        assertEquals(linePath, item.path)
        assertTrue(item.fields.any { it.path == productNamePath })
        assertTrue(item.fields.any { it.path == "$linePath.quantity" })
        assertTrue(item.fields.any { it.path == "$linePath.unit_price_amount_krw" })
        assertFalse(layout.otherFields.any { it.path.startsWith("product_candidates[") })
        assertFalse(item.fields.any { it.path == descriptionPath })
        assertEquals("구매 내역 표기명", item.matchingPurchaseName!!.label)
        assertEquals(setOf(descriptionPath), layout.hiddenPaths)
        assertEquals(before, YeonsikOcrEnvelopeCodec.encodePersisted(source))
    }

    @Test fun differingPurchaseTextRemainsVisibleAsAnIndependentSourceFact() {
        val base = envelope()
        val record = base.purchaseRecords.single()
        val source = base.copy(purchaseRecords = listOf(record.copy(lineItems = listOf(
            record.lineItems.single().copy(description = "판매처 생수 묶음 상품")))))
        val item = layout(source).items.single()
        assertNull(item.matchingPurchaseName)
        assertEquals("구매 내역 표기명", item.fields.single { it.path == descriptionPath }.label)
        assertEquals("판매처 생수 묶음 상품", source.purchaseRecords.single().lineItems.single().description)
        assertEquals("생수", source.productCandidates.single().productName)
    }

    @Test fun equalTextWithoutAProductLinkNeverMergesSeparateProducts() {
        val base = envelope()
        val record = base.purchaseRecords.single()
        val source = base.copy(purchaseRecords = listOf(record.copy(lineItems = listOf(
            record.lineItems.single().copy(productClientKey = null)))))
        val layout = layout(source)
        assertTrue(layout.otherFields.any { it.path == productNamePath })
        assertEquals("상품명", layout.items.single().fields.single { it.path == descriptionPath }.label)
        assertTrue(layout.hiddenPaths.isEmpty())
    }

    @Test fun twoProductsKeepTheirOwnFieldsAndStableLineKeys() {
        val base = envelope()
        val first = base.productCandidates.single()
        val record = base.purchaseRecords.single()
        val line = record.lineItems.single()
        val source = base.copy(productCandidates = listOf(first, first.copy(clientKey = "cover", productName = "스위치 커버")),
            purchaseRecords = listOf(record.copy(lineItems = listOf(line.copy(lineKey = "water"),
                line.copy(lineKey = "cover-line", productClientKey = "cover", description = "스위치 커버")))))
        val items = layout(source).items
        assertEquals(listOf(1, 2), items.map { it.number })
        assertEquals("purchase_records[purchase-1].line_items[cover-line]", items[1].path)
        assertTrue(items[0].fields.any { it.path == productNamePath })
        assertFalse(items[0].fields.any { it.path.startsWith("product_candidates[cover]") })
        assertEquals("상품명", items[1].fields.single { it.path == "product_candidates[cover].product_name" }.label)
        assertEquals(2, layout(source).hiddenPaths.size)
    }

    @Test fun oneProductUsedByTwoPurchaseLinesHasOnlyOneProductEditor() {
        val base = envelope()
        val record = base.purchaseRecords.single()
        val line = record.lineItems.single()
        val source = base.copy(purchaseRecords = listOf(record.copy(lineItems = listOf(
            line.copy(lineKey = "first"), line.copy(lineKey = "second")))))
        val layout = layout(source)
        assertEquals(1, layout.items.flatMap { it.fields }.count { it.path == productNamePath })
        assertTrue(layout.items[1].fields.any { it.path.endsWith(".description") })
    }

    @Test fun draftsErrorsAndNavigationRevealMatchingTextWithoutReparentingItsEditor() {
        val source = envelope()
        val collapsed = layout(source)
        val attention = layout(source, setOf(descriptionPath))
        assertEquals(collapsed.items.single().matchingPurchaseName, attention.items.single().matchingPurchaseName)
        assertTrue(attention.items.single().purchaseNameNeedsAttention)
        assertFalse(descriptionPath in attention.hiddenPaths)
        assertEquals(collapsed.items.single().fields, attention.items.single().fields)
    }
}
