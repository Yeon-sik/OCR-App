package com.pricetrace.receiptscanner.domain

import java.time.Duration
import java.time.OffsetDateTime

/** One recorded change to a field during review, independent of how it was stored. */
data class FieldCorrection(
    val fieldPath: String,
    val previousValue: String?,
    val newValue: String?,
    val editedAt: String,
)

/**
 * A receipt the user already confirmed, used as its own labelled sample.
 *
 * Nothing extra has to be transcribed: whatever the reviewer changed is, by definition, a place the
 * recognizer disagreed with the paper receipt.
 */
data class ReviewedReceiptSample(
    val documentId: String,
    val parserVersion: String?,
    val receipt: ReceiptV2,
    val corrections: List<FieldCorrection>,
    val ocrCompletedAt: String?,
    val reviewedAt: String?,
)

enum class ReviewFieldScope { DOCUMENT, LINE }

enum class ReviewFieldGroup(val label: String, val scope: ReviewFieldScope) {
    MERCHANT_NAME("판매처명", ReviewFieldScope.DOCUMENT),
    MERCHANT_BRANCH("지점명", ReviewFieldScope.DOCUMENT),
    MERCHANT_ADDRESS("주소", ReviewFieldScope.DOCUMENT),
    BUSINESS_REGISTRATION_NUMBER("사업자등록번호", ReviewFieldScope.DOCUMENT),
    PHONE("전화번호", ReviewFieldScope.DOCUMENT),
    ISSUED_ON("구매일", ReviewFieldScope.DOCUMENT),
    PURCHASE_TIME("구매시각", ReviewFieldScope.DOCUMENT),
    CURRENCY("통화", ReviewFieldScope.DOCUMENT),
    GRAND_TOTAL("최종 합계", ReviewFieldScope.DOCUMENT),
    OTHER_TOTALS("소계·할인·세금·수수료", ReviewFieldScope.DOCUMENT),
    PAYMENT("결제수단·결제금액", ReviewFieldScope.DOCUMENT),
    LINE_DESCRIPTION("상품명", ReviewFieldScope.LINE),
    LINE_QUANTITY("수량", ReviewFieldScope.LINE),
    LINE_UNIT_PRICE("단가", ReviewFieldScope.LINE),
    LINE_NET_AMOUNT("행 금액", ReviewFieldScope.LINE),
    LINE_SKU("판매처 상품코드", ReviewFieldScope.LINE),
    LINE_TYPE("행 유형", ReviewFieldScope.LINE),
    LINE_STRUCTURE("행 추가·삭제", ReviewFieldScope.LINE),
    ;

    companion object {
        fun of(fieldPath: String): ReviewFieldGroup? = when {
            fieldPath.startsWith("line_items[") -> lineGroup(fieldPath)
            fieldPath == "merchant.name" -> MERCHANT_NAME
            fieldPath == "merchant.branch_name" -> MERCHANT_BRANCH
            fieldPath == "merchant.address" -> MERCHANT_ADDRESS
            fieldPath == "merchant.business_registration_number" -> BUSINESS_REGISTRATION_NUMBER
            fieldPath == "merchant.phone" -> PHONE
            fieldPath == "document.issued_on" || fieldPath == "document.issued_at" -> ISSUED_ON
            fieldPath == "document.source.notes.purchase_local_time" -> PURCHASE_TIME
            fieldPath == "document.currency" -> CURRENCY
            fieldPath == "totals.grand_total_amount_minor" -> GRAND_TOTAL
            fieldPath.startsWith("totals.") -> OTHER_TOTALS
            fieldPath.startsWith("payments[") -> PAYMENT
            else -> null
        }

        private fun lineGroup(fieldPath: String): ReviewFieldGroup? {
            val suffix = fieldPath.substringAfter(']', missingDelimiterValue = "")
            return when {
                suffix.isEmpty() -> LINE_STRUCTURE
                suffix == ".description" -> LINE_DESCRIPTION
                suffix == ".quantity" -> LINE_QUANTITY
                suffix == ".unit_price_amount_minor" -> LINE_UNIT_PRICE
                suffix == ".net_amount_minor" -> LINE_NET_AMOUNT
                suffix == ".identifiers.merchant_sku" -> LINE_SKU
                suffix == ".type" -> LINE_TYPE
                else -> null
            }
        }
    }
}

data class ReviewFieldAccuracy(
    val group: ReviewFieldGroup,
    /** Occurrences the recognizer was expected to produce a value for. */
    val observedCount: Int,
    /** The recognizer read something, but not what was printed. */
    val misreadCount: Int,
    /** The recognizer produced nothing and the reviewer had to supply the value. */
    val missedCount: Int,
    /** The recognizer produced a value that was not on the receipt at all. */
    val spuriousCount: Int,
    /** Total characters the reviewer had to change, over the entries counted above. */
    val editDistanceTotal: Int,
) {
    val correctedCount: Int get() = misreadCount + missedCount + spuriousCount
    val errorRate: Double? get() = observedCount.takeIf { it > 0 }?.let { correctedCount.toDouble() / it }
    val averageEditDistance: Double? get() =
        correctedCount.takeIf { it > 0 }?.let { editDistanceTotal.toDouble() / it }
}

data class ReviewAccuracySummary(
    val sampleCount: Int,
    val lineItemCount: Int,
    val parserVersions: List<String>,
    val fields: List<ReviewFieldAccuracy>,
    val correctionsPerReceipt: Double,
    val medianReviewSeconds: Long?,
    val timedSampleCount: Int,
) {
    /** Fields ordered by how much review work they caused, which is what the effort should follow. */
    val worstFields: List<ReviewFieldAccuracy> get() = fields
        .filter { it.correctedCount > 0 }
        .sortedWith(compareByDescending<ReviewFieldAccuracy> { it.correctedCount }.thenBy { it.group.ordinal })
}

/**
 * Turns confirmed receipts into a field-level error report.
 *
 * Caveat worth repeating wherever this is shown: it measures disagreements the reviewer *caught*. An
 * error nobody noticed counts as correct here, so these rates are a floor, not the true error rate.
 */
object ReviewAccuracyCalculator {
    fun summarize(samples: List<ReviewedReceiptSample>): ReviewAccuracySummary {
        val perGroup = ReviewFieldGroup.entries.associateWith { MutableAccuracy() }

        samples.forEach { sample ->
            sample.netCorrections().forEach { (fieldPath, change) ->
                val group = ReviewFieldGroup.of(fieldPath) ?: return@forEach
                val accumulator = requireNotNull(perGroup[group])
                val before = change.first
                val after = change.second
                when {
                    before.isNullOrBlank() -> accumulator.missed++
                    after.isNullOrBlank() -> accumulator.spurious++
                    else -> accumulator.misread++
                }
                accumulator.editDistance += ReceiptEvaluationCalculator.levenshteinDistance(
                    before.orEmpty(),
                    after.orEmpty(),
                )
            }
        }

        val lineItemCount = samples.sumOf { it.receipt.lineItems.size }
        ReviewFieldGroup.entries.forEach { group ->
            val accumulator = requireNotNull(perGroup[group])
            // A value the recognizer invented is an opportunity it got wrong, but it is missing from the
            // confirmed receipt, so it has to be added back to the denominator. Rows are the exception:
            // a row whose field was merely cleared still exists in the final count.
            accumulator.observed = when {
                group == ReviewFieldGroup.LINE_STRUCTURE -> lineItemCount + accumulator.spurious
                group.scope == ReviewFieldScope.LINE -> lineItemCount
                else -> samples.count { sample -> group.appliesTo(sample) } + accumulator.spurious
            }
        }

        val durations = samples.mapNotNull { it.reviewSeconds() }.sorted()
        return ReviewAccuracySummary(
            sampleCount = samples.size,
            lineItemCount = lineItemCount,
            parserVersions = samples.mapNotNull { it.parserVersion }.distinct().sorted(),
            fields = ReviewFieldGroup.entries.map { group ->
                requireNotNull(perGroup[group]).toAccuracy(group)
            },
            correctionsPerReceipt = if (samples.isEmpty()) {
                0.0
            } else {
                perGroup.values.sumOf { it.misread + it.missed + it.spurious }.toDouble() / samples.size
            },
            medianReviewSeconds = durations.medianOrNull(),
            timedSampleCount = durations.size,
        )
    }

    /**
     * Collapses every change to one field into a single before/after pair, so retyping the same field or
     * undoing an edit is not counted as a recognition error.
     */
    private fun ReviewedReceiptSample.netCorrections(): Map<String, Pair<String?, String?>> = corrections
        .sortedBy { it.editedAt }
        .groupBy { it.fieldPath }
        .mapValues { (_, changes) -> changes.first().previousValue to changes.last().newValue }
        .filter { (_, change) -> change.first != change.second }

    private fun ReviewFieldGroup.appliesTo(sample: ReviewedReceiptSample): Boolean {
        val receipt = sample.receipt
        return when (this) {
            ReviewFieldGroup.MERCHANT_NAME -> receipt.merchant.name != null
            ReviewFieldGroup.MERCHANT_BRANCH -> receipt.merchant.branchName != null
            ReviewFieldGroup.MERCHANT_ADDRESS -> receipt.merchant.address != null
            ReviewFieldGroup.BUSINESS_REGISTRATION_NUMBER -> receipt.merchant.businessRegistrationNumber != null
            ReviewFieldGroup.PHONE -> receipt.merchant.phone != null
            ReviewFieldGroup.ISSUED_ON -> receipt.document.issuedOn != null || receipt.document.issuedAt != null
            ReviewFieldGroup.PURCHASE_TIME -> receipt.document.source.purchaseLocalTime() != null
            ReviewFieldGroup.CURRENCY -> receipt.document.currency != null
            ReviewFieldGroup.GRAND_TOTAL -> receipt.totals.grandTotalAmountMinor != null
            ReviewFieldGroup.OTHER_TOTALS -> listOfNotNull(
                receipt.totals.subtotalAmountMinor,
                receipt.totals.discountAmountMinor,
                receipt.totals.taxAmountMinor,
                receipt.totals.feeAmountMinor,
            ).isNotEmpty()
            ReviewFieldGroup.PAYMENT -> receipt.payments.isNotEmpty()
            else -> false
        }
    }

    private fun ReviewedReceiptSample.reviewSeconds(): Long? {
        val start = ocrCompletedAt?.toOffsetDateTimeOrNull() ?: return null
        val end = reviewedAt?.toOffsetDateTimeOrNull() ?: return null
        val seconds = Duration.between(start, end).seconds
        return seconds.takeIf { it >= 0 }
    }

    private fun String.toOffsetDateTimeOrNull(): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(this) }.getOrNull()

    private fun List<Long>.medianOrNull(): Long? = when {
        isEmpty() -> null
        size % 2 == 1 -> this[size / 2]
        else -> (this[size / 2 - 1] + this[size / 2]) / 2
    }

    private class MutableAccuracy {
        var observed = 0
        var misread = 0
        var missed = 0
        var spurious = 0
        var editDistance = 0

        fun toAccuracy(group: ReviewFieldGroup) = ReviewFieldAccuracy(
            group = group,
            observedCount = observed,
            misreadCount = misread,
            missedCount = missed,
            spuriousCount = spurious,
            editDistanceTotal = editDistance,
        )
    }
}
