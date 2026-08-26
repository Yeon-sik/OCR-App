package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionEvidenceImage
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionEvidenceLine
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionRequest
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class GeminiInteractionsProtocolTest {
    @Test
    fun `request is stateless structured and does not contain an API key`() {
        val body = GeminiInteractionsProtocol.createRequestBody(request(), "gemini-test-model")
        val root = Json.parseToJsonElement(body).jsonObject

        assertEquals("gemini-test-model", root.getValue("model").jsonPrimitive.content)
        assertFalse(root.getValue("store").jsonPrimitive.boolean)
        assertFalse(root.getValue("background").jsonPrimitive.boolean)
        assertFalse(root.getValue("stream").jsonPrimitive.boolean)
        assertEquals(
            "application/json",
            root.getValue("response_format").jsonObject.getValue("mime_type").jsonPrimitive.content,
        )
        assertEquals(
            "object",
            root.getValue("response_format").jsonObject
                .getValue("schema").jsonObject
                .getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            listOf("plausible", "needs_review", "insufficient_evidence"),
            root.getValue("response_format").jsonObject
                .getValue("schema").jsonObject
                .getValue("properties").jsonObject
                .getValue("evidenceVerdict").jsonObject
                .getValue("enum").jsonArray
                .map { it.jsonPrimitive.content },
        )
        val image = root.getValue("input").jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "image" }
        assertNotNull(image)
        assertEquals("AQID", requireNotNull(image).getValue("data").jsonPrimitive.content)
        assertFalse(body.contains("test-secret-key"))
    }

    @Test
    fun `completed model output text is extracted`() {
        val response = """
            {
              "status": "completed",
              "steps": [
                {
                  "type": "model_output",
                  "content": [
                    {"type": "text", "text": "{\"evidenceVerdict\":\"plausible\",\"candidates\":[]}"}
                  ]
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            "{\"evidenceVerdict\":\"plausible\",\"candidates\":[]}",
            GeminiInteractionsProtocol.extractOutputText(response),
        )
    }

    @Test
    fun `non-completed response is rejected`() {
        assertEquals(null, GeminiInteractionsProtocol.extractOutputText("{\"status\":\"in_progress\",\"steps\":[]}"))
    }

    private fun request(): ReceiptCorrectionRequest = ReceiptCorrectionRequest(
        documentId = "doc-1",
        targets = listOf(
            ReceiptCorrectionTarget(
                lineItemId = "line-1",
                description = "테스트 상품",
                quantity = "1",
                unitPriceAmountMinor = 1_000,
                netAmountMinor = 1_000,
                sourceLineIds = listOf("ocr-1"),
            ),
        ),
        evidenceLines = listOf(ReceiptCorrectionEvidenceLine("ocr-1", 0, "테스트 상품 1,000")),
        evidenceImages = listOf(
            ReceiptCorrectionEvidenceImage(
                id = "crop-1",
                mimeType = "image/jpeg",
                bytes = byteArrayOf(1, 2, 3),
                sourceLineIds = listOf("ocr-1"),
            ),
        ),
    )
}
