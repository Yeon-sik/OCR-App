package com.pricetrace.receiptscanner.nutrition

import java.net.URI

const val FITNESS_NUTRITION_DRAFT_SCHEMA = "fitness-nutrition-draft.v1"
const val FITNESS_NUTRITION_PARSER_VERSION = "nutrition-label-parser.v2"
const val EXTERNAL_NUTRITION_LOOKUP_VERSION = "external-nutrition-lookup.v1"
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
    /** Provenance values are source facts and must survive import, revision, and projection. */
    val sourceType: String = NutritionContract.SOURCE_TYPE,
    val sourceReference: String = NutritionContract.defaultSourceReference(documentId),
    val sourceVersion: String = parserVersion,
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
    const val EXTERNAL_REFERENCE_SOURCE_TYPE = "external_reference"

    val sourceTypes: Set<String> = setOf(SOURCE_TYPE, EXTERNAL_REFERENCE_SOURCE_TYPE)

    fun defaultSourceReference(documentId: String): String = "ocr-document:$documentId"

    /**
     * Validates only the provenance contract. Nutrient completeness remains the responsibility
     * of [NutritionLabelValidator]. The URL check is intentionally syntactic: OCR-App never
     * fetches or trusts a remote page during import.
     */
    fun provenanceErrors(
        sourceType: String,
        sourceReference: String,
        parserVersion: String,
        sourceVersion: String,
    ): List<String> = buildList {
        if (sourceType !in sourceTypes) {
            add("지원하지 않는 영양성분 출처 유형입니다: $sourceType")
        }
        if (sourceReference.isBlank()) {
            add("영양성분 출처 참조는 비어 있을 수 없습니다.")
        }
        if (parserVersion.isBlank()) {
            add("영양성분 parser_version은 비어 있을 수 없습니다.")
        }
        if (sourceVersion.isBlank()) {
            add("영양성분 source_version은 비어 있을 수 없습니다.")
        }
        if (sourceType == EXTERNAL_REFERENCE_SOURCE_TYPE) {
            if (!isPublicHttpUrl(sourceReference)) {
                add("external_reference source_reference는 공개 http/https URL이어야 합니다.")
            }
            if (parserVersion != EXTERNAL_NUTRITION_LOOKUP_VERSION) {
                add("external_reference parser_version은 $EXTERNAL_NUTRITION_LOOKUP_VERSION 이어야 합니다.")
            }
            if (sourceVersion != EXTERNAL_NUTRITION_LOOKUP_VERSION) {
                add("external_reference source_version은 $EXTERNAL_NUTRITION_LOOKUP_VERSION 이어야 합니다.")
            }
        }
    }

    private fun isPublicHttpUrl(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed != value) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (scheme !in setOf("http", "https") || uri.userInfo != null || host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host == "::1" || host == "0.0.0.0" || host == "127.0.0.1") return false
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull()?.toString() == it }) {
            val values = octets.map(String::toInt)
            val first = values[0]
            val second = values[1]
            if (first == 0 || first == 10 || first == 127 ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
            ) return false
        }
        return true
    }

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
            addAll(
                NutritionContract.provenanceErrors(
                    sourceType = draft.sourceType,
                    sourceReference = draft.sourceReference,
                    parserVersion = draft.parserVersion,
                    sourceVersion = draft.sourceVersion,
                ),
            )
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

