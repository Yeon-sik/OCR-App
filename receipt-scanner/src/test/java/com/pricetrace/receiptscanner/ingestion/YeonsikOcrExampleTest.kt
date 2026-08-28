package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentType
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YeonsikOcrExampleTest {
    @Test
    fun `checked in envelope examples are importable without workflow selection`() {
        val files = listOf(
            "yeonsik-ocr.merchant.example.json",
            "yeonsik-ocr.restaurant.example.json",
            "yeonsik-ocr.packaged-product.example.json",
        )
        val importer = ExternalJsonImporter()
        val outcomes = files.associateWith { name ->
            val file = sequenceOf(File("examples", name), File("../examples", name))
                .firstOrNull(File::isFile) ?: error("example not found: $name")
            importer.import(file.readText(), "example-$name", workflowType = null)
        }
        outcomes.forEach { (name, outcome) ->
            assertTrue("$name: $outcome", outcome is ExternalJsonImportOutcome.Success)
            assertTrue((outcome as ExternalJsonImportOutcome.Success).result.draft is CanonicalDraft.Envelope)
        }

        val restaurant = ((outcomes.getValue("yeonsik-ocr.restaurant.example.json") as ExternalJsonImportOutcome.Success)
            .result.draft as CanonicalDraft.Envelope).value
        assertEquals("example-yeonsik-ocr.restaurant.example.json", restaurant.receipt!!.document.id)
        assertEquals(ReceiptFulfillmentType.DINE_IN, restaurant.receipt.document.fulfillment.type)
        assertEquals(
            listOf(FoodServiceRole.MAIN, FoodServiceRole.OPTION, FoodServiceRole.SIDE),
            restaurant.receipt.lineItems.map { it.foodService?.role },
        )
        assertEquals("line-1", restaurant.receipt.lineItems[1].foodService?.appliesToLineId)
        assertEquals(3, restaurant.nutrition.size)
        assertEquals(3, restaurant.links.size)
        assertEquals(
            setOf(
                IngestionProjection.PRICETRACE_RECEIPT,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.CASHOS_RECEIPT,
            ),
            restaurant.targets,
        )

        val packaged = ((outcomes.getValue("yeonsik-ocr.packaged-product.example.json") as ExternalJsonImportOutcome.Success)
            .result.draft as CanonicalDraft.Envelope).value
        val label = packaged.nutrition.single() as IngestionNutrition.ProductLabel
        assertEquals("parsed", label.draft.status.wireValue)
        assertEquals(null, label.draft.confirmedAt)
    }
}
