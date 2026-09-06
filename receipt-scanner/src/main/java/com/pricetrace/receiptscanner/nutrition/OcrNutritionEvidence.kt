package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.ocr.OcrLine

internal fun OcrLine.toNutritionEvidence(): NutritionFieldEvidence = NutritionFieldEvidence(
    ocrLineId = id,
    pageId = pageId,
    rawText = text,
    confidence = confidence,
)
