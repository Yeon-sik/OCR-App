package com.pricetrace.receiptscanner.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelValidatorTest {
    private fun draft() = NutritionLabelDraft(
        documentId = "nutrition-relations-test",
        productName = "테스트 상품",
        basisAmount = 100.0,
        basisUnit = "g",
        nutrients = mapOf(
            NutritionField.CALORIES_KCAL to 450.0,
            NutritionField.PROTEIN_GRAMS to 6.0,
            NutritionField.CARBS_GRAMS to 43.0,
            NutritionField.SUGARS_GRAMS to 8.0,
            NutritionField.FAT_GRAMS to 28.0,
            NutritionField.SATURATED_FAT_GRAMS to 9.0,
            NutritionField.TRANS_FAT_GRAMS to 0.4,
            NutritionField.SODIUM_MG to 150.0,
        ),
    )

    @Test fun literalTotalsWithContainedSugarAndFatAreValid() {
        val label = draft()
        assertTrue(NutritionLabelValidator.validate(label).isReadyForUpload)
        assertEquals(43.0, label.value(NutritionField.CARBS_GRAMS))
        assertEquals(8.0, label.value(NutritionField.SUGARS_GRAMS))
        assertEquals(28.0, label.value(NutritionField.FAT_GRAMS))
        assertEquals(9.0, label.value(NutritionField.SATURATED_FAT_GRAMS))
        assertEquals(0.4, label.value(NutritionField.TRANS_FAT_GRAMS))
    }

    @Test fun sugarsCannotExceedTotalCarbohydrate() {
        assertRelationFailure(NutritionField.SUGARS_GRAMS, 50.0, "당류", "탄수화물")
    }

    @Test fun addedSugarsCannotExceedSugars() {
        assertRelationFailure(NutritionField.ADDED_SUGARS_GRAMS, 9.0, "첨가당", "당류")
    }

    @Test fun saturatedFatCannotExceedTotalFat() {
        assertRelationFailure(NutritionField.SATURATED_FAT_GRAMS, 30.0, "포화지방", "지방")
    }

    @Test fun transFatCannotExceedTotalFat() {
        assertRelationFailure(NutritionField.TRANS_FAT_GRAMS, 30.0, "트랜스지방", "지방")
    }

    @Test fun equalParentAndChildValuesIncludingExplicitZeroAreValid() {
        val zero = draft().copy(nutrients = NutritionField.entries.associateWith { 0.0 })
        assertTrue(NutritionLabelValidator.validate(zero).isReadyForUpload)
        assertTrue(NutritionLabelValidator.validate(draft().withNutrient(
            NutritionField.ADDED_SUGARS_GRAMS, 8.0,
        )).isReadyForUpload)
    }

    @Test fun fiberAndCombinedFatChildrenHaveNoExtraHardLimit() {
        val label = draft().copy(nutrients = draft().nutrients + mapOf(
            NutritionField.FIBER_GRAMS to 50.0,
            NutritionField.SATURATED_FAT_GRAMS to 20.0,
            NutritionField.TRANS_FAT_GRAMS to 20.0,
        ))
        assertTrue(NutritionLabelValidator.validate(label).isReadyForUpload)
        assertNull(NutritionField.FIBER_GRAMS.includedIn)
    }

    @Test fun unknownOptionalValuesRemainAbsentAndKnownChildrenNeverFillMissingParents() {
        val label = draft().withNutrient(NutritionField.TRANS_FAT_GRAMS, null)
        assertTrue(NutritionLabelValidator.validate(label).isReadyForUpload)
        for (field in listOf(NutritionField.ADDED_SUGARS_GRAMS, NutritionField.TRANS_FAT_GRAMS,
            NutritionField.FIBER_GRAMS, NutritionField.CHOLESTEROL_MG)) {
            assertNull(label.value(field))
            assertFalse(label.nutrients.containsKey(field))
        }
        val missingParent = label.withNutrient(NutritionField.CARBS_GRAMS, null)
        val errors = NutritionLabelValidator.validate(missingParent).errors
        assertNull(missingParent.value(NutritionField.CARBS_GRAMS))
        assertTrue(errors.contains("탄수화물 값을 확인하세요."))
        assertFalse(errors.any { it.contains("포함되므로") })
    }

    @Test fun existingProvenanceBasisAndNumericChecksRemainActive() {
        val invalid = draft().copy(sourceReference = "", basisAmount = 0.0, basisUnit = "unknown",
            nutrients = draft().nutrients + mapOf(
                NutritionField.PROTEIN_GRAMS to Double.NaN,
                NutritionField.CHOLESTEROL_MG to -1.0,
            ))
        val errors = NutritionLabelValidator.validate(invalid).errors
        assertTrue(errors.any { it.contains("출처 참조") })
        assertTrue(errors.any { it.contains("기준량") })
        assertTrue(errors.any { it.contains("기준 단위") })
        assertTrue(errors.any { it.contains("단백질") })
        assertTrue(errors.any { it.contains("콜레스테롤") })
    }

    private fun assertRelationFailure(field: NutritionField, value: Double, child: String, parent: String) {
        val label = draft().withNutrient(field, value)
        val result = NutritionLabelValidator.validate(label)
        assertFalse(result.isReadyForUpload)
        assertEquals(listOf("${child} 값은 ${parent}에 포함되므로 ${parent} 값보다 클 수 없습니다."), result.errors)
        assertEquals(value, label.value(field))
    }
}
