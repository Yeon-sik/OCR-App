package com.pricetrace.receiptscanner.domain

import com.pricetrace.receiptscanner.parser.multiplyExactMinor
import kotlin.math.abs

enum class ReconciliationHypothesisCode {
    /** A row has no amount yet, so the line total cannot be compared with the receipt total. */
    UNRESOLVED_LINE_AMOUNT,

    /** The receipt total itself is missing. */
    GRAND_TOTAL_UNKNOWN,

    /** Quantity x unit price on one row already explains the whole gap. */
    LINE_CONSERVATION_MISMATCH,

    /** One row amount could be a recognition slip of a value that closes the gap. */
    LINE_AMOUNT_MISREAD,

    /** The receipt total could be a recognition slip of the line total. */
    GRAND_TOTAL_MISREAD,

    /** A row worth exactly the surplus looks duplicated or not a product at all. */
    DUPLICATE_LINE,

    /** The gap equals a printed discount, tax or fee that has no row of its own. */
    ADJUSTMENT_ROW_MISSING,

    /** Nothing on the draft explains the gap; a printed row was probably never recognized. */
    MISSING_LINE,

    /** The line total exceeds the receipt total and no single row explains it. */
    UNEXPLAINED_SURPLUS,
}

/** An action the reviewer may take. Applying one is always an explicit user edit, never automatic. */
sealed interface ReconciliationSuggestion {
    data class SetLineNetAmount(val lineItemId: String, val amountMinor: Long) : ReconciliationSuggestion
    data class SetGrandTotal(val amountMinor: Long) : ReconciliationSuggestion
    data class RemoveLineItem(val lineItemId: String) : ReconciliationSuggestion
    data class AddLineItem(val type: ReceiptLineType, val amountMinor: Long) : ReconciliationSuggestion
}

data class ReconciliationHypothesis(
    val code: ReconciliationHypothesisCode,
    val message: String,
    val evidence: String,
    val lineItemId: String?,
    val suggestion: ReconciliationSuggestion?,
    val rank: Int,
)

data class ReconciliationDiagnosis(
    val reconciliation: ReconciliationResult,
    val hypotheses: List<ReconciliationHypothesis>,
) {
    val hasSuggestions: Boolean get() = hypotheses.any { it.suggestion != null }
}

/**
 * Explains why line amounts and the receipt total disagree.
 *
 * Every hypothesis is derived from numbers already on the draft: the closing amount for a row is fixed
 * by arithmetic (`row + difference`), and OCR shape rules only decide whether that amount is a credible
 * misreading worth showing. Nothing is applied on its own — the reviewer compares each candidate with
 * the paper receipt and accepts it as their own edit.
 */
object ReconciliationDiagnostics {
    private const val MAX_HYPOTHESES = 8

    fun analyze(receipt: ReceiptV2): ReconciliationDiagnosis {
        val reconciliation = ReceiptReconciler.reconcile(receipt)
        val hypotheses = buildList {
            addAll(blockers(receipt))
            val difference = reconciliation.differenceMinor
            if (difference != null && difference != 0L) {
                addAll(conservationHypotheses(receipt, difference))
                addAll(lineMisreadHypotheses(receipt, difference))
                addAll(grandTotalMisreadHypotheses(receipt, reconciliation))
                addAll(surplusHypotheses(receipt, difference))
                addAll(adjustmentHypotheses(receipt, difference))
                addAll(missingLineHypotheses(difference, isEmpty()))
            }
        }
        return ReconciliationDiagnosis(
            reconciliation = reconciliation,
            hypotheses = hypotheses.sortedBy { it.rank }.take(MAX_HYPOTHESES),
        )
    }

    private fun blockers(receipt: ReceiptV2): List<ReconciliationHypothesis> = buildList {
        if (receipt.totals.grandTotalAmountMinor == null) {
            add(
                ReconciliationHypothesis(
                    code = ReconciliationHypothesisCode.GRAND_TOTAL_UNKNOWN,
                    message = "최종 결제금액을 읽지 못했습니다. 영수증 하단 금액을 확인해 입력하세요.",
                    evidence = "totals.grand_total_amount_minor = null",
                    lineItemId = null,
                    suggestion = null,
                    rank = -20,
                ),
            )
        }
        receipt.lineItems.filter { it.netAmountMinor == null }.forEach { item ->
            add(
                ReconciliationHypothesis(
                    code = ReconciliationHypothesisCode.UNRESOLVED_LINE_AMOUNT,
                    message = "‘${item.description ?: "설명 없는 행"}’의 금액을 읽지 못해 합계를 대조할 수 없습니다.",
                    evidence = "net_amount_minor = null",
                    lineItemId = item.id,
                    suggestion = item.expectedFromConservation()?.let {
                        ReconciliationSuggestion.SetLineNetAmount(item.id, it)
                    },
                    rank = -10,
                ),
            )
        }
    }

    /**
     * Two printed numbers on the same row (quantity and unit price) disagree with the printed row amount
     * by exactly the missing difference. That is the strongest evidence available without the image.
     */
    private fun conservationHypotheses(receipt: ReceiptV2, difference: Long): List<ReconciliationHypothesis> =
        receipt.lineItems.mapNotNull { item ->
            val net = item.netAmountMinor ?: return@mapNotNull null
            val expected = item.expectedFromConservation() ?: return@mapNotNull null
            if (expected == net) return@mapNotNull null
            if (subtractOrNull(expected, net) != difference) return@mapNotNull null
            ReconciliationHypothesis(
                code = ReconciliationHypothesisCode.LINE_CONSERVATION_MISMATCH,
                message = "‘${item.description ?: "설명 없는 행"}’의 수량 × 단가는 ${expected.won()}이고 " +
                    "인식된 행 금액은 ${net.won()}입니다. 이 행만 고치면 차액이 사라집니다.",
                evidence = "${item.quantity?.value} × ${item.unitPriceAmountMinor} = $expected ≠ $net",
                lineItemId = item.id,
                suggestion = ReconciliationSuggestion.SetLineNetAmount(item.id, expected),
                rank = 0,
            )
        }

    private fun lineMisreadHypotheses(receipt: ReceiptV2, difference: Long): List<ReconciliationHypothesis> =
        receipt.lineItems.mapNotNull { item ->
            val net = item.netAmountMinor ?: return@mapNotNull null
            val candidate = addOrNull(net, difference) ?: return@mapNotNull null
            if (candidate == 0L) return@mapNotNull null
            val kind = OcrDigitConfusion.classify(net, candidate)
            if (kind == MisreadKind.NONE) return@mapNotNull null
            ReconciliationHypothesis(
                code = ReconciliationHypothesisCode.LINE_AMOUNT_MISREAD,
                message = "‘${item.description ?: "설명 없는 행"}’이 ${candidate.won()}이면 차액이 사라집니다. " +
                    kind.reason(),
                evidence = "인식값 $net → 후보 $candidate (${kind.name})",
                lineItemId = item.id,
                suggestion = ReconciliationSuggestion.SetLineNetAmount(item.id, candidate),
                rank = kind.rank(),
            )
        }

    private fun grandTotalMisreadHypotheses(
        receipt: ReceiptV2,
        reconciliation: ReconciliationResult,
    ): List<ReconciliationHypothesis> {
        val grandTotal = reconciliation.grandTotalMinor ?: return emptyList()
        val lineTotal = reconciliation.lineNetTotalMinor ?: return emptyList()
        val kind = OcrDigitConfusion.classify(grandTotal, lineTotal)
        if (kind == MisreadKind.NONE) return emptyList()
        val everyLineHasEvidence = receipt.lineItems.isNotEmpty() &&
            receipt.lineItems.all { it.ocrSourceLineReferences().isNotEmpty() }
        return listOf(
            ReconciliationHypothesis(
                code = ReconciliationHypothesisCode.GRAND_TOTAL_MISREAD,
                message = "최종 합계가 ${lineTotal.won()}이면 행 합계와 일치합니다. " +
                    kind.reason() + " 영수증 하단 금액을 다시 확인하세요.",
                evidence = "인식값 $grandTotal → 후보 $lineTotal (${kind.name})" +
                    if (everyLineHasEvidence) ", 모든 행에 OCR 근거 있음" else "",
                lineItemId = null,
                suggestion = ReconciliationSuggestion.SetGrandTotal(lineTotal),
                // The printed total is the number a reviewer reads most reliably, so it ranks just
                // below a row-level explanation of the same strength.
                rank = kind.rank() + 1,
            ),
        )
    }

    private fun surplusHypotheses(receipt: ReceiptV2, difference: Long): List<ReconciliationHypothesis> {
        if (difference >= 0) return emptyList()
        val surplus = -difference
        return receipt.lineItems.filter { it.netAmountMinor == surplus }.map { item ->
            val duplicate = receipt.lineItems.any { other ->
                other.id != item.id &&
                    other.netAmountMinor == item.netAmountMinor &&
                    other.description != null &&
                    other.description == item.description
            }
            ReconciliationHypothesis(
                code = ReconciliationHypothesisCode.DUPLICATE_LINE,
                message = if (duplicate) {
                    "‘${item.description}’이 두 번 인식되었을 수 있습니다. 원본에서 한 번만 인쇄되었다면 이 행을 삭제하세요."
                } else {
                    "‘${item.description ?: "설명 없는 행"}’의 금액이 초과분과 같습니다. " +
                        "상품 행이 아니라 안내문이나 합계였는지 확인하세요."
                },
                evidence = "초과분 $surplus = 행 금액 ${item.netAmountMinor}",
                lineItemId = item.id,
                suggestion = ReconciliationSuggestion.RemoveLineItem(item.id),
                rank = if (duplicate) 40 else 45,
            )
        }
    }

    private fun adjustmentHypotheses(receipt: ReceiptV2, difference: Long): List<ReconciliationHypothesis> {
        val candidates = listOf(
            Triple("할인", receipt.totals.discountAmountMinor, ReceiptLineType.DISCOUNT),
            Triple("세금", receipt.totals.taxAmountMinor, ReceiptLineType.TAX),
            Triple("수수료", receipt.totals.feeAmountMinor, ReceiptLineType.FEE),
        )
        return candidates.mapNotNull { (label, amount, type) ->
            if (amount == null || amount == 0L) return@mapNotNull null
            if (abs(amount) != abs(difference)) return@mapNotNull null
            ReconciliationHypothesis(
                code = ReconciliationHypothesisCode.ADJUSTMENT_ROW_MISSING,
                message = "차액이 $label 합계 ${amount.won()}과 같습니다. " +
                    "$label 이 행 목록에 빠졌는지 확인하고, 원본에 인쇄되어 있으면 행으로 추가하세요.",
                evidence = "차액 $difference ↔ $label $amount",
                lineItemId = null,
                suggestion = ReconciliationSuggestion.AddLineItem(type, difference),
                rank = 50,
            )
        }
    }

    private fun missingLineHypotheses(difference: Long, noOtherExplanation: Boolean): List<ReconciliationHypothesis> =
        when {
            difference > 0 -> listOf(
                ReconciliationHypothesis(
                    code = ReconciliationHypothesisCode.MISSING_LINE,
                    message = "행 합계가 ${difference.won()} 부족합니다. 인식되지 않은 행이 있는지 원본과 대조하고, " +
                        "있다면 직접 입력해 추가하세요.",
                    evidence = "차액 $difference > 0",
                    lineItemId = null,
                    suggestion = ReconciliationSuggestion.AddLineItem(ReceiptLineType.PRODUCT, difference),
                    rank = 60,
                ),
            )
            noOtherExplanation -> listOf(
                ReconciliationHypothesis(
                    code = ReconciliationHypothesisCode.UNEXPLAINED_SURPLUS,
                    message = "행 합계가 최종 합계보다 ${(-difference).won()} 큽니다. " +
                        "상품이 아닌 문장이 행으로 인식되었는지 확인하세요.",
                    evidence = "차액 $difference < 0",
                    lineItemId = null,
                    suggestion = null,
                    rank = 65,
                ),
            )
            else -> emptyList()
        }

    private fun ReceiptV2LineItem.expectedFromConservation(): Long? {
        if (type != ReceiptLineType.PRODUCT && type != ReceiptLineType.SERVICE) return null
        val quantity = quantity?.value ?: return null
        val unitPrice = unitPriceAmountMinor ?: return null
        return multiplyExactMinor(quantity, unitPrice)
    }

    private fun MisreadKind.rank(): Int = when (this) {
        MisreadKind.CONFUSABLE_DIGIT -> 10
        MisreadKind.TRANSPOSED_DIGITS -> 20
        MisreadKind.DROPPED_DIGIT -> 22
        MisreadKind.INSERTED_DIGIT -> 24
        MisreadKind.SUBSTITUTED_DIGIT -> 30
        MisreadKind.NONE -> Int.MAX_VALUE
    }

    private fun MisreadKind.reason(): String = when (this) {
        MisreadKind.CONFUSABLE_DIGIT -> "인쇄가 흐릴 때 자주 섞이는 숫자 한 자리 차이입니다."
        MisreadKind.TRANSPOSED_DIGITS -> "이웃한 두 자리가 뒤바뀐 형태입니다."
        MisreadKind.DROPPED_DIGIT -> "한 자리가 인식에서 빠진 형태입니다."
        MisreadKind.INSERTED_DIGIT -> "인쇄되지 않은 한 자리가 더해진 형태입니다."
        MisreadKind.SUBSTITUTED_DIGIT -> "한 자리만 다릅니다."
        MisreadKind.NONE -> ""
    }

    private fun addOrNull(left: Long, right: Long): Long? = runCatching { Math.addExact(left, right) }.getOrNull()
    private fun subtractOrNull(left: Long, right: Long): Long? = runCatching { Math.subtractExact(left, right) }.getOrNull()

    private fun Long.won(): String {
        val sign = if (this < 0) "-" else ""
        val digits = abs(this).toString().reversed().chunked(3).joinToString(",").reversed()
        return "$sign${digits}원"
    }
}
