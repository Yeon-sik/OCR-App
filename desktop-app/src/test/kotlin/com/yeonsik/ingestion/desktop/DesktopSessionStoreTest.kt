package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveCheckpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopSessionStoreTest {
    @Test
    fun `session record round trips and never persists runtime credentials`() = runBlocking {
        val directory = Files.createTempDirectory("yeonsik-desktop-session")
        val evidencePath = directory.resolve("receipt.jpg").also { Files.writeString(it, "local evidence") }
        val session = sampleSession()
        val store = DesktopSessionStore(directory.resolve("sessions"))
        val attachment = DesktopEvidenceAttachment("evidence-1", SourceAttachmentType.RECEIPT, evidencePath.toAbsolutePath())

        store.saveRecord(
            DesktopSessionRecord(
                session = session,
                rawJson = "{\"schema_version\":\"yeonsik-ocr.v1\"}",
                canonicalJson = "{\"schema_version\":\"yeonsik-ocr.v1\"}",
                evidence = listOf(attachment),
            ),
        )

        val loaded = requireNotNull(store.loadRecord(session.ingestionId))
        assertEquals(session, loaded.session)
        assertEquals(attachment.path, loaded.evidence.single().path)
        assertEquals(session.canonicalFingerprint, store.findByCanonicalFingerprint("canonical-1")?.canonicalFingerprint)
        assertEquals(session.ingestionId, store.findByImportFingerprint("import-1")?.ingestionId)

        val persisted = Files.readString(directory.resolve("sessions").resolve("desktop-test.json"))
        assertFalse(persisted.contains("access_token"))
        assertFalse(persisted.contains("refresh_token"))
        assertFalse(persisted.contains("password"))
        assertTrue(Files.list(directory.resolve("sessions")).use { stream -> stream.noneMatch { it.fileName.toString().contains(".tmp-") } })
    }

    @Test
    fun `bundle metadata round trips`() = runBlocking {
        val directory = Files.createTempDirectory("yeonsik-desktop-session-v2")
        val store = DesktopSessionStore(directory)
        val session = sampleSession()
        val metadata = DesktopBundleMetadata(
            sourcePath = "C:\\incoming\\receipt.yeonsik",
            canonicalSha256 = "a".repeat(64),
            manifestJson = "{\"bundle_version\":\"yeonsik-bundle.v1\"}",
            validationStatus = DesktopBundleValidationStatus.VALID,
            archiveStatus = DesktopEvidenceArchiveStatus.ARCHIVED,
            archiveCheckpoint = EvidenceArchiveCheckpoint(
                canonicalArtifactId = "artifact-id",
                evidenceObjectIdsBySha256 = mapOf("b".repeat(64) to "object-id"),
                boundSourceFileIds = setOf("receipt-1"),
            ),
            verificationEventRecorded = true,
        )
        store.saveRecord(DesktopSessionRecord(session, "{}", "{}", emptyList(), metadata))
        assertEquals(metadata, store.loadRecord(session.ingestionId)?.bundle)
        val persisted = Files.readString(directory.resolve("desktop-test.json"))
        assertFalse(persisted.contains("access_token"))
        assertFalse(persisted.contains("refresh_token"))
        assertFalse(persisted.contains("password"))
    }

    @Test
    fun `v1 session records remain readable`() = runBlocking {
        val directory = Files.createTempDirectory("yeonsik-desktop-session-v1")
        val store = DesktopSessionStore(directory)
        val session = sampleSession()
        store.saveRecord(DesktopSessionRecord(session, "{}", "{}", emptyList()))
        val path = directory.resolve("desktop-test.json")
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val legacy = JsonObject(root.toMutableMap().apply {
            put("schema_version", JsonPrimitive("desktop-ingestion-session.v1"))
            remove("bundle")
        })
        Files.writeString(path, Json.encodeToString(JsonElement.serializer(), legacy))

        val loaded = requireNotNull(store.loadRecord(session.ingestionId))
        assertEquals(session, loaded.session)
        assertEquals(null, loaded.bundle)
    }

    private fun sampleSession(): IngestionSession = IngestionSession(
        ingestionId = "desktop-test",
        localDocumentId = "desktop-document-test",
        envelopeStorageKey = "desktop-document-test/ingestion/yeonsik-ocr.json",
        canonicalFingerprint = "canonical-1",
        reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
        createdAt = "2026-09-07T00:00:00Z",
        updatedAt = "2026-09-07T00:00:00Z",
        projections = listOf(
            ProjectionState(
                projection = IngestionProjection.PRICETRACE_RECEIPT,
                status = ProjectionStatus.PENDING,
                updatedAt = "2026-09-07T00:00:00Z",
            ),
        ),
        attachments = listOf(
            LocalEvidence("evidence-1", SourceAttachmentType.RECEIPT, fileReadable = true),
        ),
        importFingerprint = "import-1",
    )
}
