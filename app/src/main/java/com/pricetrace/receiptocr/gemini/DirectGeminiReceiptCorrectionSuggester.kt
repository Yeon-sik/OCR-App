package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionBatch
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionCandidate
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionFailureReason
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionOutcome
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionPrompt
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionProvider
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionRequest
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionSuggester
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionTarget
import com.pricetrace.receiptscanner.correction.ReceiptMerchantFieldSemantics
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceAssessment
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceVerdict
import com.pricetrace.receiptscanner.correction.ReceiptFieldCheck
import com.pricetrace.receiptscanner.correction.ReceiptFieldVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal class DirectGeminiReceiptCorrectionSuggester(
    private val apiKeyProvider: () -> String?,
    private val modelName: String = GeminiInteractionsProtocol.DEFAULT_MODEL,
    private val transport: GeminiHttpTransport = HttpsGeminiTransport(),
) : ReceiptCorrectionSuggester {
    override val provider: ReceiptCorrectionProvider
        get() {
            val configured = !apiKeyProvider().isNullOrBlank()
            return ReceiptCorrectionProvider(
                id = PROVIDER_ID,
                displayName = "Gemini API 직접 연결",
                model = modelName,
                isAvailable = configured,
                unavailableReason = if (configured) null else "Gemini API 키를 먼저 저장하세요.",
            )
        }

    override suspend fun suggest(request: ReceiptCorrectionRequest): ReceiptCorrectionOutcome {
        val apiKey = apiKeyProvider()?.trim()?.takeIf(String::isNotEmpty)
            ?: return ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.NOT_CONFIGURED)
        if (request.targets.isEmpty() || request.evidenceLines.isEmpty()) {
            return ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.NO_ELIGIBLE_EVIDENCE)
        }

        return try {
            val requestBody = GeminiInteractionsProtocol.createRequestBody(request, modelName)
            val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                transport.post(apiKey = apiKey, requestBody = requestBody)
            } ?: return ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.NETWORK, "Timeout")

            when (response.statusCode) {
                in 200..299 -> decodeSuccess(response.body, request)
                401, 403 -> ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.AUTHENTICATION)
                429 -> ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.RATE_LIMITED)
                408 -> ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.NETWORK)
                in 500..599 -> ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.PROVIDER)
                else -> ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.PROVIDER)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.NETWORK)
        } catch (_: Exception) {
            ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.PROVIDER)
        }
    }

    private fun decodeSuccess(
        responseBody: String,
        request: ReceiptCorrectionRequest,
    ): ReceiptCorrectionOutcome {
        val outputText = GeminiInteractionsProtocol.extractOutputText(responseBody)
            ?: return ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.INVALID_RESPONSE)
        val assessment = decodeAssessment(outputText, request)
            ?: return ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.INVALID_RESPONSE)
        val candidates = decodeCandidates(outputText, request)
            ?: return ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.INVALID_RESPONSE)
        if (assessment.verdict == ReceiptEvidenceVerdict.PLAUSIBLE && candidates.isNotEmpty()) {
            return ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.INVALID_RESPONSE)
        }
        return ReceiptCorrectionOutcome.Success(
            ReceiptCorrectionBatch(
                candidates = candidates,
                assessment = assessment,
                providerId = PROVIDER_ID,
                model = modelName,
                promptVersion = ReceiptCorrectionPrompt.VERSION,
            ),
        )
    }

    private fun decodeAssessment(
        outputText: String,
        request: ReceiptCorrectionRequest,
    ): ReceiptEvidenceAssessment? = runCatching {
        val root = json.parseToJsonElement(outputText).jsonObject
        val verdict = ReceiptEvidenceVerdict.fromWireValue(
            root.getValue("evidenceVerdict").jsonPrimitive.content.trim(),
        ) ?: return@runCatching null
        val merchantVerdict = root["merchantVerdict"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.let(ReceiptEvidenceVerdict::fromWireValue)
        val fieldChecks = root["fieldChecks"]?.jsonArray?.map { element ->
            val raw = element.jsonObject
            ReceiptFieldCheck(
                fieldPath = requireNotNull(raw["fieldPath"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)),
                verdict = requireNotNull(
                    raw["verdict"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.let(ReceiptFieldVerdict::fromWireValue),
                ),
                sourceLineIds = raw["sourceLineIds"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.distinct()
                    ?: emptyList(),
                reason = raw["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            )
        }.orEmpty()
        val expectedMerchantPaths = request.targets
            .map(ReceiptCorrectionTarget::fieldPath)
            .filter(ReceiptMerchantFieldSemantics::isMerchantFieldPath)
            .toSet()
        if (expectedMerchantPaths.isNotEmpty() &&
            (merchantVerdict == null ||
                fieldChecks.size != expectedMerchantPaths.size ||
                fieldChecks.map(ReceiptFieldCheck::fieldPath).toSet() != expectedMerchantPaths)
        ) {
            return@runCatching null
        }
        ReceiptEvidenceAssessment(
            verdict = verdict,
            merchantVerdict = merchantVerdict,
            fieldChecks = fieldChecks,
        )
    }.getOrNull()

    private fun decodeCandidates(
        outputText: String,
        request: ReceiptCorrectionRequest,
    ): List<ReceiptCorrectionCandidate>? = runCatching {
        val root = json.parseToJsonElement(outputText).jsonObject
        val rawCandidates = root["candidates"]?.jsonArray ?: return@runCatching null
        rawCandidates.take(MAX_CANDIDATES).mapIndexed { index, element ->
            val raw = element.jsonObject
            ReceiptCorrectionCandidate(
                id = "gemini_${request.documentId}_$index",
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
                promptVersion = ReceiptCorrectionPrompt.VERSION,
            )
        }
    }.getOrNull()

    companion object {
        const val PROVIDER_ID = "gemini-api-direct"
        private const val MAX_CANDIDATES = 12
        private const val REQUEST_TIMEOUT_MS = 45_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}

internal data class GeminiHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface GeminiHttpTransport {
    suspend fun post(apiKey: String, requestBody: String): GeminiHttpResponse
}

internal class HttpsGeminiTransport : GeminiHttpTransport {
    override suspend fun post(apiKey: String, requestBody: String): GeminiHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(GeminiInteractionsProtocol.ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                val bodyBytes = requestBody.toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                connection.outputStream.use { it.write(bodyBytes) }
                val statusCode = connection.responseCode
                val responseStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                GeminiHttpResponse(
                    statusCode = statusCode,
                    body = responseStream?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_RESPONSE_BYTES) throw IOException("Gemini response is too large")
                            output.write(buffer, 0, count)
                        }
                        output.toString(StandardCharsets.UTF_8.name())
                    }.orEmpty(),
                )
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 45_000
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
