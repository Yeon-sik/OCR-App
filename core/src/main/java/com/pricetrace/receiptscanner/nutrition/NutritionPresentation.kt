package com.pricetrace.receiptscanner.nutrition

/** Display grouping only. Wire order, literal nutrient values and unknowns are unchanged. */
object NutritionPresentation {
    data class Entry(val field: NutritionField, val depth: Int = 0) {
        val label: String
            get() = when (field) {
                NutritionField.CARBS_GRAMS, NutritionField.FAT_GRAMS -> "${field.koreanLabel} (총)"
                else -> field.includedIn?.let { "${field.koreanLabel} (${it.koreanLabel}에 포함)" }
                    ?: field.koreanLabel
            }
    }

    // Fiber is grouped for readability only; it has no enforced parent relation.
    val entries: List<Entry> = listOf(
        Entry(NutritionField.CALORIES_KCAL),
        Entry(NutritionField.PROTEIN_GRAMS),
        Entry(NutritionField.CARBS_GRAMS),
        Entry(NutritionField.SUGARS_GRAMS, 1),
        Entry(NutritionField.ADDED_SUGARS_GRAMS, 2),
        Entry(NutritionField.FIBER_GRAMS, 1),
        Entry(NutritionField.FAT_GRAMS),
        Entry(NutritionField.SATURATED_FAT_GRAMS, 1),
        Entry(NutritionField.TRANS_FAT_GRAMS, 1),
        Entry(NutritionField.SODIUM_MG),
        Entry(NutritionField.CHOLESTEROL_MG),
    )
}
