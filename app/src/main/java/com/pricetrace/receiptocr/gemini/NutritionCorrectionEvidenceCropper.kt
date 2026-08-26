package com.pricetrace.receiptocr.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionEvidenceImage
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionRequest
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object NutritionCorrectionEvidenceCropper {
    private const val MAX_IMAGES = 10
    private const val MAX_WIDTH = 1_600
    private const val JPEG_QUALITY = 82

    suspend fun attachEvidenceImages(
        request: NutritionCorrectionRequest,
        pages: List<ReceiptPage>,
        ocrDocument: OcrDocument,
        fileStore: ReceiptFileStore,
    ): NutritionCorrectionRequest = withContext(Dispatchers.Default) {
        val pageMetadata = pages.associateBy { it.id }
        val lines = ocrDocument.lines.associateBy { it.id }
        val pageBitmaps = mutableMapOf<String, Bitmap>()
        try {
            val images = buildList {
                val croppedLineIds = mutableSetOf<String>()
                request.targets.forEach { target ->
                    if (size >= MAX_IMAGES) return@forEach
                    target.sourceLineIds.forEach sourceLine@{ sourceLineId ->
                        if (size >= MAX_IMAGES || !croppedLineIds.add(sourceLineId)) return@sourceLine
                        val line = lines[sourceLineId] ?: return@sourceLine
                        val box = line.boundingBox ?: return@sourceLine
                        val page = pageMetadata[line.pageId] ?: return@sourceLine
                        val bitmap = pageBitmaps[line.pageId]
                            ?: BitmapFactory.decodeFile(fileStore.resolveStorageKey(page.storageKey).absolutePath)
                                ?.also { decoded -> pageBitmaps[line.pageId] = decoded }
                            ?: return@sourceLine
                        val crop = bitmap.cropWithPadding(box) ?: return@sourceLine
                        val resized = crop.limitWidth(MAX_WIDTH)
                        val bytes = ByteArrayOutputStream().use { output ->
                            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                            output.toByteArray()
                        }
                        if (resized !== crop) resized.recycle()
                        crop.recycle()
                        if (bytes.isNotEmpty()) {
                            add(
                                NutritionCorrectionEvidenceImage(
                                    id = "nutrition_${target.fieldPath}_$sourceLineId",
                                    mimeType = "image/jpeg",
                                    bytes = bytes,
                                    sourceLineIds = listOf(sourceLineId),
                                ),
                            )
                        }
                    }
                }
            }
            request.copy(evidenceImages = images)
        } finally {
            pageBitmaps.values.forEach(Bitmap::recycle)
        }
    }

    private fun Bitmap.cropWithPadding(box: BoundingBox): Bitmap? {
        val padding = max(16, ((box.bottom - box.top) * 0.45f).roundToInt())
        val left = max(0, box.left - padding)
        val top = max(0, box.top - padding)
        val right = min(width, box.right + padding)
        val bottom = min(height, box.bottom + padding)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    private fun Bitmap.limitWidth(maxWidth: Int): Bitmap {
        if (width <= maxWidth) return this
        val nextHeight = (height.toFloat() * maxWidth / width).roundToInt().coerceAtLeast(1)
        return scale(maxWidth, nextHeight)
    }
}
