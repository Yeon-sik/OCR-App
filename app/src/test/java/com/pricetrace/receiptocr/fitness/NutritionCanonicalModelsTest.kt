package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.MealComponentReference
import com.pricetrace.receiptscanner.ingestion.NutritionNutrientProvenance
import com.pricetrace.receiptscanner.ingestion.NutritionRange
import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.ingestion.ProductCandidateEvidence
import com.pricetrace.receiptscanner.ingestion.RestaurantNutritionEstimate
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionFieldEvidence
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionCanonicalModelsTest {
    @Test
    fun productLabelUsesNutritionLabelContractAndPreservesSevenObservedNutrients() {
        val draft = verifiedDraft()
        val payload = CanonicalNutritionPayloadFactory.fromProductLabel(
            localDocumentId = "ocr-label-session",
            revisionSeq = 3,
            idempotencyKey = "nutrition-label-key",
            draft = draft,
        )
        val root = Json.parseToJsonElement(payload.toRpcJson()).jsonObject

        assertEquals(NUTRITION_LABEL_V1, root["p_input_contract"]?.jsonPrimitive?.content)
        assertEquals("ocr-label-session", payload.sourceDocumentRef.substringAfter("/ingestion/").substringBefore("/revision"))
        assertEquals(true, root["p_user_verified"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(CanonicalNutritionImportPayload.REQUIRED_NUTRIENTS, root["p_required_nutrients"]!!.jsonObject.keys)
        assertEquals(CanonicalNutritionImportPayload.REQUIRED_NUTRIENTS, root["p_nutrient_provenance"]!!.jsonObject.keys)
        assertEquals(JsonNull, root["p_estimation_evidence"])

        val required = root["p_required_nutrients"]!!.jsonObject
        val provenance = root["p_nutrient_provenance"]!!.jsonObject
        NutritionField.requiredFields.forEach { field ->
            val key = field.wireKey
            assertEquals(draft.value(field), required[key]?.jsonPrimitive?.content?.toDouble())
            val item = provenance[key]!!.jsonObject
            assertEquals(draft.value(field), item["value"]?.jsonPrimitive?.content?.toDouble())
            assertEquals("observed", item["value_status"]?.jsonPrimitive?.content)
            assertEquals("product_label_ocr", item["source_type"]?.jsonPrimitive?.content)
            assertTrue(item["evidence_refs"]!!.toString().contains("ocr-line-$key"))
        }
        assertFalse(root["p_provenance"]!!.jsonObject["estimated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun envelopeVerificationFlagCannotAuthorizeParsedNutritionDraft() {
        val parsed = verifiedDraft().copy(
            status = com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus.PARSED,
            confirmedAt = null,
        )
        assertFalse(
            runCatching {
                CanonicalNutritionPayloadFactory.fromProductLabel(
                    localDocumentId = "ocr-label-session",
                    revisionSeq = 1,
                    idempotencyKey = "parsed-label-key",
                    draft = parsed,
                    envelopeVerified = true,
                )
            }.isSuccess,
        )
    }

    @Test
    fun productLabelCarriesOnlyExplicitObservedHierarchyOnFitnessV3Rpc() {
        val candidate = ProductCandidate(
            clientKey = "product-1",
            productName = "Test cereal",
            brand = "Brand",
            subBrand = "Brand Original",
            manufacturer = "Test Foods",
            sourceAttachmentIds = listOf("product-photo-1"),
            evidence = listOf(
                ProductCandidateEvidence(
                    sourceAttachmentIds = listOf("product-photo-1"),
                    sourceType = "product_photo",
                    sourceRef = "product-photo-1",
                    field = "manufacturer_name",
                    observedValue = "Test Foods",
                ),
            ),
        )
        val payload = CanonicalNutritionPayloadFactory.fromProductLabel(
            localDocumentId = "ocr-label-session",
            revisionSeq = 3,
            idempotencyKey = "nutrition-label-v3-key",
            draft = verifiedDraft(),
            productCandidate = candidate,
            useV3Contract = true,
        )

        val v1 = Json.parseToJsonElement(payload.toRpcJson()).jsonObject
        assertFalse(v1.containsKey("p_manufacturer_name"))
        assertFalse(v1.containsKey("p_brand_name"))
        assertFalse(v1.containsKey("p_sub_brand_name"))
        assertFalse(v1.containsKey("p_product_name"))

        val v3 = Json.parseToJsonElement(payload.toRpcJson(includeHierarchyFields = true)).jsonObject
        assertEquals(NUTRITION_LABEL_V1, v3["p_input_contract"]?.jsonPrimitive?.content)
        assertEquals("Test Foods", v3["p_manufacturer_name"]?.jsonPrimitive?.content)
        assertEquals("Brand", v3["p_brand_name"]?.jsonPrimitive?.content)
        assertEquals("Brand Original", v3["p_sub_brand_name"]?.jsonPrimitive?.content)
        assertEquals("Test cereal", v3["p_product_name"]?.jsonPrimitive?.content)
        assertFalse(v3.containsKey("p_category_hierarchy"))
        assertFalse(v3.toString().contains("product_label_hierarchy"))
    }

    @Test
    fun restaurantEstimateUsesFoodEstimateContractWithConfidenceRangeAndDeclaredEvidence() {
        val evidenceRefs = NutritionField.requiredFields.associate { field ->
            field to NutritionNutrientProvenance(
                valueStatus = "estimated",
                sourceType = "food_image_estimate",
                evidenceRefs = listOf("food-photo-1/${field.wireKey}"),
            )
        }
        val estimate = RestaurantNutritionEstimate(
            nutrients = NutritionField.requiredFields.associateWith { field -> value(field) },
            estimated = true,
            confidence = "medium",
            ranges = mapOf(
                NutritionField.CALORIES_KCAL to NutritionRange(min = 400.0, point = 500.0, max = 600.0),
            ),
            nutrientProvenance = evidenceRefs,
            confidenceScore = 0.82,
        )
        val payload = CanonicalNutritionPayloadFactory.fromRestaurantEstimate(
            localDocumentId = "ocr-restaurant-session",
            revisionSeq = 4,
            idempotencyKey = "food-estimate-key",
            restaurantName = "Test Restaurant",
            item = IngestionNutrition.RestaurantEstimate(
                clientKey = "food-1",
                lineId = "line-1",
                menuName = "Noodles",
                estimate = estimate,
            ),
        )
        val root = Json.parseToJsonElement(payload.toRpcJson()).jsonObject

        assertEquals(FOOD_ESTIMATE_V1, root["p_input_contract"]?.jsonPrimitive?.content)
        assertEquals("Test Restaurant", root["p_brand"]?.jsonPrimitive?.content)
        assertEquals(CanonicalNutritionImportPayload.REQUIRED_NUTRIENTS, root["p_required_nutrients"]!!.jsonObject.keys)
        assertEquals(CanonicalNutritionImportPayload.REQUIRED_NUTRIENTS, root["p_nutrient_provenance"]!!.jsonObject.keys)

        val estimationEvidence = root["p_estimation_evidence"]!!.jsonObject
        assertEquals(0.82, estimationEvidence["confidence"]?.jsonPrimitive?.content?.toDouble())
        val caloriesRange = estimationEvidence["range"]!!.jsonObject["calories_kcal"]!!.jsonObject
        assertEquals(400.0, caloriesRange["min"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(500.0, caloriesRange["point"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(600.0, caloriesRange["max"]?.jsonPrimitive?.content?.toDouble())

        NutritionField.requiredFields.forEach { field ->
            val item = root["p_nutrient_provenance"]!!.jsonObject[field.wireKey]!!.jsonObject
            assertEquals("estimated", item["value_status"]?.jsonPrimitive?.content)
            assertEquals("food_image_estimate", item["source_type"]?.jsonPrimitive?.content)
            assertTrue(item["evidence_refs"]!!.toString().contains("food-photo-1/${field.wireKey}"))
        }
        assertTrue(root["p_provenance"]!!.jsonObject["estimated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun mealComponentRoleIsProvenanceOnlyAndNeverCreatesPriceTraceIdentity() {
        val estimate = RestaurantNutritionEstimate(
            nutrients = NutritionField.requiredFields.associateWith { 30.0 },
            estimated = true,
            confidence = "0.70",
            nutrientProvenance = NutritionField.requiredFields.associateWith { field ->
                NutritionNutrientProvenance(
                    valueStatus = "estimated",
                    sourceType = "food_image_estimate",
                    evidenceRefs = listOf("food-side-1/${field.wireKey}"),
                )
            },
        )
        val payload = CanonicalNutritionPayloadFactory.fromMealComponentEstimate(
            localDocumentId = "ocr-component-session",
            revisionSeq = 2,
            idempotencyKey = "component-key",
            restaurantName = "Test Restaurant",
            item = IngestionNutrition.MealComponentEstimate(
                clientKey = "food-side-1",
                menuName = "Complimentary kimchi",
                componentRole = "complimentary_side",
                reference = MealComponentReference(
                    restaurantName = "Test Restaurant",
                    branchName = "Main",
                ),
                estimate = estimate,
            ),
        )
        val root = Json.parseToJsonElement(payload.toRpcJson()).jsonObject

        assertEquals(
            "complimentary_side",
            root["p_provenance"]!!.jsonObject["component_role"]?.jsonPrimitive?.content,
        )
        assertEquals(JsonNull, root["p_pricetrace_identity"])
    }

    private fun verifiedDraft(): NutritionLabelDraft = NutritionLabelDraft(
        documentId = "remote-label",
        parserVersion = "parser-test",
        productName = "Test cereal",
        brand = "Brand",
        category = "processed",
        basisAmount = 100.0,
        basisUnit = "g",
        nutrients = NutritionField.requiredFields.associateWith(::value),
        evidence = NutritionField.requiredFields.associate { field ->
            field.wireKey to listOf(
                NutritionFieldEvidence(
                    ocrLineId = "ocr-line-${field.wireKey}",
                    pageId = "page-1",
                    rawText = "${field.wireKey}: value",
                    confidence = 0.99f,
                ),
            )
        }.toMap(),
    ).asUserVerified("2026-08-28T10:00:00+09:00")

    private fun value(field: NutritionField): Double = when (field) {
        NutritionField.CALORIES_KCAL -> 380.0
        NutritionField.PROTEIN_GRAMS -> 10.0
        NutritionField.CARBS_GRAMS -> 70.0
        NutritionField.FAT_GRAMS -> 5.0
        NutritionField.SODIUM_MG -> 100.0
        NutritionField.SATURATED_FAT_GRAMS -> 1.0
        NutritionField.SUGARS_GRAMS -> 12.0
        else -> 0.0
    }
}
