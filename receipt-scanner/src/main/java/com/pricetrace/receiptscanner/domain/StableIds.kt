package com.pricetrace.receiptscanner.domain

import java.security.MessageDigest
import java.util.UUID

object StableIds {
    fun newDocumentId(): String = "receipt_${UUID.randomUUID()}"

    fun newOcrDocumentId(): String = "ocr_${UUID.randomUUID()}"

    fun pageId(documentId: String, pageIndex: Int, sha256: String): String =
        "page_${sha256("$documentId|$pageIndex|$sha256").take(20)}"

    fun lineId(documentId: String, pageId: String, sourceLineId: String, rawText: String): String =
        "line_${sha256("$documentId|$pageId|$sourceLineId|${rawText.trim()}").take(20)}"

    fun editId(documentId: String, fieldPath: String, editedAt: String): String =
        "edit_${sha256("$documentId|$fieldPath|$editedAt").take(20)}"

    /**
     * Undo and rapid retyping can produce several edits on one field within a single clock reading, and
     * the history table is keyed by this id. The sequence keeps those rows distinct instead of upserting
     * one over another.
     */
    fun editId(documentId: String, fieldPath: String, editedAt: String, sequence: Int): String =
        if (sequence == 0) {
            editId(documentId, fieldPath, editedAt)
        } else {
            "edit_${sha256("$documentId|$fieldPath|$editedAt|$sequence").take(20)}"
        }

    /** Row the reviewer transcribed themselves; it has no OCR line to derive an id from. */
    fun userEnteredLineId(documentId: String, createdAt: String, sequence: Int): String =
        "line_user_${sha256("$documentId|$createdAt|$sequence").take(20)}"

    fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
