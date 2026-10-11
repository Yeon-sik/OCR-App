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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessMealProjectionSubmitterTest {
    @Test
    fun v5ExplicitConsumptionReusesTheExistingMealRpc() = runTest {
        val eatenAt = "2026-10-09T12:00:00+09:00"
        val draft = com.pricetrace.receiptscanner.ingestion.YeonsikOcrV5Json.decode(
            java.io.File("../examples/yeonsik-ocr.v5.restaurant-purchase.example.json").readText(), "v5-meal",
        )
        val envelope = draft.copy(
            source = draft.source.copy(userText = "$eatenAt 1 serving 섭취"),
            consumption = listOf(IngestionConsumption("consumption-1", consumedAt = eatenAt, status = ConsumptionVerificationStatus.USER_VERIFIED,
                items = listOf(com.pricetrace.receiptscanner.ingestion.IngestionConsumptionItem("food-1", 1.0, "serving", 1.0, "user_provided")))),
        )
        val transport = QueueTransport(NutritionHttpResponse(200,
            """[{"meal_import_id":"meal-v5","meal_record_id":"record-v5","idempotent_replay":false,"eaten_at":"$eatenAt","record_date":"2026-10-09","item_count":1,"nutrition_food_ids":["food-v5"],"contract_version":"verified-meal.v1"}]"""))
        val result = FitnessMealProjectionSubmitter(NutritionSupabaseGateway(FakeStore(signedIn()), transport)).submit(
            request(envelope, """[{"nutrition_food_id":"food-v5","canonical_import_id":"nutrition-v5","input_contract":"food-estimate.v1","nutrition_client_key":"food-1","visibility":"private"},{"nutrition_food_id":"food-v5","canonical_import_id":"nutrition-v5","visibility":"public","publication_revision":1}]"""),
        )
        assertTrue(result is ProjectionSubmission.Success)
        assertTrue(transport.requests.single().url.endsWith("/import_verified_meal_v1"))
        val body = Json.parseToJsonElement(transport.requests.single().body!!).jsonObject
        assertEquals(eatenAt, body.getValue("p_eaten_at").jsonPrimitive.content)
        assertEquals("dining_out", body.getValue("p_source").jsonObject.getValue("meal_kind").jsonPrimitive.content)
        assertEquals("yeonsik-ocr.v5", body.getValue("p_source").jsonObject.getValue("schema_version").jsonPrimitive.content)
        assertEquals("맛단-맛있는 다이어트식단", body.getValue("p_source").jsonObject.getValue("store_name").jsonPrimitive.content)
        assertEquals("영등포점", body.getValue("p_source").jsonObject.getValue("branch_name").jsonPrimitive.content)
        assertEquals("food-1", body.getValue("p_items").jsonArray.single().jsonObject.getValue("client_key").jsonPrimitive.content)
    }

    @Test
    fun v5MealKeepsTheSharedSellerAndBranchAcrossConsumedItems() = runTest {
        val source = submittedSource(withSecondPurchase(v5MealEnvelope(), "맛단-맛있는 다이어트식단", "영등포점"))
        assertEquals(JsonPrimitive("맛단-맛있는 다이어트식단"), source["store_name"])
        assertEquals(JsonPrimitive("영등포점"), source["branch_name"])
    }

    @Test
    fun v5MealKeepsOnlyTheSharedSellerWhenConsumedBranchesDiffer() = runTest {
        val source = submittedSource(withSecondPurchase(v5MealEnvelope(), "맛단-맛있는 다이어트식단", "강남점"))
        assertEquals(JsonPrimitive("맛단-맛있는 다이어트식단"), source["store_name"])
        assertFalse(source.containsKey("branch_name"))
    }

    @Test
    fun v5MealOmitsMixedSellersEvenWhenBranchLabelsMatch() = runTest {
        val source = submittedSource(withSecondPurchase(v5MealEnvelope(), "별도 식당", "영등포점"))
        assertFalse(source.containsKey("store_name"))
        assertFalse(source.containsKey("branch_name"))
    }

    @Test
    fun v5UnknownBranchNeverSelectsAKnownBranchFromOnlySomeConsumedItems() = runTest {
        val original = v5MealEnvelope()
        val unknown = original.copy(purchaseRecords = original.purchaseRecords.map { it.copy(sellerBranchName = null) })
        listOf(unknown, withSecondPurchase(unknown, "맛단-맛있는 다이어트식단", "영등포점")).forEach { envelope ->
            val source = submittedSource(envelope)
            assertEquals(JsonPrimitive("맛단-맛있는 다이어트식단"), source["store_name"])
            assertFalse(source.containsKey("branch_name"))
        }
    }

    @Test
    fun v5MealUsesLineSellerOverrideWithoutInheritingRecordBranch() = runTest {
        val original = v5MealEnvelope()
        val source = submittedSource(original.copy(purchaseRecords = original.purchaseRecords.map { record ->
            record.copy(lineItems = record.lineItems.map { it.copy(sellerOverride = "별도 식당 강남점") })
        }))
        assertEquals(JsonPrimitive("별도 식당 강남점"), source["store_name"])
        assertFalse(source.containsKey("branch_name"))
    }

    @Test
    fun v5MealSnapshotFollowsOnlyConsumedLocalLinksInsteadOfRecordOrder() = runTest {
        val original = v5MealEnvelope()
        val unconsumedOther = withSecondPurchase(original, "별도 식당", "강남점", consumed = false)
        val firstSource = submittedSource(unconsumedOther)
        assertEquals(JsonPrimitive("맛단-맛있는 다이어트식단"), firstSource["store_name"])
        assertEquals(JsonPrimitive("영등포점"), firstSource["branch_name"])
        val consumedSecond = unconsumedOther.copy(consumption = original.consumption.map { consumption ->
            consumption.copy(items = consumption.items.map { it.copy(nutritionClientKey = "food-2") })
        })
        val secondSource = submittedSource(consumedSecond)
        assertEquals(JsonPrimitive("별도 식당"), secondSource["store_name"])
        assertEquals(JsonPrimitive("강남점"), secondSource["branch_name"])
    }

    @Test
    fun v5PurchaseWithoutExplicitConsumptionNeverCallsMealRpc() = runTest {
        val envelope = com.pricetrace.receiptscanner.ingestion.YeonsikOcrV5Json.decode(
            java.io.File("../examples/yeonsik-ocr.v5.restaurant-purchase.example.json").readText(), "no-consumption",
        )
        val transport = QueueTransport()
        val result = FitnessMealProjectionSubmitter(NutritionSupabaseGateway(FakeStore(signedIn()), transport))
            .submit(request(envelope, "[]")) as ProjectionSubmission.Failure
        assertEquals("consumption_artifact_missing", result.message)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun v2ReceiptAndReceiptFreeRestaurantMealKeepTheirOwnSourcePair() = runTest {
        val receipt = com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec.decode(
            java.io.File("../examples/yeonsik-ocr.v2.restaurant.example.json").readText(), "v2-meal-branch",
        ).let { envelope -> envelope.copy(consumption = envelope.consumption.map {
            it.copy(status = ConsumptionVerificationStatus.USER_VERIFIED)
        }) }
        val sourceReceipt = requireNotNull(receipt.receipt)
        val receiptSource = submittedSource(receipt)
        assertEquals(JsonPrimitive(sourceReceipt.merchant.name), receiptSource["store_name"])
        assertEquals(JsonPrimitive("강남점"), receiptSource["branch_name"])
        val unknownBranch = submittedSource(receipt.copy(receipt = sourceReceipt.copy(
            merchant = sourceReceipt.merchant.copy(branchName = null),
        )))
        assertEquals(JsonPrimitive(sourceReceipt.merchant.name), unknownBranch["store_name"])
        assertFalse(unknownBranch.containsKey("branch_name"))

        val draft = com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec.decode(
            java.io.File("../examples/yeonsik-ocr.v2.restaurant-food-photo.example.json").readText(), "v2-free-meal",
        )
        val free = draft.copy(consumption = receipt.consumption.take(1).map { consumption ->
            consumption.copy(nutritionClientKeys = emptySet(),
                items = listOf(consumption.items.first().copy(nutritionClientKey = draft.nutrition.single().clientKey)))
        })
        val freeSource = submittedSource(free)
        assertEquals(JsonPrimitive("키키덮밥"), freeSource["store_name"])
        assertEquals(JsonPrimitive("영등포점"), freeSource["branch_name"])
    }

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
        assertEquals("estimated", item["source_provenance"]!!.jsonObject["amount_status"]?.jsonPrimitive?.content)
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
        val provenance = item["source_provenance"]!!.jsonObject
        assertEquals("meal_component_estimate", provenance["nutrition_kind"]?.jsonPrimitive?.content)
        assertEquals("complimentary_side", provenance["component_role"]?.jsonPrimitive?.content)
        assertEquals("estimated", provenance["amount_status"]?.jsonPrimitive?.content)
    }

    @Test
    fun unverifiedConsumptionCannotReachFitnessMealRpc() = runTest {
        val transport = QueueTransport()
        val result = FitnessMealProjectionSubmitter(
            NutritionSupabaseGateway(FakeStore(signedIn()), transport),
        ).submit(
            request(
                envelope = packagedEnvelope("2026-09-06T08:10:00+09:00").copy(
                    consumption = packagedEnvelope("2026-09-06T08:10:00+09:00").consumption.map {
                        it.copy(status = ConsumptionVerificationStatus.UNVERIFIED)
                    },
                ),
                dependencyMetadata = """[{"nutrition_food_id":"food-1"}]""",
            ),
        )

        assertEquals(
            "consumption_artifact_not_user_verified",
            (result as ProjectionSubmission.Failure).message,
        )
        assertTrue(transport.requests.isEmpty())
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

    @Test
    fun incompleteConsumptionIsRejectedBeforeRemoteCall() = runTest {
        val transport = QueueTransport()
        val source = packagedEnvelope("2026-09-06T08:10:00+09:00")
        val result = FitnessMealProjectionSubmitter(
            NutritionSupabaseGateway(FakeStore(signedIn()), transport),
        ).submit(
            request(
                envelope = source.copy(
                    consumption = source.consumption.map { consumption ->
                        consumption.copy(
                            items = consumption.items.map {
                                it.copy(amount = null, unit = null, amountStatus = "unknown")
                            },
                        )
                    },
                ),
                dependencyMetadata = """[{"nutrition_food_id":"food-1"}]""",
            ),
        )

        val failure = result as ProjectionSubmission.Failure
        assertEquals("consumption_artifact_incomplete", failure.message)
        assertFalse(failure.retryable)
        assertTrue(transport.requests.isEmpty())
    }

    private fun v5MealEnvelope(): YeonsikOcrEnvelope {
        val draft = com.pricetrace.receiptscanner.ingestion.YeonsikOcrV5Json.decode(
            java.io.File("../examples/yeonsik-ocr.v5.restaurant-purchase.example.json").readText(), "v5-meal",
        )
        val eatenAt = "2026-10-09T12:00:00+09:00"
        return draft.copy(
            source = draft.source.copy(userText = "$eatenAt 1 serving 섭취"),
            consumption = listOf(IngestionConsumption("consumption-1", consumedAt = eatenAt,
                status = ConsumptionVerificationStatus.USER_VERIFIED,
                items = listOf(com.pricetrace.receiptscanner.ingestion.IngestionConsumptionItem(
                    "food-1", 1.0, "serving", 1.0, "user_provided",
                )))),
        )
    }

    private fun withSecondPurchase(
        original: YeonsikOcrEnvelope,
        seller: String,
        branch: String?,
        consumed: Boolean = true,
    ): YeonsikOcrEnvelope {
        val record = original.purchaseRecords.single()
        val nutrition = original.nutrition.single() as IngestionNutrition.RestaurantEstimate
        return original.copy(
            purchaseRecords = original.purchaseRecords + record.copy(clientKey = "purchase-2", seller = seller,
                sellerBranchName = branch, lineItems = record.lineItems.map { it.copy(lineKey = "line-2") }),
            nutrition = original.nutrition + nutrition.copy(clientKey = "food-2"),
            purchaseNutritionLinks = original.purchaseNutritionLinks + original.purchaseNutritionLinks.single().copy(
                purchaseRecordClientKey = "purchase-2", purchaseLineKey = "line-2", nutritionClientKey = "food-2",
            ),
            consumption = if (!consumed) original.consumption else original.consumption.map {
                it.copy(items = it.items + it.items.single().copy(nutritionClientKey = "food-2"))
            },
        ).also { com.pricetrace.receiptscanner.ingestion.YeonsikOcrV5Json.validate(it) }
    }

    private suspend fun submittedSource(envelope: YeonsikOcrEnvelope): JsonObject {
        val eatenAt = envelope.consumption.first().consumedAt
        val keys = envelope.consumption.flatMap { it.items }.map { it.nutritionClientKey }
        val foodIds = JsonArray(keys.map { JsonPrimitive("food-$it") })
        val transport = QueueTransport(NutritionHttpResponse(200,
            """[{"meal_import_id":"meal-branch","meal_record_id":"record-branch","idempotent_replay":false,"eaten_at":"$eatenAt","record_date":"${eatenAt?.take(10)}","item_count":${keys.size},"nutrition_food_ids":$foodIds,"contract_version":"verified-meal.v1"}]"""))
        val metadata = JsonArray(envelope.nutrition.map { item -> buildJsonObject {
            put("nutrition_food_id", JsonPrimitive("food-${item.clientKey}"))
            put("input_contract", JsonPrimitive(FOOD_ESTIMATE_V1))
            put("nutrition_client_key", JsonPrimitive(item.clientKey))
        } }).toString()
        val result = FitnessMealProjectionSubmitter(NutritionSupabaseGateway(FakeStore(signedIn()), transport))
            .submit(request(envelope, metadata))
        assertTrue(result.toString(), result is ProjectionSubmission.Success)
        return Json.parseToJsonElement(transport.requests.single().body!!).jsonObject.getValue("p_source").jsonObject
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
