package com.pricetrace.receiptocr.publishing.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.pricetrace.receiptscanner.publishing.model.BlogDraft

enum class PublicationClipboardContent { TITLE, BODY, ALL_TEXT }

sealed interface PublicationExportResult {
    data object Completed : PublicationExportResult
    data class Failed(val message: String) : PublicationExportResult
}

class PublicationClipboardExporter(private val context: Context) {
    fun copy(draft: BlogDraft, content: PublicationClipboardContent): PublicationExportResult = try {
        val text = when (content) {
            PublicationClipboardContent.TITLE -> draft.title
            PublicationClipboardContent.BODY -> draft.body
            PublicationClipboardContent.ALL_TEXT -> draft.fullText
        }
        val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("블로그 게시물", text))
        PublicationExportResult.Completed
    } catch (_: Exception) {
        PublicationExportResult.Failed("클립보드에 복사하지 못했습니다. 다시 시도하세요.")
    }
}
