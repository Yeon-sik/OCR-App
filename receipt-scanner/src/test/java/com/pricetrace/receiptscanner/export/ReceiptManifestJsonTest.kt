package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptManifestJsonTest {
    @Test
    fun `manifest preserves parser version and reviewed at`() {
        val manifest = ReceiptExportManifest(
            documentId = "doc-manifest-test",
            pages = listOf(
                ExportPageManifest(
                    pageId = "page-1",
                    sha256 = "sha-1",
                    mimeType = "image/jpeg",
                    width = 100,
                    height = 200,
                    pageIndex = 0,
                    revision = 1,
                ),
            ),
            ocrEngine = OcrEngineInfo(name = "mlkit", version = "16.0.1"),
            parserVersion = "generic-parser.v6",
            jsonRevision = "revision-123",
            idempotencyKey = "idempotency-123",
            reviewStatus = "user_verified",
            reviewedAt = "2026-08-03T10:15:00+09:00",
            exportedAt = "2026-08-03T10:16:00+09:00",
            receiptStorageKey = "doc-manifest-test/exports/revision-123/receipt.json",
            ocrDebugStorageKey = "doc-manifest-test/exports/revision-123/ocr-debug.json",
        )

        val encoded = ReceiptManifestJson.encode(manifest)
        val root = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("generic-parser.v6", root.getValue("parser_version").jsonPrimitive.content)
        assertEquals("2026-08-03T10:15:00+09:00", root.getValue("reviewed_at").jsonPrimitive.content)
    }
}
