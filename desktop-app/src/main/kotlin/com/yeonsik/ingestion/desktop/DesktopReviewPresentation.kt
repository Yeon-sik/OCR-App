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
    field.path.startsWith("nutrition[") && field.path.substringAfterLast('.') in setOf("menu_name", "product_name", "brand", "category") -> ReviewFieldCategory.PRODUCT
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

/** Display labels only; enum wire values and canonical paths stay untouched. */
internal fun reviewEnumLabel(value: String): String = reviewEnumLabels[value] ?: value.ifEmpty { "없음" }

private val reviewEnumLabels = mapOf(
    "unknown" to "미확인", "delivery" to "배달", "takeout" to "포장", "dine_in" to "매장 식사",
    "printed" to "원본에 표시", "user_confirmed" to "사용자 확인", "user_verified" to "검수 완료", "unverified" to "미검수",
    "card" to "카드", "cash" to "현금", "bank_transfer" to "계좌 이체", "mobile_payment" to "간편 결제",
    "gift_card" to "상품권", "points" to "포인트", "mixed" to "복합 결제",
    "authorized" to "승인", "paid" to "결제 완료", "refunded" to "환불 완료", "voided" to "무효", "cancelled" to "취소",
    "pending" to "대기", "ordered" to "주문 완료", "shipped" to "발송 완료", "delivered" to "배송 완료",
    "product" to "상품", "service" to "서비스", "discount" to "할인", "fee" to "수수료", "tax" to "세금",
    "tip" to "팁", "refund" to "환불", "rounding" to "반올림", "other" to "기타",
    "main" to "주 메뉴", "option" to "추가 옵션", "side" to "곁들임", "included" to "기본 포함",
    "complimentary" to "무료 제공", "complimentary_side" to "무료 곁들임", "review_event" to "리뷰 행사", "promotion" to "판촉 혜택",
    "retail" to "소매", "restaurant" to "외식", "food_service" to "음식점", "transport" to "교통",
    "accommodation" to "숙박", "healthcare" to "의료", "professional_service" to "전문 서비스",
    "utility" to "공과금", "government" to "공공기관", "financial" to "금융", "marketplace" to "거래 플랫폼",
    "meat" to "육류", "poultry" to "가금류", "seafood" to "수산물", "egg" to "달걀", "grain" to "곡류",
    "vegetable" to "채소", "fruit" to "과일", "legume" to "콩류", "dairy" to "유제품", "nut_seed" to "견과·씨앗",
    "processed" to "가공식품", "beverage" to "음료", "recipe" to "요리", "sauce" to "소스", "cereal" to "시리얼",
    "serving" to "회 제공량", "each" to "개", "itemized" to "개별 가격 확인", "ambiguous" to "가격 불명확",
    "purchase_line" to "구매 품목", "menu_component" to "메뉴 구성요소", "confirmed" to "확인됨", "estimated" to "추정",
    "user_provided" to "사용자 입력", "observed" to "원본에서 확인",
)

internal fun reviewLineGroup(path: String): String? = Regex("^(.+\\.line_items\\[[^]]+]).+").find(path)?.groupValues?.get(1)

internal fun reviewLineDetail(field: CanonicalEditableField): Boolean = field.path.substringAfterLast('.') in
    setOf("type", "role", "applies_to_line_id", "benefit_kind", "merchant_sku", "price_status", "product_client_key")

internal fun reviewerField(field: CanonicalEditableField, envelope: YeonsikOcrEnvelope): CanonicalEditableField {
    val segments = Regex("\\[([^]]+)]").findAll(field.path).map { it.groupValues[1] }.toList()
    var label = field.label
    // The registry prefixes labels with stable keys; replace only that exact prefix.
    for (prefix in listOf(segments.joinToString("/")) + segments.reversed()) {
        if (prefix.isNotBlank() && label.startsWith("$prefix ")) label = label.removePrefix("$prefix ")
    }
    val nutritionIndex = envelope.nutrition.indexOfFirst { field.path.startsWith("nutrition[${it.clientKey}].") }
    if (nutritionIndex >= 0 && envelope.nutrition.size > 1) label = "음식 ${nutritionIndex + 1} · $label"
    val productIndex = envelope.productCandidates.indexOfFirst { field.path.startsWith("product_candidates[${it.clientKey}].") }
    if (productIndex >= 0 && envelope.productCandidates.size > 1) label = "상품 ${productIndex + 1} · $label"
    if (field.path.startsWith("receipt.payments[")) label = "결제 ${(segments.firstOrNull()?.toIntOrNull() ?: 0) + 1} · $label"
    return field.copy(label = label)
}
