package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.LinkedProductReview

internal data class PurchaseItemReviewGroup(
    val path: String,
    val number: Int,
    val fields: List<CanonicalEditableField>,
    val matchingPurchaseName: CanonicalEditableField? = null,
    val purchaseNameNeedsAttention: Boolean = false,
)

internal data class PurchaseItemReviewLayout(
    val otherFields: List<CanonicalEditableField>,
    val items: List<PurchaseItemReviewGroup>,
) {
    val hiddenPaths: Set<String> get() = items.filterNot { it.purchaseNameNeedsAttention }
        .mapNotNull { it.matchingPurchaseName?.path }.toSet()
}

/** Groups explicit product links for display only. Purchase descriptions remain independent source facts. */
internal fun purchaseItemReviewLayout(
    fields: List<CanonicalEditableField>,
    envelope: YeonsikOcrEnvelope?,
    attentionPaths: Set<String> = emptySet(),
): PurchaseItemReviewLayout {
    val lineOrder = mutableListOf<String>()
    envelope?.receipt?.lineItems?.forEach { lineOrder += "receipt.line_items[${it.id}]" }
    val productOwners = linkedMapOf<String, String>()
    val linkedLines = mutableSetOf<String>()
    envelope?.purchaseRecords?.forEach { record ->
        record.lineItems.forEachIndexed { index, line ->
            val linePath = "purchase_records[${record.clientKey}].line_items[${line.lineKey ?: index}]"
            lineOrder += linePath
            val product = envelope.productCandidates.singleOrNull { it.clientKey == line.productClientKey }
            if (product != null) {
                linkedLines += linePath
                // A shared product has one editor, even when it occurs on several purchase lines.
                productOwners.putIfAbsent("product_candidates[${product.clientKey}]", linePath)
            }
        }
    }

    val otherFields = mutableListOf<CanonicalEditableField>()
    val grouped = linkedMapOf<String, MutableList<CanonicalEditableField>>()
    fields.forEach { field ->
        val owner = reviewLineGroup(field.path)
            ?: productOwners[field.path.substringBeforeLast('.')]
        if (owner == null) otherFields += field
        else grouped.getOrPut(owner) { mutableListOf() } += field
    }
    val orderedPaths = lineOrder.filter { it in grouped } + grouped.keys.filter { it !in lineOrder }
    val items = orderedPaths.mapIndexed { index, path ->
        val itemFields = grouped.getValue(path)
        val productName = itemFields.singleOrNull { it.path.startsWith("product_candidates[") && it.path.endsWith(".product_name") }
        val description = itemFields.singleOrNull { it.path == "$path.description" }
        val matchingName = description?.takeIf {
            path in linkedLines && productName != null &&
                LinkedProductReview.sameObservedText(productName.value, it.value)
        }?.copy(label = "구매 내역 표기명")
        val displayedFields = itemFields.filterNot { it.path == matchingName?.path }.map { field ->
            when {
                field.path == "$path.description" && path.startsWith("purchase_records[") ->
                    field.copy(label = if (path in linkedLines) "구매 내역 표기명" else "상품명")
                field.path.startsWith("product_candidates[") ->
                    field.copy(label = field.label.replace(Regex("^상품 \\d+ · "), ""))
                else -> field
            }
        }
        PurchaseItemReviewGroup(path, lineOrder.indexOf(path).takeIf { it >= 0 }?.plus(1) ?: index + 1,
            displayedFields, matchingName, matchingName?.path in attentionPaths)
    }
    return PurchaseItemReviewLayout(otherFields, items)
}

internal fun purchaseItemDetail(field: CanonicalEditableField): Boolean =
    if (field.path.startsWith("product_candidates[")) field.path.substringAfterLast('.') !in
        setOf("product_name", "brand_name", "variant_name", "specification_text")
    else reviewLineDetail(field)

internal fun purchaseItemFieldOrder(field: CanonicalEditableField): Int = when (field.path.substringAfterLast('.')) {
    "product_name" -> 0
    "description" -> 1
    "brand_name" -> 2
    "variant_name", "option_text", "specification_text" -> 3
    "quantity" -> 4
    else -> 5
}
