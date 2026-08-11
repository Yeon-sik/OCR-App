package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReceiptExampleTest {
    @Test
    fun `documented synthetic receipt is a strict receipt v2`() {
        val candidates = listOf(
            File("examples/receipt.v2.example.json"),
            File("../examples/receipt.v2.example.json"),
        )
        val source = requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate documented example from ${File(".").absolutePath}"
        }.readText()

        val receipt = ReceiptV2Json.decode(source)

        assertEquals(TranscriptionStatus.USER_VERIFIED, receipt.document.source.transcriptionStatus)
        assertEquals(1_300L, receipt.totals.grandTotalAmountMinor)
        assertTrue(ReceiptV2Json.encodeCanonical(receipt).isNotBlank())
    }
}
