package com.pricetrace.receiptscanner.capture

import com.pricetrace.receiptscanner.domain.ReceiptPage

enum class CaptureFailureReason(val wireValue: String) {
    UNSUPPORTED("unsupported"),
    MODEL_DOWNLOAD_REQUIRED("model_download_required"),
    USER_CANCELLED("user_cancelled"),
    CAPTURE_FAILED("capture_failed"),
}

sealed interface CaptureOutcome {
    data class Success(
        val pages: List<ReceiptPage>,
        val possibleDuplicatePageIds: List<String> = emptyList(),
    ) : CaptureOutcome

    data class Failure(
        val reason: CaptureFailureReason,
        /** Sanitized operational detail only. Never put OCR text or a file path here. */
        val detail: String? = null,
    ) : CaptureOutcome
}

data class CapturedPageContent(
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg",
)

interface DocumentCaptureProvider {
    suspend fun importPages(
        documentId: String,
        pages: List<CapturedPageContent>,
    ): CaptureOutcome
}
