package com.pricetrace.receiptscanner.storage

import android.content.Context
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.OffsetDateTime

data class SessionInputMetadata(
    val inputOrigin: InputOrigin = InputOrigin.ANDROID_OCR,
    val upstreamDocumentId: String? = null,
    val importFingerprint: String? = null,
)

data class ReceiptSession(
    val documentId: String,
    val createdAt: String,
    val updatedAt: String,
    val ocrStatus: String,
    val reviewStatus: String,
    val jsonRevision: String?,
    val exportStatus: String,
    val uploadStatus: String,
    val lastError: String?,
    val retryCount: Int,
    val merchantName: String?,
    val issuedOn: String?,
    val grandTotalAmountMinor: Long?,
    val receiptStorageKey: String?,
    val manifestStorageKey: String?,
    val reviewedAt: String?,
    val ocrCompletedAt: String? = null,
    val workflowType: OcrWorkflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
    val inputOrigin: InputOrigin = InputOrigin.ANDROID_OCR,
    val upstreamDocumentId: String? = null,
    val importFingerprint: String? = null,
    val displayTitle: String? = null,
    val workflowDraftStorageKey: String? = null,
) {
    val hasPersistedCanonicalDraft: Boolean
        get() = if (workflowType == OcrWorkflowType.FITNESS_NUTRITION) {
            workflowDraftStorageKey != null
        } else {
            receiptStorageKey != null || workflowDraftStorageKey != null
        }

    /** External JSON may be restored without pages only when its canonical draft is persisted. */
    fun canRestore(pages: List<ReceiptPage>): Boolean = pages.isNotEmpty() ||
        (inputOrigin == InputOrigin.EXTERNAL_JSON && hasPersistedCanonicalDraft)
}

data class ReviewEdit(
    val id: String,
    val documentId: String,
    val fieldPath: String,
    val previousValue: String?,
    val newValue: String?,
    val provenanceJson: String?,
    val editedAt: String,
)

data class DeletionResult(
    val metadataDeleted: Boolean,
    val filesDeleted: Boolean,
    val detail: String? = null,
) {
    val isComplete: Boolean get() = metadataDeleted && filesDeleted
}

interface ReceiptSessionRepository {
    fun observeSessions(): Flow<List<ReceiptSession>>
    fun observeSession(documentId: String): Flow<ReceiptSession?>
    fun observePages(documentId: String): Flow<List<ReceiptPage>>
    fun observeEdits(documentId: String): Flow<List<ReviewEdit>>
    suspend fun getSession(documentId: String): ReceiptSession?
    suspend fun getPages(documentId: String): List<ReceiptPage>
    suspend fun getEdits(documentId: String): List<ReviewEdit>
    suspend fun findSessionsByImportFingerprint(importFingerprint: String): List<ReceiptSession>
    suspend fun findSessionsByUpstreamDocumentId(upstreamDocumentId: String): List<ReceiptSession>
    suspend fun createSession(
        documentId: String,
        workflowType: OcrWorkflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
        createdAt: String = OffsetDateTime.now().toString(),
        inputMetadata: SessionInputMetadata = SessionInputMetadata(),
    )
    suspend fun addPages(documentId: String, pages: List<ReceiptPage>): List<String>
    suspend fun updateSession(session: ReceiptSession)
    suspend fun appendEdit(edit: ReviewEdit)
    suspend fun persistDraftSnapshot(session: ReceiptSession, edits: List<ReviewEdit>)
    suspend fun deleteSession(documentId: String): DeletionResult
}

class RoomReceiptSessionRepository internal constructor(
    private val dao: ReceiptSessionDao,
    private val fileStore: ReceiptFileStore,
) : ReceiptSessionRepository {
    companion object {
        fun create(context: Context, fileStore: ReceiptFileStore): RoomReceiptSessionRepository {
            val dao = ReceiptDatabase.getInstance(context).receiptSessionDao()
            return RoomReceiptSessionRepository(dao, fileStore)
        }
    }
    override fun observeSessions(): Flow<List<ReceiptSession>> =
        dao.observeSessions().map { sessions -> sessions.map(ScanSessionEntity::toDomain) }

    override fun observeSession(documentId: String): Flow<ReceiptSession?> =
        dao.observeSession(documentId).map { entity -> entity?.toDomain() }

    override fun observePages(documentId: String): Flow<List<ReceiptPage>> =
        dao.observePages(documentId).map { pages -> pages.map(ReceiptPageEntity::toDomain) }

    override fun observeEdits(documentId: String): Flow<List<ReviewEdit>> =
        dao.observeEdits(documentId).map { edits -> edits.map(ReviewEditEntity::toDomain) }

    override suspend fun getSession(documentId: String): ReceiptSession? = dao.getSession(documentId)?.toDomain()

    override suspend fun getPages(documentId: String): List<ReceiptPage> =
        dao.getPages(documentId).map(ReceiptPageEntity::toDomain)

    override suspend fun getEdits(documentId: String): List<ReviewEdit> =
        dao.getEdits(documentId).map(ReviewEditEntity::toDomain)

    override suspend fun findSessionsByImportFingerprint(importFingerprint: String): List<ReceiptSession> =
        dao.findSessionsByImportFingerprint(importFingerprint).map(ScanSessionEntity::toDomain)

    override suspend fun findSessionsByUpstreamDocumentId(upstreamDocumentId: String): List<ReceiptSession> =
        dao.findSessionsByUpstreamDocumentId(upstreamDocumentId).map(ScanSessionEntity::toDomain)

    override suspend fun createSession(
        documentId: String,
        workflowType: OcrWorkflowType,
        createdAt: String,
        inputMetadata: SessionInputMetadata,
    ) {
        dao.upsertSession(
            ScanSessionEntity(
                documentId = documentId,
                createdAt = createdAt,
                updatedAt = createdAt,
                ocrStatus = "unprocessed",
                reviewStatus = "draft",
                jsonRevision = null,
                exportStatus = "not_exported",
                uploadStatus = "local_only",
                lastError = null,
                retryCount = 0,
                merchantName = null,
                issuedOn = null,
                grandTotalAmountMinor = null,
                receiptStorageKey = null,
                manifestStorageKey = null,
                reviewedAt = null,
                ocrCompletedAt = null,
                workflowType = workflowType.wireValue,
                inputOrigin = inputMetadata.inputOrigin.wireValue,
                upstreamDocumentId = inputMetadata.upstreamDocumentId,
                importFingerprint = inputMetadata.importFingerprint,
                displayTitle = null,
                workflowDraftStorageKey = null,
            ),
        )
    }

    override suspend fun addPages(documentId: String, pages: List<ReceiptPage>): List<String> {
        val existingDuplicates = pages.flatMap { page -> dao.findDuplicatePageIds(page.sha256, page.id) }
        val batchDuplicates = pages.groupBy(ReceiptPage::sha256)
            .values
            .filter { matches -> matches.size > 1 }
            .flatten()
            .map(ReceiptPage::id)
        val duplicates = (existingDuplicates + batchDuplicates).distinct()
        dao.upsertPages(pages.map(ReceiptPage::toEntity))
        val current = requireNotNull(dao.getSession(documentId)) { "Session does not exist" }
        dao.upsertSession(current.copy(updatedAt = OffsetDateTime.now().toString(), lastError = null))
        return duplicates
    }

    override suspend fun updateSession(session: ReceiptSession) {
        dao.upsertSession(session.toEntity())
    }

    override suspend fun appendEdit(edit: ReviewEdit) {
        dao.upsertEdit(edit.toEntity())
    }

    override suspend fun persistDraftSnapshot(session: ReceiptSession, edits: List<ReviewEdit>) {
        dao.persistDraftSnapshot(session.toEntity(), edits.map(ReviewEdit::toEntity))
    }

    override suspend fun deleteSession(documentId: String): DeletionResult {
        val session = dao.getSession(documentId)
        if (session == null) {
            val filesDeleted = try {
                fileStore.deleteDocumentFiles(documentId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            return DeletionResult(
                metadataDeleted = true,
                filesDeleted = filesDeleted,
                detail = if (filesDeleted) null else "delete_orphan_files_failed",
            )
        }
        dao.upsertSession(
            session.copy(
                reviewStatus = "deleting",
                updatedAt = OffsetDateTime.now().toString(),
                lastError = null,
            ),
        )
        val filesDeleted = try {
            fileStore.deleteDocumentFiles(documentId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!filesDeleted) {
            dao.upsertSession(session.copy(lastError = "delete_files_failed"))
            return DeletionResult(false, false, "delete_files_failed")
        }
        return try {
            dao.deleteSession(session)
            DeletionResult(metadataDeleted = dao.getSession(documentId) == null, filesDeleted = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            dao.upsertSession(session.copy(lastError = "delete_metadata_failed"))
            DeletionResult(false, true, "delete_metadata_failed")
        }
    }
}

private fun ScanSessionEntity.toDomain() = ReceiptSession(
    documentId = documentId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    ocrStatus = ocrStatus,
    reviewStatus = reviewStatus,
    jsonRevision = jsonRevision,
    exportStatus = exportStatus,
    uploadStatus = uploadStatus,
    lastError = lastError,
    retryCount = retryCount,
    merchantName = merchantName,
    issuedOn = issuedOn,
    grandTotalAmountMinor = grandTotalAmountMinor,
    receiptStorageKey = receiptStorageKey,
    manifestStorageKey = manifestStorageKey,
    reviewedAt = reviewedAt,
    ocrCompletedAt = ocrCompletedAt,
    workflowType = OcrWorkflowType.fromWireValue(workflowType),
    inputOrigin = InputOrigin.fromWireValue(inputOrigin),
    upstreamDocumentId = upstreamDocumentId,
    importFingerprint = importFingerprint,
    displayTitle = displayTitle,
    workflowDraftStorageKey = workflowDraftStorageKey,
)

private fun ReceiptSession.toEntity() = ScanSessionEntity(
    documentId = documentId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    ocrStatus = ocrStatus,
    reviewStatus = reviewStatus,
    jsonRevision = jsonRevision,
    exportStatus = exportStatus,
    uploadStatus = uploadStatus,
    lastError = lastError,
    retryCount = retryCount,
    merchantName = merchantName,
    issuedOn = issuedOn,
    grandTotalAmountMinor = grandTotalAmountMinor,
    receiptStorageKey = receiptStorageKey,
    manifestStorageKey = manifestStorageKey,
    reviewedAt = reviewedAt,
    ocrCompletedAt = ocrCompletedAt,
    workflowType = workflowType.wireValue,
    inputOrigin = inputOrigin.wireValue,
    upstreamDocumentId = upstreamDocumentId,
    importFingerprint = importFingerprint,
    displayTitle = displayTitle,
    workflowDraftStorageKey = workflowDraftStorageKey,
)

private fun ReceiptPage.toEntity() = ReceiptPageEntity(
    pageId = id,
    documentId = documentId,
    storageKey = storageKey,
    sha256 = sha256,
    mimeType = mimeType,
    width = width,
    height = height,
    pageIndex = pageIndex,
    createdAt = createdAt,
    revision = revision,
)

private fun ReceiptPageEntity.toDomain() = ReceiptPage(
    id = pageId,
    documentId = documentId,
    storageKey = storageKey,
    sha256 = sha256,
    mimeType = mimeType,
    width = width,
    height = height,
    pageIndex = pageIndex,
    createdAt = createdAt,
    revision = revision,
)

private fun ReviewEdit.toEntity() = ReviewEditEntity(
    editId = id,
    documentId = documentId,
    fieldPath = fieldPath,
    previousValue = previousValue,
    newValue = newValue,
    provenanceJson = provenanceJson,
    editedAt = editedAt,
)

private fun ReviewEditEntity.toDomain() = ReviewEdit(
    id = editId,
    documentId = documentId,
    fieldPath = fieldPath,
    previousValue = previousValue,
    newValue = newValue,
    provenanceJson = provenanceJson,
    editedAt = editedAt,
)
