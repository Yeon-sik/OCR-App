package com.pricetrace.receiptscanner.publishing.generator

import com.pricetrace.receiptscanner.nutrition.NutritionPresentation
import com.pricetrace.receiptscanner.publishing.model.PublicationNutrient
import com.pricetrace.receiptscanner.publishing.model.PublicationSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** Shared presentation rules keep the generated image and text consistent. */
object PublicationText {
    data class NutrientRow(val nutrient: PublicationNutrient, val label: String, val depth: Int)

    /** Text and PNG use the review hierarchy without rewriting the flat source snapshot. */
    fun nutrientRows(source: PublicationSource): List<NutrientRow> = source.nutrients
        .sortedBy { nutrient ->
            NutritionPresentation.entries.indexOfFirst { it.field.wireKey == nutrient.key }
                .takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        .map { nutrient ->
            val entry = NutritionPresentation.entries.firstOrNull { it.field.wireKey == nutrient.key }
            NutrientRow(nutrient, entry?.label ?: nutrient.label, entry?.depth ?: 0)
        }

    fun number(value: Double): String {
        require(value.isFinite()) { "Cannot publish a non-finite value" }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    fun displayName(source: PublicationSource): String {
        val name = singleLine(source.productName)
        val brand = source.brand?.let(::singleLine)?.takeIf(String::isNotBlank)
        return if (brand == null || name.startsWith(brand, ignoreCase = true)) name else "$brand $name"
    }

    fun basis(source: PublicationSource): String? = source.basisAmount?.let { amount ->
        require(amount.isFinite() && amount > 0.0) { "Invalid publication basis" }
        "${number(amount)} ${source.basisUnit}"
    }

    fun sourceLabel(source: PublicationSource): String = when (source.sourceType) {
        "product_label_ocr" -> "제품 포장 영양성분표 (사용자 확인)"
        "external_reference" -> "외부 영양정보 자료 (사용자 확인)"
        else -> "사용자가 확인한 영양정보"
    }

    fun confirmedDate(source: PublicationSource): String? = source.confirmedAt?.let { value ->
        runCatching { OffsetDateTime.parse(value).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDate.parse(value).toString() }.getOrNull()
    }

    fun abbreviate(value: String, maxCodePoints: Int): String {
        if (value.codePointCount(0, value.length) <= maxCodePoints) return value
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints - 1)) + "…"
    }

    private fun singleLine(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
