package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YeonsikOcrExampleTest {
    @Test
    fun `checked in envelope examples are importable`() {
        val files = listOf(
            "yeonsik-ocr.merchant.example.json" to OcrWorkflowType.PRICE_TRACE_MERCHANT,
            "yeonsik-ocr.restaurant.example.json" to OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT,
            "yeonsik-ocr.packaged-product.example.json" to OcrWorkflowType.FITNESS_NUTRITION,
        )
        files.forEach { (name, workflow) ->
            val file = sequenceOf(File("examples", name), File("../examples", name))
                .firstOrNull(File::isFile) ?: error("example not found: $name")
            val outcome = ExternalJsonImporter().import(file.readText(), "example-$name", workflow)
            assertTrue("$name: $outcome", outcome is ExternalJsonImportOutcome.Success)
            assertTrue((outcome as ExternalJsonImportOutcome.Success).result.draft is CanonicalDraft.Envelope)
        }
    }
}
