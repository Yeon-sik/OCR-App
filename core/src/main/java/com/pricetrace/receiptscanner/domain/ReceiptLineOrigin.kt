package com.pricetrace.receiptscanner.domain

/**
 * OCR sometimes drops a whole printed row. The reviewer must be able to transcribe it from the paper
 * receipt, but the result must never look like OCR evidence. Such rows carry this marker instead of an
 * OCR line reference so the exported JSON states plainly that a human, not the recognizer, produced them.
 */
const val USER_ENTERED_SOURCE_PREFIX = "user_entered:"

fun userEnteredSourceReference(lineItemId: String): String = "$USER_ENTERED_SOURCE_PREFIX$lineItemId"

fun ReceiptV2LineItem.ocrSourceLineReferences(): List<String> =
    sourceLineReferences.filterNot { it.startsWith(USER_ENTERED_SOURCE_PREFIX) }

/** True when every reference is a user transcription marker, so no OCR line backs this row. */
fun ReceiptV2LineItem.isUserEntered(): Boolean =
    sourceLineReferences.isNotEmpty() && ocrSourceLineReferences().isEmpty()
