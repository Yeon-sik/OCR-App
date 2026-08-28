package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.NutritionNutrientProvenance
import com.pricetrace.receiptscanner.ingestion.NutritionRange
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