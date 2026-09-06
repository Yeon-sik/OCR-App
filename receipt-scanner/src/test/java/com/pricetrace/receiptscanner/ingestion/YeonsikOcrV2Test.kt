package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YeonsikOcrV2Test {
    @Test
    fun `v2 accepts the Project product candidate shape and converts its facts to internal evidence`() {
        val envelope = YeonsikOcrV2Json.decode(projectProductOnlyJson(), "local-project-product")
        val candidate = envelope.productCandidates.single()

        assertEquals("Test Drink", candidate.productName)
        assertEquals("Test Brand", candidate.brand)
        assertEquals("Zero", candidate.variant)
        assertEquals("500 ml", candidate.specification)
        assertEquals(listOf("product-photo-1"), candidate.sourceAttachmentIds)
        assertEquals(0.93, candidate.confidence, 0.0)
        assertTrue(candidate.evidence.isNotEmpty())
        assertTrue(candidate.evidence.all { it.sourceAttachmentIds == listOf("product-photo-1") })

        val encoded = JsonSupport.parse(YeonsikOcrV2Json.encode(envelope))
        val encodedCandidate = encoded["product_candidates"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "client_key", "product_name", "brand_name", "manufacturer_name", "variant_name",
                "specification_text", "content_amount", "content_unit", "package_count", "barcodes",
                "source_attachment_ids", "confidence",
            ),
            encodedCandidate.keys,
        )
        assertEquals("ean13", encodedCandidate["barcodes"]!!.jsonArray.single().jsonObject["scheme"]!!.jsonPrimitive.content)
        assertFalse(encodedCandidate.containsKey("evidence"))
    }

    @Test
    fun `v2 examples route product and meal component facts without importing external verification`() {
        val importer = ExternalJsonImporter()
        val packaged = success(importer.import(
            readExample("yeonsik-ocr.v2.packaged-product.example.json"),
            "local-packaged-v2",
        ))
        assertEquals(OcrWorkflowType.FITNESS_NUTRITION, packaged.workflowType)
        val packagedEnvelope = (packaged.draft as CanonicalDraft.Envelope).value
        assertEquals(YEONSIK_OCR_V2_SCHEMA, packagedEnvelope.schemaVersion)
        assertEquals("product-1", packagedEnvelope.productCandidates.single().clientKey)
        assertEquals(
            ProductCandidateBarcode(type = "ean13", value = "8801234567890"),
            packagedEnvelope.productCandidates.single().barcodes.single(),
        )
        assertEquals("2026-08-27T08:10:00+09:00", packagedEnvelope.consumption.single().consumedAt)
        assertEquals(40.0, packagedEnvelope.consumption.single().items.single().amount ?: -1.0, 0.0)
        assertEquals("estimated", packagedEnvelope.consumption.single().items.single().amountStatus)
        assertEquals(ConsumptionVerificationStatus.UNVERIFIED, packagedEnvelope.consumption.single().status)
        assertEquals(
            setOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.FITNESS_MEAL,
                IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
            ),
            packagedEnvelope.targets,
        )

        val restaurant = success(importer.import(
            readExample("yeonsik-ocr.v2.restaurant.example.json"),
            "local-restaurant-v2",
        ))
        assertEquals(OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT, restaurant.workflowType)
        val restaurantEnvelope = (restaurant.draft as CanonicalDraft.Envelope).value
        val component = restaurantEnvelope.nutrition.single { it.clientKey == "food-3" }
            as IngestionNutrition.MealComponentEstimate
        assertEquals(null, component.lineId)
        assertEquals("complimentary_side", component.componentRole)
        assertEquals(null, component.reference?.restaurantMenuId)
        assertTrue(restaurantEnvelope.links.none { it.nutritionClientKey == component.clientKey })
        assertEquals("2026-08-27T19:30:00+09:00", restaurantEnvelope.consumption.single().consumedAt)
        assertTrue(IngestionProjection.FITNESS_MEAL in restaurantEnvelope.targets)
        assertFalse(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE in restaurantEnvelope.targets)
    }

    @Test
    fun `v2 accepts component roles on every canonical nutrition outer and writes the canonical shape`() {
        val legacyRoot = JsonSupport.parse(readExample("yeonsik-ocr.v2.restaurant.example.json"))
        val canonicalRoot = JsonObject(legacyRoot.toMutableMap().apply {
            put(
                "nutrition",
                JsonArray(legacyRoot["nutrition"]!!.jsonArray.map(::canonicalNutritionOuter)),
            )
        })
        val envelope = YeonsikOcrV2Json.decode(
            Json.encodeToString(JsonElement.serializer(), canonicalRoot),
            "local-canonical-nutrition-roles",
        )
        val component = envelope.nutrition.single { it.clientKey == "food-3" }
            as IngestionNutrition.MealComponentEstimate
        assertEquals("complimentary_side", component.componentRole)
        assertEquals(null, component.reference)

        val encodedNutrition = JsonSupport.parse(YeonsikOcrV2Json.encode(envelope))["nutrition"]!!.jsonArray
        assertEquals(JsonNull, encodedNutrition.single { it.jsonObject["kind"]!!.jsonPrimitive.content == "restaurant_estimate" }
            .jsonObject["component_role"])
        assertEquals(
            "complimentary_side",
            encodedNutrition.single { it.jsonObject["kind"]!!.jsonPrimitive.content == "meal_component_estimate" }
                .jsonObject["component_role"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `v2 accepts the persisted product nutrition link alias and emits the canonical wire target`() {
        val envelope = YeonsikOcrV2Json.decode(
            projectPackagedExample(),
            "local-canonical-product-link",
        )
        assertTrue(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK in envelope.targets)

        val targets = JsonSupport.parse(YeonsikOcrV2Json.encode(envelope))["projection_targets"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("pricetrace_product_nutrition_link" in targets)
        assertFalse("fitness_product_nutrition_link" in targets)
    }

    @Test
    fun `v2 codec preserves amount status and only persisted reads restore verification`() {
        val source = success(ExternalJsonImporter().import(
            readExample("yeonsik-ocr.v2.packaged-product.example.json"),
            "local-v2-round-trip",
        )).let { (it.draft as CanonicalDraft.Envelope).value }
        val userVerified = source.copy(
            consumption = source.consumption.map { consumption ->
                consumption.copy(
                    status = ConsumptionVerificationStatus.USER_VERIFIED,
                    items = consumption.items.map { item -> item.copy(amountStatus = "estimated") },
                )
            },
        )

        val encoded = YeonsikOcrV2Json.encode(userVerified)
        val encodedItem = JsonSupport.parse(encoded)["consumption"]!!.jsonArray.single()
            .jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("estimated", encodedItem["amount_status"]?.jsonPrimitive?.content)
        assertEquals(
            ConsumptionVerificationStatus.UNVERIFIED,
            YeonsikOcrV2Json.decode(encoded, "local-v2-external-read").consumption.single().status,
        )
        assertEquals(
            ConsumptionVerificationStatus.USER_VERIFIED,
            YeonsikOcrV2Json.decode(
                encoded,
                "local-v2-persisted-read",
                preservePersistedVerification = true,
            ).consumption.single().status,
        )

        val tamperedExternalStatus = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace("\"status\": \"unverified\"", "\"status\": \"user_verified\"")
        assertEquals(
            ConsumptionVerificationStatus.UNVERIFIED,
            YeonsikOcrV2Json.decode(tamperedExternalStatus, "local-v2-tampered").consumption.single().status,
        )
    }

    @Test
    fun `v2 rejects identity fields that GPT must not provide`() {
        val invalid = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace(
                "\"source_version\": \"chatgpt-vision-v2\",",
                "\"source_version\": \"chatgpt-vision-v2\", \"catalog_product_id\": \"not-a-client-fact\",",
            )

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.decode(invalid, "local-invalid-v2")
        }
    }

    @Test
    fun `v2 rejects malformed observable product identifiers`() {
        val invalid = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace("\"value\": \"8801234567890\"", "\"value\": \"not-a-barcode\"")

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.decode(invalid, "local-invalid-product-identifier")
        }
    }

    @Test
    fun `v2 product candidate requires a readable product photo`() {
        val imported = success(ExternalJsonImporter().import(
            readExample("yeonsik-ocr.v2.packaged-product.example.json"),
            "local-product-evidence",
        ))
        val envelope = (imported.draft as CanonicalDraft.Envelope).value
        val artifactKey = IngestionArtifactKeys.productCandidate("product-1")

        assertTrue(
            IngestionEvidenceGate.evaluate(
                envelope = envelope,
                evidence = listOf(LocalEvidence("product-photo-1", SourceAttachmentType.PRODUCT_PHOTO, true)),
                artifactKeys = setOf(artifactKey),
            ).isAllowed,
        )
        assertFalse(
            IngestionEvidenceGate.evaluate(
                envelope = envelope,
                evidence = listOf(LocalEvidence("nutrition-label-1", SourceAttachmentType.NUTRITION_LABEL, true)),
                artifactKeys = setOf(artifactKey),
            ).isAllowed,
        )
    }

    @Test
    fun `v2 review object is strict but its status cannot self authorize`() {
        val invalid = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace(
                "\"review\": {\"status\": \"ready\", \"blocking_issues\": [], \"warnings\": []}",
                "\"review\": {\"status\": \"ready\", \"blocking_issues\": [], \"warnings\": [], \"user_verified\": true}",
            )

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.decode(invalid, "local-invalid-review")
        }
    }

    @Test
    fun `v2 accepts incomplete consumption as a review draft without inventing meal values`() {
        val draft = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace("\"consumed_at\": \"2026-08-27T08:10:00+09:00\"", "\"consumed_at\": null")
            .replace("\"amount\": 40", "\"amount\": null")
            .replace("\"unit\": \"g\"", "\"unit\": null")
            .replace("\"amount_status\": \"estimated\"", "\"amount_status\": \"unknown\"")

        val envelope = YeonsikOcrV2Json.decode(draft, "local-missing-meal-time")
        val consumption = envelope.consumption.single()
        assertEquals(null, consumption.consumedAt)
        assertEquals(null, consumption.items.single().amount)
        assertEquals(null, consumption.items.single().unit)
        assertEquals("unknown", consumption.items.single().amountStatus)
    }

    @Test
    fun `v2 encoder rejects legacy label confidence values`() {
        val source = success(ExternalJsonImporter().import(
            readExample("yeonsik-ocr.v2.restaurant.example.json"),
            "local-v2-strict-encoder",
        )).let { (it.draft as CanonicalDraft.Envelope).value }
        val legacyEstimate = source.copy(
            nutrition = source.nutrition.map { item ->
                if (item is IngestionNutrition.RestaurantEstimate) {
                    item.copy(estimate = item.estimate.copy(confidence = "high", confidenceScore = null))
                } else {
                    item
                }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.encode(legacyEstimate)
        }
    }

    @Test
    fun `version codec still dispatches the original v1 contract`() {
        val v1 = readExample("yeonsik-ocr.packaged-product.example.json")
        val envelope = YeonsikOcrEnvelopeCodec.decode(v1, "local-v1")
        assertEquals(YEONSIK_OCR_SCHEMA, envelope.schemaVersion)
        val encoded = YeonsikOcrEnvelopeCodec.encode(envelope)
        assertEquals(YEONSIK_OCR_SCHEMA, JsonSupport.parse(encoded)["schema_version"]?.jsonPrimitive?.content)
        assertNotNull(envelope.nutrition.single())
        assertFalse(JsonSupport.parse(encoded).containsKey("product_candidates"))
    }

    private fun success(outcome: ExternalJsonImportOutcome): com.pricetrace.receiptscanner.importer.ExternalJsonImportResult =
        (outcome as? ExternalJsonImportOutcome.Success)?.result
            ?: error("expected successful import: $outcome")

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }

    private fun canonicalNutritionOuter(value: JsonElement): JsonObject {
        val old = value.jsonObject
        val kind = old["kind"]!!.jsonPrimitive.content
        return buildJsonObject {
            put("client_key", old["client_key"]!!)
            put("kind", old["kind"]!!)
            put("line_id", old["line_id"] ?: JsonNull)
            put("menu_name", old["menu_name"] ?: JsonNull)
            put(
                "component_role",
                if (kind == "meal_component_estimate") JsonPrimitive("complimentary_side") else JsonNull,
            )
            put("payload", old["payload"] ?: JsonNull)
            put("estimate", old["estimate"] ?: JsonNull)
        }
    }

    private fun projectPackagedExample(): String {
        val legacyRoot = JsonSupport.parse(readExample("yeonsik-ocr.v2.packaged-product.example.json"))
        val oldCandidate = legacyRoot["product_candidates"]!!.jsonArray.single().jsonObject
        val sourceAttachmentIds = oldCandidate["evidence"]!!.jsonArray
            .flatMap { it.jsonObject["source_attachment_ids"]!!.jsonArray }
            .map { it.jsonPrimitive.content }
            .distinct()
        val candidate = buildJsonObject {
            put("client_key", oldCandidate["client_key"]!!)
            put("product_name", oldCandidate["product_name"]!!)
            put("brand_name", oldCandidate["brand"] ?: JsonNull)
            put("manufacturer_name", oldCandidate["manufacturer"] ?: JsonNull)
            put("variant_name", oldCandidate["variant"] ?: JsonNull)
            put("specification_text", oldCandidate["specification"] ?: JsonNull)
            put("content_amount", oldCandidate["content_amount"] ?: JsonNull)
            put("content_unit", oldCandidate["content_unit"] ?: JsonNull)
            put("package_count", oldCandidate["package_count"] ?: JsonNull)
            put("barcodes", JsonArray(oldCandidate["barcodes"]!!.jsonArray.map { value ->
                buildJsonObject {
                    put("scheme", value.jsonObject["type"]!!)
                    put("value", value.jsonObject["value"]!!)
                }
            }))
            put("source_attachment_ids", JsonArray(sourceAttachmentIds.map(::JsonPrimitive)))
            put("confidence", JsonPrimitive(0.93))
        }
        val canonicalRoot = JsonObject(legacyRoot.toMutableMap().apply {
            put("product_candidates", JsonArray(listOf(candidate)))
            put(
                "nutrition",
                JsonArray(legacyRoot["nutrition"]!!.jsonArray.map(::canonicalNutritionOuter)),
            )
        })
        return Json.encodeToString(JsonElement.serializer(), canonicalRoot)
    }

    private fun projectProductOnlyJson(): String = """
        {
          "schema_version": "yeonsik-ocr.v2",
          "mode": "packaged_product",
          "source": {
            "producer": "chatgpt",
            "source_files": [
              {"id": "product-photo-1", "type": "product_photo", "label": "상품 사진"}
            ],
            "user_text": "상품 사진만"
          },
          "merchant_candidate": null,
          "receipt": null,
          "product_candidates": [
            {
              "client_key": "product-1",
              "product_name": "Test Drink",
              "brand_name": "Test Brand",
              "manufacturer_name": null,
              "variant_name": "Zero",
              "specification_text": "500 ml",
              "content_amount": 500,
              "content_unit": "ml",
              "package_count": 1,
              "barcodes": [{"scheme": "ean13", "value": "8800000000000"}],
              "source_attachment_ids": ["product-photo-1"],
              "confidence": 0.93
            }
          ],
          "nutrition": [],
          "consumption": [],
          "classification_hints": {
            "cashos": {
              "category_hint": null,
              "institution_hint": null,
              "payment_method_hint": null
            }
          },
          "links": [],
          "projection_targets": ["pricetrace_product_candidate"],
          "review": {
            "status": "needs_review",
            "blocking_issues": ["source_image_required"],
            "warnings": []
          }
        }
    """.trimIndent()

    private object JsonSupport {
        private val json = Json { ignoreUnknownKeys = false }
        fun parse(value: String): JsonObject = json.parseToJsonElement(value).jsonObject
    }
}
