package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
