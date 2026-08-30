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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Entity(tableName = "ingestion_sessions", indices = [Index("local_document_id"), Index("canonical_fingerprint"), Index("import_fingerprint")])
internal data class IngestionSessionEntity(
    @PrimaryKey @ColumnInfo(name = "ingestion_id") val ingestionId: String,
    @ColumnInfo(name = "local_document_id") val localDocumentId: String,
    @ColumnInfo(name = "envelope_storage_key") val envelopeStorageKey: String,
    @ColumnInfo(name = "canonical_fingerprint") val canonicalFingerprint: String,
    @ColumnInfo(name = "review_status") val reviewStatus: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "revision_seq") val revisionSeq: Long,
    @ColumnInfo(name = "verified_canonical_fingerprint") val verifiedCanonicalFingerprint: String?,
    @ColumnInfo(name = "verified_at") val verifiedAt: String?,
    @ColumnInfo(name = "verified_artifact_fingerprints_json") val verifiedArtifactFingerprintsJson: String = "{}",
    @ColumnInfo(name = "import_fingerprint") val importFingerprint: String?,
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
    @ColumnInfo(name = "metadata_json") val metadataJson: String?,
)

@Entity(tableName = "ingestion_attachments", primaryKeys = ["ingestion_id", "attachment_id"], indices = [Index("ingestion_id"), Index("page_id")])
internal data class IngestionAttachmentEntity(
    @ColumnInfo(name = "ingestion_id") val ingestionId: String,
    @ColumnInfo(name = "attachment_id") val attachmentId: String,
    val type: String,
    @ColumnInfo(name = "page_id") val pageId: String?,
    @ColumnInfo(name = "file_readable") val fileReadable: Boolean,
)

internal fun IngestionSessionEntity.toDomain(
    projections: List<IngestionProjectionEntity>,
    attachments: List<IngestionAttachmentEntity> = emptyList(),
) = IngestionSession(
    ingestionId = ingestionId,
    localDocumentId = localDocumentId,
    envelopeStorageKey = envelopeStorageKey,
    canonicalFingerprint = canonicalFingerprint,
    reviewStatus = IngestionReviewStatus.entries.first { it.wireValue == reviewStatus },
    createdAt = createdAt,
    updatedAt = updatedAt,
    projections = projections.map(IngestionProjectionEntity::toDomain),
    attachments = attachments.map { LocalEvidence(it.attachmentId, SourceAttachmentType.fromWireValue(it.type), it.fileReadable, it.pageId) },
    revisionSeq = revisionSeq.coerceAtLeast(1),
    verifiedCanonicalFingerprint = verifiedCanonicalFingerprint,
    verifiedAt = verifiedAt,
    verifiedArtifactFingerprints = decodeArtifactFingerprints(verifiedArtifactFingerprintsJson),
    importFingerprint = importFingerprint ?: canonicalFingerprint,
)

internal fun IngestionProjectionEntity.toDomain() = ProjectionState(
    projection = IngestionProjection.entries.first { it.wireValue == projection },
    status = ProjectionStatus.fromPersisted(status),
    idempotencyKey = idempotencyKey,
    remoteId = remoteId,
    attemptCount = attemptCount,
    lastError = lastError,
    updatedAt = updatedAt,
    metadataJson = metadataJson,
)

internal fun ProjectionState.toEntity(ingestionId: String) = IngestionProjectionEntity(
    ingestionId = ingestionId,
    projection = projection.wireValue,
    status = status.wireValue,
    idempotencyKey = idempotencyKey,
    remoteId = remoteId,
    attemptCount = attemptCount,
    lastError = lastError,
    updatedAt = updatedAt,
    metadataJson = metadataJson,
)

internal fun LocalEvidence.toEntity(ingestionId: String) = IngestionAttachmentEntity(ingestionId, attachmentId, type.wireValue, pageId, fileReadable)

internal fun IngestionSession.toEntity() = IngestionSessionEntity(
    ingestionId = ingestionId,
    localDocumentId = localDocumentId,
    envelopeStorageKey = envelopeStorageKey,
    canonicalFingerprint = canonicalFingerprint,
    reviewStatus = reviewStatus.wireValue,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revisionSeq = revisionSeq,
    verifiedCanonicalFingerprint = verifiedCanonicalFingerprint,
    verifiedAt = verifiedAt,
    verifiedArtifactFingerprintsJson = encodeArtifactFingerprints(verifiedArtifactFingerprints),
    importFingerprint = importFingerprint,
)

private fun encodeArtifactFingerprints(value: Map<String, String>): String =
    JsonObject(value.mapValues { (_, fingerprint) -> JsonPrimitive(fingerprint) }).toString()

private fun decodeArtifactFingerprints(value: String): Map<String, String> = runCatching {
    Json.parseToJsonElement(value).jsonObject.mapValues { (_, element) ->
        (element as? JsonPrimitive)?.content ?: error("artifact fingerprint must be a string")
    }
}.getOrDefault(emptyMap())
