package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrLine
import java.util.Locale

class NutritionLabelParser {
    val version: String = FITNESS_NUTRITION_PARSER_VERSION

    fun parse(document: OcrDocument): NutritionLabelDraft {
        val warnings = mutableListOf<String>()
        val allLines = document.lines.sortedWith(compareBy(OcrLine::pageIndex, OcrLine::recognitionOrder))
        val lines = locateNutritionRegion(allLines, warnings)
        val evidence = linkedMapOf<String, MutableList<NutritionFieldEvidence>>()
        val nutrients = linkedMapOf<NutritionField, Double>()

        fieldPatterns.forEach { definition ->
            val matches = fieldMatches(lines, definition)
            val distinctValues = matches.map { it.value }.distinctBy { normalizedNumber(it) }
            when {
                distinctValues.isEmpty() -> Unit
                distinctValues.size == 1 -> {
                    nutrients[definition.field] = distinctValues.single()
                    evidence.getOrPut(definition.field.wireKey, ::mutableListOf)
                        .addAll(matches.flatMap { it.lines }.map { it.toNutritionEvidence() }.distinctBy { it.ocrLineId })
                }
                else -> warnings += "${definition.field.koreanLabel} 값이 여러 개 감지되어 자동 확정하지 않았습니다."
            }
        }

        val basisMatches = basisMatches(lines)
        val distinctBases = basisMatches.map { it.second }.distinct()
        val basis = distinctBases.singleOrNull()
        if (distinctBases.size > 1) warnings += "영양성분 기준량이 여러 개 감지되어 자동 확정하지 않았습니다."
        if (basis != null) {
            evidence.getOrPut("basis", ::mutableListOf)
                .addAll(basisMatches.flatMap { it.first }.map { it.toNutritionEvidence() }.distinctBy { it.ocrLineId })
        }

        return NutritionLabelDraft(
            documentId = document.documentId,
            productName = "",
            brand = null,
            basisAmount = basis?.amount,
            basisUnit = basis?.unit ?: NutritionUnit.SERVING,
            nutrients = nutrients,
            evidence = evidence.mapValues { it.value.toList() },
            parseWarnings = warnings,
        )
    }

    private fun locateNutritionRegion(lines: List<OcrLine>, warnings: MutableList<String>): List<OcrLine> {
        val regions = lines
            .groupBy(OcrLine::pageIndex)
            .toSortedMap()
            .values
            .mapNotNull { pageLines ->
                val headerIndex = pageLines.indexOfFirst { isNutritionHeader(it.text) }
                if (headerIndex < 0) return@mapNotNull null
                val endOffset = pageLines
                    .drop(headerIndex + 1)
                    .indexOfFirst { isNutritionRegionEnd(it.text) }
                val endIndex = if (endOffset < 0) pageLines.size else headerIndex + 1 + endOffset
                pageLines.subList(headerIndex, endIndex)
            }
        val selected = regions.maxByOrNull { region ->
            region.sumOf { line -> fieldPatterns.count { it.aliasOnly.containsMatchIn(line.text) } }
        }
        if (selected != null && selected.any(::looksLikeNutritionLine)) return selected

        warnings += "영양정보 박스를 식별하지 못해 영양성분 라인을 보수적으로 찾았습니다."
        return lines.filterIndexed { index, line ->
            looksLikeNutritionLine(line) ||
                lines.getOrNull(index - 1)?.let(::looksLikeNutritionLine) == true ||
                lines.getOrNull(index + 1)?.let(::looksLikeNutritionLine) == true
        }
    }

    private fun looksLikeNutritionLine(line: OcrLine): Boolean =
        fieldPatterns.any { it.aliasOnly.containsMatchIn(line.text) } ||
            explicitBasisPatterns.any { it.containsMatchIn(line.text.lowercase(Locale.ROOT)) }

    private fun isNutritionHeader(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return nutritionHeaderMarkers.any(normalized::contains)
    }

    private fun isNutritionRegionEnd(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return nutritionRegionEndMarkers.any(normalized::contains)
    }

    private fun fieldMatches(lines: List<OcrLine>, definition: FieldPattern): List<FieldMatch> = buildList {
        lines.forEachIndexed { index, line ->
            val singleValue = definition.extract(line.text)
            if (singleValue != null) {
                add(FieldMatch(listOf(line), singleValue))
            } else {
                val next = lines.getOrNull(index + 1)
                if (next != null && next.pageIndex == line.pageIndex) {
                    definition.extract("${line.text} ${next.text}")?.let { value ->
                        add(FieldMatch(listOf(line, next), value))
                    }
                }
            }
        }
    }

    private fun basisMatches(lines: List<OcrLine>): List<Pair<List<OcrLine>, Basis>> = buildList {
        lines.forEachIndexed { index, line ->
            val singleBasis = extractBasis(line.text)
            if (singleBasis != null) {
                add(listOf(line) to singleBasis)
            } else {
                val next = lines.getOrNull(index + 1)
                if (next != null && next.pageIndex == line.pageIndex) {
                    extractBasis("${line.text} ${next.text}")?.let { basis ->
                        add(listOf(line, next) to basis)
                    }
                }
            }
        }
    }

    private fun extractBasis(rawText: String): Basis? {
        val text = rawText.lowercase(Locale.ROOT).replace(',', '.')
        explicitBasisPatterns.forEach { pattern ->
            val match = pattern.find(text) ?: return@forEach
            val amount = match.groups[1]?.value?.toDoubleOrNull() ?: return@forEach
            val unit = NutritionUnit.normalize(match.groups[2]?.value)
            if (amount > 0.0 && unit in NutritionUnit.supported) return Basis(amount, unit)
        }
        return null
    }

    private data class Basis(val amount: Double, val unit: String)
    private data class FieldMatch(val lines: List<OcrLine>, val value: Double)

    private data class FieldPattern(
        val field: NutritionField,
        val aliasOnly: Regex,
        val amountPattern: Regex,
        val blockedPrefixes: List<String> = emptyList(),
    ) {
        fun extract(rawText: String): Double? {
            val normalized = rawText.lowercase(Locale.ROOT).replace(',', '.')
            amountPattern.findAll(normalized).forEach { match ->
                val prefix = normalized.substring(0, match.range.first).trimEnd()
                if (blockedPrefixes.any(prefix::endsWith)) return@forEach
                val amount = match.groups[1]?.value?.toDoubleOrNull() ?: return@forEach
                val unit = match.groups[2]?.value?.lowercase(Locale.ROOT) ?: return@forEach
                if (!amount.isFinite() || amount < 0.0) return@forEach
                return when (field.canonicalUnit) {
                    "kcal" -> amount.takeIf { unit == "kcal" || unit == "㎉" }
                    "mg" -> when (unit) {
                        "mg", "㎎" -> amount
                        "g", "ｇ" -> amount * 1_000.0
                        else -> null
                    }
                    "g" -> when (unit) {
                        "g", "ｇ" -> amount
                        "mg", "㎎" -> amount / 1_000.0
                        else -> null
                    }
                    else -> null
                }
            }
            return null
        }
    }

    companion object {
        private val nutritionHeaderMarkers = listOf(
            "영양정보",
            "영양성분",
            "nutritionfacts",
            "nutritioninformation",
            "nutrition",
        )
        private val nutritionRegionEndMarkers = listOf(
            "원재료",
            "알레르기",
            "보관",
            "주의",
            "제조원",
            "유통기한",
            "ingredients",
            "allergen",
            "storage",
            "manufacturer",
            "importer",
        )
        private val explicitBasisPatterns = listOf(
            Regex("(?:1\\s*회\\s*(?:제공량|분량)|1\\s*회분|per\\s*serving)\\s*[:：]?\\s*(\\d+(?:[.]\\d+)?)\\s*(g|mg|kg|ml|l|개|pack|portion|serving|회분?)", RegexOption.IGNORE_CASE),
            Regex("(?:영양성분\\s*)?(\\d+(?:[.]\\d+)?)\\s*(g|ml)\\s*(?:당|기준|per)", RegexOption.IGNORE_CASE),
            Regex("per\\s*(\\d+(?:[.]\\d+)?)\\s*(g|ml)", RegexOption.IGNORE_CASE),
        )

        private fun definition(
            field: NutritionField,
            aliases: String,
            blockedPrefixes: List<String> = emptyList(),
        ): FieldPattern {
            val aliasOnly = Regex(aliases, RegexOption.IGNORE_CASE)
            val units = when (field.canonicalUnit) {
                "kcal" -> "kcal|㎉"
                else -> "mg|g|㎎|ｇ"
            }
            return FieldPattern(
                field = field,
                aliasOnly = aliasOnly,
                amountPattern = Regex(
                    "(?:$aliases)\\s*[:：]?\\s*(\\d+(?:[.]\\d+)?)\\s*($units)",
                    RegexOption.IGNORE_CASE,
                ),
                blockedPrefixes = blockedPrefixes,
            )
        }

        private val fieldPatterns = listOf(
            definition(NutritionField.SATURATED_FAT_GRAMS, "포화\\s*지방|saturated\\s*fat"),
            definition(NutritionField.TRANS_FAT_GRAMS, "트랜스\\s*지방|trans\\s*fat"),
            definition(NutritionField.ADDED_SUGARS_GRAMS, "첨가\\s*당(?:류)?|added\\s*sugars?"),
            definition(NutritionField.FIBER_GRAMS, "식이\\s*섬유|dietary\\s*fib(?:er|re)"),
            definition(NutritionField.CHOLESTEROL_MG, "콜레스테롤|cholesterol"),
            definition(NutritionField.SODIUM_MG, "나트륨|sodium"),
            definition(NutritionField.SUGARS_GRAMS, "당류|sugars?", blockedPrefixes = listOf("첨가", "added")),
            definition(NutritionField.PROTEIN_GRAMS, "단백질|protein"),
            definition(NutritionField.CARBS_GRAMS, "탄수화물|total\\s*carbohydrate|carbohydrates?|carbs?"),
            definition(
                NutritionField.FAT_GRAMS,
                "(?:총\\s*)?지방|total\\s*fat",
                blockedPrefixes = listOf("포화", "트랜스"),
            ),
            definition(NutritionField.CALORIES_KCAL, "열량|칼로리|calories?|energy"),
        )

        private fun normalizedNumber(value: Double): String = "%.8f".format(Locale.US, value).trimEnd('0').trimEnd('.')
    }
}
