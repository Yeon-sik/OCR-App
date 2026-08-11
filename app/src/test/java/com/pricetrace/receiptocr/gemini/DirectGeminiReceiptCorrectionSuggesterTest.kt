package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionEvidenceLine
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionFailureReason
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionOutcome
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionRequest
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGeminiReceiptCorrectionSuggesterTest {
    @Test
    fun `successful response maps candidates and keeps key out of JSON body`() = runTest {
        var capturedKey: String? = null
        var capturedBody: String? = null
        val suggester = DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            modelName = "gemini-test-model",
            transport = GeminiHttpTransport { apiKey, body ->
                capturedKey = apiKey
                capturedBody = body
                GeminiHttpResponse(200, completedResponse(candidateOutput()))
            },
        )

        val outcome = suggester.suggest(request())

        assertTrue(outcome is ReceiptCorrectionOutcome.Success)
        val batch = (outcome as ReceiptCorrectionOutcome.Success).batch
        assertEquals("gemini-api-direct", batch.providerId)
        assertEquals("gemini-test-model", batch.model)
        assertEquals(1, batch.candidates.size)
        assertEquals("테스트상품", batch.candidates.single().proposedValue)
        assertEquals(TEST_API_KEY, capturedKey)
        assertFalse(requireNotNull(capturedBody).contains(TEST_API_KEY))
    }

    @Test
    fun `missing key returns not configured without calling transport`() = runTest {
        var called = false
        val suggester = DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = { null },
            transport = GeminiHttpTransport { _, _ ->
                called = true
                GeminiHttpResponse(200, "")
            },
        )

        val outcome = suggester.suggest(request())

        assertEquals(
            ReceiptCorrectionFailureReason.NOT_CONFIGURED,
            (outcome as ReceiptCorrectionOutcome.Failure).reason,
        )
        assertFalse(called)
        assertFalse(suggester.provider.isAvailable)
    }

    @Test
    fun `401 maps to authentication without exposing provider body`() = runTest {
        val suggester = DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            transport = GeminiHttpTransport { _, _ -> GeminiHttpResponse(401, "secret provider detail") },
        )

        val failure = suggester.suggest(request()) as ReceiptCorrectionOutcome.Failure

        assertEquals(ReceiptCorrectionFailureReason.AUTHENTICATION, failure.reason)
        assertEquals(null, failure.safeDetail)
    }

    @Test
    fun `429 maps to rate limited`() = runTest {
        val suggester = DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            transport = GeminiHttpTransport { _, _ -> GeminiHttpResponse(429, "") },
        )

        val failure = suggester.suggest(request()) as ReceiptCorrectionOutcome.Failure

        assertEquals(ReceiptCorrectionFailureReason.RATE_LIMITED, failure.reason)
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
    )

    private fun completedResponse(outputText: String): String = """
        {
          "status": "completed",
          "steps": [
            {
              "type": "model_output",
              "content": [
                {"type": "text", "text": ${jsonString(outputText)}}
              ]
            }
          ]
        }
    """.trimIndent()

    private fun candidateOutput(): String = """
        {
          "candidates": [
            {
              "fieldPath": "line_items[line-1].description",
              "oldValue": "테스트 상품",
              "proposedValue": "테스트상품",
              "sourceLineIds": ["ocr-1"],
              "confidencePercent": 88,
              "reason": "상품명 공백 교정"
            }
          ]
        }
    """.trimIndent()

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private companion object {
        const val TEST_API_KEY = "test-key-with-at-least-twenty-characters"
    }
}
