package com.yeonsik.ingestion.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.pricetrace.receiptscanner.ingestion.*
import com.pricetrace.receiptscanner.nutrition.NutritionField

internal val LocalReviewFieldSource = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

/** Resolve only explicit source links. Coordinates are not present in canonical review fields. */
internal fun reviewFieldSources(envelope: YeonsikOcrEnvelope, path: String): List<String> {
    val key = path.substringAfter('[', "").substringBefore(']')
    val field = path.substringAfterLast('.')
    return when {
        path.startsWith("receipt.") -> envelope.receipt?.document?.source?.sourceImages.orEmpty()
        path.startsWith("merchant_candidate.") -> envelope.merchantCandidate?.sourceAttachmentIds.orEmpty()
        path.startsWith("product_candidates[") -> envelope.productCandidates.singleOrNull { it.clientKey == key }?.let { product ->
            product.evidence.filter { it.field == field }.flatMap { it.sourceAttachmentIds }
                .ifEmpty { product.effectiveSourceAttachmentIds }
        }.orEmpty()
        path.startsWith("nutrition[") -> when (val nutrition = envelope.nutrition.singleOrNull { it.clientKey == key }) {
            is IngestionNutrition.ProductLabel -> nutrition.draft.evidence[field].orEmpty().map { it.pageId }
            is IngestionNutrition.RestaurantEstimate -> nutrition.estimate.nutrientProvenance[NutritionField.fromWireKey(field)]?.evidenceRefs.orEmpty()
            is IngestionNutrition.RestaurantMenuEstimate -> nutrition.estimate.nutrientProvenance[NutritionField.fromWireKey(field)]?.evidenceRefs.orEmpty()
            is IngestionNutrition.MealComponentEstimate -> nutrition.estimate.nutrientProvenance[NutritionField.fromWireKey(field)]?.evidenceRefs.orEmpty()
            else -> emptyList()
        }
        path.startsWith("purchase_records[") -> envelope.purchaseRecords.singleOrNull { it.clientKey == key }?.let { purchase ->
            val relative = path.substringAfter("].")
            // Unscoped line evidence can be ambiguous; never associate it by a display name.
            purchase.evidence.filter { it.field == relative || (".line_items[" !in path && it.field == field) }
                .flatMap { it.sourceAttachmentIds }
        }.orEmpty()
        else -> emptyList()
    }.distinct()
}
