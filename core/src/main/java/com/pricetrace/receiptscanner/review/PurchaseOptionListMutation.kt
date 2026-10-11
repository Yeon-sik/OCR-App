package com.pricetrace.receiptscanner.review

/** Closed commands for list structure; scalar edits still go through CanonicalFieldRegistry. */
sealed interface PurchaseOptionListMutation {
    data class Add(
        val description: String,
        val quantity: Double? = null,
        val amountKrw: Long? = null,
    ) : PurchaseOptionListMutation

    data class Delete(val optionKey: String) : PurchaseOptionListMutation
    data class Reorder(val optionKeys: List<String>) : PurchaseOptionListMutation
}
