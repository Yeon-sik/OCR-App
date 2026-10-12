package com.pricetrace.receiptocr.publishing.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pricetrace.receiptscanner.publishing.model.BlogDraft

enum class PublicationShareContent { TEXT, IMAGES, ALL }

/** Receiver-neutral Android Sharesheet. A completed result means the chooser opened, not published. */
class PublicationShareExporter(
    private val context: Context,
    private val launch: (Intent) -> Unit = { context.startActivity(it) },
) {
    fun share(draft: BlogDraft, content: PublicationShareContent): PublicationExportResult = try {
        val intent = buildIntent(draft, content)
        val chooser = Intent.createChooser(intent, when (content) {
            PublicationShareContent.TEXT -> "게시물 텍스트 공유"
            PublicationShareContent.IMAGES -> "게시물 이미지 공유"
            PublicationShareContent.ALL -> "게시물 전체 공유"
        }).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.clipData != null) {
                clipData = intent.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        // Do not pre-query resolveActivity: package visibility can hide an available receiver.
        launch(chooser)
        PublicationExportResult.Completed
    } catch (_: ActivityNotFoundException) {
        PublicationExportResult.Failed("공유할 앱을 찾지 못했습니다. 제목과 본문 복사를 이용하세요.")
    } catch (_: SecurityException) {
        PublicationExportResult.Failed("이미지 읽기 권한을 전달하지 못했습니다. 다시 생성한 뒤 공유하세요.")
    } catch (_: Exception) {
        PublicationExportResult.Failed("공유를 시작하지 못했습니다. 텍스트 복사를 이용하세요.")
    }

    fun buildIntent(draft: BlogDraft, content: PublicationShareContent): Intent {
        val images = if (content == PublicationShareContent.TEXT) emptyList() else {
            draft.evidenceImages.map { it.uri to it.mimeType } +
                draft.generatedImages.map { it.uri to it.mimeType }
        }
        require(content != PublicationShareContent.IMAGES || images.isNotEmpty()) {
            "No publication images available"
        }
        val uris = images.map { (uri, mime) ->
            require(mime.startsWith("image/")) { "Unsupported share MIME type" }
            Uri.parse(uri).also { require(it.scheme == "content") { "Only content URIs are shareable" } }
        }
        return Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "text/plain" else {
                images.map { it.second }.distinct().singleOrNull() ?: "image/*"
            }
            putExtra(Intent.EXTRA_TITLE, draft.title)
            if (content != PublicationShareContent.IMAGES) {
                putExtra(Intent.EXTRA_SUBJECT, draft.title)
                putExtra(Intent.EXTRA_TEXT, draft.fullText)
            }
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else if (uris.isNotEmpty()) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            if (uris.isNotEmpty()) {
                clipData = ClipData(
                    ClipDescription("게시물 이미지", arrayOf(type!!)),
                    ClipData.Item(uris.first()),
                ).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
