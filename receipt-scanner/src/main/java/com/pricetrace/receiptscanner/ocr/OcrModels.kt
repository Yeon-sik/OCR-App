package com.pricetrace.receiptscanner.ocr

import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.domain.ReceiptPage
import java.io.Closeable

data class OcrEngineInfo(
    val name: String,
    val version: String,
)

data class OcrElement(
    val id: String,
    val text: String,
    val boundingBox: BoundingBox?,
    val confidence: Float?,
    val recognitionOrder: Int,
)

data class OcrLine(
    val id: String,
    val pageId: String,
    val pageIndex: Int,
    val text: String,
    val boundingBox: BoundingBox?,
    val elements: List<OcrElement>,
    val confidence: Float?,
    val recognitionOrder: Int,
)

data class OcrBlock(
    val id: String,
    val pageId: String,
    val pageIndex: Int,
    val text: String,
    val boundingBox: BoundingBox?,
    val lines: List<OcrLine>,
    val recognitionOrder: Int,
)

data class OcrPage(
    val pageId: String,
    val pageIndex: Int,
    val rawText: String,
    val blocks: List<OcrBlock>,
)

data class OcrDocument(
    val documentId: String,
    val rawText: String,
    val pages: List<OcrPage>,
    val engine: OcrEngineInfo,
) {
    val lines: List<OcrLine> get() = pages.flatMap { page -> page.blocks.flatMap(OcrBlock::lines) }
}

data class OcrInputPage(
    val metadata: ReceiptPage,
    val bytes: ByteArray,
)

enum class OcrFailureReason(val wireValue: String) {
    MODEL_DOWNLOAD_REQUIRED("model_download_required"),
    IMAGE_DECODE_FAILED("image_decode_failed"),
    ENGINE_FAILED("ocr_failed"),
}

sealed interface OcrOutcome {
    data class Success(val document: OcrDocument) : OcrOutcome
    data class Failure(val reason: OcrFailureReason, val detail: String? = null) : OcrOutcome
}

interface ReceiptOcrEngine : Closeable {
    suspend fun recognize(documentId: String, pages: List<OcrInputPage>): OcrOutcome
}
