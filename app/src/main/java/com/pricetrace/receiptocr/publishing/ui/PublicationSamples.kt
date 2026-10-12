package com.pricetrace.receiptocr.publishing.ui

import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.publishing.adapter.NutritionPublicationAdapter
import com.pricetrace.receiptscanner.publishing.adapter.PublicationAdaptation

/** Explicit sample data for standalone Compose previews and tests; never used by the live entry. */
object PublicationSamples {
    fun nutritionDraft() = NutritionLabelDraft(
        documentId = "publication-preview-sample",
        productName = "닥터유 단백질바",
        brand = "오리온",
        basisAmount = 50.0,
        basisUnit = "g",
        nutrients = mapOf(
            NutritionField.CALORIES_KCAL to 249.0,
            NutritionField.CARBS_GRAMS to 24.0,
            NutritionField.SUGARS_GRAMS to 8.0,
            NutritionField.PROTEIN_GRAMS to 12.0,
            NutritionField.FAT_GRAMS to 11.0,
            NutritionField.SATURATED_FAT_GRAMS to 4.0,
            NutritionField.SODIUM_MG to 180.0,
            NutritionField.FIBER_GRAMS to 2.5,
            NutritionField.CHOLESTEROL_MG to 0.0,
        ),
        status = NutritionDraftStatus.USER_VERIFIED,
        confirmedAt = "2026-10-12T10:00:00+09:00",
    )

    fun source() = (NutritionPublicationAdapter.adapt(nutritionDraft()) as PublicationAdaptation.Ready).source
}
