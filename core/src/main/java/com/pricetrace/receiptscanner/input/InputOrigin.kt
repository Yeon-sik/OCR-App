package com.pricetrace.receiptscanner.input

/** How a canonical draft entered the app. This is independent from the selected workflow. */
enum class InputOrigin(val wireValue: String) {
    ANDROID_OCR("android_ocr"),
    EXTERNAL_JSON("external_json");

    companion object {
        fun fromWireValue(value: String?): InputOrigin = entries.firstOrNull { it.wireValue == value }
            ?: ANDROID_OCR
    }
}
