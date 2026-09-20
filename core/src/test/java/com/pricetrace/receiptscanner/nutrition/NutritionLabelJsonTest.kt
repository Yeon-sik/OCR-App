package com.pricetrace.receiptscanner.nutrition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelJsonTest {
    @Test
    fun localDraftRoundTripsEvidenceAndUnknownValues() {
        val original = completeDraft().copy(
            evidence = mapOf(
                NutritionField.SODIUM_MG.wireKey to listOf(
                    NutritionFieldEvidence("line-1", "page-1", "나트륨 120 mg", 0.91f),
                ),
            ),
        )

        val decoded = NutritionLabelJson.decode(NutritionLabelJson.encode(original))

        assertEquals(original, decoded)
        assertNull(decoded.value(NutritionField.FIBER_GRAMS))
    }

    @Test
    fun externalReferenceProvenanceRoundTripsWithoutFallingBackToOcr() {
        val original = completeDraft().copy(
            parserVersion = EXTERNAL_NUTRITION_LOOKUP_VERSION,
            sourceType = NutritionContract.EXTERNAL_REFERENCE_SOURCE_TYPE,
            sourceReference = "https://nutrition.example.com/products/test-cereal",
            sourceVersion = EXTERNAL_NUTRITION_LOOKUP_VERSION,
        )

        val decoded = NutritionLabelJson.decode(NutritionLabelJson.encode(original))

        assertEquals(original, decoded)
        val wire = Json.parseToJsonElement(NutritionLabelJson.encode(decoded)).jsonObject
        assertEquals("external_reference", wire["source_type"]?.toString()?.trim('"'))
        assertEquals(
            "https://nutrition.example.com/products/test-cereal",
            wire["source_reference"]?.toString()?.trim('"'),
        )
        assertEquals(EXTERNAL_NUTRITION_LOOKUP_VERSION, wire["source_version"]?.toString()?.trim('"'))
    }

    @Test
    fun serverRowContainsLatestFitnessColumnsButNoPrivateOcrEvidence() {
        val verified = completeDraft().asUserVerified("2026-08-11T10:00:00+09:00")
        val encoded = NutritionLabelJson.encodeServerRow(
            draft = verified,
            ownerId = "fitness-user",
            updatedAt = "2026-08-11T10:00:00+09:00",
            revision = 3,
        )
        val root = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("ocr-nutrition:ocr-document", root.getValue("id").toString().trim('"'))
        assertEquals("fitness-user", root.getValue("owner_id").toString().trim('"'))
        assertEquals("external_menu", root.getValue("kind").toString().trim('"'))
        assertEquals("private", root.getValue("visibility").toString().trim('"'))
        assertEquals("product_label_ocr", root.getValue("source_type").toString().trim('"'))
        assertEquals(JsonNull, root[NutritionField.FIBER_GRAMS.wireKey])
        assertFalse(root.containsKey("evidence"))
        assertFalse(root.containsKey("raw_text"))
        assertFalse(encoded.contains("ocr line text"))
        assertTrue(root.containsKey("category"))
        assertTrue(root.containsKey("cooking_method"))
        assertTrue(root.containsKey("revision"))
    }

    private fun completeDraft(): NutritionLabelDraft = NutritionLabelDraft(
        documentId = "ocr-document",
        productName = "테스트 상품",
        brand = "테스트 브랜드",
        basisAmount = 100.0,
        basisUnit = "g",
        nutrients = mapOf(
            NutritionField.CALORIES_KCAL to 200.0,
            NutritionField.PROTEIN_GRAMS to 10.0,
            NutritionField.CARBS_GRAMS to 30.0,
            NutritionField.FAT_GRAMS to 5.0,
            NutritionField.SODIUM_MG to 120.0,
            NutritionField.SATURATED_FAT_GRAMS to 2.0,
            NutritionField.SUGARS_GRAMS to 7.0,
        ),
        parseWarnings = listOf("ocr line text"),
    )
}
