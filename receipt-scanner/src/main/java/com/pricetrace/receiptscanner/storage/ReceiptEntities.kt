package com.pricetrace.receiptscanner.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_sessions",
    indices = [
        Index("input_origin"),
        Index("upstream_document_id"),
        Index("import_fingerprint"),
    ],
)
internal data class ScanSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    @ColumnInfo(name = "ocr_status")
    val ocrStatus: String,
    @ColumnInfo(name = "review_status")
    val reviewStatus: String,
    @ColumnInfo(name = "json_revision")
    val jsonRevision: String?,
    @ColumnInfo(name = "export_status")
    val exportStatus: String,
    @ColumnInfo(name = "upload_status")
    val uploadStatus: String,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int,
    @ColumnInfo(name = "merchant_name")
    val merchantName: String?,
    @ColumnInfo(name = "issued_on")
    val issuedOn: String?,
    @ColumnInfo(name = "grand_total_amount_minor")
    val grandTotalAmountMinor: Long?,
    @ColumnInfo(name = "receipt_storage_key")
    val receiptStorageKey: String?,
    @ColumnInfo(name = "manifest_storage_key")
    val manifestStorageKey: String?,
    @ColumnInfo(name = "reviewed_at")
    val reviewedAt: String?,
    /** When the OCR draft became available, so review effort can be measured against it. */
    @ColumnInfo(name = "ocr_completed_at")
    val ocrCompletedAt: String?,
    @ColumnInfo(name = "workflow_type", defaultValue = "'pricetrace_receipt'")
    val workflowType: String,
    @ColumnInfo(name = "input_origin", defaultValue = "'android_ocr'")
    val inputOrigin: String,
    @ColumnInfo(name = "upstream_document_id")
    val upstreamDocumentId: String?,
    @ColumnInfo(name = "import_fingerprint")
    val importFingerprint: String?,
    @ColumnInfo(name = "display_title")
    val displayTitle: String?,
    @ColumnInfo(name = "workflow_draft_storage_key")
    val workflowDraftStorageKey: String?,
)

@Entity(
    tableName = "receipt_pages",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["document_id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("document_id"), Index("sha256")],
)
internal data class ReceiptPageEntity(
    @PrimaryKey
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "storage_key")
    val storageKey: String,
    val sha256: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    val revision: Int,
)

@Entity(
    tableName = "review_edits",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["document_id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("document_id")],
)
internal data class ReviewEditEntity(
    @PrimaryKey
    @ColumnInfo(name = "edit_id")
    val editId: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "field_path")
    val fieldPath: String,
    @ColumnInfo(name = "previous_value")
    val previousValue: String?,
    @ColumnInfo(name = "new_value")
    val newValue: String?,
    @ColumnInfo(name = "provenance_json")
    val provenanceJson: String?,
    @ColumnInfo(name = "edited_at")
    val editedAt: String,
)

@Entity(
    tableName = "price_observation_queue",
    indices = [
        Index("status"),
        Index(value = ["local_document_id", "local_line_item_id"]),
    ],
)
internal data class PriceObservationQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "queue_id")
    val queueId: String,
    /** Local-only context. Neither value is included in the RPC payload. */
    @ColumnInfo(name = "local_document_id")
    val localDocumentId: String?,
    @ColumnInfo(name = "local_line_item_id")
    val localLineItemId: String?,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "store_id")
    val storeId: String,
    @ColumnInfo(name = "observed_on")
    val observedOn: String,
    @ColumnInfo(name = "catalog_product_id")
    val catalogProductId: String,
    @ColumnInfo(name = "unit_price_krw")
    val unitPriceKrw: Int,
    val status: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    @ColumnInfo(name = "observation_id")
    val observationId: String?,
    val replayed: Boolean?,
    @ColumnInfo(name = "applied_action")
    val appliedAction: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
