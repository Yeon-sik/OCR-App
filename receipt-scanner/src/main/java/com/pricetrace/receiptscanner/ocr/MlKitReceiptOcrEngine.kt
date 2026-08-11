package com.pricetrace.receiptscanner.ocr

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.pricetrace.receiptscanner.domain.BoundingBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MlKitReceiptOcrEngine : ReceiptOcrEngine {
    private val recognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build(),
    )

    override suspend fun recognize(documentId: String, pages: List<OcrInputPage>): OcrOutcome {
        if (pages.isEmpty()) {
            return OcrOutcome.Failure(OcrFailureReason.IMAGE_DECODE_FAILED, "no_pages")
        }
        return try {
            var recognitionOrder = 0
            val ocrPages = pages.sortedBy { it.metadata.pageIndex }.map { inputPage ->
                val bitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(inputPage.bytes, 0, inputPage.bytes.size)
                } ?: error("image_decode_failed")
                val result = try {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                } finally {
                    bitmap.recycle()
                }
                result.toDomain(inputPage, recognitionOrder).also { page ->
                    recognitionOrder += page.blocks.sumOf { block ->
                        1 + block.lines.sumOf { line -> 1 + line.elements.size }
                    }
                }
            }
            OcrOutcome.Success(
                OcrDocument(
                    documentId = documentId,
                    rawText = ocrPages.joinToString(separator = "\n") { it.rawText },
                    pages = ocrPages,
                    engine = OcrEngineInfo(ENGINE_NAME, ENGINE_VERSION),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = when {
                error.message == "image_decode_failed" -> OcrFailureReason.IMAGE_DECODE_FAILED
                (error as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE ->
                    OcrFailureReason.MODEL_DOWNLOAD_REQUIRED
                else -> OcrFailureReason.ENGINE_FAILED
            }
            OcrOutcome.Failure(reason, error.safeDetail())
        }
    }

    override fun close() {
        recognizer.close()
    }

    private fun Text.toDomain(input: OcrInputPage, startOrder: Int): OcrPage {
        var order = startOrder
        val blocks = textBlocks.mapIndexed { blockIndex, block ->
            val blockOrder = order++
            val lines = block.lines.mapIndexed { lineIndex, line ->
                val lineOrder = order++
                val elements = line.elements.mapIndexed { elementIndex, element ->
                    OcrElement(
                        id = "ocr_${input.metadata.id}_b${blockIndex}_l${lineIndex}_e$elementIndex",
                        text = element.text,
                        boundingBox = element.boundingBox.toDomain(),
                        confidence = null,
                        recognitionOrder = order++,
                    )
                }
                OcrLine(
                    id = "ocr_${input.metadata.id}_l$lineIndex-$blockIndex",
                    pageId = input.metadata.id,
                    pageIndex = input.metadata.pageIndex,
                    text = line.text,
                    boundingBox = line.boundingBox.toDomain(),
                    elements = elements,
                    confidence = null,
                    recognitionOrder = lineOrder,
                )
            }
            OcrBlock(
                id = "ocr_${input.metadata.id}_b$blockIndex",
                pageId = input.metadata.id,
                pageIndex = input.metadata.pageIndex,
                text = block.text,
                boundingBox = block.boundingBox.toDomain(),
                lines = lines,
                recognitionOrder = blockOrder,
            )
        }
        return OcrPage(
            pageId = input.metadata.id,
            pageIndex = input.metadata.pageIndex,
            rawText = text,
            blocks = blocks,
        )
    }

    private fun Rect?.toDomain(): BoundingBox? = this?.let { rect ->
        BoundingBox(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun Throwable.safeDetail(): String = when (message) {
        "image_decode_failed" -> "image_decode_failed"
        else -> this::class.java.simpleName.ifBlank { "ocr_error" }
    }

    companion object {
        const val ENGINE_NAME = "mlkit-text-recognition-v2-korean"
        const val ENGINE_VERSION = "16.0.1"
    }
}
