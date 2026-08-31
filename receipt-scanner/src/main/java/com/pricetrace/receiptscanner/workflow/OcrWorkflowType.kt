package com.pricetrace.receiptscanner.workflow

/** A capture/OCR session stays generic; only parsing, review, and publication differ by workflow. */
enum class OcrWorkflowType(val wireValue: String) {
    PRICE_TRACE_RECEIPT("pricetrace_receipt"),
    PRICE_TRACE_RESTAURANT_RECEIPT("pricetrace_restaurant_receipt"),
    PRICE_TRACE_MERCHANT("pricetrace_merchant"),
    FITNESS_NUTRITION("fitness_nutrition"),
    ;

    companion object {
        fun fromWireValue(value: String?): OcrWorkflowType = entries.firstOrNull {
            it.wireValue == value
        } ?: PRICE_TRACE_RECEIPT
    }
}
