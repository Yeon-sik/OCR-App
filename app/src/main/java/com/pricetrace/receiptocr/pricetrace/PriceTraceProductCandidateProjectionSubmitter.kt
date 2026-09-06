package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.PriceTraceProductIdentityJson
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Routes fact-only product observations to PriceTrace; identity is accepted only from its response. */
internal class PriceTraceProductCandidateProjectionSubmitter(
    private val gateway: PriceTraceCanonicalGateway,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        if (request.projection != IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE) {
            return ProjectionSubmission.Failure("unsupported_pricetrace_product_projection", retryable = false)
        }
        val candidates = request.envelope?.productCandidates
            ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        if (candidates.isEmpty()) {
            return ProjectionSubmission.Failure("product_candidate_missing", retryable = false)
        }
        return when (val result = gateway.submitProductCandidates(request.idempotencyKey, candidates)) {
            is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
                message = result.message ?: result.kind.name,
                retryable = result.kind.retryable,
            )
            is PriceTraceCanonicalOutcome.Success -> try {
                val identities = PriceTraceProductIdentityJson.tryDecode(
                    Json.encodeToString(JsonObject.serializer(), result.response),
                )
                require(identities.keys.all { it in candidates.map { candidate -> candidate.clientKey }.toSet() }) {
                    "PriceTrace product response contains an unknown product candidate key"
                }
                val remoteId = result.response.requiredProductId()
                ProjectionSubmission.Success(
                    remoteId = remoteId,
                    metadataJson = Json.encodeToString(JsonObject.serializer(), result.response),
                )
            } catch (error: Exception) {
                ProjectionSubmission.Failure("pricetrace_product_identity_invalid", retryable = false)
            }
        }
    }

    private fun JsonObject.requiredProductId(): String {
        val direct = (this["candidateId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
        if (direct != null) return direct
        val responseCandidate = (this["responses"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("candidateId")?.jsonPrimitive?.contentOrNull }
            ?.firstOrNull { it.isNotBlank() }
        if (responseCandidate != null) return responseCandidate
        return PriceTraceProductIdentityJson.tryDecode(
            Json.encodeToString(JsonObject.serializer(), this),
        ).values.firstOrNull()?.catalogProductId
            ?: error("PriceTrace product response did not return a candidate or catalog identity")
    }
}
