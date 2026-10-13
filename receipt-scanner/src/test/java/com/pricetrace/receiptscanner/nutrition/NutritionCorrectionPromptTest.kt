package com.pricetrace.receiptscanner.nutrition

import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionCorrectionPromptTest {
    @Test fun hardRulesPreserveTotalsAndRejectImpossibleChildrenWithoutSynthesizingParents() {
        val prompt = NutritionCorrectionPrompt.build(NutritionCorrectionRequest("prompt-test", emptyList(), emptyList()))
        for (rule in listOf(
            "carbs_grams is TOTAL carbohydrate and already includes sugars_grams",
            "Never subtract sugars_grams from carbs_grams or add sugars_grams to that total",
            "added_sugars_grams is contained in sugars_grams",
            "fat_grams is TOTAL fat and already includes saturated_fat_grams and trans_fat_grams",
            "Never subtract saturated_fat_grams or trans_fat_grams from fat_grams or add them to that total",
            "sugars_grams > carbs_grams",
            "added_sugars_grams > sugars_grams",
            "saturated_fat_grams > fat_grams",
            "trans_fat_grams > fat_grams",
            "Never synthesize a missing parent total from child values. Unknown is not zero",
            "You may propose only an existing target fieldPath",
        )) assertTrue(rule, prompt.contains(rule))
    }
}
