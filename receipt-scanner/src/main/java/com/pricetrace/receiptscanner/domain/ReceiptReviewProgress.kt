package com.pricetrace.receiptscanner.domain

/**
 * What still stands between the current draft and a `user_verified` receipt.
 *
 * The reviewer needs to know how much work is left before opening every row, so the counts are derived
 * from the same validation result that blocks confirmation — never from a separate rule set.
 */
data class ReceiptReviewProgress(
    val lineItemCount: Int,
    val attentionLineItemIds: List<String>,
    val userEnteredLineItemCount: Int,
    val blockingIssueCount: Int,
    val warningIssueCount: Int,
    val isBalanced: Boolean,
) {
    val attentionLineItemCount: Int get() = attentionLineItemIds.size
    val settledLineItemCount: Int get() = lineItemCount - attentionLineItemCount

    /** Share of rows that carry neither a blocking issue nor a low-confidence flag. */
    val lineCompletionRatio: Double
        get() = if (lineItemCount == 0) 0.0 else settledLineItemCount.toDouble() / lineItemCount

    val canMarkUserVerified: Boolean get() = blockingIssueCount == 0

    companion object {
        fun of(receipt: ReceiptV2, validation: ReceiptValidationResult): ReceiptReviewProgress {
            val attention = receipt.lineItems.filterIndexed { index, item ->
                item.confidence == ConfidenceLevel.LOW ||
                    validation.issues.any { issue ->
                        issue.severity == ValidationSeverity.ERROR &&
                            issue.fieldPath.startsWith("line_items[$index]")
                    }
            }
            return ReceiptReviewProgress(
                lineItemCount = receipt.lineItems.size,
                attentionLineItemIds = attention.map { it.id },
                userEnteredLineItemCount = receipt.lineItems.count { it.isUserEntered() },
                blockingIssueCount = validation.issues.count { it.severity == ValidationSeverity.ERROR },
                warningIssueCount = validation.issues.count { it.severity == ValidationSeverity.WARNING },
                isBalanced = validation.reconciliation.isBalanced,
            )
        }
    }
}
