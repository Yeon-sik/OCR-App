package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.MerchantCandidate
import com.pricetrace.receiptscanner.ingestion.NutritionNutrientProvenance
import com.pricetrace.receiptscanner.ingestion.NutritionRange
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.nutrition.NutritionField
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessCanonicalProjectionSubmitterTest {
    @Test
    fun restaurantBundlePublishesEveryEstimateThroughIndependentCanonicalRpcCalls() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(response("canonical-1", "food-1"), response("canonical-2", "food-2"))
        val submitter = FitnessCanonicalProjectionSubmitter(NutritionSupabaseGateway(store, transport))

        val result = submitter.submit(
            ProjectionRequest(
                ingestionId = "ingestion-1",
                projection = IngestionProjection.FITNESS_NUTRITION,
                canonicalPayload = "{}",
                resolvedIdentity = emptyMap(),
                idempotencyKey = "bundle-key",
                envelope = YeonsikOcrEnvelope(
                    mode = com.pricetrace.receiptscanner.ingestion.IngestionMode.RESTAURANT,
                    source = IngestionSource(producer = "chatgpt-project", sourceFiles = emptyList()),
                    merchantCandidate = MerchantCandidate(name = "Test Restaurant"),
                    nutrition = listOf(estimate("menu-1"), estimate("menu-2")),
                ),
                localDocumentId = "ocr-local-document",
                revisionSeq = 7,
                canonicalFingerprint = "fingerprint-7",
            ),
        )

        assertTrue(result is ProjectionSubmission.Success)
        assertEquals("food-2", (result as ProjectionSubmission.Success).remoteId)
        assertEquals(2, transport.requests.size)

        val first = Json.parseToJsonElement(requireNotNull(transport.requests[0].body)).jsonObject
        val second = Json.parseToJsonElement(requireNotNull(transport.requests[1].body)).jsonObject
        assertEquals(FOOD_ESTIMATE_V1, first["p_input_contract"]?.jsonPrimitive?.content)
        assertEquals(FOOD_ESTIMATE_V1, second["p_input_contract"]?.jsonPrimitive?.content)
        assertEquals(true, first["p_user_verified"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, second["p_user_verified"]?.jsonPrimitive?.content?.toBoolean())
        assertNotEquals(first["p_idempotency_key"], second["p_idempotency_key"])
        assertEquals("ocr-local-document", first["p_source_document_ref"]?.jsonPrimitive?.content?.substringAfter("/ingestion/")?.substringBefore("/revision"))
        val evidence = first["p_estimation_evidence"]?.jsonObject
        assertNotNull(evidence)
        assertEquals(0.82, evidence?.get("confidence")?.jsonPrimitive?.content?.toDouble())
        val range = evidence?.get("range")?.jsonObject?.get("calories_kcal")?.jsonObject
        assertEquals(400.0, range?.get("min")?.jsonPrimitive?.content?.toDouble())
        assertEquals(500.0, range?.get("point")?.jsonPrimitive?.content?.toDouble())
        assertEquals(600.0, range?.get("max")?.jsonPrimitive?.content?.toDouble())
        assertTrue(first["p_nutrient_provenance"]!!.jsonObject.keys.containsAll(CanonicalNutritionImportPayload.REQUIRED_NUTRIENTS))
    }

    private fun estimate(clientKey: String): IngestionNutrition.RestaurantEstimate {
        val provenance = NutritionField.requiredFields.associateWith { field ->
            NutritionNutrientProvenance(
                valueStatus = "estimated",
                sourceType = "food_image_estimate",
                evidenceRefs = listOf("$clientKey/${field.wireKey}"),
            )
        }
        return IngestionNutrition.RestaurantEstimate(
            clientKey = clientKey,
            lineId = "line-$clientKey",
            menuName = "Menu $clientKey",
            estimate = com.pricetrace.receiptscanner.ingestion.RestaurantNutritionEstimate(
                nutrients = NutritionField.requiredFields.associateWith(::value),
                estimated = true,
                confidence = "high",
                ranges = mapOf(NutritionField.CALORIES_KCAL to NutritionRange(min = 400.0, point = 500.0, max = 600.0)),
                nutrientProvenance = provenance,
                confidenceScore = 0.82,
            ),
        )
    }

    private fun value(field: NutritionField): Double = when (field) {
        NutritionField.CALORIES_KCAL -> 500.0
        NutritionField.PROTEIN_GRAMS -> 20.0
        NutritionField.CARBS_GRAMS -> 60.0
        NutritionField.FAT_GRAMS -> 15.0
        NutritionField.SODIUM_MG -> 800.0
        NutritionField.SATURATED_FAT_GRAMS -> 5.0
        NutritionField.SUGARS_GRAMS -> 8.0
        else -> 0.0
    }

    private fun response(id: String, foodId: String) = NutritionHttpResponse(
        200,
        """[{"canonical_import_id":"$id","idempotent_replay":false,"nutrition_food_id":"$foodId","input_contract":"food-estimate.v1","projection_source_type":"ocr_app","projection_import_id":"$id-projection","catalog_product_id":null,"estimation_evidence_id":"$id-evidence","visibility":"private"}]""",
    )

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

        override fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig> = runCatching {
            config = NutritionSupabaseConfig(url = url, publishableKey = publishableKey)
            config
        }

        override fun saveSession(
            userId: String,
            email: String,
            accessToken: String,
            refreshToken: String,
        ): Result<NutritionSupabaseConfig> = runCatching {
            config = config.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
            config
        }

        override fun clearSession(): Boolean {
            config = config.copy(userId = "", email = "", accessToken = "", refreshToken = "")
            return true
        }
    }
}
