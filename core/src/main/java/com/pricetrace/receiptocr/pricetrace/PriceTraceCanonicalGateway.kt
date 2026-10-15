package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Contract
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Json
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Payload
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.OcrProjectionResponseReader
import com.pricetrace.receiptscanner.ingestion.OcrMerchantIdentityResolutionRequest
import com.pricetrace.receiptscanner.ingestion.OcrMerchantIdentityResolutionSubmitter
import com.pricetrace.receiptscanner.ingestion.OcrMenuIdentityResolutionSubmitter
import com.pricetrace.receiptscanner.ingestion.OcrReceiptMenuResolutionRequest
import com.pricetrace.receiptscanner.ingestion.OcrStandaloneMenuResolutionRequest
import com.pricetrace.receiptscanner.ingestion.OcrPurchaseMenuResolutionRequest
import com.pricetrace.receiptscanner.ingestion.OcrPurchaseIdentityResolutionSubmitter
import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.ingestion.ProductCandidateBarcode
import com.pricetrace.receiptscanner.ingestion.ProductCandidateEvidence
import com.pricetrace.receiptscanner.ingestion.StandalonePriceObservation
import com.pricetrace.receiptscanner.ingestion.StandalonePriceObservationKind
import com.pricetrace.receiptscanner.ingestion.PriceTraceV4SubmissionCompatibility
import com.pricetrace.receiptscanner.ingestion.PriceTraceV4SubmissionReason
import com.pricetrace.receiptscanner.ingestion.PurchaseNutritionIdentity
import com.pricetrace.receiptscanner.ingestion.*
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V5_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import com.pricetrace.receiptscanner.ingestion.PriceTraceProductIdentityJson
import com.pricetrace.receiptscanner.ingestion.isNormalPriceObservationCandidate
import com.pricetrace.receiptscanner.publisher.PriceObservationJson
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException

sealed interface PriceTraceCanonicalOutcome {
    data class Success(val response: JsonObject) : PriceTraceCanonicalOutcome
    data class Failure(val kind: PriceObservationFailureKind, val message: String? = null, val metadataJson: String? = null) : PriceTraceCanonicalOutcome
}

sealed interface PriceTraceProductReadOutcome {
    data class Success(val revision: String) : PriceTraceProductReadOutcome
    data class Failure(val kind: PriceObservationFailureKind, val message: String? = null) : PriceTraceProductReadOutcome
}

/** PriceTrace's verified receipt.v2 and merchant-only candidate RPC boundary. */
class PriceTraceCanonicalGateway(
    private val store: PriceTraceSupabaseStore,
    private val transport: PriceObservationHttpTransport = HttpsPriceObservationHttpTransport(),
) {
    /** Reads PT's saved response without reconstructing or submitting legacy receipt facts. */
    suspend fun readAcceptedReceiptResponse(receiptId: String): PriceTraceCanonicalOutcome =
        readAcceptedResponse { config ->
            require(java.util.UUID.fromString(receiptId).toString().equals(receiptId, ignoreCase = true)) {
                "server-issued receipt ID is invalid"
            }
            val response = transport.execute(request(
                config, "POST", "/rest/v1/rpc/get_verified_receipt_ingestion_response_v1",
                body = buildJsonObject { put("p_receipt_id", JsonPrimitive(receiptId)) }.encode(),
            ))
            if (response.statusCode !in 200..299) {
                return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response))
            }
            val saved = decodeResponse(response.body)
            require(saved.requiredStringOrNull("receiptId") == receiptId) { "PT saved receipt selector mismatch" }
            PriceTraceCanonicalOutcome.Success(saved)
        }

    /** Exact request keys and owner RLS correlate standalone rows; array position is never authority. */
    suspend fun readAcceptedStandaloneResponses(
        idempotencyKey: String,
        clientKeys: List<String>,
    ): PriceTraceCanonicalOutcome = readAcceptedResponse { config ->
        require(idempotencyKey.isNotBlank() && clientKeys.isNotEmpty() &&
            clientKeys.all(String::isNotBlank) && clientKeys.distinct().size == clientKeys.size) {
            "PT saved standalone selectors are invalid"
        }
        val savedRows = mutableListOf<JsonObject>()
        for (clientKey in clientKeys) {
            val itemKey = StableIds.sha256("$idempotencyKey|observation=$clientKey")
            val response = transport.execute(request(
                config, "GET",
                "/rest/v1/standalone_price_observation_ingestion_requests" +
                    "?select=idempotency_key,response&idempotency_key=eq.$itemKey&limit=2",
            ))
            if (response.statusCode !in 200..299) {
                return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response))
            }
            val rows = json.parseToJsonElement(response.body).jsonArray
            require(rows.size == 1) { "PT saved standalone selector is missing or ambiguous" }
            val row = rows.single().jsonObject
            require(row.requiredStringOrNull("idempotency_key") == itemKey) { "PT saved standalone key mismatch" }
            val saved = row["response"] as? JsonObject ?: error("PT saved standalone response missing")
            decodeStandaloneResponse(saved.encode())
            savedRows += buildJsonObject {
                put("priceObservationClientKey", JsonPrimitive(clientKey))
                put("response", saved)
            }
        }
        PriceTraceCanonicalOutcome.Success(buildJsonObject {
            put("schemaVersion", JsonPrimitive("receipt-independent-price-observation.v3"))
            put("observations", JsonArray(savedRows))
        })
    }

    /** Purchase UUIDs come exclusively from an accepted owner response, never canonical facts. */
    suspend fun readAcceptedPurchaseResponses(
        envelope: YeonsikOcrEnvelope,
        cached: JsonObject,
    ): PriceTraceCanonicalOutcome = readAcceptedResponse { config ->
        require(envelope.schemaVersion == YEONSIK_OCR_V5_SCHEMA)
        val records = envelope.purchaseRecords.filter { it.priceTraceSubmissionEligible }.associateBy { it.clientKey }
        val sources = (cached["sources"] as? JsonArray)?.map { it.jsonObject }
            ?: error("pricetrace_purchase_checkpoint_sources_missing")
        val keys = sources.map { it.requiredStringOrNull("purchaseRecordClientKey") }
        require(keys.distinct().size == keys.size && keys.toSet() == records.keys) {
            "pricetrace_purchase_checkpoint_client_key_mismatch"
        }
        val sourceIds = sources.map { requireNotNull(it.requiredStringOrNull("purchaseSourceId")) { "purchase source ID missing" } }
        require(sourceIds.distinct().size == sourceIds.size) { "pricetrace_purchase_checkpoint_source_duplicate" }
        val recovered = sources.map { source ->
            val key = requireNotNull(source.requiredStringOrNull("purchaseRecordClientKey"))
            val record = records.getValue(key)
            PurchaseNutritionIdentity.validateLineResults(record, source)
            if (PurchaseNutritionIdentity.hasAuthorityMetadata(source) &&
                PurchaseNutritionIdentity.pendingResolutions(JsonObject(mapOf("sources" to JsonArray(listOf(source)))).encode()).isEmpty()) source else {
                val sourceId = requireNotNull(source.requiredStringOrNull("purchaseSourceId"))
                require(java.util.UUID.fromString(sourceId).toString().equals(sourceId, ignoreCase = true)) {
                    "server-issued purchase source ID is invalid"
                }
                val response = transport.execute(request(
                    config, "POST", "/rest/v1/rpc/get_purchase_price_ingestion_response_v1",
                    body = buildJsonObject { put("p_purchase_source_id", JsonPrimitive(sourceId)) }.encode(),
                ))
                if (response.statusCode !in 200..299) {
                    return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response), response.body)
                }
                val saved = PriceTracePurchaseObservationV4Json.decodeResponse(response.body).raw
                require(saved.requiredStringOrNull("purchaseSourceId") == sourceId) { "PT saved purchase selector mismatch" }
                PurchaseNutritionIdentity.validateLineResults(record, saved)
                require(PurchaseNutritionIdentity.hasAuthorityMetadata(saved)) { "pricetrace_purchase_authority_metadata_missing" }
                JsonObject(saved + ("purchaseRecordClientKey" to JsonPrimitive(key)))
            }
        }
        val ids = recovered.flatMap { (it["observationIds"] as? JsonArray).orEmpty() }
        PriceTraceCanonicalOutcome.Success(JsonObject(cached + mapOf("sources" to JsonArray(recovered),
            "observationIds" to JsonArray(ids), "observationCount" to JsonPrimitive(ids.size),
            "observationCreated" to JsonPrimitive(ids.isNotEmpty()))))
    }

    suspend fun resolveOcrPurchaseMenu(request: OcrPurchaseMenuResolutionRequest): PriceTraceCanonicalOutcome =
        readAcceptedResponse { config ->
            require(java.util.UUID.fromString(request.purchaseSourceId).toString().equals(request.purchaseSourceId, true))
            request.selectedOwnerRestaurantId?.let {
                require(java.util.UUID.fromString(it).toString().equals(it, true))
                require(request.reviewedMerchant != null) { "private_restaurant_selection_requires_reviewed_merchant" }
            }
            // The orchestrator supplies these source facts only after checking the current review fingerprint.
            // A candidate response is never authority: the purchase-bound RPC independently checks ownership and facts.
            val candidateId = request.reviewedMerchant?.let { merchant ->
                val candidate = submitMerchantOnce(StableIds.sha256("purchase-merchant|${request.purchaseSourceId}|${merchant.name}|${merchant.branchName}"), merchant, config)
                if (candidate is PriceTraceCanonicalOutcome.Failure) return@readAcceptedResponse candidate
                requireNotNull((candidate as PriceTraceCanonicalOutcome.Success).response.requiredStringOrNull("candidateId"))
            }
            val rpc = if (candidateId == null) "resolve_ocr_purchase_restaurant_line_v1"
                else "resolve_user_confirmed_purchase_restaurant_line_v1"
            val response = transport.execute(request(config, "POST", "/rest/v1/rpc/$rpc",
                body = buildJsonObject {
                    put("p_purchase_source_id", JsonPrimitive(request.purchaseSourceId))
                    put("p_line_key", JsonPrimitive(request.lineKey))
                    put("p_user_verified", JsonPrimitive(true))
                    if (candidateId != null) put("p_merchant_candidate_id", JsonPrimitive(candidateId))
                    else if (request.sourceNamespace != null || request.sourceLocationCode != null) put("p_merchant", buildJsonObject {
                        put("source_namespace", request.sourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
                        put("source_location_code", request.sourceLocationCode?.let(::JsonPrimitive) ?: JsonNull)
                    })
                    request.selectedOwnerRestaurantId?.let { put("p_owner_restaurant_id", JsonPrimitive(it)) }
                    if (candidateId != null && request.resolutionAction != null) {
                        put("p_owner_restaurant_id", request.selectedOwnerRestaurantId?.let(::JsonPrimitive) ?: JsonNull)
                        put("p_owner_menu_id", request.selectedOwnerMenuId?.let(::JsonPrimitive) ?: JsonNull)
                        put("p_resolution_action", JsonPrimitive(request.resolutionAction))
                    }
                }.encode()))
            if (response.statusCode !in 200..299) return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response), response.body)
            var source = PriceTracePurchaseObservationV4Json.decodeResponse(response.body).raw
            require(source.requiredStringOrNull("purchaseSourceId") == request.purchaseSourceId)
            require(PurchaseNutritionIdentity.hasAuthorityMetadata(source)) { "pricetrace_purchase_authority_metadata_missing" }
            require((source["lineResults"] as? JsonArray)?.singleOrNull {
                it.jsonObject["lineKey"]?.jsonPrimitive?.contentOrNull == request.lineKey
            } != null) { "pricetrace_purchase_resolution_line_mismatch" }
            request.envelope?.let { source = synchronizeSourceComponents(source, request.purchaseRecordClientKey, it, config) }
            if (request.resolutionAction == "review" && request.resolvePlatform) {
                source = synchronizeSourcePlatform(source,config)
            }
            val bound = JsonObject(source + ("purchaseRecordClientKey" to JsonPrimitive(request.purchaseRecordClientKey)))
            PriceTraceCanonicalOutcome.Success(JsonObject(mapOf("sources" to JsonArray(listOf(bound)),
                "observationCreated" to source.getValue("observationCreated"),
                "observationIds" to source.getValue("observationIds"), "observationCount" to source.getValue("observationCount"))))
        }

    suspend fun reviewPurchaseIdentity(input: PurchaseIdentityReviewRequest): PriceTraceCanonicalOutcome = readAcceptedResponse { config ->
        val command = input.command
        var mutation: JsonObject? = null
        if (command.action != PurchaseIdentityAction.SEARCH) {
            if (command.action == PurchaseIdentityAction.SELECT && command.selectedKind == "global_verified") {
                require(command.entity != PurchaseIdentityEntity.PLATFORM)
                val response = transport.execute(request(config,"POST","/rest/v1/rpc/resolve_purchase_global_identity_v1",body = buildJsonObject {
                    put("p_purchase_source_id",input.purchaseSourceId); put("p_line_key",command.lineKey); put("p_entity",command.entity.wireValue)
                    put("p_identity_id",requireNotNull(command.selectedId)); put("p_user_verified",true)
                }.encode()))
                if (response.statusCode !in 200..299) return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response),response.body)
                var source = PriceTracePurchaseObservationV4Json.decodeResponse(response.body).raw
                require(source.requiredStringOrNull("purchaseSourceId") == input.purchaseSourceId)
                source = synchronizeSourceComponents(source,command.purchaseRecordClientKey,input.envelope,config)
                mutation = purchaseSourceMetadata(source,command.purchaseRecordClientKey)
            } else if (command.entity == PurchaseIdentityEntity.PLATFORM) {
                val response = transport.execute(request(config,"POST","/rest/v1/rpc/resolve_purchase_owner_platform_v1",body = buildJsonObject {
                    put("p_purchase_source_id", input.purchaseSourceId)
                    put("p_platform_id", command.selectedId?.let(::JsonPrimitive) ?: JsonNull)
                    put("p_create", command.action == PurchaseIdentityAction.CREATE); put("p_user_verified", true)
                }.encode()))
                if (response.statusCode !in 200..299) return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response),response.body)
                val source = PriceTracePurchaseObservationV4Json.decodeResponse(response.body).raw
                require(source.requiredStringOrNull("purchaseSourceId") == input.purchaseSourceId)
                mutation = purchaseSourceMetadata(source,command.purchaseRecordClientKey)
            } else {
                val record = input.envelope.purchaseRecords.single { it.clientKey == command.purchaseRecordClientKey }
                val line = record.lineItems.single { it.lineKey == command.lineKey }
                val merchant = MerchantCandidate(name = requireNotNull(line.sellerOverride ?: record.seller),
                    branchName = record.sellerBranchName.takeIf { line.sellerOverride == null },
                    businessKind = com.pricetrace.receiptscanner.domain.BusinessKind.FOOD_SERVICE,
                    sourceNamespace = record.sellerSourceNamespace.takeIf { line.sellerOverride == null },
                    sourceLocationCode = record.sellerSourceCode.takeIf { line.sellerOverride == null })
                val outcome = resolveOcrPurchaseMenu(OcrPurchaseMenuResolutionRequest(command.purchaseRecordClientKey,input.purchaseSourceId,command.lineKey,
                    reviewedMerchant = merchant,
                    selectedOwnerRestaurantId = command.selectedId.takeIf { command.entity == PurchaseIdentityEntity.RESTAURANT },
                    selectedOwnerMenuId = command.selectedId.takeIf { command.entity == PurchaseIdentityEntity.MENU },
                    resolutionAction = "${command.action.wireValue}_${command.entity.wireValue}", envelope = input.envelope))
                if (outcome is PriceTraceCanonicalOutcome.Failure) return@readAcceptedResponse outcome
                mutation = (outcome as PriceTraceCanonicalOutcome.Success).response
            }
        }
        val response = transport.execute(request(config,"POST","/rest/v1/rpc/search_purchase_owner_identity_v1",body = buildJsonObject {
            put("p_purchase_source_id",input.purchaseSourceId); put("p_line_key",command.lineKey)
            put("p_entity",command.entity.wireValue); put("p_query",command.query)
        }.encode()))
        if (response.statusCode !in 200..299) return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response),response.body)
        val review = Json.parseToJsonElement(response.body).jsonObject
        require(review["entity"] == JsonPrimitive(command.entity.wireValue) && review["query"] == JsonPrimitive(command.query))
        // Validate IDs and cardinality before they enter durable UI metadata.
        PurchaseIdentityReview.merge(null,command,review)
        PriceTraceCanonicalOutcome.Success(JsonObject((mutation ?: JsonObject(emptyMap())) + ("identityReview" to review)))
    }

    suspend fun synchronizePurchaseContext(input: OcrPurchaseMenuResolutionRequest): PriceTraceCanonicalOutcome = readAcceptedResponse { config ->
        val response = transport.execute(request(config,"POST","/rest/v1/rpc/get_purchase_price_ingestion_response_v1",body = buildJsonObject {
            put("p_purchase_source_id",input.purchaseSourceId)
        }.encode()))
        if (response.statusCode !in 200..299) return@readAcceptedResponse PriceTraceCanonicalOutcome.Failure(classify(response),response.body)
        var source = PriceTracePurchaseObservationV4Json.decodeResponse(response.body).raw
        require(source.requiredStringOrNull("purchaseSourceId") == input.purchaseSourceId)
        source = synchronizeSourceComponents(source,input.purchaseRecordClientKey,requireNotNull(input.envelope),config)
        if (input.resolvePlatform) source = synchronizeSourcePlatform(source,config)
        PriceTraceCanonicalOutcome.Success(purchaseSourceMetadata(source,input.purchaseRecordClientKey))
    }

    private suspend fun synchronizeSourcePlatform(source: JsonObject,config: PriceTraceSupabaseConfig): JsonObject {
        val response = transport.execute(request(config,"POST","/rest/v1/rpc/resolve_purchase_owner_platform_v1",body = buildJsonObject {
            put("p_purchase_source_id",source.getValue("purchaseSourceId")); put("p_platform_id",JsonNull); put("p_create",false); put("p_user_verified",true)
        }.encode()))
        if (response.statusCode !in 200..299) throw PurchaseContextFailure(PriceTraceCanonicalOutcome.Failure(classify(response),
            "pricetrace_platform_resolution_failed:${response.statusCode}"))
        val saved = PriceTracePurchaseObservationV4Json.decodeResponse(response.body).raw
        require(saved["purchaseSourceId"] == source["purchaseSourceId"])
        (saved["platformIdentity"] as? JsonObject)?.let { identity ->
            val id = identity.getValue("id").jsonPrimitive.content
            require(java.util.UUID.fromString(id).toString().equals(id,true) && identity["authorityStatus"] == JsonPrimitive("owner_confirmed"))
        }
        return JsonObject(saved + ("platformResolutionChecked" to JsonPrimitive(true)))
    }

    private fun purchaseSourceMetadata(source: JsonObject, recordKey: String): JsonObject = JsonObject(mapOf(
        "sources" to JsonArray(listOf(JsonObject(source + ("purchaseRecordClientKey" to JsonPrimitive(recordKey))))),
        "observationCreated" to source.getValue("observationCreated"),"observationIds" to source.getValue("observationIds"),
        "observationCount" to source.getValue("observationCount")))

    private suspend fun synchronizeSourceComponents(initial: JsonObject, recordKey: String, envelope: YeonsikOcrEnvelope, config: PriceTraceSupabaseConfig): JsonObject {
        var source = initial
        val fingerprint = StableIds.sha256(YeonsikOcrV5Json.canonicalize(envelope))
        val links = envelope.purchaseNutritionLinks.filter { it.purchaseRecordClientKey == recordKey && it.relationType == PurchaseNutritionRelation.MENU_COMPONENT }
        for ((lineKey, components) in links.groupBy { it.purchaseLineKey }) {
            val parent = source.getValue("lineResults").jsonArray.single { it.jsonObject["lineKey"] == JsonPrimitive(lineKey) }.jsonObject
            if (parent["observationCreated"] != JsonPrimitive(true)) continue
            val payload = JsonArray(components.map { link ->
                val item = envelope.nutrition.single { it.clientKey == link.nutritionClientKey }
                val name = when(item) { is IngestionNutrition.RestaurantEstimate -> item.menuName; is IngestionNutrition.MealComponentEstimate -> item.menuName; else -> error("component food estimate required") }
                buildJsonObject { put("nutritionClientKey",link.nutritionClientKey); put("name",name); put("role",link.componentRole.wireValue)
                    put("evidenceRefs",JsonArray(YeonsikOcrV5Json.foodEvidenceIds(item).sorted().map(::JsonPrimitive))) }
            })
            val saved = (source["menuComponents"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { it["canonicalFingerprint"] == JsonPrimitive(fingerprint) && it["lineKey"] == JsonPrimitive(lineKey) }
            fun validateSaved(rows: List<JsonObject>, parentRow: JsonObject) {
                require(rows.map { it["nutritionClientKey"] }.distinct().size == rows.size)
                require(rows.groupBy { it["id"] }.values.all { group -> group.map { listOf(it["name"],it["role"],it["parentOwnerMenuId"],it["parentRestaurantMenuId"]) }.distinct().size == 1 })
                rows.forEach { row ->
                    val expected = payload.map { it.jsonObject }.single { it["nutritionClientKey"] == row["nutritionClientKey"] }
                    val id = row.getValue("id").jsonPrimitive.content
                    require(java.util.UUID.fromString(id).toString().equals(id,true))
                    require(listOf("name","role","evidenceRefs").all { row[it] == expected[it] }) { "pricetrace_component_facts_mismatch" }
                    if (parentRow["authorityStatus"] == JsonPrimitive("owner_confirmed")) {
                        require(row["parentOwnerMenuId"] == parentRow.getValue("ownerConfirmedIds").jsonObject["restaurantMenuId"] && row["parentRestaurantMenuId"] == JsonNull)
                    } else {
                        require(parentRow["authorityStatus"] == JsonPrimitive("exact") && row["parentRestaurantMenuId"] == parentRow.getValue("authoritativeIds").jsonObject["restaurantMenuId"] && row["parentOwnerMenuId"] == JsonNull)
                    }
                }
            }
            validateSaved(saved,parent)
            if (saved.map { it["nutritionClientKey"] }.toSet() == components.map { JsonPrimitive(it.nutritionClientKey) }.toSet() && saved.size == components.size) continue
            val savedKeys = saved.map { it["nutritionClientKey"] }.toSet()
            for (batch in payload.filter { it.jsonObject["nutritionClientKey"] !in savedKeys }.chunked(100)) {
            val response = transport.execute(request(config,"POST","/rest/v1/rpc/register_purchase_menu_components_v1",body = buildJsonObject {
                put("p_purchase_source_id", source.getValue("purchaseSourceId")); put("p_line_key",lineKey)
                put("p_canonical_fingerprint",fingerprint); put("p_components",JsonArray(batch)); put("p_user_verified",true)
            }.encode()))
            if (response.statusCode !in 200..299) throw PurchaseContextFailure(PriceTraceCanonicalOutcome.Failure(classify(response),
                "pricetrace_component_registration_failed:${response.statusCode}"))
            val updated = PriceTracePurchaseObservationV4Json.decodeResponse(response.body).raw
            require(updated["purchaseSourceId"] == source["purchaseSourceId"])
            PurchaseNutritionIdentity.validateLineResults(envelope.purchaseRecords.single { it.clientKey == recordKey },updated)
            val rows = updated.getValue("menuComponents").jsonArray.map { it.jsonObject }.filter { it["canonicalFingerprint"] == JsonPrimitive(fingerprint) && it["lineKey"] == JsonPrimitive(lineKey) }
            validateSaved(rows,updated.getValue("lineResults").jsonArray.single { it.jsonObject["lineKey"] == JsonPrimitive(lineKey) }.jsonObject)
            source = updated
            }
            val finalRows = source.getValue("menuComponents").jsonArray.map { it.jsonObject }.filter { it["canonicalFingerprint"] == JsonPrimitive(fingerprint) && it["lineKey"] == JsonPrimitive(lineKey) }
            require(finalRows.size == components.size && finalRows.map { it["nutritionClientKey"] }.toSet() == components.map { JsonPrimitive(it.nutritionClientKey) }.toSet())
        }
        return source
    }

    private suspend fun readAcceptedResponse(
        read: suspend (PriceTraceSupabaseConfig) -> PriceTraceCanonicalOutcome,
    ): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        suspend fun safely(config: PriceTraceSupabaseConfig): PriceTraceCanonicalOutcome = try {
            read(config)
        } catch (error: PurchaseContextFailure) {
            error.outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
        } catch (_: IOException) {
            PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
        } catch (error: Exception) {
            PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
        }
        val first = safely(initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) return first
        val refreshed = refresh(initial) ?: return first
        return safely(refreshed)
    }

    private class PurchaseContextFailure(val outcome: PriceTraceCanonicalOutcome.Failure) : RuntimeException()

    suspend fun submitVerifiedReceipt(idempotencyKey: String, receipt: ReceiptV2): PriceTraceCanonicalOutcome {
        if (receipt.document.source.transcriptionStatus != TranscriptionStatus.USER_VERIFIED) {
            return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, "receipt_not_user_verified")
        }
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitReceiptOnce(idempotencyKey, receipt, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) return first
        val refreshed = refresh(initial) ?: return first
        return submitReceiptOnce(idempotencyKey, receipt, refreshed)
    }

    suspend fun submitMerchantCandidate(idempotencyKey: String, merchant: com.pricetrace.receiptscanner.ingestion.MerchantCandidate): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitMerchantOnce(idempotencyKey, merchant, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) return first
        val refreshed = refresh(initial) ?: return first
        return submitMerchantOnce(idempotencyKey, merchant, refreshed)
    }

    /** Uses only a PriceTrace-issued resolution id and authenticated owner session. */
    suspend fun resolveOcrMerchantIdentity(
        request: OcrMerchantIdentityResolutionRequest,
    ): PriceTraceCanonicalOutcome {
        require(request.resolutionId.isNotBlank()) { "PriceTrace resolutionId is required" }
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = resolveOcrMerchantIdentityOnce(request, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return resolveOcrMerchantIdentityOnce(request, refreshed)
    }

    suspend fun resolveOcrReceiptMenuIdentity(
        request: OcrReceiptMenuResolutionRequest,
    ): PriceTraceCanonicalOutcome {
        require(request.resolutionId.isNotBlank() && request.sourceLineId.isNotBlank()) {
            "PriceTrace receipt menu resolution identity is required"
        }
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = resolveOcrReceiptMenuIdentityOnce(request, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return resolveOcrReceiptMenuIdentityOnce(request, refreshed)
    }

    suspend fun resolveOcrStandaloneRestaurantMenu(
        request: OcrStandaloneMenuResolutionRequest,
    ): PriceTraceCanonicalOutcome {
        require(request.resolutionId.isNotBlank() && request.priceObservationClientKey.isNotBlank()) {
            "PriceTrace standalone menu resolution identity is required"
        }
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = resolveOcrStandaloneRestaurantMenuOnce(request, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return resolveOcrStandaloneRestaurantMenuOnce(request, refreshed)
    }

    suspend fun submitProductCandidates(
        idempotencyKey: String,
        candidates: List<ProductCandidate>,
    ): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitProductCandidatesOnce(idempotencyKey, candidates, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return submitProductCandidatesOnce(idempotencyKey, candidates, refreshed)
    }

    /** Publishes receipt-independent v3 price facts through PriceTrace's identity boundary. */
    suspend fun submitStandalonePriceObservations(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
    ): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitStandaloneOnce(idempotencyKey, envelope, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return submitStandaloneOnce(idempotencyKey, envelope, refreshed)
    }

    /** Publishes one or more purchase records through PriceTrace's confirmed V4 RPC. */
    suspend fun submitPurchasePriceObservationsV4(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
        contract: PriceTracePurchaseObservationV4Contract = PriceTracePurchaseObservationV4Contract(),
    ): PriceTraceCanonicalOutcome {
        if (envelope.purchaseRecords.any { it.priceTraceSourceEligible } &&
            envelope.purchaseRecords.none { it.priceTraceSubmissionEligible }
        ) {
            return PriceTraceCanonicalOutcome.Failure(
                PriceObservationFailureKind.CONTRACT,
                PriceTraceV4SubmissionCompatibility.incompatibilityReason(envelope.purchaseRecords)
                    ?: PriceTraceV4SubmissionReason.SUBMISSION_INCOMPATIBLE,
            )
        }
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitPurchaseV4Once(idempotencyKey, envelope, initial, contract)
        if (first !is PriceTraceCanonicalOutcome.Failure ||
            first.kind != PriceObservationFailureKind.AUTHENTICATION
        ) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return submitPurchaseV4Once(idempotencyKey, envelope, refreshed, contract)
    }

    /** Reads the exact PriceTrace product revision required by the cross-service link contract. */
    suspend fun readExactProductRevision(catalogProductId: String): PriceTraceProductReadOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = readExactProductRevisionOnce(catalogProductId, initial)
        if (first !is PriceTraceProductReadOutcome.Failure ||
            first.kind != PriceObservationFailureKind.AUTHENTICATION
        ) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return readExactProductRevisionOnce(catalogProductId, refreshed)
    }

    private suspend fun readExactProductRevisionOnce(
        catalogProductId: String,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceProductReadOutcome {
        return try {
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = "/rest/v1/rpc/get_product_read_v1",
                    body = PriceObservationJson.encodeProductReadRequest(
                        query = null,
                        limit = 1,
                        catalogProductId = catalogProductId,
                    ),
                ),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceProductReadOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
            }
            val read = PriceObservationJson.decodeProductRead(response.body)
            require(read.products.size == 1 && read.products.single().catalogProductId == catalogProductId) {
                "PriceTrace exact product read did not return the requested catalog product"
            }
            PriceTraceProductReadOutcome.Success(read.revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
        } catch (_: IOException) {
            PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.NETWORK)
        } catch (error: Exception) {
            PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
        }
    }

    private suspend fun submitReceiptOnce(
        idempotencyKey: String,
        receipt: ReceiptV2,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        val sanitized = receipt.copy(
            document = receipt.document.copy(
                source = receipt.document.source.copy(sourceImages = emptyList(), rawText = null),
            ),
            payments = receipt.payments.map { payment -> payment.copy(reference = null) },
        )
        require(sanitized.lineItems.flatMap { it.identifiers }.all { it.scheme == "merchant_sku" }) {
            "PriceTrace verified receipt accepts only merchant_sku identifiers"
        }
        val body = buildJsonObject {
            put("p_idempotency_key", JsonPrimitive(idempotencyKey))
            put("p_receipt", json.parseToJsonElement(ReceiptV2Json.encodeCanonical(sanitized)))
        }.encode()
        val response = transport.execute(request(config, "POST", "/rest/v1/rpc/submit_verified_receipt_v2", body = body))
        if (response.statusCode !in 200..299) {
            return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
        }
        PriceTraceCanonicalOutcome.Success(decodeResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun submitMerchantOnce(
        idempotencyKey: String,
        merchant: com.pricetrace.receiptscanner.ingestion.MerchantCandidate,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        val merchantJson = buildJsonObject {
            put("merchant_name", JsonPrimitive(merchant.name))
            put("branch_name", merchant.branchName?.let(::JsonPrimitive) ?: JsonNull)
            put("business_kind", JsonPrimitive(merchant.businessKind.wireValue))
            put("business_registration_number", merchant.businessRegistrationNumber?.let(::JsonPrimitive) ?: JsonNull)
            put("address", merchant.address?.let(::JsonPrimitive) ?: JsonNull)
            put("phone", merchant.phone?.let(::JsonPrimitive) ?: JsonNull)
            put("source_namespace", merchant.sourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
            put("source_location_code", merchant.sourceLocationCode?.let(::JsonPrimitive) ?: JsonNull)
        }
        val body = buildJsonObject {
            put("p_idempotency_key", JsonPrimitive(idempotencyKey))
            put("p_merchant", merchantJson)
            put("p_user_verified", JsonPrimitive(true))
        }.encode()
        val response = transport.execute(request(config, "POST", "/rest/v1/rpc/submit_merchant_identity_candidate_v1", body = body))
        if (response.statusCode !in 200..299) {
            return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
        }
        PriceTraceCanonicalOutcome.Success(decodeResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun resolveOcrMerchantIdentityOnce(
        resolution: OcrMerchantIdentityResolutionRequest,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        val merchant = resolution.merchant
        require(merchant.businessKind == com.pricetrace.receiptscanner.domain.BusinessKind.FOOD_SERVICE) {
            "OCR merchant resolution requires a food_service source fact"
        }
        val merchantJson = buildJsonObject {
            put("merchant_name", JsonPrimitive(merchant.name))
            put("branch_name", merchant.branchName?.let(::JsonPrimitive) ?: JsonNull)
            put("business_kind", JsonPrimitive(merchant.businessKind.wireValue))
            put("business_registration_number", merchant.businessRegistrationNumber?.let(::JsonPrimitive) ?: JsonNull)
            put("address", merchant.address?.let(::JsonPrimitive) ?: JsonNull)
            put("phone", merchant.phone?.let(::JsonPrimitive) ?: JsonNull)
            put("source_namespace", merchant.sourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
            put("source_location_code", merchant.sourceLocationCode?.let(::JsonPrimitive) ?: JsonNull)
        }
        val body = buildJsonObject {
            put("p_resolution_id", JsonPrimitive(resolution.resolutionId))
            put("p_merchant", merchantJson)
            put("p_user_verified", JsonPrimitive(true))
        }.encode()
        val response = transport.execute(
            request(config, "POST", "/rest/v1/rpc/resolve_ocr_merchant_identity_v1", body = body),
        )
        if (response.statusCode !in 200..299) {
            return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
        }
        PriceTraceCanonicalOutcome.Success(decodeResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun resolveOcrReceiptMenuIdentityOnce(
        resolution: OcrReceiptMenuResolutionRequest,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        val facts = resolution.menuFacts
        require(facts.itemName.isNotBlank()) { "OCR receipt menu resolution requires an item_name source fact" }
        val menuFacts = buildJsonObject {
            put("item_name", JsonPrimitive(facts.itemName))
            put("serving_label", facts.servingLabel?.let(::JsonPrimitive) ?: JsonNull)
            put("category_label", facts.categoryLabel?.let(::JsonPrimitive) ?: JsonNull)
            put("source_product_code_namespace", facts.sourceMenuCodeNamespace?.let(::JsonPrimitive) ?: JsonNull)
            put("source_product_code", facts.sourceMenuCode?.let(::JsonPrimitive) ?: JsonNull)
        }
        val body = buildJsonObject {
            put("p_resolution_id", JsonPrimitive(resolution.resolutionId))
            put("p_menu_facts", menuFacts)
            put("p_user_verified", JsonPrimitive(true))
        }.encode()
        val response = transport.execute(
            request(config, "POST", "/rest/v1/rpc/resolve_ocr_receipt_menu_identity_v1", body = body),
        )
        if (response.statusCode !in 200..299) {
            return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
        }
        PriceTraceCanonicalOutcome.Success(decodeReceiptMenuResolutionResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun resolveOcrStandaloneRestaurantMenuOnce(
        resolution: OcrStandaloneMenuResolutionRequest,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        val merchant = resolution.merchant
        val facts = resolution.menuFacts
        require(merchant.name.isNotBlank() && facts.itemName.isNotBlank()) {
            "OCR standalone menu resolution requires merchant and item source facts"
        }
        val merchantJson = buildJsonObject {
            put("merchant_name", JsonPrimitive(merchant.name))
            put("branch_name", merchant.branchName?.let(::JsonPrimitive) ?: JsonNull)
            put("source_namespace", merchant.sourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
            put("source_location_code", merchant.sourceLocationCode?.let(::JsonPrimitive) ?: JsonNull)
            put("business_registration_number", merchant.businessRegistrationNumber?.let(::JsonPrimitive) ?: JsonNull)
            put("address", merchant.address?.let(::JsonPrimitive) ?: JsonNull)
            put("phone", merchant.phone?.let(::JsonPrimitive) ?: JsonNull)
        }
        val itemJson = buildJsonObject {
            put("item_name", JsonPrimitive(facts.itemName))
            put("serving_label", facts.servingLabel?.let(::JsonPrimitive) ?: JsonNull)
            put("category_label", facts.categoryLabel?.let(::JsonPrimitive) ?: JsonNull)
            put("source_menu_code_namespace", facts.sourceMenuCodeNamespace?.let(::JsonPrimitive) ?: JsonNull)
            put("source_menu_code", facts.sourceMenuCode?.let(::JsonPrimitive) ?: JsonNull)
        }
        val body = buildJsonObject {
            put("p_resolution_id", JsonPrimitive(resolution.resolutionId))
            put("p_merchant", merchantJson)
            put("p_item", itemJson)
            put("p_user_verified", JsonPrimitive(true))
        }.encode()
        val response = transport.execute(
            request(config, "POST", "/rest/v1/rpc/resolve_ocr_standalone_restaurant_menu_v1", body = body),
        )
        if (response.statusCode !in 200..299) {
            return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
        }
        PriceTraceCanonicalOutcome.Success(decodeStandaloneMenuResolutionResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun submitProductCandidatesOnce(
        idempotencyKey: String,
        candidates: List<ProductCandidate>,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        require(candidates.isNotEmpty()) { "product_candidates_required" }
        val responses = mutableListOf<JsonObject>()
        candidates.forEach { candidate ->
            val candidateKey = StableIds.sha256("$idempotencyKey|candidate=${candidate.clientKey}")
            val body = buildJsonObject {
                put("p_idempotency_key", JsonPrimitive(candidateKey))
                put("p_candidate", productCandidateJson(candidate))
            }.encode()
            // The RPC is a PriceTrace-owned contract. The OCR-App sends facts only;
            // CatalogProduct IDs and product revisions are accepted only from its response.
            val response = transport.execute(
                request(config, "POST", "/rest/v1/rpc/submit_product_candidate_v1", body = body),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceCanonicalOutcome.Failure(
                    classify(response),
                    response.body.takeIf(String::isNotBlank),
                )
            }
            responses += decodeResponse(response.body)
        }
        val products = candidates.zip(responses).mapNotNull { (candidate, response) ->
            response.requiredStringOrNull("catalogProductId", "catalog_product_id")?.let { catalogId ->
                buildJsonObject {
                    put("clientKey", JsonPrimitive(candidate.clientKey))
                    put("catalogProductId", JsonPrimitive(catalogId))
                    put("productRevision", response["productRevision"] ?: response["product_revision"] ?: JsonNull)
                }
            }
        }
        PriceTraceCanonicalOutcome.Success(buildJsonObject {
            put("schemaVersion", JsonPrimitive("product-candidate.v1"))
            put("contract", JsonPrimitive("PRICETRACE_PRODUCT_CANDIDATE"))
            put("responses", JsonArray(responses))
            put("products", JsonArray(products))
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun submitStandaloneOnce(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        require(envelope.priceObservations.isNotEmpty()) { "standalone_price_observation_missing" }
        require(envelope.merchantCandidate != null) { "merchant_candidate_missing" }
        require(envelope.priceObservations.all { it.netAmountMinor != null }) {
            "price_observation_net_amount_required"
        }
        val responses = mutableListOf<Pair<String, JsonObject>>()
        envelope.priceObservations.forEach { observation ->
            val observationKey = StableIds.sha256("$idempotencyKey|observation=${observation.clientKey}")
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = "/rest/v1/rpc/ingest_verified_standalone_price_observation_v1",
                    body = buildJsonObject {
                        put("p_idempotency_key", JsonPrimitive(observationKey))
                        put("p_observation", standaloneObservationJson(observation, envelope))
                    }.encode(),
                ),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
            }
            // Pair each server response with the exact local request key at the call site. The
            // response array order is never used later to infer Nutrition identity.
            responses += observation.clientKey to decodeStandaloneResponse(response.body)
        }
        PriceTraceCanonicalOutcome.Success(buildJsonObject {
            put("schemaVersion", JsonPrimitive("receipt-independent-price-observation.v3"))
            put("observations", JsonArray(responses.map { (clientKey, response) ->
                buildJsonObject {
                    put("priceObservationClientKey", JsonPrimitive(clientKey))
                    put("response", response)
                }
            }))
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun submitPurchaseV4Once(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
        config: PriceTraceSupabaseConfig,
        contract: PriceTracePurchaseObservationV4Contract,
    ): PriceTraceCanonicalOutcome = try {
        val records = envelope.purchaseRecords.filter { it.priceTraceSubmissionEligible }
        require(records.isNotEmpty()) { "purchase_price_observation_missing" }
        val responses = records.map { record ->
            val recordKey = StableIds.sha256("$idempotencyKey|purchase_record=${record.clientKey}")
            val payload = PriceTracePurchaseObservationV4Payload(
                contract = contract,
                purchaseRecord = record,
                verificationBasis = envelope.review.verificationBasis,
            )
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = contract.rpcPath,
                    body = buildJsonObject {
                        put("p_idempotency_key", JsonPrimitive(recordKey))
                        put("p_purchase", payload.toJson())
                    }.encode(),
                ),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceCanonicalOutcome.Failure(
                    classify(response),
                    response.body.takeIf(String::isNotBlank),
                )
            }
            val decoded = PriceTracePurchaseObservationV4Json.decodeResponse(response.body)
            if (envelope.schemaVersion == YEONSIK_OCR_V5_SCHEMA) {
                PurchaseNutritionIdentity.validateLineResults(record, decoded.raw)
                decoded.copy(raw = JsonObject(decoded.raw + ("purchaseRecordClientKey" to JsonPrimitive(record.clientKey))))
            } else decoded
        }
        val accepted = buildJsonObject {
            put("schemaVersion", JsonPrimitive("purchase-price-observation.v4"))
            put("contractVersion", JsonPrimitive("purchase-price.v4"))
            put("sourceSaved", JsonPrimitive(true))
            put("observationCreated", JsonPrimitive(responses.any { it.observationCreated }))
            put(
                "observationCount",
                JsonPrimitive(responses.sumOf { it.observationIds.size }),
            )
            put("purchaseSourceIds", JsonArray(responses.map {
                JsonPrimitive(it.purchaseSourceId)
            }))
            put("observationIds", JsonArray(responses.flatMap { it.observationIds }.map(::JsonPrimitive)))
            put("sources", JsonArray(responses.map { it.raw }))
        }
        if (PurchaseNutritionIdentity.needsAuthorityRecovery(envelope, accepted.encode())) {
            when (val recovery = readAcceptedPurchaseResponses(envelope, accepted)) {
                is PriceTraceCanonicalOutcome.Success -> recovery
                is PriceTraceCanonicalOutcome.Failure -> recovery.copy(metadataJson = accepted.encode())
            }
        } else PriceTraceCanonicalOutcome.Success(accepted)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private fun standaloneObservationJson(
        observation: StandalonePriceObservation,
        envelope: YeonsikOcrEnvelope,
    ): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive("receipt-independent-price-observation.v3"))
        put("contract_version", JsonPrimitive("price-observation.v3"))
        put("kind", JsonPrimitive(observation.kind.wireValue))
        put("verification_basis", JsonPrimitive(
            when (envelope.review.verificationBasis) {
                com.pricetrace.receiptscanner.ingestion.VerificationBasis.SOURCE_EVIDENCE -> "source_evidence"
                com.pricetrace.receiptscanner.ingestion.VerificationBasis.MANUAL_CANONICAL_REVIEW -> "manual_canonical_review"
            },
        ))
        put("transcription_status", JsonPrimitive("user_verified"))
        observation.observedOn?.let { put("observed_on", JsonPrimitive(it)) }
        observation.observedAt?.let { put("observed_at", JsonPrimitive(it)) }
        put("currency", JsonPrimitive(observation.currency))
        put("gross_price", observation.grossAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("discount", observation.discountAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("net_price", observation.netAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("quantity", observation.quantity?.let { quantity ->
            val expectedUnit = when (observation.kind) {
                StandalonePriceObservationKind.RETAIL_PURCHASE -> "each"
                StandalonePriceObservationKind.RESTAURANT_PURCHASE -> "serving"
            }
            require(quantity.unit == expectedUnit) {
                "PriceTrace standalone quantity unit must be $expectedUnit"
            }
            JsonPrimitive(quantity.value.toLong())
        } ?: JsonNull)
        put("unit_price", observation.unitPriceAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("merchant", merchantObservationJson(envelope, observation.kind))
        when (observation.kind) {
            StandalonePriceObservationKind.RETAIL_PURCHASE -> {
                val candidate = envelope.productCandidates.singleOrNull {
                    it.clientKey == observation.productClientKey
                } ?: error("retail_product_candidate_missing")
                put("product", retailProductObservationJson(candidate))
            }
            StandalonePriceObservationKind.RESTAURANT_PURCHASE -> {
                put("item", buildJsonObject {
                    put("item_name", JsonPrimitive(requireNotNull(observation.itemName)))
                    put("serving_label", JsonPrimitive("1회 제공"))
                    put("category_label", JsonPrimitive("restaurant"))
                })
            }
        }
    }

    private fun merchantObservationJson(
        envelope: YeonsikOcrEnvelope,
        kind: StandalonePriceObservationKind,
    ): JsonObject {
        val merchant = requireNotNull(envelope.merchantCandidate)
        return buildJsonObject {
            put("merchant_name", JsonPrimitive(merchant.name))
            merchant.branchName?.let { put("branch_name", JsonPrimitive(it)) }
            merchant.sourceNamespace?.let { put("source_namespace", JsonPrimitive(it)) }
            merchant.sourceLocationCode?.let {
                put(
                    if (kind == StandalonePriceObservationKind.RETAIL_PURCHASE) "source_code" else "source_location_code",
                    JsonPrimitive(it),
                )
            }
            merchant.businessRegistrationNumber?.let { put("business_registration_number", JsonPrimitive(it)) }
            merchant.address?.let { put("address", JsonPrimitive(it)) }
            merchant.phone?.let { put("phone", JsonPrimitive(it)) }
        }
    }

    private fun retailProductObservationJson(candidate: ProductCandidate): JsonObject = buildJsonObject {
        put("product_client_key", JsonPrimitive(candidate.clientKey))
        put("merchant_sku", candidate.merchantSku?.let(::JsonPrimitive) ?: JsonNull)
        put("product_name", JsonPrimitive(candidate.productName))
        put("brand", candidate.brand?.let(::JsonPrimitive) ?: JsonNull)
        put("sub_brand", candidate.subBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", candidate.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("specification", candidate.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("variant", candidate.variant?.let(::JsonPrimitive) ?: JsonNull)
        put("identifiers", JsonArray(candidate.barcodes.map { barcode -> buildJsonObject {
            put("scheme", JsonPrimitive(priceTraceIdentifierScheme(barcode)))
            put("value", JsonPrimitive(barcode.value.filterNot { it == ' ' || it == '-' }))
        }}))
    }

    private fun productCandidateJson(candidate: ProductCandidate): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive("PRICETRACE_PRODUCT_CANDIDATE"))
        put("contract_version", JsonPrimitive("product-candidate.v1"))
        put("source_app", JsonPrimitive("pricetrace_ocr_app"))
        put("client_key", JsonPrimitive(candidate.clientKey))
        put("sub_brand", candidate.subBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("source_version", candidate.sourceVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("candidate_type", JsonPrimitive(candidate.candidateType))
        put("product_name", JsonPrimitive(candidate.productName))
        put("brand", candidate.brand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", candidate.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("specification", candidate.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("content_amount", candidate.contentAmount?.let(::JsonPrimitive) ?: JsonNull)
        put("content_unit", candidate.contentUnit?.let(::JsonPrimitive) ?: JsonNull)
        put("package_count", candidate.packageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("variant", candidate.variant?.let(::JsonPrimitive) ?: JsonNull)
        put("identifiers", JsonArray(candidateIdentifiers(candidate)))
        put("evidence", JsonArray(candidate.evidence.mapNotNull { evidence ->
            val field = priceTraceProductCandidateEvidenceField(evidence)
            if (field == null) {
                null
            } else {
                val sourceRef = evidence.sourceRef?.takeIf(String::isNotBlank)
                    ?: evidence.source?.takeIf(String::isNotBlank)
                    ?: evidence.sourceAttachmentIds.firstOrNull()
                    ?: error("product candidate evidence source_ref is required")
                buildJsonObject {
                    put("source_type", JsonPrimitive(evidence.sourceType))
                    put("source_ref", JsonPrimitive(sourceRef))
                    put("field", JsonPrimitive(field))
                    put("observed_value", evidence.observedValue?.let(::JsonPrimitive) ?: JsonNull)
                    put("content_hash", evidence.contentHash?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }))
        put("provenance", buildJsonObject {
            candidate.evidence.firstOrNull()?.sourceAttachmentIds?.firstOrNull()
                ?.let { put("capture_id", JsonPrimitive(it)) }
            put("extraction_method", JsonPrimitive("mixed"))
            put("extractor", JsonPrimitive("ocr-app"))
            candidate.sourceVersion?.let { put("extractor_version", JsonPrimitive(it)) }
            candidate.sourceVersion?.let { put("source_revision", JsonPrimitive(it)) }
        })
    }

    /** Normalize only the V4 order-history aliases at the PriceTrace boundary. */
    private fun priceTraceProductCandidateEvidenceField(
        evidence: ProductCandidateEvidence,
    ): String? = if (evidence.sourceType != "order_history") {
        evidence.field
    } else {
        when (evidence.field) {
            "brand_name" -> "brand"
            "manufacturer_name" -> "manufacturer"
            "variant_name" -> "variant"
            "specification_text" -> "specification"
            "sub_brand_name" -> "sub_brand"
            "barcodes" -> null
            else -> evidence.field
        }
    }

    private fun candidateIdentifiers(candidate: ProductCandidate): List<JsonObject> {
        return candidate.barcodes.map { barcode -> buildJsonObject {
            put("scheme", JsonPrimitive(priceTraceIdentifierScheme(barcode)))
            put("value", JsonPrimitive(barcode.value))
        }}
    }

    /** Convert the Project's barcode observation type to PriceTrace's existing identifier scheme. */
    private fun priceTraceIdentifierScheme(barcode: ProductCandidateBarcode): String =
        when (barcode.type.lowercase().replace("-", "").replace("_", "")) {
            "ean", "ean8", "ean13" -> "ean"
            "upc", "upca", "upce" -> "upc"
            "gtin", "gtin8", "gtin12", "gtin13", "gtin14", "barcode" -> "gtin"
            else -> when (barcode.value.filterNot { it == ' ' || it == '-' }.length) {
                8, 13 -> "ean"
                12 -> "upc"
                14 -> "gtin"
                else -> error("unsupported product candidate barcode type: ${barcode.type}")
            }
        }

    private fun decodeResponse(value: String): JsonObject {
        val element = json.parseToJsonElement(value)
        val row = when (element) {
            is JsonObject -> element
            else -> element.jsonArray.single().jsonObject
        }
        require(listOf("receiptId", "candidateId", "catalogProductId").any { row[it] is JsonPrimitive }) {
            "PriceTrace canonical response is missing receiptId/candidateId/catalogProductId"
        }
        return row
    }

    /** PT menu-resolution RPCs return either an exact owner response or an OCR review envelope. */
    private fun decodeReceiptMenuResolutionResponse(value: String): JsonObject {
        val row = decodeJsonObject(value)
        val hasReceipt = (row["receiptId"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        val pendingResolution = (row["status"] as? JsonPrimitive)?.contentOrNull
            .equals("needs_ocr_resolution", ignoreCase = true) &&
            (row["resolutionId"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        require(hasReceipt || pendingResolution) {
            "PriceTrace receipt menu resolution response is neither exact nor reviewable"
        }
        return row
    }

    private fun decodeStandaloneMenuResolutionResponse(value: String): JsonObject {
        val row = decodeJsonObject(value)
        val observationId = sequenceOf("observationId", "observation_id", "priceObservationId", "id")
            .mapNotNull { key -> (row[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .firstOrNull()
        val exact = row["kind"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            .equals("restaurant_purchase", ignoreCase = true) &&
            observationId != null && PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(row) != null
        val ocrResolution = PriceTraceIdentityJson.ocrResolution(row.encode())
        val pending = row["kind"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            .equals("restaurant_purchase", ignoreCase = true) &&
            row["merchantResolutionStatus"] != null && row["menuResolutionStatus"] != null &&
            ocrResolution?.status.equals("needs_ocr_resolution", ignoreCase = true)
        require(exact || pending) {
            "PriceTrace standalone menu resolution response is neither exact nor reviewable"
        }
        return row
    }

    private fun decodeJsonObject(value: String): JsonObject {
        val element = json.parseToJsonElement(value)
        return when (element) {
            is JsonObject -> element
            else -> element.jsonArray.single().jsonObject
        }
    }

    private fun decodeStandaloneResponse(value: String): JsonObject {
        val element = json.parseToJsonElement(value)
        val row = when (element) {
            is JsonObject -> element
            else -> element.jsonArray.single().jsonObject
        }
        require(row.requiredStringOrNull("observationId", "observation_id", "priceObservationId", "id") != null) {
            "PriceTrace standalone response is missing observationId"
        }
        return row
    }

    private fun JsonObject.requiredStringOrNull(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    private fun classify(response: PriceObservationHttpResponse): PriceObservationFailureKind {
        if (response.statusCode == 401 || response.statusCode == 403) return PriceObservationFailureKind.AUTHENTICATION
        if (response.statusCode == 408) return PriceObservationFailureKind.NETWORK_TIMEOUT
        if (response.statusCode in 500..599) return PriceObservationFailureKind.SERVER
        val body = response.body.lowercase()
        return when {
            response.statusCode == 409 || body.contains("idempotency key") -> PriceObservationFailureKind.IDEMPOTENCY_MISMATCH
            else -> PriceObservationFailureKind.CONTRACT
        }
    }

    private suspend fun refresh(config: PriceTraceSupabaseConfig): PriceTraceSupabaseConfig? = try {
        if (config.refreshToken.isBlank()) return null
        val response = transport.execute(
            request(
                config = config,
                method = "POST",
                path = "/auth/v1/token?grant_type=refresh_token",
                authenticated = false,
                body = buildJsonObject { put("refresh_token", JsonPrimitive(config.refreshToken)) }.encode(),
            ),
        )
        if (response.statusCode !in 200..299) return null
        val root = json.parseToJsonElement(response.body).jsonObject
        val user = root["user"] as? JsonObject
        store.saveSession(
            userId = (user?.get("id") as? JsonPrimitive)?.contentOrNull ?: config.userId,
            email = (user?.get("email") as? JsonPrimitive)?.contentOrNull ?: config.email,
            accessToken = (root["access_token"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            refreshToken = (root["refresh_token"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        ).getOrNull()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun request(
        config: PriceTraceSupabaseConfig,
        method: String,
        path: String,
        authenticated: Boolean = true,
        body: String? = null,
    ): PriceObservationHttpRequest = PriceObservationHttpRequest(
        method = method,
        url = config.url.trimEnd('/') + path,
        headers = buildMap {
            put("apikey", config.publishableKey)
            put("Accept", "application/json")
            if (authenticated) put("Authorization", "Bearer ${config.accessToken}")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
        },
        body = body,
    )

    private fun JsonElement.encode(): String = json.encodeToString(JsonElement.serializer(), this)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

class PriceTraceCanonicalProjectionSubmitter(
    private val gateway: PriceTraceCanonicalGateway,
) : IngestionProjectionSubmitter, OcrMerchantIdentityResolutionSubmitter, OcrMenuIdentityResolutionSubmitter, OcrPurchaseIdentityResolutionSubmitter, PurchaseIdentityReviewSubmitter,
    OcrProjectionResponseReader {
    override suspend fun reviewPurchaseIdentity(request: PurchaseIdentityReviewRequest): ProjectionSubmission =
        when (val result = gateway.reviewPurchaseIdentity(request)) {
            is PriceTraceCanonicalOutcome.Failure -> result.toProjectionFailure(reviewAware = true)
            is PriceTraceCanonicalOutcome.Success -> ProjectionSubmission.Success(request.purchaseSourceId, metadataJson = result.response.encode(),
                requiresReview = PurchaseNutritionIdentity.pendingResolutions(result.response.encode()).isNotEmpty())
        }

    override suspend fun synchronizePurchaseContext(request: OcrPurchaseMenuResolutionRequest): ProjectionSubmission =
        purchaseProjectionResult(gateway.synchronizePurchaseContext(request))

    override suspend fun readAcceptedProjection(request: ProjectionRequest): ProjectionSubmission {
        val cached = runCatching { Json.parseToJsonElement(request.previousMetadataJson.orEmpty()).jsonObject }.getOrNull()
            ?: return ProjectionSubmission.Failure("pricetrace_checkpoint_metadata_missing", false, requiresReview = true)
        val envelope = request.envelope
        if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION &&
            envelope?.schemaVersion == YEONSIK_OCR_V5_SCHEMA
        ) {
            return purchaseProjectionResult(gateway.readAcceptedPurchaseResponses(envelope, cached))
        }
        val outcome = when (request.projection) {
            IngestionProjection.PRICETRACE_RECEIPT -> {
                val receiptId = (cached["receiptId"] as? JsonPrimitive)?.contentOrNull
                    ?: return ProjectionSubmission.Failure("pricetrace_checkpoint_receipt_id_missing", false, requiresReview = true)
                gateway.readAcceptedReceiptResponse(receiptId)
            }
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> {
                val keys = (cached["observations"] as? JsonArray)?.mapNotNull {
                    ((it as? JsonObject)?.get("priceObservationClientKey") as? JsonPrimitive)?.contentOrNull
                }.orEmpty()
                val currentKeys = request.envelope?.priceObservations?.map { it.clientKey }.orEmpty()
                if (keys.isEmpty() || keys.distinct().size != keys.size || keys.toSet() != currentKeys.toSet()) {
                    return ProjectionSubmission.Failure("pricetrace_checkpoint_client_key_mismatch", false, requiresReview = true)
                }
                gateway.readAcceptedStandaloneResponses(request.idempotencyKey, keys)
            }
            else -> return ProjectionSubmission.Failure("unsupported_pricetrace_checkpoint", false)
        }
        return when (outcome) {
            is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
                "pricetrace_checkpoint_read_failed: ${outcome.message ?: outcome.kind.name}",
                outcome.kind.retryable, requiresReview = true,
            )
            is PriceTraceCanonicalOutcome.Success -> {
                val response = outcome.response
                val standalone = request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION
                val review = PriceTraceIdentityJson.requiresOcrReview(response) ||
                    if (standalone) PriceTraceIdentityJson.standaloneRestaurantIdentityNeedsReview(response)
                    else !PriceTraceIdentityJson.merchantResolutionIsExact(response)
                val remoteId = if (!standalone) response.requiredId("receiptId") else
                    (response["observations"] as? JsonArray)?.firstNotNullOfOrNull {
                        ((it as? JsonObject)?.get("response") as? JsonObject)?.standaloneId()
                    } ?: return ProjectionSubmission.Failure("pricetrace_checkpoint_identity_missing", false, requiresReview = true)
                ProjectionSubmission.Success(
                    remoteId, response.encode(), requiresReview = review,
                    alsoUploaded = if (!standalone && request.envelope?.receipt?.let { response.hasCompleteObservations(it) } == true) {
                        setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                    } else emptySet(),
                )
            }
        }
    }

    override suspend fun resolveMerchantIdentity(
        request: OcrMerchantIdentityResolutionRequest,
    ): ProjectionSubmission = when (val result = gateway.resolveOcrMerchantIdentity(request)) {
        is PriceTraceCanonicalOutcome.Success -> {
            val requiresReview = !PriceTraceIdentityJson.merchantResolutionIsExact(result.response) ||
                !PriceTraceIdentityJson.ocrResolutionIsResolved(result.response) ||
                PriceTraceIdentityJson.requiresOcrReview(result.response)
            ProjectionSubmission.Success(
                remoteId = result.response.requiredId("receiptId"),
                metadataJson = result.response.encode(),
                alsoUploaded = if (result.response.hasCompleteObservations(request.receipt)) {
                    setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                } else {
                    emptySet()
                },
                requiresReview = requiresReview,
            )
        }
        is PriceTraceCanonicalOutcome.Failure -> result.toProjectionFailure(reviewAware = true)
    }

    override suspend fun resolveReceiptMenuIdentity(
        request: OcrReceiptMenuResolutionRequest,
    ): ProjectionSubmission = when (val result = gateway.resolveOcrReceiptMenuIdentity(request)) {
        is PriceTraceCanonicalOutcome.Success -> {
            val response = result.response
            val hasReceiptResponse = response.stringField("receiptId") != null
            val requiresReview = !hasReceiptResponse ||
                !PriceTraceIdentityJson.merchantResolutionIsExact(response) ||
                PriceTraceIdentityJson.requiresOcrReview(response)
            ProjectionSubmission.Success(
                remoteId = response.stringField("receiptId")
                    ?: response.stringField("resolutionId")
                    ?: request.resolutionId,
                metadataJson = response.encode(),
                alsoUploaded = if (!requiresReview && response.hasCompleteObservations(request.receipt)) {
                    setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                } else {
                    emptySet()
                },
                requiresReview = requiresReview,
            )
        }
        is PriceTraceCanonicalOutcome.Failure -> result.toProjectionFailure(reviewAware = true)
    }

    override suspend fun resolveStandaloneMenuIdentity(
        request: OcrStandaloneMenuResolutionRequest,
    ): ProjectionSubmission {
        return when (val result = gateway.resolveOcrStandaloneRestaurantMenu(request)) {
            is PriceTraceCanonicalOutcome.Success -> {
                val exactIdentity = PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(result.response)
                val requiresReview = exactIdentity == null || PriceTraceIdentityJson.requiresOcrReview(result.response)
                val remoteId = result.response.standaloneId()
                    ?: PriceTraceIdentityJson.ocrResolution(result.response.encode())?.resolutionId
                    ?: return ProjectionSubmission.Failure(
                        "pricetrace_standalone_resolution_identity_invalid",
                        retryable = false,
                        requiresReview = true,
                        metadataJson = result.response.encode(),
                    )
                ProjectionSubmission.Success(
                    remoteId = remoteId,
                    metadataJson = result.response.encode(),
                    primaryUploaded = !requiresReview,
                    primaryPendingReason = if (requiresReview) "pricetrace_standalone_menu_identity_requires_review" else null,
                    requiresReview = requiresReview,
                )
            }
            is PriceTraceCanonicalOutcome.Failure -> result.toProjectionFailure(reviewAware = true)
        }
    }

    override suspend fun resolvePurchaseMenuIdentity(request: OcrPurchaseMenuResolutionRequest): ProjectionSubmission =
        purchaseProjectionResult(gateway.resolveOcrPurchaseMenu(request))

    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        val envelope = request.envelope ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        return when (request.projection) {
            IngestionProjection.PRICETRACE_RECEIPT,
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> {
                if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION &&
                    envelope.purchaseRecords.isNotEmpty()
                ) {
                    if (envelope.review.status != com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus.READY) {
                        return ProjectionSubmission.Failure("canonical_review_required", retryable = false)
                    }
                    if (envelope.purchaseRecords.none { it.priceTraceSubmissionEligible }) {
                        return ProjectionSubmission.Failure(
                            PriceTraceV4SubmissionCompatibility.incompatibilityReason(envelope.purchaseRecords)
                                ?: PriceTraceV4SubmissionReason.SUBMISSION_INCOMPATIBLE,
                            retryable = false,
                        )
                    }
                    return purchaseProjectionResult(gateway.submitPurchasePriceObservationsV4(
                        request.idempotencyKey,
                        envelope,
                    ))
                }
                if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION &&
                    envelope.priceObservations.isNotEmpty()
                ) {
                    if (envelope.priceObservations.any { it.netAmountMinor == null }) {
                        return ProjectionSubmission.Failure(
                            "price_observation_net_amount_required",
                            retryable = false,
                        )
                    }
                    if (envelope.review.status != com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus.READY) {
                        return ProjectionSubmission.Failure("canonical_review_required", retryable = false)
                    }
                    when (val result = gateway.submitStandalonePriceObservations(request.idempotencyKey, envelope)) {
                        is PriceTraceCanonicalOutcome.Success -> {
                            val observations = (result.response["observations"] as? JsonArray).orEmpty()
                            val observationResponses = observations.mapNotNull { observation ->
                                ((observation as? JsonObject)?.get("response") as? JsonObject)
                            }
                            val requiresReview = PriceTraceIdentityJson.requiresOcrReview(result.response) ||
                                PriceTraceIdentityJson.standaloneRestaurantIdentityNeedsReview(result.response)
                            val remoteId = observationResponses.firstNotNullOfOrNull { response ->
                                response.standaloneId()
                                    ?: PriceTraceIdentityJson.ocrResolution(response.encode())?.resolutionId
                            } ?: return ProjectionSubmission.Failure(
                                "pricetrace_observation_identity_invalid",
                                retryable = false,
                                requiresReview = requiresReview,
                                metadataJson = result.response.encode(),
                            )
                            return ProjectionSubmission.Success(
                                remoteId = remoteId,
                                metadataJson = result.response.encode(),
                                primaryUploaded = !requiresReview,
                                primaryPendingReason = if (requiresReview) {
                                    "pricetrace_standalone_menu_identity_requires_review"
                                } else null,
                                requiresReview = requiresReview,
                            )
                        }
                        is PriceTraceCanonicalOutcome.Failure -> return result.toProjectionFailure(reviewAware = true)
                    }
                }
                val sourceReceipt = envelope.receipt
                    ?: return ProjectionSubmission.Failure("receipt_artifact_missing", retryable = false)
                // Older locally approved snapshots retained `parsed`. Core's artifact gate is
                // the proof of review; preserve their canonical fingerprints and replay keys.
                val receipt = if (sourceReceipt.document.source.transcriptionStatus == TranscriptionStatus.USER_VERIFIED) {
                    sourceReceipt
                } else if (request.receiptUserVerified) {
                    sourceReceipt.copy(document = sourceReceipt.document.copy(
                        source = sourceReceipt.document.source.copy(transcriptionStatus = TranscriptionStatus.USER_VERIFIED),
                    ))
                } else {
                    return ProjectionSubmission.Failure("receipt_not_user_verified", retryable = false)
                }
                if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION &&
                    receipt.lineItems.none { it.isNormalPriceObservationCandidate() }
                ) {
                    return ProjectionSubmission.Failure("price_observation_no_eligible_lines", retryable = false)
                }
                when (val result = gateway.submitVerifiedReceipt(request.idempotencyKey, receipt)) {
                    is PriceTraceCanonicalOutcome.Success -> {
                        // Parse at the authority boundary; the raw response remains durable metadata.
                        try {
                            PriceTraceIdentityJson.decode(result.response)
                        } catch (_: Exception) {
                            return ProjectionSubmission.Failure(
                                "pricetrace_identity_invalid",
                                retryable = false,
                            )
                        }
                        val observationUploaded = result.response.hasCompleteObservations(receipt)
                        ProjectionSubmission.Success(
                            remoteId = result.response.requiredId("receiptId"),
                            metadataJson = result.response.encode(),
                            alsoUploaded = when (request.projection) {
                                IngestionProjection.PRICETRACE_RECEIPT -> if (observationUploaded) {
                                    setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                                } else {
                                    emptySet()
                                }
                                IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> setOf(IngestionProjection.PRICETRACE_RECEIPT)
                                else -> emptySet()
                            },
                            primaryUploaded = request.projection != IngestionProjection.PRICETRACE_PRICE_OBSERVATION || observationUploaded,
                            primaryPendingReason = if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION && !observationUploaded) {
                                "price_observation_incomplete"
                            } else {
                                null
                            },
                            requiresReview = PriceTraceIdentityJson.requiresOcrReview(result.response),
                        )
                    }
                    is PriceTraceCanonicalOutcome.Failure -> result.toProjectionFailure(reviewAware = true)
                }
            }
            IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE -> {
                val merchant = envelope.merchantCandidate
                    ?: return ProjectionSubmission.Failure("merchant_candidate_missing", retryable = false)
                when (val result = gateway.submitMerchantCandidate(request.idempotencyKey, merchant)) {
                    is PriceTraceCanonicalOutcome.Success -> ProjectionSubmission.Success(
                        remoteId = result.response.requiredId("candidateId"),
                        metadataJson = result.response.encode(),
                    )
                    is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
                        message = result.message ?: result.kind.name,
                        retryable = result.kind.retryable,
                    )
                }
            }
            else -> ProjectionSubmission.Failure("unsupported_pricetrace_projection", retryable = false)
        }
    }

    private fun JsonObject.requiredId(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("PriceTrace response is missing $key")

    private fun purchaseProjectionResult(result: PriceTraceCanonicalOutcome): ProjectionSubmission = when (result) {
        is PriceTraceCanonicalOutcome.Success -> {
            val remoteId = (result.response["sources"] as? JsonArray)?.firstOrNull()
                ?.let { it as? JsonObject }?.purchaseSourceId()
            if (remoteId == null) ProjectionSubmission.Failure("pricetrace_purchase_source_identity_invalid", false)
            else {
                val created = result.response["observationCreated"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val pending = PurchaseNutritionIdentity.pendingResolutions(result.response.encode()).isNotEmpty()
                val reason = (result.response["sources"] as? JsonArray).orEmpty().asSequence()
                    .flatMap { (it.jsonObject["lineResults"] as? JsonArray).orEmpty().asSequence() }
                    .mapNotNull { (it.jsonObject["reasonCode"] as? JsonPrimitive)?.contentOrNull }.firstOrNull()
                ProjectionSubmission.Success(remoteId, result.response.encode(), primaryUploaded = created,
                    requiresReview = pending,
                    primaryPendingReason = if (created) null else reason ?: "price_observation_not_created")
            }
        }
        is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
            result.message ?: result.kind.name, result.kind.retryable, metadataJson = result.metadataJson,
        )
    }

    private fun PriceTraceCanonicalOutcome.Failure.toProjectionFailure(
        reviewAware: Boolean = false,
    ): ProjectionSubmission.Failure {
        val message = message ?: kind.name
        val requiresReview = reviewAware && listOf(
            "needs_ocr_resolution",
            "needs_user_selection",
            "ambiguous",
            "unresolved_catalog",
        ).any { token -> message.contains(token, ignoreCase = true) }
        return ProjectionSubmission.Failure(
            message = message,
            retryable = kind.retryable && !requiresReview,
            requiresReview = requiresReview,
        )
    }

    private fun JsonObject.standaloneId(): String? = listOf(
        "observationId", "observation_id", "priceObservationId", "id",
    ).firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    private fun JsonObject.purchaseSourceId(): String? =
        (this["purchaseSourceId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.hasCompleteObservations(receipt: ReceiptV2): Boolean {
        val observationIds = (this["observationIds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
        if (observationIds.isEmpty()) return false

        val lineResults = (this["lines"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: return false
        val expectedLineIds = receipt.lineItems.map { it.id }.toSet()
        val returnedLineIds = lineResults.mapNotNull { it.stringField("sourceLineId") }.toSet()
        if (lineResults.size != receipt.lineItems.size || returnedLineIds != expectedLineIds) return false

        val eligibleLineIds = receipt.lineItems
            .filter { it.isNormalPriceObservationCandidate() }
            .map { it.id }
            .toSet()
        val observationLines = lineResults.filter {
            it.stringField("sourceLineId") in eligibleLineIds &&
                it.stringField("resolutionStatus") != "semantic_only"
        }
        return eligibleLineIds.isNotEmpty() && observationLines.isNotEmpty() && observationLines.all { line ->
            val observationId = line.stringField("observationId")
                ?: line.stringField("restaurantObservationId")
            observationId != null && observationId in observationIds
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    private fun JsonObject.encode(): String = Json.encodeToString(JsonObject.serializer(), this)
}
