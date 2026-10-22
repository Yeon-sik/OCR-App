package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.CanonicalFieldRegistry
import com.pricetrace.receiptscanner.review.CanonicalFieldType
import org.junit.Assert.*
import org.junit.Test
import java.awt.Rectangle
import java.io.File

class DesktopReviewPresentationTest {
    private fun field(path: String, type: CanonicalFieldType = CanonicalFieldType.DECIMAL) =
        CanonicalEditableField(path, path.substringAfterLast('.'), type, true)

    @Test fun nutrientsKeepTheirCanonicalUnitsAndAmountsKeepTheirExplicitUnits() {
        assertEquals("kcal", reviewFieldUnit(field("nutrition[n].nutrients.calories_kcal"), null))
        assertEquals("g", reviewFieldUnit(field("nutrition[n].nutrients.protein_grams"), null))
        assertEquals("mg", reviewFieldUnit(field("nutrition[n].nutrients.sodium_mg"), null))
        assertEquals("mg", reviewFieldUnit(field("nutrition[n].nutrients.cholesterol_mg"), null))
        assertEquals("원", reviewFieldUnit(field("purchase_records[p].totals.paid_amount_krw"), null))
        assertNull(reviewFieldUnit(field("receipt.totals.grand_total_amount_minor"), null))
        assertNull(reviewFieldUnit(field("nutrition[n].basis_amount"), null))
    }

    @Test fun unitPreviewFollowsPendingUnitWithoutChangingSourceValues() {
        val envelope = YeonsikOcrV2Json.decode(File("../examples/yeonsik-ocr.v2.packaged-product.example.json").readText(), "presentation")
        val basis = CanonicalFieldRegistry.fields(envelope).single { it.path.endsWith(".basis_amount") }
        val unitPath = basis.path.substringBeforeLast('.') + ".basis_unit"
        assertEquals("g", reviewFieldUnit(basis, envelope))
        assertEquals("ml", reviewFieldUnit(basis, envelope, mapOf(unitPath to "ml")))
        assertEquals("g", CanonicalFieldRegistry.value(envelope, unitPath))
    }

    @Test fun displayTimeDropsFractionAndOffsetFromDisplayOnly() {
        val source = "2026-10-23T03:15:09.123456789+09:00"
        assertEquals("2026-10-23-03:15:09", reviewTimestamp(source))
        assertEquals(source, reviewTimestampInput("2026-10-23-03:15:09", source))
        assertEquals("+09:00", reviewTimestampOffset(source))
        assertEquals("2026-10-23", reviewTimestamp("2026-10-23"))
        assertEquals("날짜 2026-10-23 · 시각 2026-10-23-03:15:09", reviewTimestampText("날짜 2026-10-23 · 시각 $source"))
        assertEquals("시각 미확인", reviewTimestampText("시각 미확인"))
    }

    @Test fun timeEditingPreservesSourceOffsetAndRejectsInventedOffsets() {
        val source = "2026-10-23T03:15:09-04:00"
        assertEquals("2026-10-23T04:16:10-04:00", reviewTimestampInput("2026-10-23-04:16:10", source))
        assertEquals("2026-10-23-04:16:10", reviewTimestampInput("2026-10-23-04:16:10", null))
        assertEquals("2026-02-30-04:16:10", reviewTimestampInput("2026-02-30-04:16:10", source))
    }

    @Test fun categoriesDistinguishNutritionPurchasingAndPayments() {
        assertEquals(ReviewFieldCategory.NUTRIENTS, reviewFieldCategory(field("nutrition[n].nutrients.sodium_mg")))
        assertEquals(ReviewFieldCategory.PURCHASE, reviewFieldCategory(field("receipt.document.fulfillment.type", CanonicalFieldType.ENUM)))
        assertEquals(ReviewFieldCategory.PAYMENT, reviewFieldCategory(field("receipt.payments[0].amount_minor")))
        assertEquals(ReviewFieldCategory.TIME, reviewFieldCategory(field("receipt.document.issued_at", CanonicalFieldType.DATETIME)))
    }

    @Test fun narrowerCellsRetainMinimumWidthAndLongFieldsCanSpanColumns() {
        val width = 1000
        val columns = reviewGridColumns(width)
        val newWidth = (width - (columns - 1) * 12f) / columns
        val previousWidth = (width - 24f) / 3
        assertTrue(newWidth / previousWidth in 0.65f..0.8f)
        assertTrue(newWidth >= 200f)
        assertEquals(1, reviewGridColumns(360))
        assertTrue(wideReviewField(field("receipt.document.issued_at", CanonicalFieldType.DATETIME)))
    }

    @Test fun defaultWindowMatchesRequestedSizeAndFitsSmallerDisplays() {
        assertEquals(1737, DEFAULT_WINDOW_WIDTH)
        assertEquals(1328, DEFAULT_WINDOW_HEIGHT)
        assertEquals(Rectangle(0, 0, 1280, 720), clampWindowBounds(
            Rectangle(40, 40, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT), listOf(Rectangle(0, 0, 1280, 720))))
    }
}
