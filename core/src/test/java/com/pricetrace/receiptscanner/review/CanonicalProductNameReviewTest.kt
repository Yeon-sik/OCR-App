package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanonicalProductNameReviewTest {
    @Test
    fun `explicit product key rename updates every shared label with one atomic undo and redo`() {
        val original = packagedEnvelope()
        val label = original.nutrition.single() as IngestionNutrition.ProductLabel
        val envelope = original.copy(
            nutrition = listOf(label, label.copy(clientKey = "second-label")),
        )
        val controller = CanonicalReviewController(envelope)

        assertTrue(controller.updateField("product_candidates[product-1].product_name", "수정 상품"))
        assertEquals("수정 상품", controller.state.value.envelope.productCandidates.single().productName)
        assertEquals(listOf("수정 상품", "수정 상품"), productNames(controller.state.value.envelope))
        assertEquals(
            setOf(
                "product_candidates[product-1].product_name",
                "nutrition[label-1].product_name",
                "nutrition[second-label].product_name",
            ),
            controller.state.value.edits.map { it.fieldPath }.toSet(),
        )
        assertTrue(controller.state.value.edits.all { it.valueType == CanonicalFieldType.TEXT })
        assertEquals(original.source, controller.state.value.envelope.source)
        assertEquals(label.draft.evidence, (controller.state.value.envelope.nutrition.first() as IngestionNutrition.ProductLabel).draft.evidence)

        assertTrue(controller.undo())
        assertEquals(envelope.nutrition, controller.state.value.envelope.nutrition)
        assertEquals(envelope.productCandidates, controller.state.value.envelope.productCandidates)
        assertFalse(controller.state.value.canUndo)
        assertTrue(controller.state.value.edits.takeLast(3).all { it.newValue == "Test cereal" })

        assertTrue(controller.redo())
        assertEquals(listOf("수정 상품", "수정 상품"), productNames(controller.state.value.envelope))
        assertFalse(controller.state.value.canRedo)
        assertTrue(controller.state.value.edits.takeLast(3).all { it.newValue == "수정 상품" })
    }

    @Test
    fun `rename preserves independently named and other-product labels`() {
        val original = packagedEnvelope()
        val product = original.productCandidates.single()
        val label = original.nutrition.single() as IngestionNutrition.ProductLabel
        val envelope = original.copy(
            productCandidates = original.productCandidates + product.copy(clientKey = "other-product"),
            nutrition = listOf(
                label.copy(draft = label.draft.copy(productName = "독립 라벨 이름")),
                label.copy(clientKey = "other-label", productClientKey = "other-product"),
            ),
        )
        val controller = CanonicalReviewController(envelope)

        assertTrue(controller.updateField("product_candidates[product-1].product_name", "수정 상품"))
        assertEquals(listOf("독립 라벨 이름", "Test cereal"), productNames(controller.state.value.envelope))
        assertEquals("Test cereal", controller.state.value.envelope.productCandidates.last().productName)
        assertEquals(1, controller.state.value.edits.size)
    }

    @Test
    fun `matching names and client keys do not substitute for an explicit product link`() {
        val original = packagedEnvelope()
        val label = original.nutrition.single() as IngestionNutrition.ProductLabel
        val controller = CanonicalReviewController(
            original.copy(nutrition = listOf(label.copy(clientKey = "product-1", productClientKey = null))),
        )

        assertTrue(controller.updateField("product_candidates[product-1].product_name", "수정 상품"))
        assertEquals(listOf("Test cereal"), productNames(controller.state.value.envelope))
        assertEquals(1, controller.state.value.edits.size)
    }

    @Test
    fun `a separately edited label stays independent on the next product rename`() {
        val controller = CanonicalReviewController(packagedEnvelope())
        assertTrue(controller.updateField("nutrition[label-1].product_name", "라벨 정식 명칭"))
        assertTrue(controller.updateField("product_candidates[product-1].product_name", "판매 상품명"))

        assertEquals(listOf("라벨 정식 명칭"), productNames(controller.state.value.envelope))
        assertTrue(controller.undo())
        assertEquals("Test cereal", controller.state.value.envelope.productCandidates.single().productName)
        assertEquals(listOf("라벨 정식 명칭"), productNames(controller.state.value.envelope))
        assertTrue(controller.undo())
        assertEquals(listOf("Test cereal"), productNames(controller.state.value.envelope))
    }

    private fun productNames(envelope: YeonsikOcrEnvelope): List<String?> =
        envelope.nutrition.filterIsInstance<IngestionNutrition.ProductLabel>().map { it.draft.productName }

    private fun packagedEnvelope(): YeonsikOcrEnvelope {
        val name = "yeonsik-ocr.v2.packaged-product.example.json"
        val fixture = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile)?.readText() ?: error("packaged product example not found")
        val decoded = YeonsikOcrV2Json.decode(fixture, "product-name-review")
        val label = decoded.nutrition.single() as IngestionNutrition.ProductLabel
        return decoded.copy(
            nutrition = listOf(label.copy(clientKey = "label-1", productClientKey = "product-1")),
            consumption = emptyList(),
            targets = decoded.targets - IngestionProjection.FITNESS_MEAL,
        )
    }
}
