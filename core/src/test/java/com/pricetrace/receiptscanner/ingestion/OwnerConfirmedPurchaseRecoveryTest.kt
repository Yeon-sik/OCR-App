package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptocr.fitness.*
import com.pricetrace.receiptocr.pricetrace.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Local adapter integration: actual review/orchestrator/gateways with deterministic HTTP boundaries. */
class OwnerConfirmedPurchaseRecoveryTest {
    private fun fixture() = File("src/test/resources/hansung-restaurant-purchase.v5.json").readText()
    private fun uuid(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

    @Test fun hansungReviewRecoveryAndRestartReuseEverySuccessfulSink() = runBlocking {
        val envelope = YeonsikOcrEnvelopeCodec.decode(fixture(), "hansung")
        val original = YeonsikOcrV5Json.canonicalize(envelope)
        val evidence = envelope.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
        val store = InMemoryIngestionSessionStore()
        val backend = Backend(envelope)
        val selected = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, IngestionProjection.CASHOS_TRANSACTION, IngestionProjection.FITNESS_NUTRITION)
        var cashCalls = 0
        fun app() = CanonicalIngestionUseCase(store, mapOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to PriceTraceCanonicalProjectionSubmitter(PriceTraceCanonicalGateway(PtStore(), backend)),
            IngestionProjection.FITNESS_NUTRITION to FitnessCanonicalProjectionSubmitter(NutritionSupabaseGateway(FitnessStore(), backend)),
            IngestionProjection.CASHOS_TRANSACTION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    cashCalls++; return ProjectionSubmission.Success("cash-853")
                }
            }))
        var useCase = app()
        val imported = useCase.importJson(fixture(), "hansung", "hansung-session", evidence) as CanonicalImportResult.Success
        useCase.submitSelected("hansung-session", imported.envelope, selected)
        assertEquals(0, backend.candidateCalls)
        assertEquals(0, backend.ingestCalls)
        val confirmed = useCase.confirm("hansung-session", imported.envelope, evidence)
        useCase.submitSelected("hansung-session", confirmed.envelope, selected)
        assertEquals(1, backend.ingestCalls)
        assertEquals(2, backend.imports.size)
        assertEquals(1, cashCalls)
        // Existing source and two private artifacts are already saved. Lose a private link response after commit.
        backend.dropLinkResponse = true
        useCase.confirm("hansung-session", confirmed.envelope, evidence)
        assertEquals(setOf("line-1", "line-2"), backend.resolved)
        assertEquals(1, backend.merchants.size)
        assertEquals(1, backend.privateLinks.size)
        useCase = app() // Process/service recreation with the same persisted checkpoint store.
        val finalStates = useCase.retrySelected("hansung-session", confirmed.envelope, selected)
        assertTrue(finalStates.toString(), finalStates.filter { it.projection in selected }.all { it.status == ProjectionStatus.UPLOADED })
        useCase.retrySelected("hansung-session", confirmed.envelope, selected)
        assertEquals(IngestionReviewStatus.READY, store.get("hansung-session")!!.reviewStatus)
        assertEquals(1, backend.ingestCalls)
        assertEquals(1, cashCalls)
        assertEquals(2, backend.imports.size)
        assertEquals(2, backend.privateLinks.size)
        assertEquals(2, backend.resolved.size)
        val metadata = store.get("hansung-session")!!.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.metadataJson
        envelope.nutrition.forEach {
            assertNull(PurchaseNutritionIdentity.exact(envelope, it.clientKey, metadata))
            assertNotNull(PurchaseNutritionIdentity.ownerConfirmed(envelope, it.clientKey, metadata))
        }
        assertEquals(original, YeonsikOcrV5Json.canonicalize(confirmed.envelope))
        assertFalse(YeonsikOcrEnvelopeCodec.encode(confirmed.envelope).contains(uuid(20)))
        useCase.importJson(fixture(), "hansung", "hansung-session", evidence)
        assertEquals(ProjectionStatus.UPLOADED, store.get("hansung-session")!!.projections.single {
            it.projection == IngestionProjection.FITNESS_NUTRITION }.status)
        assertEquals(1, backend.ingestCalls)
    }

    @Test fun privateIdentityRejectsMalformedIdsAndNeverBecomesGlobalExact() {
        val envelope = YeonsikOcrEnvelopeCodec.decode(fixture(), "hansung")
        val backend = Backend(envelope).apply { resolved.addAll(listOf("line-1", "line-2")) }
        val source = JsonObject(backend.source() + ("purchaseRecordClientKey" to JsonPrimitive("purchase-1")))
        val metadata = buildJsonObject { put("sources", JsonArray(listOf(source))) }.toString()
        assertNotNull(PurchaseNutritionIdentity.ownerConfirmed(envelope, "food-1", metadata))
        assertNull(PurchaseNutritionIdentity.exact(envelope, "food-1", metadata))
        assertNull(PurchaseNutritionIdentity.ownerConfirmed(envelope, "food-1", metadata.replace(uuid(21), "not-an-id")))
        assertNull(PurchaseNutritionIdentity.ownerConfirmed(envelope, "food-1", metadata.replace("owner_confirmed", "exact")))
    }

    @Test fun secondPurchaseReusesPrivateIdsButKeepsFourNutritionArtifactsAndTwoCashTransactions() = runBlocking {
        val rawA = fixture()
        val rawB = rawA.replace("2026-10-13", "2026-11-10").replace("5700", "6000").replace("3800", "4000")
            .replace("9500", "10000").replace("\"purchase-1\"", "\"purchase-2\"").replace("\"food-1\"", "\"food-3\"").replace("\"food-2\"", "\"food-4\"")
        val a = Backend(YeonsikOcrEnvelopeCodec.decode(rawA, "a"))
        val b = Backend(YeonsikOcrEnvelopeCodec.decode(rawB, "b"), sourceNumber = 25, artifactOffset = 100)
        var active = a
        val pt = object : PriceObservationHttpTransport { override suspend fun execute(request: PriceObservationHttpRequest) = active.execute(request) }
        val fitness = object : NutritionHttpTransport { override suspend fun execute(request: NutritionHttpRequest) = active.execute(request) }
        val store = InMemoryIngestionSessionStore(); var cashCalls = 0
        val useCase = CanonicalIngestionUseCase(store, mapOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to PriceTraceCanonicalProjectionSubmitter(PriceTraceCanonicalGateway(PtStore(), pt)),
            IngestionProjection.FITNESS_NUTRITION to FitnessCanonicalProjectionSubmitter(NutritionSupabaseGateway(FitnessStore(), fitness)),
            IngestionProjection.CASHOS_TRANSACTION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission { cashCalls++; return ProjectionSubmission.Success("cash-$cashCalls") }
            }))
        val selected = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, IngestionProjection.FITNESS_NUTRITION, IngestionProjection.CASHOS_TRANSACTION)
        for ((raw, backend, key) in listOf(Triple(rawA, a, "a"), Triple(rawB, b, "b"))) {
            active = backend
            val evidence = backend.envelope.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
            val imported = useCase.importJson(raw, key, key, evidence) as CanonicalImportResult.Success
            val confirmed = useCase.confirm(key, imported.envelope, evidence)
            useCase.submitSelected(key, confirmed.envelope, selected)
            useCase.confirm(key, confirmed.envelope, evidence)
            useCase.retrySelected(key, confirmed.envelope, selected)
            assertEquals(YeonsikOcrV5Json.canonicalize(backend.envelope), YeonsikOcrV5Json.canonicalize(confirmed.envelope))
            assertTrue(store.get(key)!!.projections.filter { it.projection in selected }.all { it.status == ProjectionStatus.UPLOADED })
        }
        assertEquals(2, cashCalls)
        assertEquals(2, a.ingestCalls + b.ingestCalls)
        val links = (a.privateLinks.values + b.privateLinks.values)
        assertEquals(4, links.size)
        assertEquals(4, links.map { it["canonical_import_id"] }.distinct().size)
        assertEquals(4, links.map { it["nutrition_food_id"] }.distinct().size)
        assertEquals(2, links.map { it["purchase_source_id"] }.distinct().size)
        assertEquals(1, links.map { it["restaurant_id"] }.distinct().size)
        assertEquals(1, links.map { it["restaurant_location_id"] }.distinct().size)
        assertEquals(2, links.map { it["restaurant_menu_id"] }.distinct().size)
    }

    @Test fun missingPrivateRpcKeepsImportedArtifactsPendingAndResumesAfterDeployment() = runBlocking {
        val envelope = YeonsikOcrEnvelopeCodec.decode(fixture(), "pending")
        val backend = Backend(envelope).apply { privateRpcMissing = true }
        val store = InMemoryIngestionSessionStore(); var cashCalls = 0
        val useCase = CanonicalIngestionUseCase(store, mapOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to PriceTraceCanonicalProjectionSubmitter(PriceTraceCanonicalGateway(PtStore(), backend)),
            IngestionProjection.FITNESS_NUTRITION to FitnessCanonicalProjectionSubmitter(NutritionSupabaseGateway(FitnessStore(), backend)),
            IngestionProjection.CASHOS_TRANSACTION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission { cashCalls++; return ProjectionSubmission.Success("cash") }
            }))
        val evidence = envelope.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
        val imported = useCase.importJson(fixture(), "pending", "pending", evidence) as CanonicalImportResult.Success
        val confirmed = useCase.confirm("pending", imported.envelope, evidence)
        val selected = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, IngestionProjection.FITNESS_NUTRITION, IngestionProjection.CASHOS_TRANSACTION)
        useCase.submitSelected("pending", confirmed.envelope, selected)
        useCase.confirm("pending", confirmed.envelope, evidence)
        val pending = store.get("pending")!!.projections.single { it.projection == IngestionProjection.FITNESS_NUTRITION }
        assertEquals(ProjectionStatus.BLOCKED, pending.status)
        assertEquals("fitness_private_purchase_link_not_deployed", pending.lastError)
        assertNotNull(pending.metadataJson)
        assertEquals(2, backend.imports.size); assertEquals(0, backend.privateLinks.size); assertEquals(1, cashCalls)
        backend.privateRpcMissing = false
        val resumed = useCase.retrySelected("pending", confirmed.envelope, selected)
        assertEquals(2, backend.imports.size); assertEquals(resumed.toString(), 2, backend.privateLinks.size); assertEquals(1, cashCalls)
        assertEquals(ProjectionStatus.UPLOADED, store.get("pending")!!.projections.single { it.projection == IngestionProjection.FITNESS_NUTRITION }.status)
    }

    @Test fun ownerChoiceIsCurrentReviewBoundAndProcessesTheSelectedLineFirst() = runBlocking {
        val envelope = YeonsikOcrEnvelopeCodec.decode(fixture(), "choice")
        val backend = Backend(envelope).apply { requireOwnerSelection = true }
        val store = InMemoryIngestionSessionStore()
        val useCase = CanonicalIngestionUseCase(store, mapOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION to
            PriceTraceCanonicalProjectionSubmitter(PriceTraceCanonicalGateway(PtStore(), backend))))
        val evidence = envelope.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
        val imported = useCase.importJson(fixture(), "choice", "choice", evidence) as CanonicalImportResult.Success
        val selection = PurchaseNutritionIdentity.OwnerRestaurantSelection("purchase-1", "line-2", uuid(21))
        assertTrue(useCase.selectOwnerPurchaseRestaurant("choice", imported.envelope, selection) is ProjectionSubmission.Failure)
        val confirmed = useCase.confirm("choice", imported.envelope, evidence)
        useCase.submitSelected("choice", confirmed.envelope, setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION))
        useCase.confirm("choice", confirmed.envelope, evidence)
        assertEquals(0, backend.resolved.size)
        val metadata = store.get("choice")!!.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.metadataJson
        assertEquals(2, PurchaseNutritionIdentity.pendingResolutions(metadata).size)
        assertEquals(2, PurchaseNutritionIdentity.ownerRestaurantChoices(metadata).first().choices.size)
        assertTrue(useCase.selectOwnerPurchaseRestaurant("choice", confirmed.envelope, selection.copy(restaurantId = uuid(999))) is ProjectionSubmission.Failure)
        useCase.selectOwnerPurchaseRestaurant("choice", confirmed.envelope, selection)
        assertEquals(setOf("line-1", "line-2"), backend.resolved)
        assertEquals("line-2", backend.selectedLine)
        assertEquals(YeonsikOcrV5Json.canonicalize(envelope), YeonsikOcrV5Json.canonicalize(confirmed.envelope))
    }

    private inner class Backend(val envelope: YeonsikOcrEnvelope, val sourceNumber: Int = 20, val artifactOffset: Int = 0) : PriceObservationHttpTransport, NutritionHttpTransport {
        var ingestCalls = 0; var candidateCalls = 0; var dropLinkResponse = false
        var privateRpcMissing = false; var requireOwnerSelection = false; var selectedLine: String? = null
        val resolved = linkedSetOf<String>()
        val merchants = linkedMapOf<String, JsonObject>()
        val imports = linkedMapOf<String, Pair<JsonObject, JsonObject>>()
        val privateLinks = linkedMapOf<String, JsonObject>()
        fun source() = buildJsonObject {
            put("purchaseSourceId", uuid(sourceNumber)); put("sourceSaved", true); put("sourceAcceptanceStatus", "accepted")
            put("lineAuthorityVersion", "purchase-line-authority.v1")
            put("observationCreated", resolved.isNotEmpty()); put("observationCount", resolved.size)
            put("observationIds", JsonArray(resolved.map { JsonPrimitive(uuid(50 + it.last().digitToInt())) }))
            put("lineResults", JsonArray(envelope.purchaseRecords.single().lineItems.mapIndexed { i, line -> buildJsonObject {
                val ready = line.lineKey in resolved
                put("lineKey", line.lineKey); put("lineOrdinal", i + 1); put("kind", "restaurant_purchase")
                put("sourceAcceptanceStatus", "accepted"); put("observationCreated", ready)
                put("observationStatus", if (ready) "created" else "not_created")
                listOf("authorityStatus", "merchantResolutionStatus", "menuResolutionStatus").forEach { put(it, if (ready) "owner_confirmed" else "unresolved") }
                put("authoritativeIds", JsonNull); put("reasonCode", if (ready) JsonNull else JsonPrimitive(if (requireOwnerSelection) "owner_restaurant_selection_required" else "restaurant_source_identity_missing"))
                if (!ready && requireOwnerSelection) put("ownerRestaurantChoices", JsonArray(listOf(21, 23).map { n -> buildJsonObject {
                    put("restaurantId", uuid(n)); put("restaurantLocationId", uuid(n + 1)); put("merchantName", "한성대학교 학생식당"); put("branchName", "지점-$n")
                } }))
                if (ready) {
                    put("observationId", uuid(51 + i)); put("observationType", "purchase_owner_price_observation")
                    put("ownerConfirmedIds", buildJsonObject { put("restaurantId", uuid(21)); put("restaurantLocationId", uuid(22)); put("restaurantMenuId", uuid(31 + i)) })
                }
            } }))
        }
        override suspend fun execute(request: PriceObservationHttpRequest): PriceObservationHttpResponse {
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            val result = when (request.url.substringAfterLast('/')) {
                "ingest_verified_purchase_price_observation_v1" -> { ingestCalls++; source() }
                "get_purchase_price_ingestion_response_v1" -> source()
                "submit_merchant_identity_candidate_v1" -> {
                    candidateCalls++
                    val merchant = body["p_merchant"]!!.jsonObject
                    assertEquals(JsonPrimitive("한성대학교 학생식당"), merchant["merchant_name"])
                    assertEquals(JsonPrimitive("food_service"), merchant["business_kind"])
                    listOf("source_namespace", "source_location_code", "branch_name", "address", "phone", "business_registration_number").forEach { assertEquals(JsonNull, merchant[it]) }
                    assertEquals(JsonPrimitive(true), body["p_user_verified"])
                    val key = body["p_idempotency_key"]!!.jsonPrimitive.content
                    merchants[key]?.let { assertEquals(it, merchant) }; merchants[key] = merchant
                    buildJsonObject { put("candidateId", uuid(40)); put("resolutionStatus", "needs_user_selection") }
                }
                "resolve_user_confirmed_purchase_restaurant_line_v1" -> {
                    assertEquals(setOf("p_purchase_source_id", "p_line_key", "p_merchant_candidate_id", "p_user_verified") +
                        if (body.containsKey("p_owner_restaurant_id")) setOf("p_owner_restaurant_id") else emptySet(), body.keys)
                    assertEquals(JsonPrimitive(uuid(40)), body["p_merchant_candidate_id"])
                    if (body.containsKey("p_owner_restaurant_id")) { assertEquals(JsonPrimitive(uuid(21)), body["p_owner_restaurant_id"]); selectedLine = body["p_line_key"]!!.jsonPrimitive.content; requireOwnerSelection = false }
                    if (!requireOwnerSelection) resolved += body["p_line_key"]!!.jsonPrimitive.content
                    source()
                }
                else -> error("Unexpected PriceTrace RPC ${request.url}")
            }
            return PriceObservationHttpResponse(200, result.toString())
        }
        override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            return when (request.url.substringAfterLast('/')) {
                "import_canonical_nutrition_v3" -> {
                    val key = body["p_idempotency_key"]!!.jsonPrimitive.content
                    val old = imports[key]
                    old?.let { assertEquals("recovery must not rewrite imported nutrition", it.first, body) }
                    val result = old?.second ?: buildJsonObject {
                        put("canonical_import_id", uuid(61 + imports.size + artifactOffset)); put("nutrition_food_id", "saved-food-${imports.size + 1 + artifactOffset}")
                        put("idempotent_replay", false); put("input_contract", "food-estimate.v1")
                        put("projection_source_type", "food_image_estimate"); put("visibility", "private")
                        put("projection_import_id", JsonNull); put("catalog_product_id", JsonNull); put("estimation_evidence_id", JsonNull)
                    }
                    imports[key] = body to result
                    NutritionHttpResponse(200, JsonArray(listOf(result)).toString())
                }
                "link_private_ocr_purchase_nutrition_v1" -> {
                    if (privateRpcMissing) return NutritionHttpResponse(404, "{\"code\":\"PGRST202\",\"message\":\"RPC not deployed\"}")
                    val key = body["p_canonical_import_id"]!!.jsonPrimitive.content
                    val saved = JsonObject(body.mapKeys { it.key.removePrefix("p_") } + mapOf(
                        "linked" to JsonPrimitive(true), "visibility" to JsonPrimitive("private"), "authority_status" to JsonPrimitive("owner_confirmed")))
                    privateLinks[key]?.let { assertEquals(it, saved) }; privateLinks[key] = saved
                    if (dropLinkResponse) { dropLinkResponse = false; NutritionHttpResponse(503, "response lost after commit") }
                    else NutritionHttpResponse(200, saved.toString())
                }
                else -> error("Unexpected/public Nutrition RPC ${request.url}")
            }
        }
    }

    private class PtStore : PriceTraceSupabaseStore {
        override fun read() = PriceTraceSupabaseConfig("https://pt.example", "abcdefghijklmnopqrst", "owner", "", "token")
        override fun saveConnection(url: String, publishableKey: String) = Result.success(read())
        override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String) = Result.success(read())
        override fun clearSession() = true
    }
    private class FitnessStore : NutritionSupabaseStore {
        override fun read() = NutritionSupabaseConfig("https://nutrition.example", "abcdefghijklmnopqrst", "owner", "", "token")
        override fun saveConnection(url: String, publishableKey: String) = Result.success(read())
        override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String) = Result.success(read())
        override fun clearSession() = true
    }
}
