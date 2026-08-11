package com.pricetrace.receiptscanner.parser

import com.pricetrace.receiptscanner.domain.FieldProvenance
import com.pricetrace.receiptscanner.domain.ParsedField
import com.pricetrace.receiptscanner.domain.ParsedPayment
import com.pricetrace.receiptscanner.domain.ParsedReceipt
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrLine

interface ParserProfile {
    val id: String
    fun appliesTo(document: OcrDocument): Boolean
}

object GenericParserProfile : ParserProfile {
    override val id: String = "generic.v15"
    override fun appliesTo(document: OcrDocument): Boolean = true
}

interface ReceiptParser {
    val version: String
    fun parse(document: OcrDocument): ParsedReceipt
}

class GenericReceiptParser(
    private val profile: ParserProfile = GenericParserProfile,
) : ReceiptParser {
    override val version: String = "generic-parser.v15"

    override fun parse(document: OcrDocument): ParsedReceipt {
        require(profile.appliesTo(document)) { "Parser profile does not apply to this document" }
        val lines = document.lines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy(OcrLine::pageIndex, OcrLine::recognitionOrder))
        val sections = ReceiptSectionDetector.detect(lines)
        val receiptFields = ReceiptFieldExtractor(profile.id).extract(sections)
        val headerLines = sections.headerLines.ifEmpty { lines.take(METADATA_FALLBACK_LINE_COUNT) }
        val transactionLines = (headerLines + sections.summaryLines + sections.paymentLines)
            .distinctBy(OcrLine::id)
        val currencyLines = (sections.paymentLines + sections.summaryLines + headerLines + sections.itemLines)
            .distinctBy(OcrLine::id)
        val phoneLine = headerLines.firstOrNull { PHONE_REGEX.containsMatchIn(it.text) }
        val transactionLine = transactionLines.firstOrNull { TRANSACTION_REGEX.containsMatchIn(it.text) }
        val explicitCurrencyLine = currencyLines.firstOrNull { CURRENCY_REGEX.containsMatchIn(it.text) }
        val grandTotalSourceIds = receiptFields.totals.grandTotalAmountMinor.provenance
            .map(FieldProvenance::ocrLineId)
            .toSet()
        val inferredCurrencyLine = if (
            explicitCurrencyLine == null && currencyLines.none { FOREIGN_CURRENCY_REGEX.containsMatchIn(it.text) }
        ) {
            currencyLines.firstOrNull { line ->
                KRW_INFERENCE_CONTEXT.containsMatchIn(line.text) &&
                    (AmountParser.extractAllMinor(line.text).isNotEmpty() || line.id in grandTotalSourceIds)
            } ?: receiptFields.totals.grandTotalAmountMinor.value?.let {
                currencyLines.firstOrNull { line -> KRW_INFERENCE_CONTEXT.containsMatchIn(line.text) }
            }
        } else {
            null
        }
        val currencyLine = explicitCurrencyLine ?: inferredCurrencyLine

        val excludedLineIds = receiptFields.nonItemSourceLineIds + setOfNotNull(
            phoneLine?.id,
            transactionLine?.id,
        )
        val lineItems = ReceiptLineItemExtractor(profile.id).extract(
            documentId = document.documentId,
            lines = sections.itemLines,
            excludedLineIds = excludedLineIds,
        )
        val paymentLines = sections.paymentLines.ifEmpty { lines }
        val payments = paymentLines.mapNotNull(::parsePayment).map { payment ->
            if (
                payment.sourceLineReferences.any(grandTotalSourceIds::contains) &&
                (payment.amountMinor == null || grandTotalSourceIds.size > 1) &&
                receiptFields.totals.grandTotalAmountMinor.value != null
            ) {
                payment.copy(
                    amountMinor = receiptFields.totals.grandTotalAmountMinor.value,
                    sourceLineReferences = (
                        payment.sourceLineReferences + grandTotalSourceIds
                        ).distinct(),
                )
            } else {
                payment
            }
        }

        return ParsedReceipt(
            documentId = document.documentId,
            sourceImages = document.pages.sortedBy { it.pageIndex }.map { it.pageId },
            rawText = document.rawText,
            merchantName = receiptFields.merchantName,
            branchName = receiptFields.branchName,
            businessRegistrationNumber = receiptFields.businessRegistrationNumber,
            address = receiptFields.address,
            phone = phoneLine.toField(
                value = phoneLine?.text?.let { PHONE_REGEX.find(it)?.value },
                ruleId = "${profile.id}.merchant.phone",
                confidence = 0.8f,
            ),
            issuedOn = receiptFields.issuedOn,
            issuedTime = receiptFields.issuedTime,
            issuedAt = receiptFields.issuedAt,
            originalDocumentId = transactionLine.toField(
                value = transactionLine?.text?.let(::extractTransactionId),
                ruleId = "${profile.id}.transaction.keyword",
                confidence = 0.75f,
            ),
            currency = currencyLine.toField(
                value = currencyLine?.let { "KRW" },
                ruleId = if (explicitCurrencyLine != null) {
                    "${profile.id}.currency.explicit_krw"
                } else {
                    "${profile.id}.currency.korean_amount_context"
                },
                confidence = if (explicitCurrencyLine != null) 0.95f else 0.78f,
            ),
            lineItems = lineItems,
            totals = receiptFields.totals,
            payments = payments,
        )
    }

    private fun parsePayment(line: OcrLine): ParsedPayment? {
        if (!PAYMENT_CONTEXT.containsMatchIn(line.text)) return null
        val amount = PAYMENT_AMOUNT_LABEL.find(line.text)?.let { label ->
            AmountParser.extractFirstMinor(line.text.substring(label.range.last + 1))
        } ?: AmountParser.extractLastMinor(line.text)
        return ParsedPayment(
            method = when {
                WALLET_KEYWORD.containsMatchIn(line.text) -> "digital_wallet"
                CARD_KEYWORD.containsMatchIn(line.text) -> "card"
                CASH_KEYWORD.containsMatchIn(line.text) -> "cash"
                else -> "other"
            },
            amountMinor = amount,
            sourceLineReferences = listOf(line.id),
        )
    }

    private fun extractTransactionId(text: String): String? = TRANSACTION_REGEX.find(text)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun OcrLine?.toField(value: String?, ruleId: String, confidence: Float): ParsedField<String> =
        if (this == null || value == null) ParsedField(null)
        else ParsedField(value, listOf(provenance(ruleId, confidence)))

    private fun OcrLine.provenance(ruleId: String, fallbackConfidence: Float) = FieldProvenance(
        sourcePageId = pageId,
        ocrLineId = id,
        boundingBox = boundingBox,
        rawText = text,
        parserRuleId = ruleId,
        confidence = minOf(confidence ?: fallbackConfidence, fallbackConfidence),
    )

    companion object {
        private const val METADATA_FALLBACK_LINE_COUNT = 16
        private val PHONE_REGEX = Regex("""\b(?:0\d{1,2})[- ]?\d{3,4}[- ]?\d{4}\b""")
        private val TRANSACTION_REGEX = Regex(
            """(?:거래|승인|영수증|전표|주문)\s*(?:번호|No\.?|#)?\s*[:：-]?\s*([A-Za-z0-9-]{4,})""",
            RegexOption.IGNORE_CASE,
        )
        private val CURRENCY_REGEX = Regex(
            """(₩|￦|\bKRW\b|(?:통화|화폐|단위)\s*[:：-]?\s*원|$GROUPED_INTEGER_PATTERN\s*원|(?<![가-힣A-Za-z])원(?![가-힣A-Za-z]))""",
            RegexOption.IGNORE_CASE,
        )
        private val FOREIGN_CURRENCY_REGEX = Regex(
            """(\b(?:USD|JPY|EUR|CNY|RMB|GBP|AUD|CAD|HKD|TWD)\b|\$|€|¥)""",
            RegexOption.IGNORE_CASE,
        )
        private val KRW_INFERENCE_CONTEXT = Regex(
            """(최\s*종\s*결\s*제\s*금\s*액|총\s*결\s*제\s*금\s*액|실\s*결\s*제\s*금\s*액|결\s*제\s*금\s*액|총\s*합\s*계|합\s*계|받\s*을\s*금\s*액|청\s*구\s*금\s*액|단\s*가|금\s*액)""",
        )
        private val CARD_KEYWORD = Regex("""카\s*드""")
        private val CASH_KEYWORD = Regex("""현\s*금""")
        private val WALLET_KEYWORD = Regex(
            """(간\s*편|카카오\s*페이|네이버\s*페이|토스\s*페이|PAYCO)""",
            RegexOption.IGNORE_CASE,
        )
        private val PAYMENT_CONTEXT = Regex(
            """(결\s*제\s*(?:금\s*액|수\s*단)|승\s*인\s*금\s*액|카\s*드\s*(?:결\s*제|승\s*인(?!\s*번\s*호))|현\s*금\s*(?:결\s*제|영\s*수)|간\s*편\s*결\s*제|(?:카카오|네이버|토스)\s*페이|PAYCO|(?:카\s*드|현\s*금)\s*[:：]?\s*(?=(?:[₩￦]\s*)?$GROUPED_INTEGER_PATTERN\s*원))""",
            RegexOption.IGNORE_CASE,
        )
        private val PAYMENT_AMOUNT_LABEL = Regex("""(결\s*제\s*금\s*액|승\s*인\s*금\s*액|결\s*제|승\s*인)""")
    }
}
