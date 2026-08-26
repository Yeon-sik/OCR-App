package com.pricetrace.receiptscanner.capture

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
import com.pricetrace.receiptscanner.storage.ReceiptSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface ScannerLaunchPreparation {
    data class Ready(val intentSender: IntentSender) : ScannerLaunchPreparation
    data class Failure(val reason: CaptureFailureReason, val detail: String? = null) : ScannerLaunchPreparation
}

/**
 * Android/ML Kit adapter. Its Android types intentionally stay in the capture adapter package;
 * [DocumentCaptureProvider] and every returned receipt domain model remain platform-neutral.
 */
class MlKitDocumentCaptureProvider(
    context: Context,
    private val fileStore: ReceiptFileStore,
    private val sessionRepository: ReceiptSessionRepository,
    pageLimit: Int = DEFAULT_PAGE_LIMIT,
) : DocumentCaptureProvider {
    private val contentResolver = context.applicationContext.contentResolver
    private val maxPageCount = pageLimit
    private val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(pageLimit)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()
    private val scanner = GmsDocumentScanning.getClient(options)

    suspend fun prepareLaunch(activity: Activity): ScannerLaunchPreparation = try {
        ScannerLaunchPreparation.Ready(scanner.getStartScanIntent(activity).await())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val reason = when ((error as? MlKitException)?.errorCode) {
            MlKitException.UNSUPPORTED -> CaptureFailureReason.UNSUPPORTED
            MlKitException.UNAVAILABLE -> CaptureFailureReason.MODEL_DOWNLOAD_REQUIRED
            else -> CaptureFailureReason.CAPTURE_FAILED
        }
        ScannerLaunchPreparation.Failure(reason, error.safeDetail())
    }

    suspend fun handleActivityResult(
        documentId: String,
        resultCode: Int,
        data: Intent?,
    ): CaptureOutcome {
        if (resultCode == Activity.RESULT_CANCELED) {
            return CaptureOutcome.Failure(CaptureFailureReason.USER_CANCELLED)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            return CaptureOutcome.Failure(CaptureFailureReason.CAPTURE_FAILED, "scanner_result_missing")
        }

        return try {
            val result = requireNotNull(GmsDocumentScanningResult.fromActivityResultIntent(data)) {
                "scanner_result_unreadable"
            }
            val pages = result.pages.orEmpty().map { page ->
                val bytes = requireNotNull(contentResolver.openInputStream(page.imageUri)) {
                    "captured_page_unreadable"
                }.use { stream -> stream.readBytes() }
                CapturedPageContent(
                    bytes = bytes,
                    mimeType = contentResolver.getType(page.imageUri) ?: "image/jpeg",
                )
            }
            importPages(documentId, pages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CaptureOutcome.Failure(CaptureFailureReason.CAPTURE_FAILED, error.safeDetail())
        }
    }

    override suspend fun importPages(
        documentId: String,
        pages: List<CapturedPageContent>,
    ): CaptureOutcome = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            return@withContext CaptureOutcome.Failure(
                CaptureFailureReason.CAPTURE_FAILED,
                "scanner_returned_no_pages",
            )
        }
        try {
            val nextPageIndex = sessionRepository.getPages(documentId)
                .maxOfOrNull { page -> page.pageIndex }
                ?.plus(1)
                ?: 0
            val storedPages = pages.mapIndexed { pageIndex, content ->
                fileStore.saveCapturedPage(documentId, nextPageIndex + pageIndex, content)
            }
            val duplicateIds = sessionRepository.addPages(documentId, storedPages)
            CaptureOutcome.Success(storedPages, duplicateIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CaptureOutcome.Failure(CaptureFailureReason.CAPTURE_FAILED, error.safeDetail())
        }
    }

    suspend fun importImageUris(
        documentId: String,
        uris: List<Uri>,
    ): CaptureOutcome = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) {
            return@withContext CaptureOutcome.Failure(CaptureFailureReason.USER_CANCELLED)
        }
        try {
            val selectedUris = uris.distinct().take(maxPageCount)
            val pages = selectedUris.map(::readSelectedImage)
            importPages(documentId, pages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CaptureOutcome.Failure(CaptureFailureReason.CAPTURE_FAILED, error.safeDetail())
        }
    }

    private fun readSelectedImage(uri: Uri): CapturedPageContent {
        val bytes = requireNotNull(contentResolver.openInputStream(uri)) { "selected_image_unreadable" }
            .use { stream -> stream.readBytes() }
        require(bytes.isNotEmpty()) { "selected_image_unreadable" }
        val mimeType = contentResolver.getType(uri)
            ?.substringBefore(';')
            ?.lowercase()
        if (mimeType == "image/jpeg" || mimeType == "image/jpg" || mimeType == "image/png") {
            return CapturedPageContent(
                bytes = bytes,
                mimeType = if (mimeType == "image/png") "image/png" else "image/jpeg",
            )
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "selected_image_unreadable"
        }
        val jpegBytes = try {
            ByteArrayOutputStream().use { output ->
                require(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "selected_image_unreadable"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        return CapturedPageContent(bytes = jpegBytes, mimeType = "image/jpeg")
    }
    private fun Throwable.safeDetail(): String = when (message) {
        "scanner_result_unreadable",
        "captured_page_unreadable",
        "selected_image_unreadable",
        -> message!!
        else -> this::class.java.simpleName.ifBlank { "capture_error" }
    }

    companion object {
        const val DEFAULT_PAGE_LIMIT = 12
    }
}
