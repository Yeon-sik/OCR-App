package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.PriceTraceProductIdentityJson
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException

/** Routes fact-only product observations to PriceTrace; identity is accepted only from its response. */
class PriceTraceProductCandidateProjectionSubmitter(
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
        val drafts = currentBrandDrafts(request.brandResolutionDrafts, candidates)
        if (drafts.size != request.brandResolutionDrafts.size) return ProjectionSubmission.Failure("stale_brand_resolution_draft",retryable=false,requiresReview=true)
        return when (val result = gateway.submitProductCandidates(request.idempotencyKey, candidates,
            PriceTraceCandidatePayload.acceptedKeys(request.previousMetadataJson,candidates))) {
            is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
                message = result.message ?: result.kind.name,
                retryable = result.kind.retryable,
                metadataJson = request.previousMetadataJson,
            )
            is PriceTraceCanonicalOutcome.Success -> try {
                val identities = PriceTraceProductIdentityJson.tryDecode(
                    Json.encodeToString(JsonObject.serializer(), result.response),
                )
                require(identities.keys.all { it in candidates.map { candidate -> candidate.clientKey }.toSet() }) {
                    "PriceTrace product response contains an unknown product candidate key"
                }
                val remoteId = result.response.requiredProductId()
                val recovered = mutableListOf<kotlinx.serialization.json.JsonObject>()
                val authorityKeys=PriceTraceCandidatePayload.acceptedKeys(result.response.toString())
                for (candidate in candidates) {
                    val intents=drafts.filter { it.productClientKey==candidate.clientKey && it.intent!=ProductBrandSelectionIntent.UNSET }
                        .sortedWith(compareBy({ it.intent != ProductBrandSelectionIntent.CLEAR }, { it.sourceField.ordinal }))
                    if(intents.isEmpty()) continue
                    val authorityKey=requireNotNull(authorityKeys[candidate.clientKey])
                    for(draft in intents) when(val mutation=gateway.setProductBrandResolution(authorityKey,draft)) {
                        is PriceTraceCanonicalOutcome.Failure -> return ProjectionSubmission.Failure(
                            mutation.message ?: mutation.kind.name,mutation.kind.retryable,
                            requiresReview=!mutation.kind.retryable,metadataJson=result.response.toString())
                        is PriceTraceCanonicalOutcome.Success -> Unit
                    }
                    when(val read=gateway.readProductBrandResolutions(authorityKey,candidate)) {
                        is PriceTraceCanonicalOutcome.Failure -> return ProjectionSubmission.Failure(read.message ?: read.kind.name,read.kind.retryable,
                            requiresReview=!read.kind.retryable,metadataJson=result.response.toString())
                        is PriceTraceCanonicalOutcome.Success -> {
                            val rows=(read.response["rows"] as kotlinx.serialization.json.JsonArray).map { it as JsonObject }
                            intents.forEach { draft ->
                                val row=rows.singleOrNull { it["source_field"]?.jsonPrimitive?.contentOrNull==draft.sourceField.wire }
                                require(if(draft.intent==ProductBrandSelectionIntent.CLEAR) row==null else row?.get("brand_id")?.jsonPrimitive?.contentOrNull==draft.brandId) { "brand_resolution_recovery_mismatch" }
                            }
                            recovered += rows.map { JsonObject(it + ("logicalProductClientKey" to kotlinx.serialization.json.JsonPrimitive(candidate.clientKey))) }
                        }
                    }
                }
                val combined=if(recovered.isEmpty() && drafts.isEmpty()) result.response else
                    JsonObject(result.response + ("brandResolutions" to kotlinx.serialization.json.JsonArray(recovered)))
                ProjectionSubmission.Success(
                    remoteId = remoteId,
                    metadataJson = Json.encodeToString(JsonObject.serializer(), combined),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ProjectionSubmission.Failure("pricetrace_product_identity_invalid", retryable = false,
                    requiresReview = true, metadataJson = result.response.toString())
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
