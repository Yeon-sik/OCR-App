package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.PriceTraceProductIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class ProductRevisionReadResult(
    val revision: String,
)

sealed interface ProductRevisionReadOutcome {
    data class Success(val value: ProductRevisionReadResult) : ProductRevisionReadOutcome
    data class Failure(val reason: NutritionGatewayFailure, val message: String? = null) : ProductRevisionReadOutcome
}

fun interface PriceTraceProductRevisionReader {
    suspend fun readExactRevision(catalogProductId: String): ProductRevisionReadOutcome
}

/**
 * Creates a retryable Fitness-owned link proposal only after PriceTrace has resolved a product
 * identity and its exact product-read revision, and Fitness has created NutritionFood.
 */
internal class FitnessProductNutritionLinkProjectionSubmitter(
    private val nutritionGateway: NutritionSupabaseGateway,
    private val productRevisionReader: PriceTraceProductRevisionReader,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        if (request.projection != IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK) {
            return ProjectionSubmission.Failure("unsupported_fitness_product_link_projection", retryable = false)
        }
        val envelope = request.envelope
            ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        val resolvedProducts = request.resolvedIdentity?.productCandidates.orEmpty()
        if (resolvedProducts.isEmpty()) {
            return ProjectionSubmission.Failure("pricetrace_product_identity_missing", retryable = true)
        }
        val nutritionMetadata = request.dependencyMetadataJson[IngestionProjection.FITNESS_NUTRITION]
            ?: return ProjectionSubmission.Failure("fitness_nutrition_metadata_missing", retryable = true)
        val nutritionRows = try {
            Json.parseToJsonElement(nutritionMetadata).jsonArray
        } catch (error: Exception) {
            return ProjectionSubmission.Failure("fitness_nutrition_metadata_invalid", retryable = true)
        }
        val nutritionItems = envelope.nutrition.filterIsInstance<IngestionNutrition.ProductLabel>()
        if (nutritionItems.isEmpty() || nutritionRows.size != nutritionItems.size) {
            return ProjectionSubmission.Failure("product_nutrition_mapping_missing", retryable = false)
        }

        val pairs = try {
            pairCandidatesToNutrition(envelope.productCandidates, nutritionItems, nutritionRows, resolvedProducts)
        } catch (error: IllegalArgumentException) {
            return ProjectionSubmission.Failure(error.message ?: "product_nutrition_mapping_missing", retryable = false)
        }
        val rawResponses = mutableListOf<String>()
        var lastRemoteId: String? = null
        pairs.forEach { (candidate, nutritionFoodId, productIdentity) ->
            val revision = productIdentity.productRevision ?: when (
                val read = productRevisionReader.readExactRevision(productIdentity.catalogProductId)
            ) {
                is ProductRevisionReadOutcome.Success -> read.value.revision
                is ProductRevisionReadOutcome.Failure -> return ProjectionSubmission.Failure(
                    message = read.message ?: read.reason.name,
                    retryable = read.reason.isRetryable(),
                )
            }
            val source = buildJsonObject {
                put("namespace", JsonPrimitive("pricetrace"))
                put("catalogProductId", JsonPrimitive(productIdentity.catalogProductId))
                put("productRevision", JsonPrimitive(revision))
                put("candidateClientKey", JsonPrimitive(candidate.clientKey))
                put("sourceSchema", JsonPrimitive("yeonsik-ocr.v2"))
            }
            val payload = try {
                ProductNutritionLinkProposalPayload(
                    catalogProductId = productIdentity.catalogProductId,
                    nutritionFoodId = nutritionFoodId,
                    sourceRevision = revision,
                    source = source,
                )
            } catch (error: IllegalArgumentException) {
                return ProjectionSubmission.Failure(error.message ?: "product_revision_invalid", retryable = false)
            }
            when (val result = nutritionGateway.proposeProductNutritionLink(payload)) {
                is NutritionProductLinkOutcome.Success -> {
                    rawResponses += result.rawResponse
                    lastRemoteId = result.response.id
                }
                is NutritionProductLinkOutcome.Failure -> return ProjectionSubmission.Failure(
                    message = result.message ?: result.reason.name,
                    retryable = result.reason.isRetryable(),
                )
            }
        }
        return ProjectionSubmission.Success(
            remoteId = requireNotNull(lastRemoteId),
            metadataJson = Json.encodeToString(
                JsonArray.serializer(),
                JsonArray(rawResponses.map(Json::parseToJsonElement)),
            ),
        )
    }

    private fun pairCandidatesToNutrition(
        candidates: List<com.pricetrace.receiptscanner.ingestion.ProductCandidate>,
        nutritionItems: List<IngestionNutrition.ProductLabel>,
        nutritionRows: JsonArray,
        resolvedProducts: Map<String, PriceTraceProductIdentity>,
    ): List<Triple<com.pricetrace.receiptscanner.ingestion.ProductCandidate, String, PriceTraceProductIdentity>> {
        val candidateByKey = candidates.associateBy { it.clientKey }
        return nutritionItems.mapIndexed { index, nutrition ->
            val candidate = candidateByKey[nutrition.clientKey]
                ?: candidates.singleOrNull().takeIf { candidates.size == 1 }
                ?: throw IllegalArgumentException("product_nutrition_mapping_missing:${nutrition.clientKey}")
            val identity = resolvedProducts[candidate.clientKey]
                ?: throw IllegalArgumentException("pricetrace_product_identity_missing:${candidate.clientKey}")
            val response = nutritionRows[index].jsonObject
            val nutritionFoodId = response["nutrition_food_id"]?.let { it as? JsonPrimitive }?.content
                ?: throw IllegalArgumentException("nutrition_food_id_missing:${nutrition.clientKey}")
            Triple(candidate, nutritionFoodId, identity)
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
}
