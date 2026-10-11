package com.pricetrace.receiptscanner.ingestion

import java.math.BigDecimal

/** Compatibility at the single-text PriceTrace receiver boundary. Never changes canonical facts. */
object PriceTracePurchaseOptions {
    const val MAX_TEXT_CHARACTERS = 500

    fun flatten(line: PurchaseRecordLine): String? = if (line.optionItems.isEmpty()) line.optionText else
        line.optionItems.joinToString(" | ") { option ->
            buildString {
                append(option.description)
                option.quantity?.let {
                    append(" ×")
                    append(BigDecimal.valueOf(it).stripTrailingZeros().toPlainString())
                }
                option.amountKrw?.let { append(" [$it KRW]") }
            }
        }

    fun incompatibilityReason(line: PurchaseRecordLine): String? {
        val text = flatten(line) ?: return null
        return when {
            '\u0000' in text || text.codePoints().anyMatch { it in 0xD800..0xDFFF } ->
                PriceTraceV4SubmissionReason.OPTION_TEXT_UNREPRESENTABLE
            text.codePointCount(0, text.length) > MAX_TEXT_CHARACTERS ->
                PriceTraceV4SubmissionReason.OPTION_TEXT_LENGTH_REQUIRED
            else -> null
        }
    }
}
