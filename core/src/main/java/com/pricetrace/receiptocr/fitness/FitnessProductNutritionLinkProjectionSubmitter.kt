package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.PriceTraceProductIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V3_SCHEMA
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement

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
 * Links reviewed receipt products directly; catalogue-only proposals retain their revision gate.
 */
class FitnessProductNutritionLinkProjectionSubmitter(
    private val nutritionGateway: NutritionSupabaseGateway,
    private val productRevisionReader: PriceTraceProductRevisionReader,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        if (request.projection != IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK) {
            return ProjectionSubmission.Failure("unsupported_fitness_product_link_projection", retryable = false)
        }
        val envelope = request.envelope
            ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        if (envelope.receipt != null) return submitReceiptProductLinks(request)
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
            pairCandidatesToNutrition(
                envelope.schemaVersion,
                envelope.productCandidates,
                nutritionItems,
                nutritionRows,
                resolvedProducts,
            )
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
                put("sourceSchema", JsonPrimitive(envelope.schemaVersion))
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
        schemaVersion: String,
        candidates: List<com.pricetrace.receiptscanner.ingestion.ProductCandidate>,
        nutritionItems: List<IngestionNutrition.ProductLabel>,
        nutritionRows: JsonArray,
        resolvedProducts: Map<String, PriceTraceProductIdentity>,
    ): List<Triple<com.pricetrace.receiptscanner.ingestion.ProductCandidate, String, PriceTraceProductIdentity>> {
        val candidateByKey = candidates.associateBy { it.clientKey }
        return nutritionItems.mapIndexed { index, nutrition ->
            // v3 must carry the explicit local relation. v1/v2 have no such wire field,
            // so retain their pre-v3 compatibility pairing without weakening v3.
            val productClientKey = nutrition.productClientKey ?: if (schemaVersion != YEONSIK_OCR_V3_SCHEMA) {
                candidateByKey[nutrition.clientKey]?.clientKey
                    ?: candidates.singleOrNull()?.clientKey
            } else {
                null
            }
                ?: throw IllegalArgumentException("product_nutrition_mapping_missing:${nutrition.clientKey}")
            val candidate = candidateByKey[productClientKey]
                ?: throw IllegalArgumentException("product_nutrition_mapping_missing:$productClientKey")
            val identity = resolvedProducts[candidate.clientKey]
                ?: throw IllegalArgumentException("pricetrace_product_identity_missing:${candidate.clientKey}")
            val response = nutritionRows[index].jsonObject
            val nutritionFoodId = response["nutrition_food_id"]?.let { it as? JsonPrimitive }?.content
                ?: throw IllegalArgumentException("nutrition_food_id_missing:${nutrition.clientKey}")
            Triple(candidate, nutritionFoodId, identity)
        }
    }

    private suspend fun submitReceiptProductLinks(request: ProjectionRequest): ProjectionSubmission {
        if (!request.receiptUserVerified) return ProjectionSubmission.Failure("receipt_ocr_review_required", false, requiresReview = true)
        val items = request.envelope!!.nutrition.filterIsInstance<IngestionNutrition.ProductLabel>()
        val metadata = request.dependencyMetadataJson[IngestionProjection.FITNESS_NUTRITION]
            ?: return ProjectionSubmission.Failure("fitness_nutrition_metadata_missing", true)
        val responses = mutableListOf<JsonElement>()
        var lastId: String? = null
        try {
            val rows = Json.parseToJsonElement(metadata).jsonArray.map { it.jsonObject }
                .filter { it["input_contract"]?.jsonPrimitive?.content in setOf("nutrition-label.v1", "external-reference.v1") }
            for (item in items) {
                val row = rows.singleOrNull { it["nutrition_client_key"]?.jsonPrimitive?.content == item.clientKey }
                    ?: rows.singleOrNull()?.takeIf { items.size == 1 }
                    ?: return ProjectionSubmission.Failure("receipt_nutrition_import_mapping_missing:${item.clientKey}", false, requiresReview = true)
                val payload = VerifiedReceiptProductNutritionPayload(
                    canonicalImportId = row.getValue("canonical_import_id").jsonPrimitive.content,
                    nutritionFoodId = row.getValue("nutrition_food_id").jsonPrimitive.content,
                    identity = VerifiedReceiptProductNutrition.identity(request, item),
                )
                when (val result = nutritionGateway.attachVerifiedReceiptProductNutrition(payload)) {
                    is VerifiedReceiptProductNutritionOutcome.Success -> { lastId = result.id; responses += Json.parseToJsonElement(result.rawResponse) }
                    is VerifiedReceiptProductNutritionOutcome.Failure -> return ProjectionSubmission.Failure(
                        result.message ?: result.reason.name, result.reason.isRetryable(), metadataJson = JsonArray(responses).toString())
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { return ProjectionSubmission.Failure(error.message ?: "receipt_product_nutrition_invalid", false, requiresReview = true) }
        return lastId?.let { ProjectionSubmission.Success(it, JsonArray(responses).toString()) }
            ?: ProjectionSubmission.Failure("nutrition_artifact_missing", false)
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
