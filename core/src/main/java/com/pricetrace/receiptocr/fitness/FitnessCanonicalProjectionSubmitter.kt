package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import com.pricetrace.receiptscanner.ingestion.PriceTraceRestaurantMenuIdentity
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V3_SCHEMA
import com.pricetrace.receiptscanner.nutrition.NutritionContract
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject

/** Publishes only canonical Nutrition contracts for the integrated OCR envelope. */
class FitnessCanonicalProjectionSubmitter(
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
        val resolvedReceiptIdentity = request.resolvedIdentity?.priceTrace
            ?: request.dependencyMetadataJson[IngestionProjection.PRICETRACE_RECEIPT]
                ?.let(PriceTraceIdentityJson::tryDecode)
        if (envelope.receipt != null && resolvedReceiptIdentity == null) {
            return ProjectionSubmission.Failure(
                "pricetrace_identity_requires_ocr_review",
                retryable = false,
                requiresReview = true,
            )
        }
        val priceTraceIdentity = resolvedReceiptIdentity?.let(PriceTraceIdentityJson::encode)
        // external-reference.v1 is implemented by Fitness's hierarchy-aware v3 RPC. Keep
        // product_label_ocr V2 calls on the legacy v2 boundary for backward compatibility.
        val useV3Contract = envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA ||
            envelope.nutrition.any { item ->
                item is IngestionNutrition.ProductLabel &&
                    item.draft.sourceType == NutritionContract.EXTERNAL_REFERENCE_SOURCE_TYPE
            }

        val responses = mutableListOf<String>()
        var lastFoodId: String? = null
        var reviewReason: String? = null
        return try {
            for (item in envelope.nutrition) {
                val itemKey = StableIds.sha256("${request.idempotencyKey}|nutrition|${item.clientKey}")
                var publicationIdentity: PriceTraceRestaurantMenuIdentity? = null
                var identityRequiredForPublication = false
                val payload = when (item) {
                    is IngestionNutrition.ProductLabel -> CanonicalNutritionPayloadFactory.fromProductLabel(
                        localDocumentId = localDocumentId,
                        revisionSeq = request.revisionSeq,
                        idempotencyKey = itemKey,
                        draft = item.draft,
                        // submitProjection() verifies the persisted envelope fingerprint first.
                        envelopeVerified = false,
                        priceTraceIdentity = priceTraceIdentity,
                        productCandidate = (item.productClientKey
                            ?: envelope.productCandidates.singleOrNull { candidate ->
                                candidate.clientKey == item.clientKey
                            }?.clientKey)?.let { productClientKey ->
                            envelope.productCandidates.singleOrNull { candidate ->
                                candidate.clientKey == productClientKey
                            } ?: return ProjectionSubmission.Failure(
                                "product_candidate_missing:$productClientKey",
                                retryable = false,
                            )
                        },
                        useV3Contract = useV3Contract,
                    )
                    is IngestionNutrition.RestaurantEstimate -> {
                        val restaurantName = envelope.receipt?.merchant?.name
                            ?: envelope.merchantCandidate?.name
                            ?: return ProjectionSubmission.Failure(
                                "restaurant_name_missing",
                                retryable = false,
                            )
                        identityRequiredForPublication = true
                        publicationIdentity = if (envelope.receipt != null) {
                            resolvedReceiptIdentity?.let { identity -> item.lineId?.let { sourceLineId ->
                                PriceTraceIdentityJson.exactRestaurantMenuForSourceLine(identity, sourceLineId)
                            } }
                        } else null
                        if (envelope.receipt != null && publicationIdentity == null) {
                            reviewReason = reviewReason ?: "restaurant_menu_identity_requires_ocr_review:${item.clientKey}"
                            continue
                        }
                        CanonicalNutritionPayloadFactory.fromRestaurantEstimate(
                            localDocumentId = localDocumentId,
                            revisionSeq = request.revisionSeq,
                            idempotencyKey = itemKey,
                            restaurantName = restaurantName,
                            item = item,
                            useV3Contract = useV3Contract,
                        )
                    }
                    is IngestionNutrition.RestaurantMenuEstimate -> {
                        val restaurantName = envelope.receipt?.merchant?.name
                            ?: envelope.merchantCandidate?.name
                            ?: return ProjectionSubmission.Failure("restaurant_name_missing", retryable = false)
                        identityRequiredForPublication = true
                        publicationIdentity = standaloneIdentityFor(
                            request = request,
                            envelope = envelope,
                            priceObservationClientKey = item.priceObservationClientKey,
                        )
                        CanonicalNutritionPayloadFactory.fromRestaurantMenuEstimate(
                            localDocumentId = localDocumentId,
                            revisionSeq = request.revisionSeq,
                            idempotencyKey = itemKey,
                            restaurantName = restaurantName,
                            item = item,
                            useV3Contract = useV3Contract,
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
                            return failure(result.message ?: result.reason.name, result.reason.isRetryable(), responses)
                        }
                    }
                    else -> when (val result = if (useV3Contract) {
                        gateway.importCanonicalV3(payload)
                    } else {
                        gateway.importCanonical(payload)
                    }) {
                        is NutritionCanonicalImportOutcome.Success -> {
                            responses += result.rawResponse
                            lastFoodId = result.response.nutritionFoodId
                            if (identityRequiredForPublication && publicationIdentity == null) {
                                reviewReason = reviewReason ?: "restaurant_menu_identity_requires_ocr_review:${item.clientKey}"
                            } else if (publicationIdentity != null) {
                                val publication = NutritionDiningOutPublicationPayload(
                                    idempotencyKey = StableIds.sha256("$itemKey|publication"),
                                    canonicalImportId = result.response.canonicalImportId,
                                    nutritionFoodId = result.response.nutritionFoodId,
                                    restaurantId = publicationIdentity.restaurantId,
                                    restaurantLocationId = publicationIdentity.restaurantLocationId,
                                    restaurantMenuId = publicationIdentity.restaurantMenuId,
                                    catalogProductId = publicationIdentity.catalogProductId,
                                )
                                when (val published = gateway.publishVerifiedDiningOutNutrition(publication)) {
                                    is NutritionDiningOutPublicationOutcome.Success -> {
                                        responses += published.rawResponse
                                        lastFoodId = published.response.nutritionFoodId
                                    }
                                    is NutritionDiningOutPublicationOutcome.Failure -> return failure(
                                        published.message ?: published.reason.name,
                                        published.reason.isRetryable(),
                                        responses,
                                    )
                                }
                            }
                        }
                        is NutritionCanonicalImportOutcome.Failure -> {
                            return failure(result.message ?: result.reason.name, result.reason.isRetryable(), responses)
                        }
                    }
                }
            }
            if (reviewReason != null) {
                return ProjectionSubmission.Failure(
                    message = reviewReason,
                    retryable = false,
                    requiresReview = true,
                    metadataJson = responseMetadata(responses),
                )
            }
            ProjectionSubmission.Success(
                remoteId = requireNotNull(lastFoodId),
                metadataJson = responseMetadata(responses),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            failure(error.message ?: "nutrition_contract_invalid", retryable = false, responses)
        } catch (error: Exception) {
            failure(error.message ?: "nutrition_projection_failed", retryable = true, responses)
        }
    }

    /** Standalone rows are returned in request order; the local client key selects that request index. */
    private fun standaloneIdentityFor(
        request: ProjectionRequest,
        envelope: com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope,
        priceObservationClientKey: String,
    ): PriceTraceRestaurantMenuIdentity? {
        val index = envelope.priceObservations.indexOfFirst { it.clientKey == priceObservationClientKey }
        if (index < 0) return null
        val raw = request.dependencyMetadataJson[IngestionProjection.PRICETRACE_PRICE_OBSERVATION] ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val row = (root["observations"] as? JsonArray)?.getOrNull(index) as? JsonObject ?: return null
        return PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(row)
    }

    private fun failure(message: String, retryable: Boolean, responses: List<String>): ProjectionSubmission.Failure =
        ProjectionSubmission.Failure(
            message = message,
            retryable = retryable,
            metadataJson = responseMetadata(responses),
        )

    private fun responseMetadata(responses: List<String>): String? = responses.takeIf { it.isNotEmpty() }?.let { rawResponses ->
        json.encodeToString(JsonArray.serializer(), buildJsonArray {
            rawResponses.forEach { add(Json.parseToJsonElement(it)) }
        })
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
