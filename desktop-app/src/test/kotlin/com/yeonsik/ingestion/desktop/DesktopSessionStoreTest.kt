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
    @Test fun `brand sidecar survives restart and old session defaults empty without changing canonical`() = runBlocking {
        val directory=Files.createTempDirectory("brand-sidecar")
        val draft=com.pricetrace.receiptscanner.ingestion.ProductBrandResolutionDraft("p",com.pricetrace.receiptscanner.ingestion.ProductBrandFactField.BRAND,"Observed",
            com.pricetrace.receiptscanner.ingestion.ProductBrandSelectionIntent.SELECTED,"00000000-0000-4000-8000-000000000001","Canonical")
        val session=sampleSession().copy(brandResolutionDrafts=listOf(draft))
        DesktopSessionStore(directory).saveRecord(DesktopSessionRecord(session,"{}","{\"brand\":\"Observed\"}",emptyList()))
        val restored=requireNotNull(DesktopSessionStore(directory).loadRecord(session.ingestionId))
        assertEquals(listOf(draft),restored.session.brandResolutionDrafts)
        assertFalse(restored.canonicalJson.contains(draft.brandId!!))
        val file=directory.resolve("desktop-test.json")
        val root=Json.parseToJsonElement(Files.readString(file)).jsonObject
        Files.writeString(file,JsonObject(root+("session" to JsonObject(root.getValue("session").jsonObject-"brand_resolution_drafts"))).toString())
        assertTrue(requireNotNull(DesktopSessionStore(directory).loadRecord(session.ingestionId)).session.brandResolutionDrafts.isEmpty())
    }
    @Test fun `resolver selections survive file store restart outside canonical`() = runBlocking {
        val directory = Files.createTempDirectory("yeonsik-identity-restart")
        val id = "00000000-0000-4000-8000-000000000021"
        val metadata = """{"identityReviews":[{"purchaseRecordClientKey":"purchase-1","lineKey":"line-1","entity":"restaurant","query":"한성","status":"owner_confirmed","choices":[{"id":"$id","name":"한성대학교 학식당"}],"selected":{"id":"$id","name":"한성대학교 학식당"}}]}"""
        val session = sampleSession().copy(projections = listOf(ProjectionState(IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            ProjectionStatus.UPLOADED,idempotencyKey = "successful-price-key",remoteId = id,metadataJson = metadata,updatedAt = "now")))
        DesktopSessionStore(directory).saveRecord(DesktopSessionRecord(session,"{}","{\"seller\":\"한성대 학식당\"}",emptyList()))
        val restored = DesktopSessionStore(directory).loadRecord(session.ingestionId)!!
        val state = com.pricetrace.receiptscanner.ingestion.PurchaseIdentityReview.states(restored.session.projections.single().metadataJson).single()
        assertEquals(id,state.selected!!.id)
        assertEquals("successful-price-key",restored.session.projections.single().idempotencyKey)
        assertFalse(restored.canonicalJson.contains(id))
        assertTrue(restored.canonicalJson.contains("한성대 학식당"))
    }
    @Test
    fun `recent records are newest first and honor the requested limit`() = runBlocking {
        val directory = Files.createTempDirectory("yeonsik-desktop-recents")
        val store = DesktopSessionStore(directory)
        val older = sampleSession().copy(
            ingestionId = "desktop-older",
            updatedAt = "2026-09-01T00:00:00Z",
        )
        val newer = sampleSession().copy(
            ingestionId = "desktop-newer",
            updatedAt = "2026-09-02T00:00:00Z",
        )
        store.saveRecord(DesktopSessionRecord(older, "{}", "{}", emptyList()))
        store.saveRecord(DesktopSessionRecord(newer, "{}", "{}", emptyList()))

        assertEquals(listOf("desktop-newer"), store.recentRecords(1).map { it.session.ingestionId })
        assertEquals(
            listOf("desktop-newer", "desktop-older"),
            store.recentRecords(8).map { it.session.ingestionId },
        )
    }

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
        val bundleSession = session.copy(
            importFingerprint = metadata.bundleFingerprint,
            bundleFingerprint = metadata.bundleFingerprint,
        )
        store.saveRecord(DesktopSessionRecord(bundleSession, "{}", "{}", emptyList(), metadata))
        assertEquals(metadata, store.loadRecord(bundleSession.ingestionId)?.bundle)
        assertEquals(bundleSession, store.findByBundleFingerprint(metadata.bundleFingerprint))
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
