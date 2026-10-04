package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.CanonicalFieldRegistry
import com.pricetrace.receiptscanner.review.CanonicalFieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanonicalReviewFieldsTest {
    @Test
    fun exactProductLinkAndEqualNamesExposeOneEditableProductName() {
        val envelope = packagedEnvelope()
        val groups = canonicalReviewFieldGroups(envelope)
        val fields = groups.visibleFields + groups.emptyOptionalFields

        assertEquals("상품명", fields.single { it.path == "product_candidates[product-1].product_name" }.label)
        assertFalse(fields.any { it.path == "nutrition[label-1].product_name" })
        assertEquals(
            CanonicalFieldRegistry.fields(envelope).map { it.path }.toSet() - "nutrition[label-1].product_name",
            fields.map { it.path }.toSet(),
        )
    }

    @Test
    fun differingOrUnlinkedLabelNamesStayEditable() {
        val envelope = packagedEnvelope()
        val label = envelope.nutrition.single() as IngestionNutrition.ProductLabel
        listOf(
            label.copy(draft = label.draft.copy(productName = "라벨 이름")),
            label.copy(productClientKey = null),
        ).forEach { separateLabel ->
            val groups = canonicalReviewFieldGroups(envelope.copy(nutrition = listOf(separateLabel)))
            assertEquals(
                "라벨 상품명",
                groups.visibleFields.single { it.path == "nutrition[label-1].product_name" }.label,
            )
        }
    }

    @Test
    fun matchingNamesOnAnotherProductNeverHideALabelField() {
        val envelope = packagedEnvelope()
        val label = envelope.nutrition.single() as IngestionNutrition.ProductLabel
        val groups = canonicalReviewFieldGroups(
            envelope.copy(nutrition = listOf(label.copy(productClientKey = "other-product"))),
        )

        assertTrue(groups.visibleFields.any { it.path == "nutrition[label-1].product_name" })
    }

    @Test
    fun onlyEmptyOptionalFieldsAreCollapsedAndValuesArePreserved() {
        val envelope = packagedEnvelope()
        val groups = canonicalReviewFieldGroups(envelope)

        assertTrue(groups.visibleFields.isNotEmpty())
        assertTrue(groups.emptyOptionalFields.isNotEmpty())
        assertTrue(groups.visibleFields.all { !it.nullable || !it.value.isNullOrBlank() })
        assertTrue(groups.emptyOptionalFields.all { it.nullable && it.value.isNullOrBlank() })
        val registry = CanonicalFieldRegistry.fields(envelope).associateBy { it.path }
        (groups.visibleFields + groups.emptyOptionalFields).forEach { field ->
            assertEquals(registry.getValue(field.path).value, field.value)
            assertEquals(registry.getValue(field.path).type, field.type)
            assertEquals(registry.getValue(field.path).nullable, field.nullable)
        }
    }

    @Test
    fun accessibilityDescriptionStatesRequirementOnlyOnceAndRetainsErrors() {
        val field = CanonicalEditableField("product.name", "상품명", CanonicalFieldType.TEXT, nullable = false)
        assertEquals("상품명, 필수 항목", canonicalFieldContentDescription(field, modified = false, error = null))
        assertEquals(
            "상품명, 선택 항목, 수정됨, 오류: 값을 확인하세요",
            canonicalFieldContentDescription(field.copy(nullable = true), modified = true, error = "값을 확인하세요"),
        )
    }

    private fun packagedEnvelope(): YeonsikOcrEnvelope {
        val name = "yeonsik-ocr.v2.packaged-product.example.json"
        val fixture = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile)?.readText() ?: error("packaged product example not found")
        val decoded = YeonsikOcrV2Json.decode(fixture, "desktop-product-name-review")
        val label = decoded.nutrition.single() as IngestionNutrition.ProductLabel
        return decoded.copy(
            nutrition = listOf(label.copy(clientKey = "label-1", productClientKey = "product-1")),
            consumption = emptyList(),
            targets = decoded.targets - IngestionProjection.FITNESS_MEAL,
        )
    }
}
