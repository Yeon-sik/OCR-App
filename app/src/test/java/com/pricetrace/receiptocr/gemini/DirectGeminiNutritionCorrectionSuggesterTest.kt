package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionEvidenceLine
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionFailureReason
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionOutcome
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionRequest
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionTarget
import com.pricetrace.receiptscanner.nutrition.NutritionEvidenceVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGeminiNutritionCorrectionSuggesterTest {
    @Test
    fun successfulResponseMapsNutritionCandidateWithoutPuttingKeyInBody() = runTest {
        var capturedBody: String? = null
        val suggester = DirectGeminiNutritionCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            modelName = "gemini-test-model",
            transport = GeminiHttpTransport { _, body ->
                capturedBody = body
                GeminiHttpResponse(200, completedResponse())
            },
        )

        val outcome = suggester.suggest(request())

        assertTrue(outcome is NutritionCorrectionOutcome.Success)
        val batch = (outcome as NutritionCorrectionOutcome.Success).batch
        assertEquals(NutritionEvidenceVerdict.NEEDS_REVIEW, batch.assessment.verdict)
        assertEquals("220", batch.candidates.single().proposedValue)
        assertFalse(requireNotNull(capturedBody).contains(TEST_API_KEY))
    }

    @Test
    fun missingKeyDoesNotCallTransport() = runTest {
        var called = false
        val suggester = DirectGeminiNutritionCorrectionSuggester(
            apiKeyProvider = { null },
            transport = GeminiHttpTransport { _, _ ->
                called = true
                GeminiHttpResponse(200, "")
            },
        )

        val outcome = suggester.suggest(request())

        assertEquals(
            NutritionCorrectionFailureReason.NOT_CONFIGURED,
            (outcome as NutritionCorrectionOutcome.Failure).reason,
        )
        assertFalse(called)
    }

    @Test
    fun plausibleResponseWithCandidateIsRejected() = runTest {
        val suggester = DirectGeminiNutritionCorrectionSuggester(
            apiKeyProvider = { TEST_API_KEY },
            transport = GeminiHttpTransport { _, _ ->
                GeminiHttpResponse(200, completedResponse().replace("needs_review", "plausible"))
            },
        )

        val outcome = suggester.suggest(request())

        assertEquals(
            NutritionCorrectionFailureReason.INVALID_RESPONSE,
            (outcome as NutritionCorrectionOutcome.Failure).reason,
        )
    }

    private fun request(): NutritionCorrectionRequest = NutritionCorrectionRequest(
        documentId = "nutrition-doc-1",
        targets = listOf(
            NutritionCorrectionTarget(
                fieldPath = "calories_kcal",
                currentValue = "210",
                sourceLineIds = listOf("line-calories"),
            ),
        ),
        evidenceLines = listOf(
            NutritionCorrectionEvidenceLine("line-calories", 0, "열량 210 kcal"),
        ),
    )

    private fun completedResponse(): String = """
        {
          "status": "completed",
          "steps": [
            {
              "type": "model_output",
              "content": [
                {"type": "text", "text": "{\"evidenceVerdict\":\"needs_review\",\"fieldChecks\":[{\"fieldPath\":\"calories_kcal\",\"verdict\":\"needs_review\",\"sourceLineIds\":[\"line-calories\"],\"reason\":\"열량 값 대조\"}],\"candidates\":[{\"fieldPath\":\"calories_kcal\",\"oldValue\":\"210\",\"proposedValue\":\"220\",\"sourceLineIds\":[\"line-calories\"],\"confidencePercent\":88,\"reason\":\"라벨 숫자 대조\"}]}"}
              ]
            }
          ]
        }
    """.trimIndent()

    private companion object {
        const val TEST_API_KEY = "test-key-with-at-least-twenty-characters"
    }
}
