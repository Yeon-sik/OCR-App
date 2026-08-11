package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.ocr.OcrBlock
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import com.pricetrace.receiptscanner.ocr.OcrLine
import com.pricetrace.receiptscanner.ocr.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelParserTest {
    private val parser = NutritionLabelParser()

    @Test
    fun parsesKoreanPackagedFoodLabelWithoutInventingUnknownOptionalValues() {
        val draft = parser.parse(
            document(
                "튼튼 프로틴바",
                "영양정보 1회 제공량 45 g",
                "열량 210 kcal",
                "나트륨 150 mg 8%",
                "탄수화물 23 g 7%",
                "당류 8 g 8%",
                "지방 9 g 17%",
                "포화 지방 3 g 20%",
                "첨가 당류 2 g",
                "단백질 15 g 27%",
            ),
        )

        assertEquals("튼튼 프로틴바", draft.productName)
        assertEquals(45.0, draft.basisAmount)
        assertEquals("g", draft.basisUnit)
        assertEquals(210.0, draft.value(NutritionField.CALORIES_KCAL))
        assertEquals(150.0, draft.value(NutritionField.SODIUM_MG))
        assertEquals(15.0, draft.value(NutritionField.PROTEIN_GRAMS))
        assertEquals(3.0, draft.value(NutritionField.SATURATED_FAT_GRAMS))
        assertEquals(2.0, draft.value(NutritionField.ADDED_SUGARS_GRAMS))
        assertEquals(9.0, draft.value(NutritionField.FAT_GRAMS))
        assertNull(draft.value(NutritionField.FIBER_GRAMS))
        assertNull(draft.value(NutritionField.CHOLESTEROL_MG))
        assertTrue(NutritionLabelValidator.validate(draft).isReadyForUpload)
    }

    @Test
    fun convertsOnlyCompatibleMassUnits() {
        val draft = parser.parse(
            document(
                "샘플 음료",
                "100 ml 당",
                "열량 50 kcal",
                "단백질 900 mg",
                "탄수화물 12 g",
                "지방 0 g",
                "나트륨 0.2 g",
                "포화지방 0 mg",
                "당류 10 g",
            ),
        )

        assertEquals(0.9, draft.value(NutritionField.PROTEIN_GRAMS))
        assertEquals(200.0, draft.value(NutritionField.SODIUM_MG))
        assertEquals(0.0, draft.value(NutritionField.SATURATED_FAT_GRAMS))
        assertEquals("ml", draft.basisUnit)
    }

    @Test
    fun conflictingDuplicateValuesRemainUnknownForManualReview() {
        val draft = parser.parse(
            document(
                "두 기준 상품",
                "1회 제공량 30 g",
                "열량 100 kcal",
                "열량 300 kcal",
                "단백질 3 g",
                "탄수화물 10 g",
                "지방 2 g",
                "나트륨 50 mg",
                "포화지방 1 g",
                "당류 4 g",
            ),
        )

        assertNull(draft.value(NutritionField.CALORIES_KCAL))
        assertTrue(draft.parseWarnings.any { it.contains("열량") })
        assertFalse(NutritionLabelValidator.validate(draft).isReadyForUpload)
    }

    @Test
    fun validatorRequiresFitnessSevenAndSupportedBasisWithoutDefaultingMissingToZero() {
        val incomplete = NutritionLabelDraft(
            documentId = "ocr-test",
            productName = "테스트",
            basisAmount = 1.0,
            basisUnit = "serving",
            nutrients = mapOf(NutritionField.CALORIES_KCAL to 0.0),
        )

        val result = NutritionLabelValidator.validate(incomplete)

        assertFalse(result.isReadyForUpload)
        assertTrue(result.errors.any { it.contains("단백질") })
        assertNull(incomplete.value(NutritionField.PROTEIN_GRAMS))
    }

    private fun document(vararg texts: String): OcrDocument {
        val documentId = "ocr-nutrition-test"
        val pageId = "page-nutrition-test"
        val lines = texts.mapIndexed { index, text ->
            OcrLine(
                id = "line-$index",
                pageId = pageId,
                pageIndex = 0,
                text = text,
                boundingBox = BoundingBox(0, index * 20, 600, index * 20 + 18),
                elements = emptyList(),
                confidence = 0.95f,
                recognitionOrder = index,
            )
        }
        val block = OcrBlock(
            id = "block-0",
            pageId = pageId,
            pageIndex = 0,
            text = texts.joinToString("\n"),
            boundingBox = null,
            lines = lines,
            recognitionOrder = 0,
        )
        return OcrDocument(
            documentId = documentId,
            rawText = block.text,
            pages = listOf(OcrPage(pageId, 0, block.text, listOf(block))),
            engine = OcrEngineInfo("synthetic", "1"),
        )
    }
}
