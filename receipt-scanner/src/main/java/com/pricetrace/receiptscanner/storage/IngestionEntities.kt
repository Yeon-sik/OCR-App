package com.pricetrace.receiptscanner.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType

@Entity(tableName = "ingestion_sessions", indices = [Index("local_document_id"), Index("canonical_fingerprint")])
internal data class IngestionSessionEntity(
    @PrimaryKey @ColumnInfo(name = "ingestion_id") val ingestionId: String,
    @ColumnInfo(name = "local_document_id") val localDocumentId: String,
    @ColumnInfo(name = "envelope_storage_key") val envelopeStorageKey: String,
    @ColumnInfo(name = "canonical_fingerprint") val canonicalFingerprint: String,
    @ColumnInfo(name = "review_status") val reviewStatus: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(tableName = "ingestion_projections", primaryKeys = ["ingestion_id", "projection"], indices = [Index("status")])
internal data class IngestionProjectionEntity(
    @ColumnInfo(name = "ingestion_id") val ingestionId: String,
    val projection: String,
    val status: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,
    @ColumnInfo(name = "remote_id") val remoteId: String?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(tableName = "ingestion_attachments", primaryKeys = ["ingestion_id", "attachment_id"], indices = [Index("ingestion_id"), Index("page_id")])
internal data class IngestionAttachmentEntity(
    @ColumnInfo(name = "ingestion_id") val ingestionId: String,
    @ColumnInfo(name = "attachment_id") val attachmentId: String,
    val type: String,
    @ColumnInfo(name = "page_id") val pageId: String?,
    @ColumnInfo(name = "file_readable") val fileReadable: Boolean,
)

internal fun IngestionSessionEntity.toDomain(projections: List<IngestionProjectionEntity>, attachments: List<IngestionAttachmentEntity> = emptyList()) = IngestionSession(
    ingestionId, localDocumentId, envelopeStorageKey, canonicalFingerprint,
    IngestionReviewStatus.entries.first { it.wireValue == reviewStatus }, createdAt, updatedAt,
    projections.map(IngestionProjectionEntity::toDomain),
    attachments.map { LocalEvidence(it.attachmentId, SourceAttachmentType.fromWireValue(it.type), it.fileReadable, it.pageId) },
)

internal fun IngestionProjectionEntity.toDomain() = ProjectionState(
    IngestionProjection.entries.first { it.wireValue == projection }, ProjectionStatus.fromPersisted(status),
    idempotencyKey, remoteId, attemptCount, lastError, updatedAt,
)

internal fun ProjectionState.toEntity(ingestionId: String) = IngestionProjectionEntity(
    ingestionId, projection.wireValue, status.wireValue, idempotencyKey, remoteId, attemptCount, lastError, updatedAt,
)

internal fun LocalEvidence.toEntity(ingestionId: String) = IngestionAttachmentEntity(ingestionId, attachmentId, type.wireValue, pageId, fileReadable)

internal fun IngestionSession.toEntity() = IngestionSessionEntity(
    ingestionId, localDocumentId, envelopeStorageKey, canonicalFingerprint, reviewStatus.wireValue, createdAt, updatedAt,
)
