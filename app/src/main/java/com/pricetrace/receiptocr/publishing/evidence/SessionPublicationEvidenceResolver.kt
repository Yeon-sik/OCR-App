package com.pricetrace.receiptocr.publishing.evidence

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.publishing.evidence.PublicationEvidenceResolution
import com.pricetrace.receiptscanner.publishing.evidence.PublicationEvidenceResolver
import com.pricetrace.receiptscanner.publishing.model.PublicationEvidenceImage
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only actual ReceiptPage records for the requested local document can resolve an original. */
class SessionPublicationEvidenceResolver(
    private val context: Context,
    private val pages: List<ReceiptPage>,
    private val resolvePageFile: (String) -> File,
) : PublicationEvidenceResolver {
    override suspend fun resolve(
        documentId: String,
        attachmentIds: List<String>,
        pageIds: List<String>,
    ): PublicationEvidenceResolution = withContext(Dispatchers.IO) {
        val notices = mutableListOf<String>()
        if (attachmentIds.isNotEmpty()) {
            notices += "첨부 식별자와 원본 이미지의 연결 정보가 없어 해당 이미지는 제외했습니다."
        }
        val ids = pageIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) {
            notices += "영양정보와 연결된 원본 페이지를 특정할 수 없어 원본 이미지를 자동 포함하지 않았습니다."
            return@withContext PublicationEvidenceResolution(notices = notices.distinct())
        }
        val documentPages = pages.filter { it.documentId == documentId }.distinctBy { it.id }
        val images = ids.mapNotNull { id ->
            val page = documentPages.firstOrNull { it.id == id }
            if (page == null) {
                notices += "요청한 원본 페이지를 이 문서에서 찾지 못했습니다."
                return@mapNotNull null
            }
            try {
                val file = resolvePageFile(page.storageKey).canonicalFile
                val root = File(context.filesDir, "receipt-scanner").canonicalFile
                check(file.path.startsWith(root.path + File.separator)) { "Evidence outside storage root" }
                check(file.isFile && file.canRead()) { "Evidence is unavailable" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Evidence is not an image" }
                check(page.mimeType in setOf("image/jpeg", "image/png") &&
                    bounds.outMimeType == page.mimeType) { "Evidence MIME mismatch" }
                check(sha256(file).equals(page.sha256, ignoreCase = true)) { "Evidence has changed" }
                PublicationEvidenceImage(
                    id = page.id,
                    uri = FileProvider.getUriForFile(context, context.packageName + ".files", file).toString(),
                    mimeType = page.mimeType,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notices += "원본 이미지 하나를 읽거나 검증할 수 없어 공유에서 제외했습니다."
                null
            }
        }
        if (images.isEmpty()) notices += "연결 가능한 원본 이미지가 없습니다. 제목·본문·생성 표는 사용할 수 있습니다."
        PublicationEvidenceResolution(images = images, notices = notices.distinct())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
