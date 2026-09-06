package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import kotlinx.coroutines.runBlocking
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
