package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.SyntheticFixtures
import com.pricetrace.receiptscanner.parser.GenericReceiptParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrDebugJsonTest {
    @Test
    fun `private OCR debug preserves blocks lines elements and bounding boxes`() {
        val original = SyntheticFixtures.ocrDocument()
        val parsed = GenericReceiptParser().parse(original)
        val encoded = OcrDebugJson.encode(original, parsed)

        val restored = OcrDebugJson.decode(encoded)

        assertEquals(original, restored)
        assertTrue(Json.parseToJsonElement(encoded).jsonObject.getValue("parse_evidence").jsonArray.isNotEmpty())
    }
}
