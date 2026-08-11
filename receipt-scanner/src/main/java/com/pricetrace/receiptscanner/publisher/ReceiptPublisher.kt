package com.pricetrace.receiptscanner.publisher

import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.export.ReceiptExportManifest
import com.pricetrace.receiptscanner.ocr.OcrLine

enum class PublicationState(val wireValue: String) {
    LOCAL_ONLY("local_only"),
    STAGED("staged"),
    REVIEW_REQUIRED("review_required"),
    FINALIZED("finalized"),
    FAILED("failed"),
}

data class PublicationStatus(
    val documentId: String,
    val state: PublicationState,
    val retryCount: Int = 0,
    val lastError: String? = null,
)

interface ReceiptPublisher {
    suspend fun stageDocument(manifest: ReceiptExportManifest, pages: List<ReceiptPage>): PublicationStatus
    suspend fun stageOcr(documentId: String, ocrLines: List<OcrLine>): PublicationStatus
    suspend fun finalizeVerifiedReceipt(
        documentId: String,
        receiptV2: ReceiptV2,
        idempotencyKey: String,
    ): PublicationStatus
    suspend fun getPublicationStatus(documentId: String): PublicationStatus
}

class LocalOnlyReceiptPublisher : ReceiptPublisher {
    override suspend fun stageDocument(
        manifest: ReceiptExportManifest,
        pages: List<ReceiptPage>,
    ): PublicationStatus = local(manifest.documentId)

    override suspend fun stageOcr(documentId: String, ocrLines: List<OcrLine>): PublicationStatus = local(documentId)

    override suspend fun finalizeVerifiedReceipt(
        documentId: String,
        receiptV2: ReceiptV2,
        idempotencyKey: String,
    ): PublicationStatus = local(documentId)

    override suspend fun getPublicationStatus(documentId: String): PublicationStatus = local(documentId)

    private fun local(documentId: String) = PublicationStatus(documentId, PublicationState.LOCAL_ONLY)
}
