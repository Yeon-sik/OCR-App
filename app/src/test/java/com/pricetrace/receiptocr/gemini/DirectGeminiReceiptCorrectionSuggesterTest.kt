package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionEvidenceLine
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionFailureReason
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionOutcome
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionRequest
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionTarget
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceVerdict
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
        assertEquals(ReceiptEvidenceVerdict.NEEDS_REVIEW, batch.assessment.verdict)
        assertEquals(1, batch.candidates.size)
        assertEquals("테스트상품", batch.candidates.single().proposedValue)
        assertEquals(TEST_API_KEY, capturedKey)
        assertFalse(requireNotNull(capturedBody).contains(TEST_API_KEY))
    }

    @Test
    fun merchantResponseParsesAllFieldChecks() = runTest {
        val suggester = DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            transport = GeminiHttpTransport { _, _ ->
                GeminiHttpResponse(200, completedResponse(merchantOutput()))
            },
        )

        val outcome = suggester.suggest(merchantRequest()) as ReceiptCorrectionOutcome.Success

        assertEquals(ReceiptEvidenceVerdict.PLAUSIBLE, outcome.batch.assessment.merchantVerdict)
        assertEquals(5, outcome.batch.assessment.fieldChecks.size)
        assertTrue(outcome.batch.assessment.fieldChecks.all {
            it.verdict.wireValue == "matches_evidence"
        })
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

    @Test
    fun `response without a preflight evidence verdict is rejected`() = runTest {
        val suggester = DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            transport = GeminiHttpTransport { _, _ ->
                GeminiHttpResponse(200, completedResponse("{\"candidates\":[]}"))
            },
        )

        val failure = suggester.suggest(request()) as ReceiptCorrectionOutcome.Failure

        assertEquals(ReceiptCorrectionFailureReason.INVALID_RESPONSE, failure.reason)
    }

    @Test
    fun `plausible verdict cannot contain correction candidates`() = runTest {
        val suggester = DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            transport = GeminiHttpTransport { _, _ ->
                GeminiHttpResponse(
                    200,
                    completedResponse(candidateOutput().replace("needs_review", "plausible")),
                )
            },
        )

        val failure = suggester.suggest(request()) as ReceiptCorrectionOutcome.Failure

        assertEquals(ReceiptCorrectionFailureReason.INVALID_RESPONSE, failure.reason)
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

    private fun merchantRequest(): ReceiptCorrectionRequest {
        val fields = listOf(
            "merchant.name" to "가상마트",
            "merchant.branch_name" to "서울점",
            "merchant.business_registration_number" to "123-45-67890",
            "merchant.address" to "서울특별시 강남구 테헤란로 1",
            "merchant.phone" to "010-1234-5678",
        )
        return ReceiptCorrectionRequest(
            documentId = "doc-merchant",
            targets = fields.map { (fieldPath, currentValue) ->
                ReceiptCorrectionTarget(
                    lineItemId = "merchant_identity",
                    description = null,
                    quantity = null,
                    unitPriceAmountMinor = null,
                    netAmountMinor = null,
                    sourceLineIds = listOf("ocr-merchant"),
                    fieldPath = fieldPath,
                    currentValue = currentValue,
                )
            },
            evidenceLines = listOf(
                ReceiptCorrectionEvidenceLine(
                    "ocr-merchant",
                    0,
                    "가상마트 서울점 주소 서울특별시 강남구 테헤란로 1 010-1234-5678",
                ),
            ),
        )
    }

    private fun merchantOutput(): String = """
        {
          "evidenceVerdict": "plausible",
          "merchantVerdict": "plausible",
          "fieldChecks": [
            {"fieldPath":"merchant.name","verdict":"matches_evidence","sourceLineIds":["ocr-merchant"],"reason":"판매처 근거 일치"},
            {"fieldPath":"merchant.branch_name","verdict":"matches_evidence","sourceLineIds":["ocr-merchant"],"reason":"지점 근거 일치"},
            {"fieldPath":"merchant.business_registration_number","verdict":"matches_evidence","sourceLineIds":["ocr-merchant"],"reason":"사업자번호 형식 일치"},
            {"fieldPath":"merchant.address","verdict":"matches_evidence","sourceLineIds":["ocr-merchant"],"reason":"주소 형식 일치"},
            {"fieldPath":"merchant.phone","verdict":"matches_evidence","sourceLineIds":["ocr-merchant"],"reason":"전화번호 형식 일치"}
          ],
          "candidates": []
        }
    """.trimIndent()

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
          "evidenceVerdict": "needs_review",
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
