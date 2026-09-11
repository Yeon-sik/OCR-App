package com.pricetrace.receiptscanner.domain

import com.pricetrace.receiptscanner.export.ReceiptV2Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReceiptVatRegressionTest {
    @Test
    fun `vat stays in receipt totals and does not create a line conservation error`() {
        val source = sequenceOf(
            File("examples/receipt.v2.example.json"),
            File("../examples/receipt.v2.example.json"),
        ).firstOrNull(File::isFile)?.readText() ?: error("receipt example not found")
        val vatReceiptJson = source.replace("\r\n", "\n")
            .replace("\"unit_price_amount_minor\": 1500", "\"unit_price_amount_minor\": 13000")
            .replace("\"gross_amount_minor\": 1500", "\"gross_amount_minor\": 13000")
            .replace("\"net_amount_minor\": 1500", "\"net_amount_minor\": 13000")
            .replace("\"discount_amount_minor\": 200", "\"discount_amount_minor\": 0")
            .replace("\"net_amount_minor\": -200", "\"net_amount_minor\": 0")
            .replace("\"items_gross_amount_minor\": 1500", "\"items_gross_amount_minor\": 13000")
            .replace("\"grand_total_amount_minor\": 1300", "\"grand_total_amount_minor\": 13000")
            .replace("\"amount_minor\": 1300", "\"amount_minor\": 13000")
            .replace(
                "\"tax_amount_minor\": null,\n    \"fee_amount_minor\": null",
                "\"tax_amount_minor\": 1181,\n    \"fee_amount_minor\": null",
            )

        val receipt = ReceiptV2Json.decode(vatReceiptJson)
        val line = receipt.lineItems.single { it.id == "line_synthetic_product_001" }
        assertEquals(13_000L, line.unitPriceAmountMinor)
        assertEquals(13_000L, line.grossAmountMinor)
        assertEquals(null, line.taxAmountMinor)
        assertEquals(13_000L, line.netAmountMinor)
        assertEquals(1_181L, receipt.totals.taxAmountMinor)
        assertEquals(13_000L, receipt.totals.grandTotalAmountMinor)

        val validation = ReceiptValidator.validateForUserVerification(receipt)
        assertTrue(validation.reconciliation.isBalanced)
        assertTrue(validation.issues.none { it.code == ValidationCode.ITEM_AMOUNT_CONSERVATION_FAILED })
        assertTrue(validation.issues.none { it.code == ValidationCode.TOTAL_RECONCILIATION_FAILED })
    }
}
