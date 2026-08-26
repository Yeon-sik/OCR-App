package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionProvider
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionCandidate
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionFailureReason
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionOutcome
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionPrompt
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionRequest
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionSuggester
import com.pricetrace.receiptscanner.nutrition.NutritionEvidenceAssessment
import com.pricetrace.receiptscanner.nutrition.NutritionEvidenceVerdict
import com.pricetrace.receiptscanner.nutrition.NutritionFieldCheck
import com.pricetrace.receiptscanner.nutrition.NutritionFieldVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

internal class DirectGeminiNutritionCorrectionSuggester(
    private val apiKeyProvider: () -> String?,
    private val modelName: String = GeminiInteractionsProtocol.DEFAULT_MODEL,
    private val transport: GeminiHttpTransport = HttpsGeminiTransport(),
) : NutritionCorrectionSuggester {
    override val provider: ReceiptCorrectionProvider
        get() {
            val configured = !apiKeyProvider().isNullOrBlank()
            return ReceiptCorrectionProvider(
                id = PROVIDER_ID,
                displayName = "Gemini 영양성분 검토",
                model = modelName,
                isAvailable = configured,
                unavailableReason = if (configured) null else "Gemini API 키를 먼저 저장하세요.",
            )
        }

    override suspend fun suggest(request: NutritionCorrectionRequest): NutritionCorrectionOutcome {
        val apiKey = apiKeyProvider()?.trim()?.takeIf(String::isNotEmpty)
            ?: return NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.NOT_CONFIGURED)
        if (request.targets.isEmpty() || request.evidenceLines.isEmpty()) {
            return NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.NO_ELIGIBLE_EVIDENCE)
        }
        return try {
            val requestBody = NutritionGeminiInteractionsProtocol.createRequestBody(request, modelName)
            val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                transport.post(apiKey = apiKey, requestBody = requestBody)
            } ?: return NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.NETWORK, "Timeout")
            when (response.statusCode) {
                in 200..299 -> decodeSuccess(response.body, request)
                401, 403 -> NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.AUTHENTICATION)
                429 -> NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.RATE_LIMITED)
                408 -> NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.NETWORK)
                in 500..599 -> NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.PROVIDER)
                else -> NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.PROVIDER)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.NETWORK)
        } catch (_: Exception) {
            NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.PROVIDER)
        }
    }

    private fun decodeSuccess(
        responseBody: String,
        request: NutritionCorrectionRequest,
    ): NutritionCorrectionOutcome {
        val outputText = GeminiInteractionsProtocol.extractOutputText(responseBody)
            ?: return NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.INVALID_RESPONSE)
        val assessment = decodeAssessment(outputText, request)
            ?: return NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.INVALID_RESPONSE)
        val candidates = decodeCandidates(outputText, request)
            ?: return NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.INVALID_RESPONSE)
        if (assessment.verdict == NutritionEvidenceVerdict.PLAUSIBLE && candidates.isNotEmpty()) {
            return NutritionCorrectionOutcome.Failure(NutritionCorrectionFailureReason.INVALID_RESPONSE)
        }
        return NutritionCorrectionOutcome.Success(
            com.pricetrace.receiptscanner.nutrition.NutritionCorrectionBatch(
                candidates = candidates,
                assessment = assessment,
                providerId = PROVIDER_ID,
                model = modelName,
                promptVersion = NutritionCorrectionPrompt.VERSION,
            ),
        )
    }

    private fun decodeAssessment(
        outputText: String,
        request: NutritionCorrectionRequest,
    ): NutritionEvidenceAssessment? = runCatching {
        val root = json.parseToJsonElement(outputText).jsonObject
        val verdict = NutritionEvidenceVerdict.fromWireValue(
            root.getValue("evidenceVerdict").jsonPrimitive.content.trim(),
        ) ?: return@runCatching null
        val fieldChecks = root["fieldChecks"]?.jsonArray?.map { element ->
            val raw = element.jsonObject
            NutritionFieldCheck(
                fieldPath = requireNotNull(raw["fieldPath"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)),
                verdict = requireNotNull(
                    raw["verdict"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.let(NutritionFieldVerdict::fromWireValue),
                ),
                sourceLineIds = raw["sourceLineIds"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.distinct()
                    ?: emptyList(),
                reason = raw["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            )
        } ?: return@runCatching null
        val expectedPaths = request.targets.map { it.fieldPath }.toSet()
        if (fieldChecks.size != expectedPaths.size || fieldChecks.map(NutritionFieldCheck::fieldPath).toSet() != expectedPaths) {
            return@runCatching null
        }
        NutritionEvidenceAssessment(verdict = verdict, fieldChecks = fieldChecks)
    }.getOrNull()

    private fun decodeCandidates(
        outputText: String,
        request: NutritionCorrectionRequest,
    ): List<NutritionCorrectionCandidate>? = runCatching {
        val root = json.parseToJsonElement(outputText).jsonObject
        val rawCandidates = root["candidates"]?.jsonArray ?: return@runCatching null
        rawCandidates.take(MAX_CANDIDATES).mapIndexed { index, element ->
            val raw = element.jsonObject
            NutritionCorrectionCandidate(
                id = "gemini_nutrition_${request.documentId}_$index",
                fieldPath = raw.getValue("fieldPath").jsonPrimitive.content.trim(),
                oldValue = raw.getValue("oldValue").jsonPrimitive.content.trim().takeIf(String::isNotEmpty),
                proposedValue = raw.getValue("proposedValue").jsonPrimitive.content.trim(),
                sourceLineIds = raw.getValue("sourceLineIds").jsonArray
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .distinct(),
                confidencePercent = requireNotNull(raw.getValue("confidencePercent").jsonPrimitive.intOrNull),
                reason = raw.getValue("reason").jsonPrimitive.content.trim(),
                providerId = PROVIDER_ID,
                model = modelName,
                promptVersion = NutritionCorrectionPrompt.VERSION,
            )
        }
    }.getOrNull()

    companion object {
        const val PROVIDER_ID = "gemini-nutrition-api-direct"
        private const val MAX_CANDIDATES = 16
        private const val REQUEST_TIMEOUT_MS = 45_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
