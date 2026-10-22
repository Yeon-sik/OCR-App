package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinkedProductReviewTest {
    @Test fun `same linked names and brands each have one editable fact without altering the registry`() {
        val envelope = fixture()
        val facts = LinkedProductReview.facts(envelope)
        assertTrue(facts.all { it.status == ProductFactStatus.SAME })
        val fields = LinkedProductReview.displayFields(envelope).map { it.path }
        assertTrue("product_candidates[product-1].product_name" in fields)
        assertTrue("product_candidates[product-1].brand_name" in fields)
        assertFalse("nutrition[label-1].product_name" in fields)
        assertFalse("nutrition[label-1].brand" in fields)
        assertNotNull(CanonicalFieldRegistry.descriptor(envelope, "nutrition[label-1].brand"))
    }

    @Test fun `only trimming is equivalent and names or brands with case or punctuation differences conflict`() {
        val original = fixture()
        val label = label(original)
        val trimmed = original.copy(nutrition = listOf(label.copy(draft = label.draft.copy(
            productName = "  Test cereal  ", brand = " Test brand ",
        ))))
        assertTrue(LinkedProductReview.facts(trimmed).all { it.status == ProductFactStatus.SAME })
        listOf("test cereal", "Test cereal.", "Testcereal").forEach { different ->
            val envelope = original.copy(nutrition = listOf(label.copy(draft = label.draft.copy(productName = different))))
            val fact = LinkedProductReview.facts(envelope).single { it.field == ProductFactField.NAME }
            assertEquals(ProductFactStatus.CONFLICT, fact.status)
            assertEquals(listOf("Test cereal", different), fact.sources.map { it.value })
            assertTrue(LinkedProductReview.hasConflicts(envelope))
        }
        val brands = original.copy(nutrition = listOf(label.copy(draft = label.draft.copy(brand = "Test Brand"))))
        assertEquals(ProductFactStatus.CONFLICT, LinkedProductReview.facts(brands).single { it.field == ProductFactField.BRAND }.status)
    }

    @Test fun `one sided brands stay incomplete with one control and no fabricated source value`() {
        val original = fixture()
        val product = original.productCandidates.single()
        val label = label(original)
        val envelope = original.copy(nutrition = listOf(label.copy(draft = label.draft.copy(brand = null))))
        val fact = LinkedProductReview.facts(envelope).single { it.field == ProductFactField.BRAND }
        assertEquals(ProductFactStatus.INCOMPLETE, fact.status)
        assertNull(fact.sources.last().value)
        val controller = CanonicalReviewController(envelope)
        assertTrue(controller.updateField(fact.primarySource.path, "Changed brand"))
        assertNull(label(controller.state.value.envelope).draft.brand)
        assertEquals(1, controller.state.value.edits.size)
        val reverse = original.copy(productCandidates = listOf(product.copy(brand = null)))
        val reverseFact = LinkedProductReview.facts(reverse).single { it.field == ProductFactField.BRAND }
        assertEquals("nutrition[label-1].brand", reverseFact.primarySource.path)
        assertFalse(LinkedProductReview.displayFields(reverse).any { it.path == "product_candidates[product-1].brand_name" })
        val reverseController = CanonicalReviewController(reverse)
        assertTrue(reverseController.updateField(reverseFact.primarySource.path, "Label only"))
        assertNull(reverseController.state.value.envelope.productCandidates.single().brand)
    }

    @Test fun `unlinked or missing product keys keep nutrition name and brand independent`() {
        val original = fixture()
        listOf(null, "missing-product").forEach { key ->
            val envelope = original.copy(nutrition = listOf(label(original).copy(productClientKey = key)))
            val paths = LinkedProductReview.displayFields(envelope).map { it.path }
            assertTrue("nutrition[label-1].product_name" in paths)
            assertTrue("nutrition[label-1].brand" in paths)
            assertTrue(LinkedProductReview.facts(envelope).none { it.linked })
        }
    }

    @Test fun `same strings on other products never share edits or dedup decisions`() {
        val original = fixture()
        val product = original.productCandidates.single()
        val label = label(original)
        val envelope = original.copy(
            productCandidates = listOf(product, product.copy(clientKey = "product-2")),
            nutrition = listOf(label, label.copy(clientKey = "label-2", productClientKey = "product-2"),
                label.copy(clientKey = "unlinked", productClientKey = null)),
        )
        assertEquals(4, LinkedProductReview.facts(envelope).size)
        assertTrue(LinkedProductReview.displayFields(envelope).any { it.path == "nutrition[unlinked].brand" })
        val controller = CanonicalReviewController(envelope)
        assertTrue(controller.updateField("product_candidates[product-1].brand_name", "Changed"))
        val after = controller.state.value.envelope
        assertEquals("Changed", (after.nutrition[0] as IngestionNutrition.ProductLabel).draft.brand)
        assertEquals("Test brand", (after.nutrition[1] as IngestionNutrition.ProductLabel).draft.brand)
        assertEquals("Test brand", (after.nutrition[2] as IngestionNutrition.ProductLabel).draft.brand)
        assertEquals("Test brand", after.productCandidates[1].brand)
    }

    @Test fun `same brand propagation is atomic audited and reversible and preserves evidence`() {
        val envelope = fixture()
        val label = label(envelope)
        val controller = CanonicalReviewController(envelope.copy(nutrition = listOf(label,
            label.copy(clientKey = "another", draft = label.draft.copy(brand = " Test brand ")))))
        val before = controller.state.value.envelope
        assertTrue(controller.updateField("product_candidates[product-1].brand_name", "Updated brand"))
        val after = controller.state.value.envelope
        assertEquals(listOf("Updated brand", "Updated brand"), after.nutrition.map { (it as IngestionNutrition.ProductLabel).draft.brand })
        assertEquals(3, controller.state.value.edits.size)
        assertTrue(controller.state.value.edits.all { it.valueType == CanonicalFieldType.TEXT })
        assertEquals(envelope.source, after.source)
        assertEquals(envelope.productCandidates.single().evidence, after.productCandidates.single().evidence)
        assertEquals(label.draft.evidence, label(after).draft.evidence)
        assertTrue(controller.undo())
        assertEquals(before.productCandidates, controller.state.value.envelope.productCandidates)
        assertEquals(before.nutrition, controller.state.value.envelope.nutrition)
        assertTrue(controller.redo())
        assertEquals(after.productCandidates, controller.state.value.envelope.productCandidates)
        assertEquals(after.nutrition, controller.state.value.envelope.nutrition)
    }

    @Test fun `conflicting brands are never overwritten by a normal product edit`() {
        val original = fixture()
        val conflicting = label(original).let { it.copy(draft = it.draft.copy(brand = "Independent brand")) }
        val controller = CanonicalReviewController(original.copy(nutrition = listOf(conflicting)))
        assertTrue(controller.updateField("product_candidates[product-1].brand_name", "New product brand"))
        assertEquals("Independent brand", label(controller.state.value.envelope).draft.brand)
        assertTrue(LinkedProductReview.hasConflicts(controller.state.value.envelope))
        assertEquals(1, controller.state.value.edits.size)
    }

    @Test fun `explicit conflict resolution selects either source or manual value with one undo checkpoint`() {
        ProductFactField.entries.forEach { field ->
            listOf("Product choice", "Label choice", "Manual choice").forEach { selected ->
                val original = fixture()
                val product = original.productCandidates.single()
                val label = label(original)
                val envelope = original.copy(
                    productCandidates = listOf(if (field == ProductFactField.NAME) product.copy(productName = "Product choice") else product.copy(brand = "Product choice")),
                    nutrition = listOf(label.copy(draft = if (field == ProductFactField.NAME) label.draft.copy(productName = "Label choice") else label.draft.copy(brand = "Label choice"))),
                )
                val controller = CanonicalReviewController(envelope)
                assertTrue(LinkedProductReview.hasConflicts(envelope))
                assertTrue(controller.resolveLinkedProductFact(product.clientKey, field, selected))
                val fact = LinkedProductReview.facts(controller.state.value.envelope).single { it.field == field }
                assertEquals(ProductFactStatus.SAME, fact.status)
                assertTrue(fact.sources.all { it.value == selected })
                assertFalse(LinkedProductReview.hasConflicts(controller.state.value.envelope))
                assertEquals(if (selected == "Manual choice") 2 else 1, controller.state.value.edits.size)
                assertTrue(controller.undo())
                assertEquals(envelope.nutrition, controller.state.value.envelope.nutrition)
                assertEquals(envelope.productCandidates, controller.state.value.envelope.productCandidates)
                assertTrue(controller.redo())
                assertFalse(LinkedProductReview.hasConflicts(controller.state.value.envelope))
            }
        }
    }

    @Test fun `resolution cannot create a link or mutate another product`() {
        val original = fixture()
        val controller = CanonicalReviewController(original)
        assertFalse(controller.resolveLinkedProductFact("missing-product", ProductFactField.NAME, "Wrong"))
        assertEquals(original, controller.state.value.envelope)
        assertTrue(controller.state.value.edits.isEmpty())
    }

    @Test fun `explicit link and review decisions survive a supported canonical JSON round trip`() {
        val original = fixture().copy(schemaVersion = com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V3_SCHEMA)
        val controller = CanonicalReviewController(original)
        assertTrue(controller.updateField("product_candidates[product-1].brand_name", "Updated brand"))
        val encoded = com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec.encodePersisted(controller.state.value.envelope)
        val restored = com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec.decode(encoded, "restored", preservePersistedVerification = true)
        assertEquals("product-1", label(restored).productClientKey)
        assertEquals(LinkedProductReview.facts(controller.state.value.envelope), LinkedProductReview.facts(restored))
        assertFalse(LinkedProductReview.displayFields(restored).any { it.path == "nutrition[label-1].brand" })
    }

    private fun label(envelope: YeonsikOcrEnvelope) = envelope.nutrition.first() as IngestionNutrition.ProductLabel

    private fun fixture(): YeonsikOcrEnvelope {
        val file = sequenceOf(File("examples/yeonsik-ocr.v2.packaged-product.example.json"), File("../examples/yeonsik-ocr.v2.packaged-product.example.json"))
            .first(File::isFile)
        val envelope = YeonsikOcrV2Json.decode(file.readText(), "linked-product-review")
        return envelope.copy(nutrition = listOf(label(envelope).copy(clientKey = "label-1", productClientKey = "product-1")),
            consumption = emptyList(), targets = envelope.targets - IngestionProjection.FITNESS_MEAL)
    }
}
