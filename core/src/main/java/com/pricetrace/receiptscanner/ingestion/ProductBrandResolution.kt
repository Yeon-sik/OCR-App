package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.*
import java.util.UUID

enum class ProductBrandFactField(val wire: String, val label: String) {
    BRAND("brand", "브랜드"), SUB_BRAND("sub_brand", "서브브랜드");
    fun observed(product: ProductCandidate): String? = when (this) { BRAND -> product.brand; SUB_BRAND -> product.subBrand }
    companion object { fun fromWire(value: String) = entries.single { it.wire == value } }
}
enum class ProductBrandSelectionIntent { UNSET, SELECTED, CLEAR }

data class ProductBrandResolutionDraft(
    val productClientKey: String,
    val sourceField: ProductBrandFactField,
    val observedValue: String,
    val intent: ProductBrandSelectionIntent = ProductBrandSelectionIntent.UNSET,
    val brandId: String? = null,
    val canonicalName: String? = null,
    val parentBrandId: String? = null,
    val parentCanonicalName: String? = null,
    val matchedName: String? = null,
    val matchKind: String? = null,
) {
    init {
        require(productClientKey.isNotBlank() && observedValue.isNotBlank())
        require(intent != ProductBrandSelectionIntent.SELECTED || validBrandUuid(brandId))
        require(intent == ProductBrandSelectionIntent.SELECTED || brandId == null)
        require(parentBrandId == null || validBrandUuid(parentBrandId))
    }
    fun matches(product: ProductCandidate): Boolean = productClientKey == product.clientKey &&
        observedValue.trim() == sourceField.observed(product)?.trim()
}

data class PriceTraceBrandMatch(val brandId: String, val canonicalName: String,
    val parentBrandId: String?, val parentCanonicalName: String?, val matchedName: String,
    val matchKind: String, val logoUrl: String?, val officialSiteUrl: String?) {
    fun select(product: ProductCandidate, field: ProductBrandFactField) = ProductBrandResolutionDraft(
        product.clientKey, field, requireNotNull(field.observed(product)).trim(), ProductBrandSelectionIntent.SELECTED,
        brandId, canonicalName, parentBrandId, parentCanonicalName, matchedName, matchKind)
}

fun validBrandUuid(value: String?): Boolean = value != null && runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)

/** Dropped stale facts never migrate onto another observation, including undo/redo. */
fun currentBrandDrafts(drafts: List<ProductBrandResolutionDraft>, products: List<ProductCandidate>) = drafts.filter { draft ->
    products.any(draft::matches)
}

object ProductBrandResolutionJson {
    fun encode(drafts: List<ProductBrandResolutionDraft>): String = JsonArray(drafts.map { d -> buildJsonObject {
        put("productClientKey", d.productClientKey); put("sourceField", d.sourceField.wire)
        put("observedValue", d.observedValue); put("intent", d.intent.name)
        put("brandId", d.brandId?.let(::JsonPrimitive) ?: JsonNull)
        put("canonicalName", d.canonicalName?.let(::JsonPrimitive) ?: JsonNull)
        put("parentBrandId", d.parentBrandId?.let(::JsonPrimitive) ?: JsonNull)
        put("parentCanonicalName", d.parentCanonicalName?.let(::JsonPrimitive) ?: JsonNull)
        put("matchedName", d.matchedName?.let(::JsonPrimitive) ?: JsonNull)
        put("matchKind", d.matchKind?.let(::JsonPrimitive) ?: JsonNull)
    } }).toString()
    fun decode(value: String): List<ProductBrandResolutionDraft> {
        val rows = Json.parseToJsonElement(value).jsonArray.map { element ->
            val r = element.jsonObject
            fun text(key: String) = r[key]?.jsonPrimitive?.contentOrNull
            ProductBrandResolutionDraft(requireNotNull(text("productClientKey")), ProductBrandFactField.fromWire(requireNotNull(text("sourceField"))),
                requireNotNull(text("observedValue")), ProductBrandSelectionIntent.valueOf(requireNotNull(text("intent"))),
                text("brandId"),text("canonicalName"),text("parentBrandId"),text("parentCanonicalName"),text("matchedName"),text("matchKind"))
        }
        require(rows.map { it.productClientKey to it.sourceField }.distinct().size == rows.size) { "duplicate_brand_resolution_draft" }
        return rows
    }
}
