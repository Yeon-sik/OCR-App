package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
import com.pricetrace.receiptscanner.storage.StoredArtifact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime

data class ExportPageManifest(
    val pageId: String,
    val sha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val pageIndex: Int,
    val revision: Int,
)

data class ReceiptExportManifest(
    val schemaVersion: String = "receipt-export-manifest.v1",
    val documentId: String,
    val pages: List<ExportPageManifest>,
    val ocrEngine: OcrEngineInfo,
    val parserVersion: String,
    val jsonRevision: String,
    val idempotencyKey: String,
    val reviewStatus: String,
    val reviewedAt: String?,
    val exportedAt: String,
    val receiptStorageKey: String,
    val ocrDebugStorageKey: String?,
)

data class ReceiptExportBundle(
    val receipt: StoredArtifact,
    val manifest: StoredArtifact,
    val ocrDebug: StoredArtifact?,
    val jsonRevision: String,
    val idempotencyKey: String,
)

class ReceiptExportService(
    private val fileStore: ReceiptFileStore,
) {
    suspend fun export(
        receipt: ReceiptV2,
        pages: List<ReceiptPage>,
        ocrEngine: OcrEngineInfo,
        parserVersion: String,
        reviewedAt: String?,
        ocrDocument: OcrDocument? = null,
    ): ReceiptExportBundle {
        require(pages.map { it.id }.toSet().containsAll(receipt.document.source.sourceImages)) {
            "Every source image must have manifest metadata"
        }
        val canonicalReceipt = ReceiptV2Json.encodeCanonical(receipt)
        val revision = ReceiptV2Json.revisionHash(receipt)
        val idempotencyKey = ReceiptV2Json.idempotencyKey(receipt)
        val exportRoot = "${receipt.document.id}/exports/$revision"
        val receiptKey = "$exportRoot/receipt.json"
        val debugKey = ocrDocument?.let { "$exportRoot/ocr-debug.json" }
        val receiptArtifact = fileStore.writeText(receiptKey, canonicalReceipt)
        val debugArtifact = ocrDocument?.let { document ->
            fileStore.writeText(requireNotNull(debugKey), OcrDebugJson.encode(document))
        }
        val manifest = ReceiptExportManifest(
            documentId = receipt.document.id,
            pages = pages.sortedBy { it.pageIndex }.map { page ->
                ExportPageManifest(
                    pageId = page.id,
                    sha256 = page.sha256,
                    mimeType = page.mimeType,
                    width = page.width,
                    height = page.height,
                    pageIndex = page.pageIndex,
                    revision = page.revision,
                )
            },
            ocrEngine = ocrEngine,
            parserVersion = parserVersion,
            jsonRevision = revision,
            idempotencyKey = idempotencyKey,
            reviewStatus = receipt.document.source.transcriptionStatus.wireValue,
            reviewedAt = reviewedAt,
            exportedAt = OffsetDateTime.now().toString(),
            receiptStorageKey = receiptKey,
            ocrDebugStorageKey = debugKey,
        )
        val manifestArtifact = fileStore.writeText(
            "$exportRoot/manifest.json",
            ReceiptManifestJson.encode(manifest),
        )
        return ReceiptExportBundle(
            receipt = receiptArtifact,
            manifest = manifestArtifact,
            ocrDebug = debugArtifact,
            jsonRevision = revision,
            idempotencyKey = idempotencyKey,
        )
    }
}

object ReceiptManifestJson {
    private val json = Json { prettyPrint = true }

    fun encode(manifest: ReceiptExportManifest): String = json.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            linkedMapOf(
                "schema_version" to JsonPrimitive(manifest.schemaVersion),
                "document_id" to JsonPrimitive(manifest.documentId),
                "pages" to JsonArray(manifest.pages.map { page ->
                    JsonObject(
                        linkedMapOf(
                            "page_id" to JsonPrimitive(page.pageId),
                            "sha256" to JsonPrimitive(page.sha256),
                            "mime_type" to JsonPrimitive(page.mimeType),
                            "width" to JsonPrimitive(page.width),
                            "height" to JsonPrimitive(page.height),
                            "page_index" to JsonPrimitive(page.pageIndex),
                            "revision" to JsonPrimitive(page.revision),
                        ),
                    )
                }),
                "ocr_engine" to JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(manifest.ocrEngine.name),
                        "version" to JsonPrimitive(manifest.ocrEngine.version),
                    ),
                ),
                "parser_version" to JsonPrimitive(manifest.parserVersion),
                "json_revision" to JsonPrimitive(manifest.jsonRevision),
                "idempotency_key" to JsonPrimitive(manifest.idempotencyKey),
                "review_status" to JsonPrimitive(manifest.reviewStatus),
                "reviewed_at" to (manifest.reviewedAt?.let(::JsonPrimitive) ?: JsonNull),
                "exported_at" to JsonPrimitive(manifest.exportedAt),
                "receipt_storage_key" to JsonPrimitive(manifest.receiptStorageKey),
                "ocr_debug_storage_key" to (manifest.ocrDebugStorageKey?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ),
    )
}
