package com.yeonsik.ingestion.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.CanonicalFieldRegistry
import com.pricetrace.receiptscanner.review.CanonicalFieldType
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val reviewTimestampFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd-HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

internal fun reviewTimestamp(value: String): String = runCatching {
    OffsetDateTime.parse(value).format(reviewTimestampFormat)
}.getOrDefault(value)

private val sourceTimestamp = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})""")

internal fun reviewTimestampText(value: String): String = sourceTimestamp.replace(value) { reviewTimestamp(it.value) }

/** Display wall time without changing its source offset or inventing an unknown time. */
internal fun reviewTimestampInput(value: String, original: String?): String {
    if (value == original?.let(::reviewTimestamp)) return original
    return runCatching {
        val offset = OffsetDateTime.parse(requireNotNull(original)).offset
        LocalDateTime.parse(value, reviewTimestampFormat).atOffset(offset).toString()
    }.getOrDefault(value)
}

internal fun reviewTimestampOffset(value: String): String? = runCatching { OffsetDateTime.parse(value).offset.id }.getOrNull()

internal val LocalReviewEnvelope = staticCompositionLocalOf<YeonsikOcrEnvelope?> { null }

internal fun reviewFieldUnit(field: CanonicalEditableField, envelope: YeonsikOcrEnvelope?, drafts: Map<String, String> = emptyMap()): String? {
    val path = field.path
    if (".nutrients." in path) return NutritionField.fromWireKey(path.substringAfterLast('.'))?.canonicalUnit
    fun sibling(name: String): String? {
        val target = path.substringBeforeLast('.') + ".$name"
        return (drafts[target] ?: envelope?.let { CanonicalFieldRegistry.value(it, target) })?.takeIf { it.isNotBlank() }
    }
    return when {
        path.endsWith(".basis_amount") -> sibling("basis_unit")
        path.endsWith(".content_amount") -> sibling("content_unit")
        path.startsWith("consumption[") && path.endsWith(".amount") -> sibling("unit")
        path.endsWith("_krw") -> "원"
        path.endsWith("_minor") -> envelope?.receipt?.document?.currency?.let { if (it == "KRW") "원" else "$it 최소단위" }
        path.endsWith(".package_count") -> "개"
        else -> null
    }
}

internal enum class ReviewFieldCategory(val title: String) {
    PRODUCT("상품 정보"), BASIS("영양 기준"), NUTRIENTS("영양성분"), PRODUCT_DETAILS("상품 상세·규격"),
    PURCHASE("구매·수령 방식"), MERCHANT("판매처 정보"), TIME("구매 일시"),
    ITEMS("구매 품목"), PAYMENT("결제 정보"), TOTALS("금액·합계"),
    CONSUMPTION("섭취 기록"), LINKS("연결 정보"), OTHER("기타 정보"),
}

internal fun reviewFieldCategory(field: CanonicalEditableField): ReviewFieldCategory = when {
    ".nutrients." in field.path -> ReviewFieldCategory.NUTRIENTS
    field.path.startsWith("nutrition[") -> ReviewFieldCategory.BASIS
    field.path.startsWith("product_candidates[") -> if (field.path.substringAfterLast('.') in setOf("product_name", "brand_name"))
        ReviewFieldCategory.PRODUCT else ReviewFieldCategory.PRODUCT_DETAILS
    field.path.startsWith("consumption[") -> ReviewFieldCategory.CONSUMPTION
    field.path.startsWith("links[") -> ReviewFieldCategory.LINKS
    field.type == CanonicalFieldType.DATE || field.type == CanonicalFieldType.DATETIME -> ReviewFieldCategory.TIME
    ".fulfillment." in field.path -> ReviewFieldCategory.PURCHASE
    ".payments[" in field.path || ".payment." in field.path -> ReviewFieldCategory.PAYMENT
    ".totals." in field.path -> ReviewFieldCategory.TOTALS
    ".merchant." in field.path || field.path.startsWith("merchant_candidate.") -> ReviewFieldCategory.MERCHANT
    ".line_items[" in field.path -> ReviewFieldCategory.ITEMS
    field.path.startsWith("purchase_records[") -> ReviewFieldCategory.PURCHASE
    else -> ReviewFieldCategory.OTHER
}

internal fun reviewGridColumns(width: Int): Int {
    val desired = when { width >= 900 -> 4; width >= 520 -> 3; else -> 2 }
    return minOf(desired, ((width + 12) / 212).coerceAtLeast(1))
}

internal fun wideReviewField(field: CanonicalEditableField): Boolean = field.type == CanonicalFieldType.DATETIME ||
    field.path.substringAfterLast('.') in setOf("address", "description", "specification_text", "note", "notes")
