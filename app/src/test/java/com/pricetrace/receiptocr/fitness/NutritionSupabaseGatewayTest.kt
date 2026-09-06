package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.MealComponentReference
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class NutritionSupabaseGatewayTest {
    @Test
    fun signInStoresReturnedSessionWithoutPersistingPassword() = runTest {
        val store = FakeStore(connection())
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """{"access_token":"access-1","refresh_token":"refresh-1","user":{"id":"user-1","email":"fit@example.com"}}""",
            ),
        )
        val gateway = NutritionSupabaseGateway(store, transport)

        val result = gateway.signIn("fit@example.com", "one-time-password")

        assertEquals(NutritionAuthOutcome.Success("fit@example.com"), result)
        assertEquals("user-1", store.read().userId)
        assertEquals("access-1", store.read().accessToken)
        assertFalse(store.read().toString().contains("one-time-password"))
        assertFalse(transport.requests.single().headers.containsKey("Authorization"))
        assertEquals("publishable-key-with-safe-length", transport.requests.single().headers["apikey"])
    }

    @Test
    fun canonicalNutritionUsesOnlyFitnessOwnedImportRpc() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"canonical_import_id":"canonical-1","idempotent_replay":false,"nutrition_food_id":"food-1","input_contract":"nutrition-label.v1","projection_source_type":"ocr_app","projection_import_id":"projection-1","catalog_product_id":null,"estimation_evidence_id":null,"visibility":"private"}]""",
            ),
        )
        val payload = CanonicalNutritionPayloadFactory.fromProductLabel(
            localDocumentId = "ocr-label-session",
            revisionSeq = 2,
            idempotencyKey = "canonical-nutrition-key",
            draft = verifiedDraft(),
        )

        val result = NutritionSupabaseGateway(store, transport).importCanonical(payload)

        val success = result as NutritionCanonicalImportOutcome.Success
        assertEquals("canonical-1", success.response.canonicalImportId)
        assertEquals("food-1", success.response.nutritionFoodId)
        assertEquals(NUTRITION_LABEL_V1, success.response.inputContract)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://nutrition.example.com/rest/v1/rpc/import_canonical_nutrition_v2",
            request.url,
        )
        assertEquals("publishable-key-with-safe-length", request.headers["apikey"])
        assertEquals("Bearer access-token", request.headers["Authorization"])

        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(
            setOf(
                "p_idempotency_key",
                "p_input_contract",
                "p_source_document_ref",
                "p_food_name",
                "p_brand",
                "p_category",
                "p_basis_amount",
                "p_basis_unit",
                "p_required_nutrients",
                "p_nutrient_provenance",
                "p_optional_nutrients",
                "p_provenance",
                "p_user_verified",
                "p_pricetrace_identity",
                "p_estimation_evidence",
            ),
            body.keys,
        )
        assertEquals("canonical-nutrition-key", body["p_idempotency_key"]?.jsonPrimitive?.content)
        assertEquals(NUTRITION_LABEL_V1, body["p_input_contract"]?.jsonPrimitive?.content)
        assertEquals(true, body["p_user_verified"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(
            CanonicalNutritionImportPayload.REQUIRED_NUTRIENTS,
            body["p_nutrient_provenance"]!!.jsonObject.keys,
        )
    }

    @Test
    fun verifiedMealUsesFitnessMealRpcAndPreservesActualItemAmounts() = runTest {
        val eatenAt = "2026-09-06T08:10:00+09:00"
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"meal_import_id":"meal-import-1","meal_record_id":"meal-record-1","idempotent_replay":false,"eaten_at":"$eatenAt","record_date":"2026-09-06","item_count":1,"nutrition_food_ids":["food-1"],"contract_version":"verified-meal.v1"}]""",
            ),
        )
        val payload = FitnessMealCanonicalPayload(
            idempotencyKey = "meal-key",
            eatenAt = eatenAt,
            items = listOf(
                FitnessMealItemPayload(
                    nutritionFoodId = "food-1",
                    clientKey = "product-1",
                    consumedAmount = 40.0,
                    consumedUnit = "g",
                    confidence = 0.92,
                    sourceProvenance = Json.parseToJsonElement(
                        """{"source_app":"ocr-app","schema_version":"yeonsik-ocr.v2","nutrition_client_key":"product-1"}""",
                    ).jsonObject,
                ),
            ),
            source = Json.parseToJsonElement(
                """{"source_app":"ocr-app","schema_version":"yeonsik-ocr.v2","meal_kind":"food","menu":"Test cereal","source_document_ref":"local-meal","consumed_at":"$eatenAt"}""",
            ).jsonObject,
        )

        val result = NutritionSupabaseGateway(FakeStore(signedIn()), transport).importVerifiedMeal(payload)

        val success = result as NutritionMealImportOutcome.Success
        assertEquals("meal-record-1", success.response.mealRecordId)
        val request = transport.requests.single()
        assertEquals(
            "https://nutrition.example.com/rest/v1/rpc/import_verified_meal_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(
            setOf("p_idempotency_key", "p_eaten_at", "p_items", "p_source", "p_pricetrace_identity"),
            body.keys,
        )
        assertEquals(eatenAt, body["p_eaten_at"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["p_pricetrace_identity"])
        val item = body["p_items"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("nutrition_food_id", "client_key", "consumed_amount", "consumed_unit", "confidence", "source_provenance"),
            item.keys,
        )
        assertEquals("food-1", item["nutrition_food_id"]?.jsonPrimitive?.content)
        assertEquals(40.0, item["consumed_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("g", item["consumed_unit"]?.jsonPrimitive?.content)
        assertEquals(0.92, item["confidence"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun mealComponentEstimateUsesSeparateFitnessRpcWithoutRestaurantMenuIdentity() = runTest {
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"component_import_id":"component-1","idempotent_replay":false,"nutrition_food_id":"food-side-1","input_contract":"meal-component-estimate.v1","source_type":"meal_component_estimate","data_version":2,"visibility":"private"}]""",
            ),
        )

        val result = NutritionSupabaseGateway(FakeStore(signedIn()), transport)
            .importMealComponentEstimate(componentPayload())

        val success = result as NutritionMealComponentImportOutcome.Success
        assertEquals("food-side-1", success.response.nutritionFoodId)
        val request = transport.requests.single()
        assertEquals(
            "https://nutrition.example.com/rest/v1/rpc/import_meal_component_estimate_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertFalse(body.containsKey("p_input_contract"))
        assertEquals(JsonNull, body["p_pricetrace_identity"])
        assertEquals(JsonNull, body["p_provenance"]!!.jsonObject["restaurant_menu_id"])
        assertEquals(true, body["p_user_verified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Kimchi", body["p_food_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun productNutritionLinkUsesFitnessOwnedProposalRpcAndExactRevision() = runTest {
        val revision = "sha256:" + "a".repeat(64)
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"id":"link-1","action":"link","identity":{"namespace":"pricetrace","catalogProductId":"catalog-1","nutritionFoodId":"food-1"},"status":"pending","sourceRevision":"$revision"}]""",
            ),
        )
        val payload = ProductNutritionLinkProposalPayload(
            catalogProductId = "catalog-1",
            nutritionFoodId = "food-1",
            sourceRevision = revision,
            source = Json.parseToJsonElement(
                """{"namespace":"pricetrace","catalogProductId":"catalog-1","productRevision":"$revision","candidateClientKey":"product-1","sourceSchema":"yeonsik-ocr.v2"}""",
            ).jsonObject,
        )

        val result = NutritionSupabaseGateway(FakeStore(signedIn()), transport)
            .proposeProductNutritionLink(payload)

        val success = result as NutritionProductLinkOutcome.Success
        assertEquals("link-1", success.response.id)
        val request = transport.requests.single()
        assertEquals(
            "https://nutrition.example.com/rest/v1/rpc/propose_product_nutrition_link_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(
            setOf("p_action", "p_namespace", "p_catalog_product_id", "p_nutrition_food_id", "p_source_revision", "p_source"),
            body.keys,
        )
        assertEquals("link", body["p_action"]?.jsonPrimitive?.content)
        assertEquals("catalog-1", body["p_catalog_product_id"]?.jsonPrimitive?.content)
        assertEquals(revision, body["p_source_revision"]?.jsonPrimitive?.content)
    }

    @Test
    fun canonicalImportRefreshesExpiredAccessTokenOnce() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            NutritionHttpResponse(401, "{}"),
            NutritionHttpResponse(
                200,
                """{"access_token":"access-2","refresh_token":"refresh-2","user":{"id":"user-1","email":"fit@example.com"}}""",
            ),
            NutritionHttpResponse(
                200,
                """[{"canonical_import_id":"canonical-1","idempotent_replay":false,"nutrition_food_id":"food-1","input_contract":"nutrition-label.v1","projection_source_type":"ocr_app","projection_import_id":null,"catalog_product_id":null,"estimation_evidence_id":null,"visibility":"private"}]""",
            ),
        )
        val gateway = NutritionSupabaseGateway(store, transport)

        val result = gateway.importCanonical(
            CanonicalNutritionPayloadFactory.fromProductLabel(
                localDocumentId = "ocr-label-session",
                revisionSeq = 1,
                idempotencyKey = "refresh-key",
                draft = verifiedDraft(),
            ),
        )

        assertTrue(result is NutritionCanonicalImportOutcome.Success)
        assertEquals("access-2", store.read().accessToken)
        assertTrue(transport.requests[1].url.contains("grant_type=refresh_token"))
        assertEquals("Bearer access-2", transport.requests[2].headers["Authorization"])
    }

    @Test
    fun connectionValidationRejectsCleartextAndAcceptsCustomHttps() {
        assertTrue(
            NutritionSupabaseConfig.validateConnection(
                "http://project.supabase.co",
                "publishable-key-with-safe-length",
            ) != null,
        )
        assertEquals(
            null,
            NutritionSupabaseConfig.validateConnection(
                "https://nutrition.example.com",
                "publishable-key-with-safe-length",
            ),
        )
        assertTrue(
            NutritionSupabaseConfig.validateConnection(
                "https://nutrition.example.com",
                "sb_" + "secret_this-key-must-never-be-stored",
            ) != null,
        )
        val serviceRolePayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"role\":\"service_role\"}".toByteArray(StandardCharsets.UTF_8),
        )
        assertTrue(
            NutritionSupabaseConfig.validateConnection(
                "https://nutrition.example.com",
                "header.$serviceRolePayload.signature-with-safe-length",
            ) != null,
        )
    }

    private fun connection() = NutritionSupabaseConfig(
        url = "https://nutrition.example.com",
        publishableKey = "publishable-key-with-safe-length",
    )

    private fun signedIn() = connection().copy(
        userId = "user-1",
        email = "fit@example.com",
        accessToken = "access-token",
        refreshToken = "refresh-token",
    )

    private fun verifiedDraft() = NutritionLabelDraft(
        documentId = "ocr-doc",
        productName = "검증 상품",
        basisAmount = 100.0,
        basisUnit = "g",
        nutrients = mapOf(
            NutritionField.CALORIES_KCAL to 100.0,
            NutritionField.PROTEIN_GRAMS to 10.0,
            NutritionField.CARBS_GRAMS to 15.0,
            NutritionField.FAT_GRAMS to 2.0,
            NutritionField.SODIUM_MG to 90.0,
            NutritionField.SATURATED_FAT_GRAMS to 1.0,
            NutritionField.SUGARS_GRAMS to 5.0,
        ),
        ).asUserVerified("2026-08-11T10:00:00+09:00")

    private fun componentPayload(): CanonicalNutritionImportPayload {
        val provenance = NutritionField.requiredFields.associateWith { field ->
            com.pricetrace.receiptscanner.ingestion.NutritionNutrientProvenance(
                valueStatus = "estimated",
                sourceType = "food_image_estimate",
                evidenceRefs = listOf("food-side-1/${field.wireKey}"),
            )
        }
        return CanonicalNutritionPayloadFactory.fromMealComponentEstimate(
            localDocumentId = "ocr-restaurant-session",
            revisionSeq = 1,
            idempotencyKey = "component-key",
            restaurantName = "Test Restaurant",
            item = IngestionNutrition.MealComponentEstimate(
                clientKey = "side-1",
                menuName = "Kimchi",
                reference = MealComponentReference(
                    restaurantName = "Test Restaurant",
                    branchName = "Main",
                ),
                estimate = com.pricetrace.receiptscanner.ingestion.RestaurantNutritionEstimate(
                    nutrients = NutritionField.requiredFields.associateWith { 30.0 },
                    estimated = true,
                    confidence = "0.7",
                    nutrientProvenance = provenance,
                ),
            ),
        )
    }

    private class QueueTransport(vararg responses: NutritionHttpResponse) : NutritionHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<NutritionHttpRequest>()

        override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class FakeStore(initial: NutritionSupabaseConfig) : NutritionSupabaseStore {
        private var config = initial

        override fun read(): NutritionSupabaseConfig = config

        override fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig> =
            runCatching {
                config = NutritionSupabaseConfig(url = url, publishableKey = publishableKey)
                config
            }

        override fun saveSession(
            userId: String,
            email: String,
            accessToken: String,
            refreshToken: String,
        ): Result<NutritionSupabaseConfig> = runCatching {
            require(userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank())
            config = config.copy(
                userId = userId,
                email = email,
                accessToken = accessToken,
                refreshToken = refreshToken,
            )
            config
        }

        override fun clearSession(): Boolean {
            config = config.copy(userId = "", email = "", accessToken = "", refreshToken = "")
            return true
        }
    }
}
