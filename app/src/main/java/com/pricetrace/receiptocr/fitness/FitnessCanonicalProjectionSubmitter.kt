package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray

/** Publishes only canonical Nutrition contracts for the integrated OCR envelope. */
internal class FitnessCanonicalProjectionSubmitter(
    private val gateway: NutritionSupabaseGateway,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        if (request.projection != IngestionProjection.FITNESS_NUTRITION) {
            return ProjectionSubmission.Failure("unsupported_fitness_projection", retryable = false)
        }
        val envelope = request.envelope
            ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        val localDocumentId = request.localDocumentId
            ?: return ProjectionSubmission.Failure("local_document_id_missing", retryable = false)
        if (envelope.nutrition.isEmpty()) {
            return ProjectionSubmission.Failure("nutrition_artifact_missing", retryable = false)
        }
        if (envelope.receipt != null && request.resolvedIdentity?.priceTrace == null) {
            return ProjectionSubmission.Failure("pricetrace_identity_missing", retryable = false)
        }
        val priceTraceIdentity = request.resolvedIdentity?.priceTrace?.let(PriceTraceIdentityJson::encode)

        val responses = mutableListOf<String>()
        var lastFoodId: String? = null
        return try {
            envelope.nutrition.forEach { item ->
                val itemKey = StableIds.sha256("${request.idempotencyKey}|nutrition|${item.clientKey}")
                val payload = when (item) {
                    is IngestionNutrition.ProductLabel -> CanonicalNutritionPayloadFactory.fromProductLabel(
                        localDocumentId = localDocumentId,
                        revisionSeq = request.revisionSeq,
                        idempotencyKey = itemKey,
                        draft = item.draft,
                        // submitProjection() verifies the persisted envelope fingerprint first.
                        envelopeVerified = false,
                        priceTraceIdentity = priceTraceIdentity,
                    )
                    is IngestionNutrition.RestaurantEstimate -> {
                        val restaurantName = envelope.receipt?.merchant?.name
                            ?: envelope.merchantCandidate?.name
                            ?: return ProjectionSubmission.Failure(
                                "restaurant_name_missing",
                                retryable = false,
                            )
                        CanonicalNutritionPayloadFactory.fromRestaurantEstimate(
                            localDocumentId = localDocumentId,
                            revisionSeq = request.revisionSeq,
                            idempotencyKey = itemKey,
                            restaurantName = restaurantName,
                            item = item,
                            priceTraceIdentity = priceTraceIdentity,
                        )
                    }
                    is IngestionNutrition.MealComponentEstimate -> {
                        val restaurantName = item.reference?.restaurantName
                            ?: envelope.receipt?.merchant?.name
                            ?: envelope.merchantCandidate?.name
                            ?: return ProjectionSubmission.Failure(
                                "restaurant_name_missing",
                                retryable = false,
                            )
                        CanonicalNutritionPayloadFactory.fromMealComponentEstimate(
                            localDocumentId = localDocumentId,
                            revisionSeq = request.revisionSeq,
                            idempotencyKey = itemKey,
                            restaurantName = restaurantName,
                            item = item,
                        )
                    }
                }
                when (item) {
                    is IngestionNutrition.MealComponentEstimate -> when (val result = gateway.importMealComponentEstimate(payload)) {
                        is NutritionMealComponentImportOutcome.Success -> {
                            responses += result.rawResponse
                            lastFoodId = result.response.nutritionFoodId
                        }
                        is NutritionMealComponentImportOutcome.Failure -> {
                            return ProjectionSubmission.Failure(
                                message = result.message ?: result.reason.name,
                                retryable = result.reason.isRetryable(),
                            )
                        }
                    }
                    else -> when (val result = gateway.importCanonical(payload)) {
                        is NutritionCanonicalImportOutcome.Success -> {
                            responses += result.rawResponse
                            lastFoodId = result.response.nutritionFoodId
                        }
                        is NutritionCanonicalImportOutcome.Failure -> {
                            return ProjectionSubmission.Failure(
                                message = result.message ?: result.reason.name,
                                retryable = result.reason.isRetryable(),
                            )
                        }
                    }
                }
            }
            ProjectionSubmission.Success(
                remoteId = requireNotNull(lastFoodId),
                metadataJson = json.encodeToString(
                    kotlinx.serialization.json.JsonArray.serializer(),
                    buildJsonArray { responses.forEach { add(Json.parseToJsonElement(it)) } },
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            ProjectionSubmission.Failure(error.message ?: "nutrition_contract_invalid", retryable = false)
        } catch (error: Exception) {
            ProjectionSubmission.Failure(error.message ?: "nutrition_projection_failed", retryable = true)
        }
    }

    private fun NutritionGatewayFailure.isRetryable(): Boolean = when (this) {
        NutritionGatewayFailure.NETWORK,
        NutritionGatewayFailure.RATE_LIMITED,
        NutritionGatewayFailure.SERVER -> true
        NutritionGatewayFailure.NOT_CONFIGURED,
        NutritionGatewayFailure.AUTHENTICATION,
        NutritionGatewayFailure.CONTRACT,
        NutritionGatewayFailure.CONFLICT -> false
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
