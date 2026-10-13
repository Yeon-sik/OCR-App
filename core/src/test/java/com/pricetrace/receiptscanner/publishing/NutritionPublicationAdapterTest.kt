package com.pricetrace.receiptscanner.publishing

import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionFieldEvidence
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.publishing.adapter.NutritionPublicationAdapter
import com.pricetrace.receiptscanner.publishing.adapter.PublicationAdaptation
import org.junit.Assert.*
import org.junit.Test

class NutritionPublicationAdapterTest {
    @Test fun verifiedDraftPreservesAllFieldsUnitsAndProvenance() {
        val draft = verifiedDraft().copy(
            nutrients = NutritionField.entries.associateWith { 0.0 },
            evidence = mapOf("calories_kcal" to listOf(
                NutritionFieldEvidence("line-1", "page-1", "열량 0 kcal", 0.9f),
                NutritionFieldEvidence("line-2", "page-1", "단백질 0 g", null),
            )),
        )
        val source = (NutritionPublicationAdapter.adapt(draft) as PublicationAdaptation.Ready).source
        assertEquals(draft.documentId, source.documentId)
        assertEquals(draft.productName, source.productName)
        assertEquals(draft.brand, source.brand)
        assertEquals(draft.basisAmount, source.basisAmount)
        assertEquals(draft.basisUnit, source.basisUnit)
        assertEquals(draft.confirmedAt, source.confirmedAt)
        assertEquals(draft.sourceType, source.sourceType)
        assertEquals(draft.sourceReference, source.sourceReference)
        assertEquals(draft.sourceVersion, source.sourceVersion)
        assertEquals(listOf("page-1"), source.evidencePageIds)
        assertTrue(source.evidenceAttachmentIds.isEmpty())
        assertEquals(11, source.nutrients.size)
        NutritionField.entries.forEach { field ->
            val nutrient = source.nutrients.single { it.key == field.wireKey }
            assertEquals(field.canonicalUnit, nutrient.unit)
            assertEquals(0.0, nutrient.value, 0.0)
        }
    }

    @Test fun parsedDraftIsBlockedEvenWhenComplete() {
        assertTrue(NutritionPublicationAdapter.adapt(verifiedDraft().copy(
            status = NutritionDraftStatus.PARSED,
        )) is PublicationAdaptation.Blocked)
    }

    @Test fun missingRequiredBasisIsBlockedByExistingValidator() {
        assertTrue(NutritionPublicationAdapter.adapt(verifiedDraft().copy(
            basisAmount = null,
        )) is PublicationAdaptation.Blocked)
    }

    @Test fun nullBrandAndUnknownOptionalNutrientsStayAbsent() {
        val source = (NutritionPublicationAdapter.adapt(verifiedDraft().copy(
            brand = null,
        )) as PublicationAdaptation.Ready).source
        assertNull(source.brand)
        assertEquals(7, source.nutrients.size)
        assertFalse(source.nutrients.any { it.key == NutritionField.FIBER_GRAMS.wireKey })
    }

    @Test fun invalidValuesAndMissingRequiredNutrientsAreBlocked() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertTrue(NutritionPublicationAdapter.adapt(verifiedDraft().copy(
                nutrients = verifiedDraft().nutrients + (NutritionField.FIBER_GRAMS to value),
            )) is PublicationAdaptation.Blocked)
        }
        assertTrue(NutritionPublicationAdapter.adapt(verifiedDraft().copy(
            nutrients = verifiedDraft().nutrients - NutritionField.SODIUM_MG,
        )) is PublicationAdaptation.Blocked)
    }

    @Test fun userVerifiedStatusDoesNotBypassParentChildValidation() {
        for ((field, value) in listOf(
            NutritionField.SUGARS_GRAMS to 50.0,
            NutritionField.ADDED_SUGARS_GRAMS to 9.0,
            NutritionField.SATURATED_FAT_GRAMS to 30.0,
            NutritionField.TRANS_FAT_GRAMS to 30.0,
        )) {
            val draft = verifiedDraft().copy(nutrients = verifiedDraft().nutrients + mapOf(
                NutritionField.CARBS_GRAMS to 43.0,
                NutritionField.SUGARS_GRAMS to 8.0,
                NutritionField.FAT_GRAMS to 28.0,
                field to value,
            ))
            val result = NutritionPublicationAdapter.adapt(draft)
            assertTrue(result is PublicationAdaptation.Blocked)
            assertTrue((result as PublicationAdaptation.Blocked).errors.any { it.contains("포함되므로") })
        }
    }

    companion object {
        fun verifiedDraft() = NutritionLabelDraft(
            documentId = "publication-test-document",
            productName = "닥터유 단백질바",
            brand = "오리온",
            basisAmount = 50.0,
            basisUnit = "g",
            nutrients = NutritionField.requiredFields.associateWith { field ->
                when (field) {
                    NutritionField.CALORIES_KCAL -> 249.0
                    NutritionField.PROTEIN_GRAMS -> 12.0
                    NutritionField.CARBS_GRAMS -> 24.0
                    NutritionField.FAT_GRAMS -> 11.0
                    NutritionField.SODIUM_MG -> 180.0
                    NutritionField.SATURATED_FAT_GRAMS -> 4.0
                    else -> 0.0
                }
            },
            status = NutritionDraftStatus.USER_VERIFIED,
            confirmedAt = "2026-10-12T10:00:00+09:00",
        )
    }
}
