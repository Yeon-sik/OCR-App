package com.pricetrace.receiptscanner.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ReceiptSessionDao {
    @Query("SELECT * FROM scan_sessions ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM scan_sessions WHERE document_id = :documentId")
    fun observeSession(documentId: String): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions WHERE document_id = :documentId")
    suspend fun getSession(documentId: String): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE import_fingerprint = :importFingerprint ORDER BY updated_at DESC")
    suspend fun findSessionsByImportFingerprint(importFingerprint: String): List<ScanSessionEntity>

    @Query("SELECT * FROM scan_sessions WHERE upstream_document_id = :upstreamDocumentId ORDER BY updated_at DESC")
    suspend fun findSessionsByUpstreamDocumentId(upstreamDocumentId: String): List<ScanSessionEntity>

    @Query("SELECT * FROM receipt_pages WHERE document_id = :documentId ORDER BY page_index, revision")
    fun observePages(documentId: String): Flow<List<ReceiptPageEntity>>

    @Query("SELECT * FROM receipt_pages WHERE document_id = :documentId ORDER BY page_index, revision")
    suspend fun getPages(documentId: String): List<ReceiptPageEntity>

    @Query("SELECT page_id FROM receipt_pages WHERE sha256 = :sha256 AND page_id != :pageId")
    suspend fun findDuplicatePageIds(sha256: String, pageId: String): List<String>

    @Upsert
    suspend fun upsertSession(session: ScanSessionEntity)

    @Upsert
    suspend fun upsertPages(pages: List<ReceiptPageEntity>)

    @Upsert
    suspend fun upsertEdit(edit: ReviewEditEntity)

    @Transaction
    suspend fun persistDraftSnapshot(session: ScanSessionEntity, edits: List<ReviewEditEntity>) {
        for (edit in edits) {
            upsertEdit(edit)
        }
        upsertSession(session)
    }

    @Query("SELECT * FROM review_edits WHERE document_id = :documentId ORDER BY edited_at")
    fun observeEdits(documentId: String): Flow<List<ReviewEditEntity>>

    @Query("SELECT * FROM review_edits WHERE document_id = :documentId ORDER BY edited_at")
    suspend fun getEdits(documentId: String): List<ReviewEditEntity>

    @Delete
    suspend fun deleteSession(session: ScanSessionEntity)

    @Query("SELECT * FROM price_observation_queue WHERE queue_id = :queueId")
    suspend fun getPriceObservationQueue(queueId: String): PriceObservationQueueEntity?

    @Query(
        "SELECT * FROM price_observation_queue " +
            "WHERE local_document_id = :localDocumentId AND local_line_item_id = :localLineItemId " +
            "ORDER BY updated_at DESC LIMIT 1",
    )
    suspend fun latestPriceObservationForLine(
        localDocumentId: String,
        localLineItemId: String,
    ): PriceObservationQueueEntity?

    @Upsert
    suspend fun upsertPriceObservationQueue(entry: PriceObservationQueueEntity)

    @Query("SELECT * FROM ingestion_sessions WHERE canonical_fingerprint = :fingerprint ORDER BY created_at LIMIT 1")
    suspend fun getIngestionSessionByFingerprint(fingerprint: String): IngestionSessionEntity?

    @Query("SELECT * FROM ingestion_sessions WHERE import_fingerprint = :fingerprint ORDER BY created_at LIMIT 1")
    suspend fun getIngestionSessionByImportFingerprint(fingerprint: String): IngestionSessionEntity?

    @Query("SELECT * FROM ingestion_sessions WHERE ingestion_id = :ingestionId")
    suspend fun getIngestionSession(ingestionId: String): IngestionSessionEntity?

    @Query("SELECT * FROM ingestion_projections WHERE ingestion_id = :ingestionId ORDER BY projection")
    suspend fun getIngestionProjections(ingestionId: String): List<IngestionProjectionEntity>

    @Query("SELECT * FROM ingestion_attachments WHERE ingestion_id = :ingestionId ORDER BY attachment_id")
    suspend fun getIngestionAttachments(ingestionId: String): List<IngestionAttachmentEntity>

    @Upsert
    suspend fun upsertIngestionSession(session: IngestionSessionEntity)

    @Upsert
    suspend fun upsertIngestionProjections(projections: List<IngestionProjectionEntity>)

    @Upsert
    suspend fun upsertIngestionAttachments(attachments: List<IngestionAttachmentEntity>)

    @Query("DELETE FROM ingestion_attachments WHERE ingestion_id = :ingestionId")
    suspend fun deleteIngestionAttachments(ingestionId: String)

    @Query("DELETE FROM ingestion_projections WHERE ingestion_id = :ingestionId")
    suspend fun deleteIngestionProjections(ingestionId: String)

    @Query("DELETE FROM ingestion_sessions WHERE ingestion_id = :ingestionId")
    suspend fun deleteIngestionSession(ingestionId: String)

    @Transaction
    suspend fun deleteIngestionSnapshot(ingestionId: String) {
        deleteIngestionAttachments(ingestionId)
        deleteIngestionProjections(ingestionId)
        deleteIngestionSession(ingestionId)
    }

    @Transaction
    suspend fun upsertIngestionSnapshot(session: IngestionSessionEntity, projections: List<IngestionProjectionEntity>, attachments: List<IngestionAttachmentEntity>) {
        // Replace child snapshots so removed pages or projections cannot survive a retry.
        deleteIngestionAttachments(session.ingestionId)
        deleteIngestionProjections(session.ingestionId)
        upsertIngestionSession(session)
        upsertIngestionProjections(projections)
        upsertIngestionAttachments(attachments)
    }
}
