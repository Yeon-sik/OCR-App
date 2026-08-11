package com.pricetrace.receiptscanner.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.pricetrace.receiptscanner.capture.CapturedPageContent
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.StableIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime

class ReceiptFileStore(context: Context) {
    private val rootDirectory = File(context.filesDir, ROOT_DIRECTORY).apply { mkdirs() }

    suspend fun saveCapturedPage(
        documentId: String,
        pageIndex: Int,
        content: CapturedPageContent,
        revision: Int = 1,
    ): ReceiptPage = withContext(Dispatchers.IO) {
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        require(content.bytes.isNotEmpty()) { "Captured image is empty" }
        require(content.mimeType == "image/jpeg" || content.mimeType == "image/png") {
            "Unsupported image MIME type"
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(content.bytes, 0, content.bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Captured image dimensions are invalid" }

        val sha256 = StableIds.sha256(content.bytes)
        val pageId = StableIds.pageId(documentId, pageIndex, sha256)
        val extension = if (content.mimeType == "image/png") "png" else "jpg"
        val storageKey = "$documentId/pages/$pageId-r$revision.$extension"
        val destination = resolveStorageKey(storageKey)
        if (!destination.exists()) {
            atomicWrite(destination, content.bytes)
        }

        ReceiptPage(
            id = pageId,
            documentId = documentId,
            storageKey = storageKey,
            sha256 = sha256,
            mimeType = content.mimeType,
            width = bounds.outWidth,
            height = bounds.outHeight,
            pageIndex = pageIndex,
            createdAt = OffsetDateTime.now().toString(),
            revision = revision,
        )
    }

    suspend fun readBytes(storageKey: String): ByteArray = withContext(Dispatchers.IO) {
        resolveStorageKey(storageKey).readBytes()
    }

    suspend fun writeText(storageKey: String, content: String): StoredArtifact = withContext(Dispatchers.IO) {
        writeTextInternal(storageKey, content)
    }

    internal suspend fun writeTextForTesting(
        storageKey: String,
        content: String,
        beforeCommit: (() -> Unit)? = null,
    ): StoredArtifact = withContext(Dispatchers.IO) {
        writeTextInternal(storageKey, content, beforeCommit)
    }

    private fun writeTextInternal(
        storageKey: String,
        content: String,
        beforeCommit: (() -> Unit)? = null,
    ): StoredArtifact {
        val destination = resolveStorageKey(storageKey)
        val bytes = content.toByteArray(Charsets.UTF_8)
        atomicWrite(destination, bytes, beforeCommit)
        return StoredArtifact(
            storageKey = storageKey,
            sha256 = StableIds.sha256(bytes),
            byteCount = bytes.size.toLong(),
        )
    }

    fun resolveStorageKey(storageKey: String): File {
        require(storageKey.isNotBlank()) { "storageKey must not be blank" }
        require(!File(storageKey).isAbsolute) { "Absolute paths are not valid storage keys" }

        val candidate = File(rootDirectory, storageKey).canonicalFile
        val canonicalRoot = rootDirectory.canonicalFile.path
        require(candidate.path == canonicalRoot || candidate.path.startsWith(canonicalRoot + File.separator)) {
            "Storage key escapes the app receipt directory"
        }
        return candidate
    }

    suspend fun deleteDocumentFiles(documentId: String): Boolean = withContext(Dispatchers.IO) {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        val directory = resolveStorageKey(documentId)
        if (!directory.exists()) return@withContext true
        directory.deleteRecursively() && !directory.exists()
    }

    private fun atomicWrite(
        destination: File,
        bytes: ByteArray,
        beforeCommit: (() -> Unit)? = null,
    ) {
        destination.parentFile?.mkdirs()
        val atomicFile = AtomicFile(destination)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            output.fd.sync()
            beforeCommit?.invoke()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw IllegalStateException("Unable to finalize stored artifact", error)
        } finally {
            // Clean up legacy temp files from earlier implementations if they are still present.
            File(destination.parentFile, ".${destination.name}.tmp").delete()
        }
    }

    companion object {
        private const val ROOT_DIRECTORY = "receipt-scanner"
    }
}

data class StoredArtifact(
    val storageKey: String,
    val sha256: String,
    val byteCount: Long,
)
