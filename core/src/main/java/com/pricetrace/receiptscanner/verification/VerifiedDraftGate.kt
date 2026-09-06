package com.pricetrace.receiptscanner.verification

import com.pricetrace.receiptscanner.input.InputOrigin

enum class VerifiedDraftGateFailure {
    SOURCE_IMAGE_REQUIRED,
    SOURCE_IMAGE_UNREADABLE,
}

data class VerifiedDraftGateResult(
    val isAllowed: Boolean,
    val failure: VerifiedDraftGateFailure? = null,
) {
    companion object {
        val ALLOWED = VerifiedDraftGateResult(isAllowed = true)
    }
}

/**
 * Checks trust/evidence prerequisites only. Domain validators remain responsible for canonical data validity.
 * Android OCR keeps its existing evidence policy; external JSON must have readable local source evidence.
 */
object VerifiedDraftGate {
    fun evaluate(
        inputOrigin: InputOrigin,
        localPageCount: Int,
        allLocalPageFilesReadable: Boolean,
    ): VerifiedDraftGateResult {
        if (inputOrigin != InputOrigin.EXTERNAL_JSON) return VerifiedDraftGateResult.ALLOWED
        if (localPageCount < 1) {
            return VerifiedDraftGateResult(false, VerifiedDraftGateFailure.SOURCE_IMAGE_REQUIRED)
        }
        if (!allLocalPageFilesReadable) {
            return VerifiedDraftGateResult(false, VerifiedDraftGateFailure.SOURCE_IMAGE_UNREADABLE)
        }
        return VerifiedDraftGateResult.ALLOWED
    }
}