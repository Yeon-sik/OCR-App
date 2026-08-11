package com.pricetrace.receiptscanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptSourceTest {
    @Test
    fun `parser version note replaces only the previous parser version`() {
        val original = ReceiptSource(
            originalDocumentId = null,
            sourceImages = listOf("page"),
            transcriptionStatus = TranscriptionStatus.PARSED,
            notes = listOf("purchase_local_time=14:35:00", "parser_version=generic-parser.v4"),
            rawText = null,
        )

        val updated = original.withParserVersion("generic-parser.v6")

        assertEquals("generic-parser.v6", updated.parserVersion())
        assertEquals(listOf("purchase_local_time=14:35:00", "parser_version=generic-parser.v6"), updated.notes)
        assertNull(updated.withParserVersion(" ").parserVersion())
    }
}
