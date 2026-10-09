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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val useV3Contract = envelope.schemaVersion in setOf(
            YEONSIK_OCR_V3_SCHEMA, com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V5_SCHEMA,
        ) ||
            envelope.nutrition.any { item ->
                item is IngestionNutrition.ProductLabel &&
                    item.draft.sourceType == NutritionContract.EXTERNAL_REFERENCE_SOURCE_TYPE
            }

        val responses = mutableListOf<String>().apply {
            // Legacy-key recovery still needs the previous server-issued import selector
            // when an owner audit lookup or a later publication fails temporarily.
            if (request.recoverCanonicalImport) request.previousMetadataJson?.let(::add)
        }
        var lastFoodId: String? = null
        var reviewReason: String? = null
        val recoveryCache = RecoveryAuditCache()
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
                            ?: com.pricetrace.receiptscanner.ingestion.PurchaseNutritionIdentity.sellerName(envelope, item.clientKey)
                            ?: return ProjectionSubmission.Failure(
                                "restaurant_name_missing",
                                retryable = false,
                            )
                        identityRequiredForPublication = true
                        publicationIdentity = if (envelope.receipt != null) {
                            resolvedReceiptIdentity?.let { identity -> item.lineId?.let { sourceLineId ->
                                PriceTraceIdentityJson.exactRestaurantMenuForSourceLine(identity, sourceLineId)
                            } }
                        } else if (envelope.schemaVersion == com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V5_SCHEMA) {
                            com.pricetrace.receiptscanner.ingestion.PurchaseNutritionIdentity.exact(
                                envelope, item.clientKey, request.dependencyMetadataJson[IngestionProjection.PRICETRACE_PRICE_OBSERVATION],
                            )
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
                            // Private import facts stay identical when authority is later recovered.
                            // PT identity belongs to the separate publication request.
                        ).let { payload ->
                            val link = envelope.purchaseNutritionLinks.singleOrNull { it.nutritionClientKey == item.clientKey }
                            if (link == null) payload else payload.copy(provenance = JsonObject(payload.provenance + mapOf(
                                "purchase_record_client_key" to JsonPrimitive(link.purchaseRecordClientKey),
                                "purchase_line_key" to JsonPrimitive(link.purchaseLineKey),
                                "nutrition_client_key" to JsonPrimitive(link.nutritionClientKey),
                            )))
                        }
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
                var publicationKeySeed = itemKey
                val recoveredImport = if (request.recoverCanonicalImport && identityRequiredForPublication) {
                    if (publicationIdentity == null) {
                        reviewReason = reviewReason ?: "restaurant_menu_identity_requires_ocr_review:${item.clientKey}"
                        continue
                    }
                    when (val recovered = recoverImportedNutrition(request, item.clientKey, payload, recoveryCache, responses)) {
                        is ImportRecovery.Failure -> return recovered.result
                        is ImportRecovery.Success -> {
                            publicationKeySeed = recovered.audit.idempotencyKey
                            NutritionCanonicalImportOutcome.Success(
                                CanonicalNutritionImportResponse(
                                    canonicalImportId = recovered.audit.canonicalImportId,
                                    idempotentReplay = true,
                                    nutritionFoodId = recovered.audit.nutritionFoodId,
                                    inputContract = recovered.audit.inputContract,
                                    projectionSourceType = recovered.audit.projectionSourceType,
                                    projectionImportId = null,
                                    catalogProductId = null,
                                    estimationEvidenceId = null,
                                    visibility = "private",
                                ),
                                rawResponse = recovered.rawResponse,
                            )
                        }
                    }
                } else null
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
                    else -> when (val result = recoveredImport ?: if (useV3Contract) {
                        gateway.importCanonicalV3(payload)
                    } else {
                        gateway.importCanonical(payload)
                    }) {
                        is NutritionCanonicalImportOutcome.Success -> {
                            if (envelope.schemaVersion == com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V5_SCHEMA) {
                                responses += v5NutritionMetadata(result.rawResponse, item.clientKey)
                            } else if (recoveredImport == null) responses += result.rawResponse
                            lastFoodId = result.response.nutritionFoodId
                            if (identityRequiredForPublication && publicationIdentity == null) {
                                reviewReason = reviewReason ?: if (envelope.schemaVersion == com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V5_SCHEMA) {
                                    "pricetrace_purchase_line_identity_metadata_missing:${item.clientKey}"
                                } else "restaurant_menu_identity_requires_ocr_review:${item.clientKey}"
                            } else if (publicationIdentity != null) {
                                val publication = NutritionDiningOutPublicationPayload(
                                    idempotencyKey = StableIds.sha256("$publicationKeySeed|publication"),
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

    private class RecoveryAuditCache(var rows: List<NutritionCanonicalImportAudit>? = null)

    private sealed interface ImportRecovery {
        data class Success(val audit: NutritionCanonicalImportAudit, val rawResponse: String) : ImportRecovery
        data class Failure(val result: ProjectionSubmission.Failure) : ImportRecovery
    }

    private suspend fun recoverImportedNutrition(
        request: ProjectionRequest,
        clientKey: String,
        expected: CanonicalNutritionImportPayload,
        cache: RecoveryAuditCache,
        responses: MutableList<String>,
    ): ImportRecovery {
        val direct = gateway.findCanonicalImports(expected.idempotencyKey)
        if (direct is NutritionCanonicalAuditOutcome.Failure) return ImportRecovery.Failure(auditFailure(direct, responses))
        direct as NutritionCanonicalAuditOutcome.Success
        responses += direct.rawResponse
        val candidates = if (direct.rows.isNotEmpty()) {
            if (direct.rows.size != 1 || direct.rows.single().idempotencyKey != expected.idempotencyKey) {
                return ImportRecovery.Failure(recoveryReviewFailure("canonical_import_recovery_key_ambiguous:$clientKey", responses))
            }
            direct.rows
        } else {
            if (cache.rows == null) {
                val ids = cachedCanonicalImportIds(request.previousMetadataJson)
                if (ids.isEmpty()) return ImportRecovery.Failure(recoveryReviewFailure(
                    "canonical_import_recovery_missing:$clientKey; 기존 import 확인이 필요합니다.", responses,
                ))
                when (val lookup = gateway.readCanonicalImportsByIds(ids)) {
                    is NutritionCanonicalAuditOutcome.Failure -> return ImportRecovery.Failure(auditFailure(lookup, responses))
                    is NutritionCanonicalAuditOutcome.Success -> {
                        if (lookup.rows.any { it.canonicalImportId !in ids }) {
                            return ImportRecovery.Failure(recoveryReviewFailure("canonical_import_recovery_unrequested_id", responses))
                        }
                        cache.rows = lookup.rows
                        responses += lookup.rawResponse
                    }
                }
            }
            requireNotNull(cache.rows).filter {
                auditSourceMatches(it.sourceDocumentRef, requireNotNull(request.localDocumentId), clientKey)
            }
        }
        if (candidates.size != 1) return ImportRecovery.Failure(recoveryReviewFailure(
            "canonical_import_recovery_missing_or_ambiguous:$clientKey; 기존 import 확인이 필요합니다.", responses,
        ))
        val audit = candidates.single()
        if (!auditSourceMatches(audit.sourceDocumentRef, requireNotNull(request.localDocumentId), clientKey)) {
            return ImportRecovery.Failure(recoveryReviewFailure("canonical_import_recovery_source_mismatch:$clientKey", responses))
        }
        if (!audit.matchesCurrentDiningOutFacts(expected)) return ImportRecovery.Failure(recoveryReviewFailure(
            "canonical_import_recovery_facts_changed:$clientKey; 기존 영양값과 수정본이 달라 자동 공개할 수 없습니다.", responses,
        ))
        return ImportRecovery.Success(audit, Json.encodeToString(JsonObject.serializer(), audit.serverRow))
    }

    private fun cachedCanonicalImportIds(raw: String?): Set<String> {
        val root = raw?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return emptySet()
        val ids = mutableSetOf<String>()
        fun visit(value: JsonElement) {
            when (value) {
                is JsonArray -> value.forEach(::visit)
                is JsonObject -> {
                    (value["canonical_import_id"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(NutritionCanonicalAuditJson::isUuid)?.let(ids::add)
                    if (value.keys.containsAll(setOf("input_contract", "source_document_ref", "nutrition_food_id", "idempotency_key"))) {
                        (value["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(NutritionCanonicalAuditJson::isUuid)?.let(ids::add)
                    }
                    value.values.forEach(::visit)
                }
                else -> Unit
            }
        }
        visit(root)
        return ids.takeIf { it.size <= 100 }.orEmpty()
    }

    private fun auditSourceMatches(sourceRef: String, localDocumentId: String, clientKey: String): Boolean {
        // The legacy sourceRef factory replaces unsupported characters and truncates segments.
        // Its output cannot prove the original doc/client key when that transformation is lossy.
        val lossless = Regex("[A-Za-z0-9_.-]{1,120}")
        if (!lossless.matches(localDocumentId) || !lossless.matches(clientKey)) return false
        val match = Regex("^ocr-app://ingestion/([^/]+)/revision/([1-9][0-9]*)/nutrition/([^/]+)$")
            .matchEntire(sourceRef) ?: return false
        return match.groupValues[1] == localDocumentId && match.groupValues[3] == clientKey &&
            match.groupValues[2].toLongOrNull()?.let { it > 0 } == true
    }

    private fun NutritionCanonicalImportAudit.matchesCurrentDiningOutFacts(expected: CanonicalNutritionImportPayload): Boolean {
        if (inputContract != FOOD_ESTIMATE_V1 || !userVerified || projectionSourceType != "food_image_estimate") return false
        val expectedRpc = json.parseToJsonElement(expected.toRpcJson()).jsonObject
        fun equal(key: String): Boolean = NutritionCanonicalAuditJson.sameFacts(requestPayload[key], expectedRpc["p_$key"])
        if (!NutritionCanonicalAuditJson.sameFacts(requestPayload["idempotency_key"], JsonPrimitive(idempotencyKey)) ||
            !equal("input_contract") || !equal("user_verified")) return false
        if ((requestPayload["source_document_ref"] as? JsonPrimitive)?.contentOrNull != sourceDocumentRef) return false
        val facts = listOf("food_name", "category", "basis_amount", "basis_unit", "required_nutrients", "optional_nutrients", "estimation_evidence")
        if (facts.any { !equal(it) }) return false
        if (!NutritionCanonicalAuditJson.sameFacts(requiredNutrients, requestPayload["required_nutrients"]) ||
            !NutritionCanonicalAuditJson.sameFacts(optionalNutrients, requestPayload["optional_nutrients"]) ||
            !NutritionCanonicalAuditJson.sameFacts(nutrientProvenance, requestPayload["nutrient_provenance"]) ||
            !NutritionCanonicalAuditJson.sameFacts(provenance, requestPayload["provenance"])) return false
        val currentProvenance = expectedRpc["p_nutrient_provenance"] as? JsonObject ?: return false
        val normalizedExpected = JsonObject(currentProvenance.mapValues { (_, element) ->
            val nutrient = element as? JsonObject ?: return false
            JsonObject(nutrient.toMutableMap().apply {
                val refs = nutrient["evidence_refs"] as? JsonArray ?: return false
                put("evidence_refs", JsonArray(refs.map { ref ->
                    if ((ref as? JsonPrimitive)?.contentOrNull == "${expected.sourceDocumentRef}/photo") {
                        JsonPrimitive("$sourceDocumentRef/photo")
                    } else ref
                }))
            })
        })
        if (!NutritionCanonicalAuditJson.sameFacts(nutrientProvenance, normalizedExpected)) return false
        val identitySourceKeys = setOf("restaurant_name", "branch_name")
        return NutritionCanonicalAuditJson.sameFacts(
            JsonObject(provenance.filterKeys { it !in identitySourceKeys }),
            JsonObject(expected.provenance.filterKeys { it !in identitySourceKeys }),
        )
    }

    private fun recoveryReviewFailure(message: String, responses: List<String>) = ProjectionSubmission.Failure(
        message, retryable = false, requiresReview = true, metadataJson = responseMetadata(responses),
    )

    private fun auditFailure(result: NutritionCanonicalAuditOutcome.Failure, responses: List<String>): ProjectionSubmission.Failure =
        ProjectionSubmission.Failure(
            message = "canonical_import_recovery_read_failed:${result.message ?: result.reason.name}",
            retryable = result.reason.isRetryable(),
            requiresReview = !result.reason.isRetryable(),
            metadataJson = responseMetadata(responses),
        )

    /** Resolve the exact per-observation server response by its local request correlation key. */
    private fun standaloneIdentityFor(
        request: ProjectionRequest,
        envelope: com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope,
        priceObservationClientKey: String,
    ): PriceTraceRestaurantMenuIdentity? {
        val raw = request.dependencyMetadataJson[IngestionProjection.PRICETRACE_PRICE_OBSERVATION] ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val row = (root["observations"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.singleOrNull {
                runCatching { it["priceObservationClientKey"]?.jsonPrimitive?.content }.getOrNull() ==
                    priceObservationClientKey
            }
            ?: return null
        val response = row["response"] as? JsonObject ?: return null
        return PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(response)
    }

    private fun failure(message: String, retryable: Boolean, responses: List<String>): ProjectionSubmission.Failure =
        ProjectionSubmission.Failure(
            message = message,
            retryable = retryable,
            metadataJson = responseMetadata(responses),
        )

    private fun responseMetadata(responses: List<String>): String? {
        val serverResponses = linkedSetOf<JsonObject>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::collect)
                is JsonObject -> serverResponses += element
                else -> Unit
            }
        }
        responses.forEach { raw ->
            runCatching { Json.parseToJsonElement(raw) }.getOrNull()?.let(::collect)
        }
        // Flatten replayed arrays and deduplicate identical immutable responses so repeated
        // failures preserve selectors without nesting/growing the checkpoint on every retry.
        return serverResponses.takeIf { it.isNotEmpty() }?.let {
            json.encodeToString(JsonArray.serializer(), JsonArray(it.toList()))
        }
    }

    /** Local checkpoint correlation only; never changes the downstream RPC response contract. */
    private fun v5NutritionMetadata(raw: String, clientKey: String): String {
        fun annotate(value: JsonElement): JsonElement = when (value) {
            is JsonArray -> JsonArray(value.map(::annotate))
            is JsonObject -> JsonObject(value + ("nutrition_client_key" to JsonPrimitive(clientKey)))
            else -> value
        }
        return annotate(Json.parseToJsonElement(raw)).toString()
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
