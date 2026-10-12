package com.pricetrace.receiptscanner.publishing.generator

import com.pricetrace.receiptscanner.publishing.model.BlogDraft
import com.pricetrace.receiptscanner.publishing.model.PublicationSource

/** Plain text only. No Android, network, platform automation or canonical ingestion dependency. */
class BlogDraftGenerator {
    fun generate(source: PublicationSource): BlogDraft {
        require(source.productName.isNotBlank()) { "Publication product name is empty" }
        require(source.nutrients.all { it.value.isFinite() && it.value >= 0.0 }) {
            "Publication contains invalid nutrient values"
        }
        val name = PublicationText.displayName(source)
        val titleNutrients = TITLE_FIELDS.mapNotNull { (key, label) ->
            label.takeIf { source.nutrients.any { it.key == key } }
        }
        val title = buildString {
            append(PublicationText.abbreviate(name, 80))
            PublicationText.basis(source)?.let { append(" "); append(it) }
            append(" 영양정보")
            if (titleNutrients.isNotEmpty()) {
                append(" | "); append(titleNutrients.joinToString("·"))
            }
        }
        val body = buildString {
            append(name); append(" 영양정보입니다.\n\n")
            PublicationText.basis(source)?.let { append("기준량: $it\n\n") }
            source.nutrients.forEach { nutrient ->
                append(nutrient.label); append(": ")
                append(PublicationText.number(nutrient.value)); append(" ")
                append(nutrient.unit); append("\n")
            }
            append("\n정보 출처: "); append(PublicationText.sourceLabel(source))
            if (source.sourceType == "external_reference") {
                append("\n출처 링크: "); append(source.sourceReference)
            }
            PublicationText.confirmedDate(source)?.let { append("\n확인일: $it") }
        }.trim()
        return BlogDraft(source = source, title = title, body = body)
    }

    private companion object {
        val TITLE_FIELDS = listOf(
            "calories_kcal" to "칼로리",
            "protein_grams" to "단백질",
            "sugars_grams" to "당류",
            "sodium_mg" to "나트륨",
        )
    }
}
