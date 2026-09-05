package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.ConsumptionVerificationStatus
import com.pricetrace.receiptscanner.ingestion.IngestionConsumption
import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.MealComponentReference
import com.pricetrace.receiptscanner.ingestion.NutritionNutrientProvenance
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.RestaurantNutritionEstimate
import com.pricetrace.receiptscanner.ingestion.SourceAttachment
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
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

class FitnessMealProjectionSubmitterTest {
    @Test
    fun itemLevelConsumptionUsesActualConsumedAtAndFitnessMealRpc() = runTest {
        val eatenAt = "2026-09-06T08:10:00+09:00"
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"meal_import_id":"meal-import-1","meal_record_id":"meal-record-1","idempotent_replay":false,"eaten_at":"$eatenAt","record_date":"2026-09-06","item_count":1,"nutrition_food_ids":["food-1"],"contract_version":"verified-meal.v1"}]""",
            ),
        )
        val result = FitnessMealProjectionSubmitter(
            NutritionSupabaseGateway(FakeStore(signedIn()), transport),
        ).submit(
            request(
                envelope = packagedEnvelope(eatenAt),
                dependencyMetadata = """[{"nutrition_food_id":"food-1","canonical_import_id":"nutrition-1"}]""",
            ),
        )

        val success = result as ProjectionSubmission.Success
        assertEquals("meal-record-1", success.remoteId)
        val request = transport.requests.single()
        assertEquals(
            "https://nutrition.example.com/rest/v1/rpc/import_verified_meal_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(eatenAt, body["p_eaten_at"]?.jsonPrimitive?.content)
        assertEquals(eatenAt, body["p_source"]!!.jsonObject["consumed_at"]?.jsonPrimitive?.content)
        val item = body["p_items"]!!.jsonArray.single().jsonObject
        assertEquals("food-1", item["nutrition_food_id"]?.jsonPrimitive?.content)
        assertEquals("product-1", item["client_key"]?.jsonPrimitive?.content)
        assertEquals(40.0, item["consumed_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("g", item["consumed_unit"]?.jsonPrimitive?.content)
        assertEquals(0.92, item["confidence"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(JsonNull, body["p_pricetrace_identity"])
    }

    @Test
    fun componentOnlyMealDoesNotSendRestaurantMenuIdentity() = runTest {
        val eatenAt = "2026-09-06T19:30:00+09:00"
        val identity = PriceTraceIdentity(
            receiptId = "receipt-1",
            lines = emptyList(),
        )
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"meal_import_id":"meal-import-2","meal_record_id":"meal-record-2","idempotent_replay":false,"eaten_at":"$eatenAt","record_date":"2026-09-06","item_count":1,"nutrition_food_ids":["food-side-1"],"contract_version":"verified-meal.v1"}]""",
            ),
        )
        val result = FitnessMealProjectionSubmitter(
            NutritionSupabaseGateway(FakeStore(signedIn()), transport),
        ).submit(
            request(
                envelope = componentEnvelope(eatenAt),
                dependencyMetadata = """[{"nutrition_food_id":"food-side-1","component_import_id":"component-1"}]""",
                resolvedIdentity = ProjectionIdentity(priceTrace = identity),
            ),
        )

        assertTrue(result is ProjectionSubmission.Success)
        val body = Json.parseToJsonElement(requireNotNull(transport.requests.single().body)).jsonObject
        assertEquals(JsonNull, body["p_pricetrace_identity"])
        val item = body["p_items"]!!.jsonArray.single().jsonObject
        assertFalse(item.containsKey("pricetrace_identity"))
        assertEquals("meal_component_estimate", item["source_provenance"]!!.jsonObject["nutrition_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun mealWithoutActualConsumedAtIsRejectedBeforeRemoteCall() = runTest {
        val transport = QueueTransport()
        val result = FitnessMealProjectionSubmitter(
            NutritionSupabaseGateway(FakeStore(signedIn()), transport),
        ).submit(
            request(
                envelope = packagedEnvelope(consumedAt = null),
                dependencyMetadata = """[{"nutrition_food_id":"food-1"}]""",
            ),
        )

        val failure = result as ProjectionSubmission.Failure
        assertEquals("one_actual_consumed_at_required", failure.message)
        assertFalse(failure.retryable)
        assertTrue(transport.requests.isEmpty())
    }

    private fun request(
        envelope: YeonsikOcrEnvelope,
        dependencyMetadata: String,
        resolvedIdentity: ProjectionIdentity? = null,
    ) = ProjectionRequest(
        ingestionId = "ingestion-meal",
        projection = IngestionProjection.FITNESS_MEAL,
        canonicalPayload = "{}",
        resolvedIdentity = resolvedIdentity,
        idempotencyKey = "meal-projection-key",
        envelope = envelope,
        localDocumentId = "local-meal",
        revisionSeq = 2,
        canonicalFingerprint = "a".repeat(64),
        dependencyMetadataJson = mapOf(IngestionProjection.FITNESS_NUTRITION to dependencyMetadata),
    )

    private fun packagedEnvelope(consumedAt: String?): YeonsikOcrEnvelope = YeonsikOcrEnvelope(
        mode = IngestionMode.PACKAGED_PRODUCT,
        schemaVersion = YEONSIK_OCR_V2_SCHEMA,
        source = IngestionSource(
            producer = "chatgpt",
            sourceFiles = listOf(SourceAttachment("product-photo-1", SourceAttachmentType.PRODUCT_PHOTO)),
        ),
        nutrition = listOf(IngestionNutrition.ProductLabel("product-1", verifiedDraft())),
        consumption = listOf(
            IngestionConsumption(
                clientKey = "meal-1",
                consumedAt = consumedAt,
                status = ConsumptionVerificationStatus.USER_VERIFIED,
                items = listOf(
                    com.pricetrace.receiptscanner.ingestion.IngestionConsumptionItem(
                        nutritionClientKey = "product-1",
                        amount = 40.0,
                        unit = "g",
                        confidence = 0.92,
                    ),
                ),
            ),
        ),
    )

    private fun componentEnvelope(eatenAt: String): YeonsikOcrEnvelope {
        val provenance = NutritionField.requiredFields.associateWith { field ->
            NutritionNutrientProvenance(
                valueStatus = "estimated",
                sourceType = "food_image_estimate",
                evidenceRefs = listOf("food-side-1/${field.wireKey}"),
            )
        }
        return YeonsikOcrEnvelope(
            mode = IngestionMode.RESTAURANT,
            schemaVersion = YEONSIK_OCR_V2_SCHEMA,
            source = IngestionSource(
                producer = "chatgpt",
                sourceFiles = listOf(SourceAttachment("food-side-1", SourceAttachmentType.FOOD_PHOTO)),
            ),
            nutrition = listOf(
                IngestionNutrition.MealComponentEstimate(
                    clientKey = "side-1",
                    menuName = "Kimchi",
                    reference = MealComponentReference(
                        restaurantName = "Test Restaurant",
                        branchName = "Main",
                    ),
                    estimate = RestaurantNutritionEstimate(
                        nutrients = NutritionField.requiredFields.associateWith { 30.0 },
                        estimated = true,
                        confidence = "0.7",
                        nutrientProvenance = provenance,
                    ),
                ),
            ),
            consumption = listOf(
                IngestionConsumption(
                    clientKey = "meal-2",
                    consumedAt = eatenAt,
                    status = ConsumptionVerificationStatus.USER_VERIFIED,
                    items = listOf(
                        com.pricetrace.receiptscanner.ingestion.IngestionConsumptionItem(
                            nutritionClientKey = "side-1",
                            amount = 1.0,
                            unit = "serving",
                            confidence = 0.7,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun verifiedDraft() = NutritionLabelDraft(
        documentId = "nutrition-1",
        productName = "Test cereal",
        basisAmount = 100.0,
        basisUnit = "g",
        nutrients = NutritionField.requiredFields.associateWith { 10.0 },
    ).asUserVerified("2026-09-06T08:00:00+09:00")

    private fun signedIn() = NutritionSupabaseConfig(
        url = "https://nutrition.example.com",
        publishableKey = "publishable-key-with-safe-length",
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
