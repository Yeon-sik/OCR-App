package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import java.math.BigDecimal
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Separately reviewed manufacturer facts. The local candidate selector stays on this device. */
data class ManufacturerProductPublicationDraft(
    val productClientKey: String,
    val productName: String,
    val brandName: String,
    val manufacturerSku: String = "",
    val manufacturerUrl: String = "",
    val specification: String,
    val contentAmount: String,
    val contentUnit: String,
    val packageCount: String,
) {
    fun validationMessage(): String? {
        val amount = contentAmount.trim().toBigDecimalOrNull()
        val count = packageCount.trim().toIntOrNull()
        return when {
            !validText(productName, 200) || !validText(brandName, 120) ->
                "제조사 페이지에 표시된 상품명과 브랜드를 확인하세요."
            !SKU.matches(manufacturerSku.trim()) -> "제조사 SKU를 확인하세요."
            !validManufacturerUrl(manufacturerUrl.trim()) -> "제조사 공식 상품 페이지의 HTTPS 주소를 입력하세요."
            !validText(specification, 120) -> "제조사 규격 표기를 입력하세요."
            amount == null || amount.signum() <= 0 || amount > MAX_CONTENT ||
                amount.stripTrailingZeros().scale() > 3 ||
                contentUnit !in CONTENT_UNITS || (contentUnit == "each" && amount.stripTrailingZeros().scale() > 0) ||
                count == null || count !in 1..1000 ->
                "내용량, 단위, 한 묶음에 포함된 포장 수를 확인하세요."
            else -> null
        }
    }

    internal fun publicFacts(): JsonObject {
        val problem = validationMessage()
        require(problem == null) { problem.orEmpty() }
        return buildJsonObject {
            put("product_name", productName.trim())
            put("brand_name", brandName.trim())
            put("manufacturer_sku", manufacturerSku.trim())
            put("manufacturer_url", manufacturerUrl.trim())
            put("specification", specification.trim())
            put("content_amount", kotlinx.serialization.json.JsonPrimitive(contentAmount.trim().toBigDecimal()))
            put("content_unit", contentUnit)
            put("package_count", packageCount.trim().toInt())
        }
    }

    companion object {
        private val SKU = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        private val MAX_CONTENT = BigDecimal("1000000")
        val CONTENT_UNITS = listOf("each", "g", "ml")

        fun from(candidate: ProductCandidate) = ManufacturerProductPublicationDraft(
            productClientKey = candidate.clientKey,
            productName = candidate.productName,
            brandName = candidate.brand.orEmpty(),
            specification = candidate.specification.orEmpty(),
            contentAmount = candidate.contentAmount?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty(),
            contentUnit = candidate.contentUnit.orEmpty(),
            packageCount = candidate.packageCount?.toString().orEmpty(),
        )

        private fun validText(value: String, maxLength: Int) =
            value.trim().length in 1..maxLength && value.none(Char::isISOControl)

        private fun validManufacturerUrl(value: String): Boolean {
            if (value.length > 1000) return false
            val url = runCatching { URI(value) }.getOrNull() ?: return false
            return url.scheme == "https" && url.host?.contains('.') == true && url.port == -1 &&
                url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null && (url.rawPath?.length ?: 0) > 1
        }
    }
}
