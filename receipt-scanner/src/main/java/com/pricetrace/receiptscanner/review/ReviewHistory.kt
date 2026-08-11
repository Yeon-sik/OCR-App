package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.FieldCorrection
import com.pricetrace.receiptscanner.storage.ReviewEdit

/** Bridges stored review history to the storage-independent accuracy model. */
fun ReviewEdit.toFieldCorrection(): FieldCorrection = FieldCorrection(
    fieldPath = fieldPath,
    previousValue = previousValue,
    newValue = newValue,
    editedAt = editedAt,
)
