package com.pricetrace.receiptscanner.correction

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.parser.AmountParser
import java.math.BigDecimal

enum class ReceiptCorrectionRejectionReason {
    DUPLICATE_FIELD,
    UNSUPPORTED_FIELD,
    UNKNOWN_LINE_ITEM,
    STALE_OLD_VALUE,
    MISSING_SOURCE_EVIDENCE,
    UNKNOWN_SOURCE_EVIDENCE,
    UNRELATED_SOURCE_EVIDENCE,
    INVALID_VALUE,
    AMOUNT_CONSERVATION_FAILED,
}

data class RejectedReceiptCorrection(
    val candidate: ReceiptCorrectionCandidate,
    val reason: ReceiptCorrectionRejectionReason,
)

data class ValidatedReceiptCorrections(
    val accepted: List<ReceiptCorrectionCandidate>,
    val rejected: List<RejectedReceiptCorrection>,
)

object ReceiptCorrectionPolicy {
    private val merchantFieldPath = Regex(
        """^merchant\.(name|branch_name|business_registration_number|address|phone)$""",
    )
    private val lineFieldPath = Regex(
        """^line_items\[([^]]+)]\.(description|quantity|unit_price_amount_minor|net_amount_minor)$""",
    )

    fun validateBatch(
        receipt: ReceiptV2,
        ocrDocument: OcrDocument,
        candidates: List<ReceiptCorrectionCandidate>,
    ): ValidatedReceiptCorrections {
        val seenFields = mutableSetOf<String>()
        val accepted = mutableListOf<ReceiptCorrectionCandidate>()
        val rejected = mutableListOf<RejectedReceiptCorrection>()
        candidates.forEach { candidate ->
            val reason = if (!seenFields.add(candidate.fieldPath)) {
                ReceiptCorrectionRejectionReason.DUPLICATE_FIELD
            } else {
                rejectionReason(receipt, ocrDocument, candidate)
            }
            if (reason == null) accepted += candidate else rejected += RejectedReceiptCorrection(candidate, reason)
        }
        return ValidatedReceiptCorrections(accepted, rejected)
    }

    fun rejectionReason(
        receipt: ReceiptV2,
        ocrDocument: OcrDocument,
        candidate: ReceiptCorrectionCandidate,
    ): ReceiptCorrectionRejectionReason? {
        if (merchantFieldPath.matches(candidate.fieldPath)) {
            return merchantRejectionReason(receipt, ocrDocument, candidate)
        }
        val match = lineFieldPath.matchEntire(candidate.fieldPath)
            ?: return ReceiptCorrectionRejectionReason.UNSUPPORTED_FIELD
        val lineItem = receipt.lineItems.firstOrNull { it.id == match.groupValues[1] }
            ?: return ReceiptCorrectionRejectionReason.UNKNOWN_LINE_ITEM
        val field = match.groupValues[2]
        if (candidate.oldValue.normalized() != lineItem.valueForCorrection(field).normalized()) {
            return ReceiptCorrectionRejectionReason.STALE_OLD_VALUE
        }
        if (candidate.sourceLineIds.isEmpty()) {
            return ReceiptCorrectionRejectionReason.MISSING_SOURCE_EVIDENCE
        }
        val knownSourceIds = ocrDocument.lines.mapTo(mutableSetOf()) { it.id }
        if (candidate.sourceLineIds.any { it !in knownSourceIds }) {
            return ReceiptCorrectionRejectionReason.UNKNOWN_SOURCE_EVIDENCE
        }
        if (candidate.sourceLineIds.any { it !in lineItem.sourceLineReferences }) {
            return ReceiptCorrectionRejectionReason.UNRELATED_SOURCE_EVIDENCE
        }
        if (candidate.confidencePercent !in 0..100 || !candidate.proposedValue.isSafeCandidateValue()) {
            return ReceiptCorrectionRejectionReason.INVALID_VALUE
        }
        if (!lineItem.isValidProposedValue(field, candidate.proposedValue)) {
            return ReceiptCorrectionRejectionReason.INVALID_VALUE
        }
        if (!lineItem.preservesAmountConservation(field, candidate.proposedValue)) {
            return ReceiptCorrectionRejectionReason.AMOUNT_CONSERVATION_FAILED
        }
        return null
    }

    private fun merchantRejectionReason(
        receipt: ReceiptV2,
        ocrDocument: OcrDocument,
        candidate: ReceiptCorrectionCandidate,
    ): ReceiptCorrectionRejectionReason? {
        val current = ReceiptMerchantFieldSemantics.currentValue(receipt, candidate.fieldPath)
        if (candidate.oldValue.normalized() != current.normalized()) {
            return ReceiptCorrectionRejectionReason.STALE_OLD_VALUE
        }
        if (candidate.sourceLineIds.isEmpty()) {
            return ReceiptCorrectionRejectionReason.MISSING_SOURCE_EVIDENCE
        }
        val linesById = ocrDocument.lines.associateBy { it.id }
        if (candidate.sourceLineIds.any { it !in linesById }) {
            return ReceiptCorrectionRejectionReason.UNKNOWN_SOURCE_EVIDENCE
        }
        if (candidate.sourceLineIds.any { sourceId ->
                !ReceiptMerchantFieldSemantics.isRelevantSourceLine(
                    fieldPath = candidate.fieldPath,
                    text = requireNotNull(linesById[sourceId]).text,
                    currentValue = current,
                    proposedValue = candidate.proposedValue,
                )
            }
        ) {
            return ReceiptCorrectionRejectionReason.UNRELATED_SOURCE_EVIDENCE
        }
        if (candidate.confidencePercent !in 0..100 || !candidate.proposedValue.isSafeCandidateValue()) {
            return ReceiptCorrectionRejectionReason.INVALID_VALUE
        }
        if (!ReceiptMerchantFieldSemantics.isValidProposedValue(candidate.fieldPath, candidate.proposedValue)) {
            return ReceiptCorrectionRejectionReason.INVALID_VALUE
        }
        return null
    }

    fun currentValue(receipt: ReceiptV2, fieldPath: String): String? {
        if (merchantFieldPath.matches(fieldPath)) {
            return ReceiptMerchantFieldSemantics.currentValue(receipt, fieldPath)
        }
        val match = lineFieldPath.matchEntire(fieldPath) ?: return null
        return receipt.lineItems.firstOrNull { it.id == match.groupValues[1] }
            ?.valueForCorrection(match.groupValues[2])
    }

    fun parseLineFieldPath(fieldPath: String): Pair<String, String>? = lineFieldPath.matchEntire(fieldPath)
        ?.let { match -> match.groupValues[1] to match.groupValues[2] }

    fun parseMerchantFieldPath(fieldPath: String): String? = merchantFieldPath.matchEntire(fieldPath)
        ?.groupValues?.get(1)

    private fun ReceiptV2LineItem.valueForCorrection(field: String): String? = when (field) {
        "description" -> description
        "quantity" -> quantity?.value
        "unit_price_amount_minor" -> unitPriceAmountMinor?.toString()
        "net_amount_minor" -> netAmountMinor?.toString()
        else -> null
    }

    private fun ReceiptV2LineItem.isValidProposedValue(field: String, proposed: String): Boolean = when (field) {
        "description" -> proposed.trim().length in 2..160
        "quantity" -> proposed.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO && it <= MAX_QUANTITY } == true
        "unit_price_amount_minor", "net_amount_minor" -> AmountParser.normalizeMinor(proposed) != null
        else -> false
    }

    private fun ReceiptV2LineItem.preservesAmountConservation(field: String, proposed: String): Boolean {
        val nextQuantity = if (field == "quantity") proposed else quantity?.value
        val nextUnitPrice = if (field == "unit_price_amount_minor") {
            AmountParser.normalizeMinor(proposed)
        } else {
            unitPriceAmountMinor
        }
        val nextNetAmount = if (field == "net_amount_minor") {
            AmountParser.normalizeMinor(proposed)
        } else {
            netAmountMinor
        }
        if (nextQuantity == null || nextUnitPrice == null || nextNetAmount == null) return true
        val expected = nextQuantity.toBigDecimalOrNull()
            ?.multiply(BigDecimal.valueOf(nextUnitPrice))
            ?: return false
        return expected.compareTo(BigDecimal.valueOf(nextNetAmount)) == 0
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String.isSafeCandidateValue(): Boolean {
        val value = trim()
        return value.isNotEmpty() && value.length <= 160 && '\n' !in value && '\r' !in value
    }

    private val MAX_QUANTITY = BigDecimal("10000")
}
