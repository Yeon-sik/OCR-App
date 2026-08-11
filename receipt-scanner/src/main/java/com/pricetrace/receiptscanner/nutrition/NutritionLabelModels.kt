package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.ocr.OcrLine

const val FITNESS_NUTRITION_DRAFT_SCHEMA = "fitness-nutrition-draft.v1"
const val FITNESS_NUTRITION_PARSER_VERSION = "nutrition-label-parser.v1"
const val FITNESS_NUTRITION_DATA_VERSION = 2

enum class NutritionDraftStatus(val wireValue: String) {
    PARSED("parsed"),
    USER_VERIFIED("user_verified"),
    ;

    companion object {
        fun fromWireValue(value: String?): NutritionDraftStatus = entries.firstOrNull {
            it.wireValue == value
        } ?: PARSED
    }
}

enum class NutritionField(
    val wireKey: String,
    val koreanLabel: String,
    val canonicalUnit: String,
    val required: Boolean,
) {
    CALORIES_KCAL("calories_kcal", "열량", "kcal", true),
    PROTEIN_GRAMS("protein_grams", "단백질", "g", true),
    CARBS_GRAMS("carbs_grams", "탄수화물", "g", true),
    FAT_GRAMS("fat_grams", "지방", "g", true),
    SODIUM_MG("sodium_mg", "나트륨", "mg", true),
    SATURATED_FAT_GRAMS("saturated_fat_grams", "포화지방", "g", true),
    SUGARS_GRAMS("sugars_grams", "당류", "g", true),
    FIBER_GRAMS("fiber_grams", "식이섬유", "g", false),
    ADDED_SUGARS_GRAMS("added_sugars_grams", "첨가당", "g", false),
    TRANS_FAT_GRAMS("trans_fat_grams", "트랜스지방", "g", false),
    CHOLESTEROL_MG("cholesterol_mg", "콜레스테롤", "mg", false),
    ;

    companion object {
        val requiredFields: List<NutritionField> = entries.filter(NutritionField::required)
        fun fromWireKey(value: String): NutritionField? = entries.firstOrNull { it.wireKey == value }
    }
}

data class NutritionFieldEvidence(
    val ocrLineId: String,
    val pageId: String,
    val rawText: String,
    val confidence: Float?,
)

data class NutritionLabelDraft(
    val documentId: String,
    val parserVersion: String = FITNESS_NUTRITION_PARSER_VERSION,
    val productName: String = "",
    val brand: String? = null,
    val category: String = NutritionContract.DEFAULT_CATEGORY,
    val basisAmount: Double? = null,
    val basisUnit: String = NutritionUnit.SERVING,
    val nutrients: Map<NutritionField, Double> = emptyMap(),
    val evidence: Map<String, List<NutritionFieldEvidence>> = emptyMap(),
    val parseWarnings: List<String> = emptyList(),
    val status: NutritionDraftStatus = NutritionDraftStatus.PARSED,
    val confirmedAt: String? = null,
) {
    val foodId: String get() = "ocr-nutrition:$documentId"
    val sourceReference: String get() = "ocr-document:$documentId"

    fun value(field: NutritionField): Double? = nutrients[field]

    fun withNutrient(field: NutritionField, value: Double?): NutritionLabelDraft {
        val updated = nutrients.toMutableMap()
        if (value == null) updated.remove(field) else updated[field] = value
        return copy(
            nutrients = updated,
            status = NutritionDraftStatus.PARSED,
            confirmedAt = null,
        )
    }

    fun asUserVerified(confirmedAt: String): NutritionLabelDraft = copy(
        status = NutritionDraftStatus.USER_VERIFIED,
        confirmedAt = confirmedAt,
    )
}

data class NutritionValidationResult(
    val errors: List<String>,
) {
    val isReadyForUpload: Boolean get() = errors.isEmpty()
}

object NutritionContract {
    const val KIND_EXTERNAL_MENU = "external_menu"
    const val DEFAULT_CATEGORY = "processed"
    const val PREP_UNSPECIFIED = "unspecified"
    const val COOKING_UNSPECIFIED = "unspecified"
    const val VISIBILITY_PRIVATE = "private"
    const val SOURCE_TYPE = "product_label_ocr"

    val categories: Set<String> = setOf(
        "meat",
        "poultry",
        "seafood",
        "egg",
        "grain",
        "vegetable",
        "fruit",
        "legume",
        "dairy",
        "nut_seed",
        "processed",
        "beverage",
        "recipe",
        "other",
    )
}

object NutritionUnit {
    const val GRAM = "g"
    const val MILLIGRAM = "mg"
    const val KILOGRAM = "kg"
    const val MILLILITER = "ml"
    const val LITER = "L"
    const val SERVING = "serving"

    val supported: Set<String> = setOf(
        GRAM,
        MILLIGRAM,
        KILOGRAM,
        MILLILITER,
        LITER,
        SERVING,
        "개",
        "portion",
        "pack",
    )

    fun normalize(raw: String?): String {
        val value = raw.orEmpty().trim().lowercase()
        return when (value) {
            "g", "gram", "grams", "그램" -> GRAM
            "mg", "milligram", "milligrams", "밀리그램" -> MILLIGRAM
            "kg", "kilogram", "kilograms", "킬로그램" -> KILOGRAM
            "ml", "milliliter", "milliliters", "millilitre", "millilitres", "밀리리터" -> MILLILITER
            "l", "liter", "liters", "litre", "litres", "리터" -> LITER
            "serving", "servings", "srv", "회", "회분" -> SERVING
            "개", "piece", "pieces", "unit", "units" -> "개"
            "portion", "portions" -> "portion"
            "pack", "packs", "팩" -> "pack"
            else -> value
        }
    }
}

object NutritionLabelValidator {
    fun validate(draft: NutritionLabelDraft): NutritionValidationResult {
        val errors = buildList {
            if (draft.productName.isBlank()) add("상품명을 입력하세요.")
            if (draft.category !in NutritionContract.categories) {
                add("Fitness App 계약에 등록된 상품 분류를 선택하세요.")
            }
            if (draft.basisAmount == null || !draft.basisAmount.isFinite() || draft.basisAmount <= 0.0) {
                add("영양성분 기준량은 0보다 커야 합니다.")
            }
            if (NutritionUnit.normalize(draft.basisUnit) !in NutritionUnit.supported) {
                add("기준 단위는 g, mg, kg, ml, L, serving, 개, portion, pack 중 하나여야 합니다.")
            }
            NutritionField.requiredFields.forEach { field ->
                val value = draft.value(field)
                if (value == null) {
                    add("${field.koreanLabel} 값을 확인하세요.")
                } else if (!value.isFinite() || value < 0.0) {
                    add("${field.koreanLabel} 값은 0 이상이어야 합니다.")
                }
            }
            NutritionField.entries.filterNot(NutritionField::required).forEach { field ->
                val value = draft.value(field)
                if (value != null && (!value.isFinite() || value < 0.0)) {
                    add("${field.koreanLabel} 값은 0 이상이거나 모름이어야 합니다.")
                }
            }
        }
        return NutritionValidationResult(errors.distinct())
    }
}

internal fun OcrLine.toNutritionEvidence(): NutritionFieldEvidence = NutritionFieldEvidence(
    ocrLineId = id,
    pageId = pageId,
    rawText = text,
    confidence = confidence,
)
