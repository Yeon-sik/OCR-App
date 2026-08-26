package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrLine
import java.util.Locale

object NutritionCorrectionRequestFactory {
    private const val MAX_EVIDENCE_LINES = 48

    fun create(
        draft: NutritionLabelDraft,
        ocrDocument: OcrDocument,
    ): NutritionCorrectionRequest {
        val allLines = ocrDocument.lines.sortedWith(compareBy(OcrLine::pageIndex, OcrLine::recognitionOrder))
        val usableLines = allLines.filterNot { isSensitive(it.text) }
        val nutritionHeaderIndex = usableLines.indexOfFirst { isNutritionHeader(it.text) }
        val productContext = usableLines
            .take(if (nutritionHeaderIndex >= 0) nutritionHeaderIndex else 8)
            .takeLast(8)
        val ingredientContext = usableLines.filter { isIngredientLine(it.text) }.take(8)
        val fieldSources = linkedMapOf<String, List<String>>()

        fieldSources[NutritionCorrectionFieldPaths.PRODUCT_NAME] = productContext.map(OcrLine::id)
        fieldSources[NutritionCorrectionFieldPaths.BRAND] = productContext.map(OcrLine::id)
        fieldSources[NutritionCorrectionFieldPaths.CATEGORY] =
            (productContext + ingredientContext).distinctBy(OcrLine::id).map(OcrLine::id)
        fieldSources[NutritionCorrectionFieldPaths.BASIS_AMOUNT] = findBasisLines(usableLines).map(OcrLine::id)
        fieldSources[NutritionCorrectionFieldPaths.BASIS_UNIT] = findBasisLines(usableLines).map(OcrLine::id)
        NutritionField.entries.forEach { field ->
            fieldSources[field.wireKey] = findFieldLines(usableLines, field).map(OcrLine::id)
        }

        val currentTargets = NutritionCorrectionFieldPaths.metadata.map { fieldPath ->
            NutritionCorrectionTarget(
                fieldPath = fieldPath,
                currentValue = NutritionCorrectionPolicy.currentValue(draft, fieldPath),
                sourceLineIds = fieldSources[fieldPath].orEmpty().distinct(),
            )
        } + NutritionField.entries.map { field ->
            NutritionCorrectionTarget(
                fieldPath = field.wireKey,
                currentValue = NutritionCorrectionPolicy.currentValue(draft, field.wireKey),
                sourceLineIds = fieldSources[field.wireKey].orEmpty().distinct(),
            )
        }
        val initialTargets = currentTargets.filter { it.sourceLineIds.isNotEmpty() }
        val evidenceIds = initialTargets.flatMap(NutritionCorrectionTarget::sourceLineIds).toSet()
        val evidenceLines = usableLines
            .asSequence()
            .filter { it.id in evidenceIds }
            .take(MAX_EVIDENCE_LINES)
            .map { line ->
                NutritionCorrectionEvidenceLine(
                    id = line.id,
                    pageIndex = line.pageIndex,
                    text = line.text,
                )
            }
            .toList()
        val evidenceLineIds = evidenceLines.map(NutritionCorrectionEvidenceLine::id).toSet()
        val targets = initialTargets
            .map { target ->
                target.copy(sourceLineIds = target.sourceLineIds.filter { it in evidenceLineIds })
            }
            .filter { it.sourceLineIds.isNotEmpty() }
        return NutritionCorrectionRequest(
            documentId = draft.documentId,
            targets = targets,
            evidenceLines = evidenceLines,
        )
    }

    private fun findFieldLines(lines: List<OcrLine>, field: NutritionField): List<OcrLine> {
        val aliases = fieldAliases[field].orEmpty()
        val excludedAliases = fieldExcludedAliases[field].orEmpty()
        return lines.flatMapIndexed { index, line ->
            val normalized = line.normalized()
            if (aliases.any(normalized::contains) && excludedAliases.none(normalized::contains)) {
                listOfNotNull(line, lines.getOrNull(index + 1)?.takeIf { next ->
                    val nextNormalized = next.normalized()
                    next.pageIndex == line.pageIndex &&
                        !aliases.any(nextNormalized::contains) &&
                        excludedAliases.none(nextNormalized::contains)
                })
            } else {
                emptyList()
            }
        }.distinctBy(OcrLine::id)
    }

    private fun findBasisLines(lines: List<OcrLine>): List<OcrLine> = lines.filter { line ->
        val normalized = line.normalized()
        basisMarkers.any(normalized::contains)
    }

    private fun OcrLine.normalized(): String = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun isNutritionHeader(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return listOf("영양정보", "영양성분", "nutritionfacts", "nutritioninformation", "nutrition")
            .any(normalized::contains)
    }

    private fun isIngredientLine(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return listOf("원재료", "원재료명", "ingredients", "ingredient", "알레르기", "allergen")
            .any(normalized::contains)
    }

    private fun isSensitive(text: String): Boolean = sensitiveMarkers.any { it.containsMatchIn(text) }

    private val basisMarkers = listOf(
        "제공량",
        "제공분량",
        "1회분",
        "총내용량",
        "perserving",
        "per100g",
        "per100ml",
        "g당",
        "ml당",
    )

    private val fieldAliases = mapOf(
        NutritionField.CALORIES_KCAL to listOf("열량", "칼로리", "calorie", "energy"),
        NutritionField.PROTEIN_GRAMS to listOf("단백질", "protein"),
        NutritionField.CARBS_GRAMS to listOf("탄수화물", "totalcarbohydrate", "carbohydrate", "carbs"),
        NutritionField.FAT_GRAMS to listOf("지방", "totalfat"),
        NutritionField.SODIUM_MG to listOf("나트륨", "sodium"),
        NutritionField.SATURATED_FAT_GRAMS to listOf("포화지방", "saturatedfat"),
        NutritionField.SUGARS_GRAMS to listOf("당류", "sugars", "sugar"),
        NutritionField.FIBER_GRAMS to listOf("식이섬유", "dietaryfiber", "fiber"),
        NutritionField.ADDED_SUGARS_GRAMS to listOf("첨가당", "첨가당류", "addedsugar", "addedsugars"),
        NutritionField.TRANS_FAT_GRAMS to listOf("트랜스지방", "transfat"),
        NutritionField.CHOLESTEROL_MG to listOf("콜레스테롤", "cholesterol"),
    )
    private val fieldExcludedAliases = mapOf(
        NutritionField.FAT_GRAMS to listOf("포화지방", "트랜스지방", "saturatedfat", "transfat"),
        NutritionField.SUGARS_GRAMS to listOf("첨가당", "첨가당류", "addedsugar", "addedsugars"),
    )


    private val sensitiveMarkers = listOf(
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE),
        Regex("(?<!\\d)0\\d{1,2}[- )]?\\d{3,4}[- ]?\\d{4}(?!\\d)"),
        Regex("(?:카드|승인|거래|회원|주민)\\s*(?:번호|ID|아이디)?", RegexOption.IGNORE_CASE),
    )
}
