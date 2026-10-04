package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ingestion.*
import com.pricetrace.receiptscanner.nutrition.NutritionField
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class FitnessCanonicalRecoveryTest {
    @Test
    fun exactAuditRecoveryReadsOwnerRowAndPublishesWithoutCanonicalImport() = runTest {
        val request = standaloneRequest()
        val audit = audit(request)
        val transport = RecoveryTransport(listOf(audit))

        val result = submitter(transport).submit(request)

        assertTrue(result is ProjectionSubmission.Success)
        assertEquals(0, transport.canonicalPosts.size)
        assertEquals(1, transport.publicationPosts.size)
        assertEquals(IMPORT_ID, transport.publicationPosts.single()["p_canonical_import_id"]!!.jsonPrimitive.content)
        assertEquals("food-menu-1", transport.publicationPosts.single()["p_nutrition_food_id"]!!.jsonPrimitive.content)
        assertTrue(transport.requests.first().url.contains("owner_id=eq.user-1"))
        assertEquals("Bearer access-token", transport.requests.first().headers["Authorization"])
    }

    @Test
    fun legacyKeyUsesExactServerAuditAndOriginalKeyForPublicationAfterMerchantCorrection() = runTest {
        val original = standaloneRequest()
        val audit = audit(original, key = "legacy-import-key", revision = 1)
        val corrected = original.copy(
            revisionSeq = 9,
            previousMetadataJson = cachedIds(IMPORT_ID),
            envelope = original.envelope!!.copy(merchantCandidate = original.envelope.merchantCandidate!!.copy(name = "Corrected Merchant")),
        )
        val transport = RecoveryTransport(listOf(audit))

        assertTrue(submitter(transport).submit(corrected) is ProjectionSubmission.Success)
        assertEquals(0, transport.canonicalPosts.size)
        assertEquals(StableIds.sha256("legacy-import-key|publication"), transport.publicationPosts.single()["p_idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(2, transport.requests.count { it.method == "GET" })
        assertTrue(transport.requests[1].url.contains("id=in.($IMPORT_ID)"))
    }

    @Test
    fun reversedMultiLineAuditRowsAreCorrelatedByExactDocumentAndClientAndPtSourceLine() = runTest {
        val decoded = YeonsikOcrV2Json.decode(readExample("yeonsik-ocr.v2.restaurant.example.json"), "multi-source")
        val receipt = decoded.receipt!!
        val items = listOf(restaurantItem("nutrition-1", "line-1"), restaurantItem("nutrition-2", "line-2"))
        val request = standaloneRequest().copy(
            envelope = decoded.copy(
                nutrition = items,
                receipt = receipt.copy(lineItems = receipt.lineItems + receipt.lineItems.first().copy(id = "line-2")),
            ),
            resolvedIdentity = ProjectionIdentity(priceTrace = PriceTraceIdentity(
                receiptId = "receipt-1", restaurantId = "restaurant-1", restaurantLocationId = "location-1",
                merchantResolutionStatus = "exact",
                lines = listOf(
                    PriceTraceLineIdentity(sourceLineId = "line-2", restaurantMenuId = "menu-2", catalogProductId = "catalog-2", resolutionStatus = "resolved"),
                    PriceTraceLineIdentity(sourceLineId = "line-1", restaurantMenuId = "menu-1", catalogProductId = "catalog-1", resolutionStatus = "resolved"),
                ),
            )),
            previousMetadataJson = cachedIds(IMPORT_ID, SECOND_IMPORT_ID),
        )
        val rows = listOf(
            audit(request, items[1], SECOND_IMPORT_ID, "legacy-2"),
            audit(request, items[0], IMPORT_ID, "legacy-1"),
        )
        val transport = RecoveryTransport(rows)

        assertTrue(submitter(transport).submit(request) is ProjectionSubmission.Success)
        assertEquals(0, transport.canonicalPosts.size)
        assertEquals(listOf(IMPORT_ID, SECOND_IMPORT_ID), transport.publicationPosts.map { it["p_canonical_import_id"]!!.jsonPrimitive.content })
        assertEquals(listOf("menu-1", "menu-2"), transport.publicationPosts.map { it["p_restaurant_menu_id"]!!.jsonPrimitive.content })
        assertEquals(3, transport.requests.count { it.method == "GET" })
    }

    @Test
    fun foreignMalformedWrongSourceAndUnverifiedAuditRowsFailClosed() = runTest {
        val request = standaloneRequest()
        val original = audit(request)
        val invalidRows = listOf(
            replace(original, "owner_id", JsonPrimitive("foreign-owner")),
            replace(original, "id", JsonPrimitive("not-a-server-uuid")),
            replace(original, "source_document_ref", JsonPrimitive("ocr-app://ingestion/another-doc/revision/1/nutrition/menu-1")),
            replace(original, "user_verified", JsonPrimitive(false)),
            replace(original, "input_contract", JsonPrimitive(NUTRITION_LABEL_V1)),
            replace(original, "projection_source_type", JsonPrimitive("product_label_ocr")),
            JsonObject(original - "request_payload"),
        )
        for (row in invalidRows) {
            val transport = RecoveryTransport(listOf(row))
            val result = submitter(transport).submit(request) as ProjectionSubmission.Failure
            assertTrue(result.message, result.requiresReview)
            assertEquals(0, transport.canonicalPosts.size)
            assertEquals(0, transport.publicationPosts.size)
        }
    }

    @Test
    fun currentFoodNutrientsOptionalValuesEvidenceAndBasisChangesRejectRecovery() = runTest {
        val request = standaloneRequest()
        val original = audit(request)
        val payload = original["request_payload"]!!.jsonObject
        val changes = listOf(
            "food_name" to JsonPrimitive("Different menu"),
            "basis_amount" to JsonPrimitive(2),
            "required_nutrients" to replace(payload["required_nutrients"]!!.jsonObject, "calories_kcal", JsonPrimitive(999)),
            "optional_nutrients" to buildJsonObject { put("fiber_grams", JsonPrimitive(8)) },
            "estimation_evidence" to replace(payload["estimation_evidence"]!!.jsonObject, "confidence", JsonPrimitive(0.1)),
            "nutrient_provenance" to replace(payload["nutrient_provenance"]!!.jsonObject, "calories_kcal",
                replace(payload["nutrient_provenance"]!!.jsonObject["calories_kcal"]!!.jsonObject, "evidence_refs", JsonArray(listOf(JsonPrimitive("wrong-source"))))),
        )
        for ((key, value) in changes) {
            val altered = replace(original, "request_payload", replace(payload, key, value))
            val transport = RecoveryTransport(listOf(altered))
            val result = submitter(transport).submit(request) as ProjectionSubmission.Failure
            assertTrue(key, result.requiresReview)
            assertEquals(0, transport.publicationPosts.size)
            assertEquals(0, transport.canonicalPosts.size)
        }
        val currentItem = request.envelope!!.nutrition.single() as IngestionNutrition.RestaurantMenuEstimate
        val editedItems = listOf(
            currentItem.copy(menuName = "Edited menu"),
            currentItem.copy(estimate = currentItem.estimate.copy(
                nutrients = currentItem.estimate.nutrients + (NutritionField.CALORIES_KCAL to 999.0),
            )),
            currentItem.copy(estimate = currentItem.estimate.copy(
                nutrients = currentItem.estimate.nutrients + (NutritionField.FIBER_GRAMS to 5.0),
            )),
            currentItem.copy(estimate = currentItem.estimate.copy(
                nutrientProvenance = currentItem.estimate.nutrientProvenance + (
                    NutritionField.CALORIES_KCAL to NutritionNutrientProvenance("estimated", "food_image_estimate", listOf("edited-photo"))
                ),
            )),
        )
        for (edited in editedItems) {
            val transport = RecoveryTransport(listOf(original))
            val result = submitter(transport).submit(request.copy(envelope = request.envelope.copy(nutrition = listOf(edited))))
                as ProjectionSubmission.Failure
            assertTrue(result.requiresReview)
            assertEquals(0, transport.publicationPosts.size)
            assertEquals(0, transport.canonicalPosts.size)
        }
    }

    @Test
    fun ambiguousMissingAndLossySourceKeysNeverCreateOrPublish() = runTest {
        val request = standaloneRequest()
        val row = audit(request)
        val cases = listOf(
            request to emptyList(),
            request to listOf(row, replace(row, "id", JsonPrimitive(SECOND_IMPORT_ID))),
            request.copy(localDocumentId = "lossy/doc") to listOf(row),
        )
        for ((current, rows) in cases) {
            val transport = RecoveryTransport(rows)
            val result = submitter(transport).submit(current) as ProjectionSubmission.Failure
            assertTrue(result.requiresReview)
            assertEquals(0, transport.publicationPosts.size)
            assertEquals(0, transport.canonicalPosts.size)
        }
    }

    @Test
    fun responseLossRetryReusesOriginalAuditAndPublicationKeyWithoutImport() = runTest {
        val original = standaloneRequest()
        val row = audit(original, key = "legacy-stable", revision = 1)
        val request = original.copy(previousMetadataJson = cachedIds(IMPORT_ID), revisionSeq = 8)
        val transport = RecoveryTransport(listOf(row), loseFirstPublicationResponse = true)
        val submitter = submitter(transport)

        val failure = submitter.submit(request) as ProjectionSubmission.Failure
        assertTrue(failure.retryable)
        assertTrue(submitter.submit(request.copy(previousMetadataJson = failure.metadataJson)) is ProjectionSubmission.Success)
        assertEquals(0, transport.canonicalPosts.size)
        assertEquals(2, transport.publicationPosts.size)
        assertEquals(transport.publicationPosts[0], transport.publicationPosts[1])
        assertEquals(StableIds.sha256("legacy-stable|publication"), transport.publicationPosts[1]["p_idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(row, transport.audits.single())
    }

    @Test
    fun emptyDirectLookupAndTransientFallbackFailuresPreserveLegacySelectorForRetry() = runTest {
        val original = standaloneRequest()
        val row = audit(original, key = "legacy-fallback-stable", revision = 1)
        var request = original.copy(previousMetadataJson = cachedIds(IMPORT_ID), revisionSeq = 8)
        val transport = RecoveryTransport(listOf(row), failingFallbackReads = 3)
        val submitter = submitter(transport)

        repeat(3) {
            val failure = submitter.submit(request) as ProjectionSubmission.Failure
            assertTrue(failure.retryable)
            assertFalse(failure.requiresReview)
            val checkpoint = Json.parseToJsonElement(requireNotNull(failure.metadataJson)).jsonArray
            assertEquals(1, checkpoint.size)
            assertEquals(IMPORT_ID, checkpoint.single().jsonObject["canonical_import_id"]!!.jsonPrimitive.content)
            assertEquals(0, transport.publicationPosts.size)
            request = request.copy(previousMetadataJson = failure.metadataJson)
        }

        assertTrue(submitter.submit(request) is ProjectionSubmission.Success)
        assertEquals(0, transport.canonicalPosts.size)
        assertEquals(1, transport.publicationPosts.size)
        val published = transport.publicationPosts.single()
        assertEquals(IMPORT_ID, published["p_canonical_import_id"]!!.jsonPrimitive.content)
        assertEquals(StableIds.sha256("legacy-fallback-stable|publication"), published["p_idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(4, transport.requests.count { it.url.contains("id=in.($IMPORT_ID)") })
    }

    @Test
    fun auditGetAuthenticationFailureRefreshesOnceAndRetriesWithCurrentOwnerSession() = runTest {
        val request = standaloneRequest()
        val transport = RecoveryTransport(listOf(audit(request)), unauthorizedFirstRead = true)

        assertTrue(submitter(transport).submit(request) is ProjectionSubmission.Success)
        assertEquals(1, transport.requests.count { it.url.contains("grant_type=refresh_token") })
        assertEquals("Bearer refreshed-access", transport.requests.last { it.method == "GET" }.headers["Authorization"])
        assertEquals(0, transport.canonicalPosts.size)
    }

    @Test
    fun auditReadServerFailureIsRetryableAndDoesNotFallThroughToImport() = runTest {
        val request = standaloneRequest()
        val transport = RecoveryTransport(listOf(audit(request)), readStatus = 500)
        val result = submitter(transport).submit(request) as ProjectionSubmission.Failure

        assertTrue(result.retryable)
        assertFalse(result.requiresReview)
        assertEquals(0, transport.canonicalPosts.size)
        assertEquals(0, transport.publicationPosts.size)
    }

    @Test
    fun legacyStandaloneWithoutExplicitAuthorityDoesNotReadImportOrPublish() = runTest {
        val request = standaloneRequest().copy(dependencyMetadataJson = mapOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to """{"observations":[{"priceObservationClientKey":"price-menu-1","response":{"kind":"restaurant_purchase","authoritativeIds":{"restaurantId":"restaurant-1","restaurantLocationId":"location-1","restaurantMenuId":"menu-1","catalogProductId":"catalog-1"}}}]}""",
        ))
        val transport = RecoveryTransport(emptyList())
        val result = submitter(transport).submit(request) as ProjectionSubmission.Failure

        assertTrue(result.requiresReview)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun recoveryFlagPreservesPackagedProductPrivateImportPath() = runTest {
        val envelope = YeonsikOcrV2Json.decode(readExample("yeonsik-ocr.v2.packaged-product.example.json"), "packaged-doc")
        val label = envelope.nutrition.single() as IngestionNutrition.ProductLabel
        val request = standaloneRequest().copy(envelope = envelope.copy(nutrition = listOf(label.copy(
            // The old example's "cereal" category predates Fitness's current category allow-list.
            draft = label.draft.copy(category = "processed").asUserVerified("2026-10-04T10:00:00+09:00"),
        ))))
        val transport = RecoveryTransport(emptyList())

        val result = submitter(transport).submit(request)
        assertTrue(result.toString(), result is ProjectionSubmission.Success)
        assertEquals(1, transport.canonicalPosts.size)
        assertEquals(0, transport.publicationPosts.size)
        assertFalse(transport.requests.any { it.method == "GET" })
        assertEquals(NUTRITION_LABEL_V1, transport.canonicalPosts.single()["p_input_contract"]!!.jsonPrimitive.content)
        assertEquals("processed", transport.canonicalPosts.single()["p_category"]!!.jsonPrimitive.content)
    }

    private fun standaloneRequest(): ProjectionRequest {
        val item = IngestionNutrition.RestaurantMenuEstimate(
            clientKey = "menu-1", menuName = "Noodles", priceObservationClientKey = "price-menu-1", estimate = estimate(),
        )
        return ProjectionRequest(
            ingestionId = "ingestion-1", projection = IngestionProjection.FITNESS_NUTRITION,
            canonicalPayload = "{}", idempotencyKey = "projection-key", localDocumentId = "ocr-doc",
            revisionSeq = 1, recoverCanonicalImport = true,
            envelope = YeonsikOcrEnvelope(
                mode = IngestionMode.RESTAURANT, source = IngestionSource("chatgpt", emptyList()),
                merchantCandidate = MerchantCandidate("Original Merchant"), nutrition = listOf(item),
                schemaVersion = YEONSIK_OCR_V3_SCHEMA,
            ),
            dependencyMetadataJson = mapOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION to """{"observations":[{"priceObservationClientKey":"price-menu-1","response":{"kind":"restaurant_purchase","authorityStatus":"exact","merchantResolutionStatus":"exact","menuResolutionStatus":"resolved","authoritativeIds":{"restaurantId":"restaurant-1","restaurantLocationId":"location-1","restaurantMenuId":"menu-1","catalogProductId":"catalog-1"}}}]}"""),
        )
    }

    private fun restaurantItem(clientKey: String, lineId: String) = IngestionNutrition.RestaurantEstimate(
        clientKey = clientKey, lineId = lineId, menuName = "Same source menu name", estimate = estimate(),
    )

    private fun estimate() = RestaurantNutritionEstimate(
        nutrients = NutritionField.requiredFields.associateWith { if (it == NutritionField.CALORIES_KCAL) 500.0 else 10.0 },
        estimated = true, confidence = "0.82", confidenceScore = 0.82,
        ranges = mapOf(NutritionField.CALORIES_KCAL to NutritionRange(400.0, 500.0, 600.0)),
        nutrientProvenance = NutritionField.requiredFields.associateWith {
            NutritionNutrientProvenance("estimated", "food_image_estimate", emptyList())
        },
    )

    private fun audit(
        request: ProjectionRequest,
        item: IngestionNutrition = request.envelope!!.nutrition.single(),
        id: String = IMPORT_ID,
        key: String = StableIds.sha256("${request.idempotencyKey}|nutrition|${item.clientKey}"),
        revision: Long = request.revisionSeq,
    ): JsonObject {
        val restaurantName = request.envelope!!.receipt?.merchant?.name ?: request.envelope.merchantCandidate!!.name
        val expected = when (item) {
            is IngestionNutrition.RestaurantEstimate -> CanonicalNutritionPayloadFactory.fromRestaurantEstimate(
                request.localDocumentId!!, revision, key, restaurantName, item,
            )
            is IngestionNutrition.RestaurantMenuEstimate -> CanonicalNutritionPayloadFactory.fromRestaurantMenuEstimate(
                request.localDocumentId!!, revision, key, restaurantName, item,
            )
            else -> error("restaurant fixture required")
        }
        val rpc = Json.parseToJsonElement(expected.toRpcJson()).jsonObject
        val payload = JsonObject(rpc.mapKeys { it.key.removePrefix("p_") })
        return buildJsonObject {
            put("id", JsonPrimitive(id)); put("owner_id", JsonPrimitive("user-1"))
            put("idempotency_key", JsonPrimitive(key)); put("source_document_ref", JsonPrimitive(expected.sourceDocumentRef))
            put("nutrition_food_id", JsonPrimitive("food-${item.clientKey}")); put("input_contract", JsonPrimitive(FOOD_ESTIMATE_V1))
            put("user_verified", JsonPrimitive(true)); put("projection_source_type", JsonPrimitive("food_image_estimate"))
            listOf("required_nutrients", "optional_nutrients", "nutrient_provenance", "provenance").forEach { put(it, payload.getValue(it)) }
            put("request_payload", payload)
        }
    }

    private fun cachedIds(vararg ids: String): String = JsonArray(ids.map {
        buildJsonObject { put("canonical_import_id", JsonPrimitive(it)); put("nutrition_food_id", JsonPrimitive("cached-food")) }
    }).toString()

    private fun replace(row: JsonObject, key: String, value: JsonElement): JsonObject = JsonObject(row + (key to value))

    private fun submitter(transport: NutritionHttpTransport) = FitnessCanonicalProjectionSubmitter(NutritionSupabaseGateway(FakeStore(), transport))

    private class RecoveryTransport(
        val audits: List<JsonObject>,
        private var loseFirstPublicationResponse: Boolean = false,
        private var unauthorizedFirstRead: Boolean = false,
        private val readStatus: Int = 200,
        private var failingFallbackReads: Int = 0,
    ) : NutritionHttpTransport {
        val requests = mutableListOf<NutritionHttpRequest>()
        val canonicalPosts = mutableListOf<JsonObject>()
        val publicationPosts = mutableListOf<JsonObject>()

        override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
            requests += request
            if (request.method == "GET") {
                if (unauthorizedFirstRead) { unauthorizedFirstRead = false; return NutritionHttpResponse(401, "{}") }
                if (readStatus != 200) return NutritionHttpResponse(readStatus, "{}")
                if (request.url.contains("id=in.") && failingFallbackReads > 0) {
                    failingFallbackReads -= 1
                    return NutritionHttpResponse(500, "{}")
                }
                val rows = if (request.url.contains("idempotency_key=eq.")) {
                    audits.filter { request.url.contains("idempotency_key=eq.${it["idempotency_key"]!!.jsonPrimitive.content}") }
                } else audits
                return NutritionHttpResponse(200, JsonArray(rows).toString())
            }
            if (request.url.contains("grant_type=refresh_token")) {
                return NutritionHttpResponse(200, """{"user":{"id":"user-1","email":"fixture@example.com"},"access_token":"refreshed-access","refresh_token":"refreshed-token"}""")
            }
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            if (request.url.endsWith("publish_verified_ocr_dining_out_nutrition_v1")) {
                publicationPosts += body
                if (loseFirstPublicationResponse) { loseFirstPublicationResponse = false; throw IOException("response lost after commit") }
                return NutritionHttpResponse(200, buildJsonObject {
                    listOf("canonical_import_id", "nutrition_food_id", "restaurant_id", "restaurant_location_id", "restaurant_menu_id", "catalog_product_id")
                        .forEach { put(it, body.getValue("p_$it")) }
                    put("nutrition_link_id", JsonPrimitive("link-1")); put("nutrition_link_revision", JsonPrimitive(1))
                    put("visibility", JsonPrimitive("public")); put("food_revision", JsonPrimitive(1)); put("publication_revision", JsonPrimitive(1))
                    put("published_at", JsonPrimitive("2026-10-04T00:00:00Z")); put("replayed", JsonPrimitive(publicationPosts.size > 1))
                }.toString())
            }
            canonicalPosts += body
            return NutritionHttpResponse(200, """[{"canonical_import_id":"$IMPORT_ID","idempotent_replay":false,"nutrition_food_id":"packaged-food","input_contract":"nutrition-label.v1","projection_source_type":"product_label_ocr","projection_import_id":null,"catalog_product_id":null,"estimation_evidence_id":null,"visibility":"private"}]""")
        }
    }

    private class FakeStore : NutritionSupabaseStore {
        private var config = NutritionSupabaseConfig(
            url = "https://nutrition.example.com", publishableKey = "publishable-fixture-safe-length",
            userId = "user-1", email = "fixture@example.com", accessToken = "access-token", refreshToken = "refresh-token",
        )
        override fun read() = config
        override fun saveConnection(url: String, publishableKey: String) = runCatching {
            config = config.copy(url = url, publishableKey = publishableKey); config
        }
        override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String) = runCatching {
            config = config.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken); config
        }
        override fun clearSession() = true
    }

    private fun readExample(name: String): String = sequenceOf(File("examples", name), File("../examples", name))
        .firstOrNull(File::isFile)?.readText() ?: error("example not found: $name")

    private companion object {
        const val IMPORT_ID = "11111111-1111-4111-8111-111111111111"
        const val SECOND_IMPORT_ID = "22222222-2222-4222-8222-222222222222"
    }
}
