package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope

enum class ProductFactField(val label: String, val productField: String, val labelField: String) {
    NAME("상품명", "product_name", "product_name"),
    BRAND("브랜드", "brand_name", "brand"),
}

enum class ProductFactStatus { SAME, CONFLICT, INCOMPLETE, MISSING, INDEPENDENT }

data class ProductFactSource(val path: String, val label: String, val value: String?)

/** A display decision, never a catalog identity or a rewrite of source evidence. */
data class ProductFactReview(
    val productClientKey: String,
    val field: ProductFactField,
    val sources: List<ProductFactSource>,
) {
    val linked: Boolean get() = sources.size > 1
    val status: ProductFactStatus get() {
        val values = sources.mapNotNull { LinkedProductReview.observedText(it.value) }
        return when {
            values.distinct().size > 1 -> ProductFactStatus.CONFLICT
            values.isEmpty() -> ProductFactStatus.MISSING
            !linked -> ProductFactStatus.INDEPENDENT
            values.size < sources.size -> ProductFactStatus.INCOMPLETE
            else -> ProductFactStatus.SAME
        }
    }
    val primarySource: ProductFactSource get() =
        sources.firstOrNull { LinkedProductReview.observedText(it.value) != null } ?: sources.first()
}

object LinkedProductReview {
    fun observedText(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

    fun sameObservedText(first: String?, second: String?): Boolean =
        observedText(first)?.let { it == observedText(second) } == true

    fun facts(envelope: YeonsikOcrEnvelope): List<ProductFactReview> =
        facts(envelope.productCandidates, envelope.nutrition)

    fun facts(products: List<ProductCandidate>, nutrition: List<IngestionNutrition>): List<ProductFactReview> =
        products.flatMap { product ->
            // Ambiguous keys must not cause deduplication or mutation of unrelated artifacts.
            val labels = if (products.count { it.clientKey == product.clientKey } == 1) {
                nutrition.filterIsInstance<IngestionNutrition.ProductLabel>()
                    .filter { it.productClientKey == product.clientKey }
            } else emptyList()
            ProductFactField.entries.map { field ->
                ProductFactReview(product.clientKey, field, buildList {
                    add(ProductFactSource(
                        "product_candidates[${product.clientKey}].${field.productField}",
                        "상품 정보에서 읽음",
                        if (field == ProductFactField.NAME) product.productName else product.brand,
                    ))
                    labels.forEachIndexed { index, label ->
                        add(ProductFactSource(
                            "nutrition[${label.clientKey}].${field.labelField}",
                            if (labels.size == 1) "영양 라벨에서 읽음" else "영양 라벨 ${index + 1}에서 읽음",
                            if (field == ProductFactField.NAME) label.draft.productName else label.draft.brand,
                        ))
                    }
                })
            }
        }

    fun hasConflicts(envelope: YeonsikOcrEnvelope): Boolean = facts(envelope).any { it.status == ProductFactStatus.CONFLICT }

    /** Preserve every registry descriptor for mutations; filter only the default review controls. */
    fun displayFields(envelope: YeonsikOcrEnvelope): List<CanonicalEditableField> {
        val facts = facts(envelope)
        val suppressed = facts.flatMap { fact ->
            fact.sources.filter { fact.status == ProductFactStatus.CONFLICT || it.path != fact.primarySource.path }
                .map { it.path }
        }.toSet()
        val humanLabels = facts.flatMap { fact ->
            fact.sources.map { source -> source.path to fact.field.label }
        }.toMap()
        return CanonicalFieldRegistry.fields(envelope).filterNot { it.path in suppressed }.map { field ->
            val key = (envelope.productCandidates.map { it.clientKey } + envelope.nutrition.map { it.clientKey })
                .firstOrNull { field.path.startsWith("product_candidates[$it].") || field.path.startsWith("nutrition[$it].") }
            field.copy(label = humanLabels[field.path] ?: key?.let { field.label.removePrefix("$it ") } ?: field.label)
        }
    }
}
