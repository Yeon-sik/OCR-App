package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.CanonicalRevisionArchiveRequest
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveCheckpoint
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.review.CanonicalFieldType
import com.pricetrace.receiptscanner.review.CanonicalReviewEdit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopProjectionCompletionPersistenceTest {
    @Test
    fun `projection completion contract round trips without changing replay identity`() = runBlocking {
        val directory = Files.createTempDirectory("desktop-completion-contract")
        val store = DesktopSessionStore(directory)
        val session = session().copy(
            projections = listOf(
                projection().copy(completionContractVersion = 5, acceptedReceiptFactsFingerprint = "accepted-facts"),
                projection().copy(
                    projection = IngestionProjection.CASHOS_RECEIPT,
                    completionContractVersion = 0,
                ),
            ),
        )
        store.saveRecord(DesktopSessionRecord(session, "raw evidence", "review snapshot", emptyList()))

        val loaded = requireNotNull(store.loadRecord(session.ingestionId))
        assertEquals(session, loaded.session)
        assertEquals(5, loaded.session.projections.first().completionContractVersion)
        assertEquals("accepted-facts", loaded.session.projections.first().acceptedReceiptFactsFingerprint)
        assertEquals("same-import-key", loaded.session.projections.first().idempotencyKey)
        assertEquals("existing-nutrition", loaded.session.projections.first().remoteId)
        assertTrue(Files.readString(directory.resolve("completion-session.json"))
            .contains("\"completion_contract_version\": 5"))
    }

    @Test
    fun `legacy records without completion contract load as unprepared and retain server metadata`() = runBlocking {
        val directory = Files.createTempDirectory("desktop-legacy-completion")
        val store = DesktopSessionStore(directory)
        val original = session()
        store.saveRecord(DesktopSessionRecord(original, "raw evidence", "review snapshot", emptyList()))
        val path = directory.resolve("completion-session.json")
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val legacySession = JsonObject(root.getValue("session").jsonObject.toMutableMap().apply {
            put("projections", JsonArray(getValue("projections").jsonArray.map { element ->
                JsonObject(element.jsonObject.toMutableMap().apply { remove("completion_contract_version") })
            }))
        })
        Files.writeString(path, JsonObject(root.toMutableMap().apply { put("session", legacySession) }).toString())
        assertFalse(Files.readString(path).contains("completion_contract_version"))

        val loaded = requireNotNull(store.loadRecord(original.ingestionId))
        assertEquals(original, loaded.session)
        assertEquals(0, loaded.session.projections.single().completionContractVersion)
        assertEquals("{\"serverImportId\":\"existing-import\"}", loaded.session.projections.single().metadataJson)
        assertEquals(7, loaded.session.projections.single().attemptCount)
    }

    @Test
    fun `saving prepared session preserves pending revision and review snapshot`() = runBlocking {
        val directory = Files.createTempDirectory("desktop-prepared-pending-revision")
        val store = DesktopSessionStore(directory)
        val edit = CanonicalReviewEdit(
            id = "edit-preserved",
            fieldPath = "product_candidates[product-1].product_name",
            previousValue = "원래 이름",
            newValue = "검수한 이름",
            provenanceJson = "{\"source\":\"human-review\"}",
            editedAt = "2026-10-04T00:00:00Z",
            valueType = CanonicalFieldType.TEXT,
        )
        val pending = CanonicalRevisionArchiveRequest(
            canonicalArtifactId = "existing-artifact",
            revisionSeq = 4,
            parentRevisionId = "existing-parent",
            canonicalSha256 = "b".repeat(64),
            canonicalJson = "pending edited snapshot",
            schemaVersion = "yeonsik-ocr.v3",
            mode = "packaged_product",
            validationStatus = "valid",
            validationIssues = emptyList(),
            edits = listOf(edit),
        )
        val original = session()
        val record = DesktopSessionRecord(
            session = original,
            rawJson = "immutable source JSON",
            canonicalJson = "previous archived snapshot",
            evidence = emptyList(),
            bundle = DesktopBundleMetadata(
                sourcePath = "C:\\incoming\\original.yeonsik",
                canonicalSha256 = "a".repeat(64),
                manifestJson = "{\"bundle_version\":\"yeonsik-bundle.v1\"}",
                validationStatus = DesktopBundleValidationStatus.VALID,
                archiveStatus = DesktopEvidenceArchiveStatus.ARCHIVED,
                archiveCheckpoint = EvidenceArchiveCheckpoint(
                    canonicalArtifactId = "existing-artifact",
                    latestRevisionId = "existing-parent",
                    latestRevisionSeq = 3,
                ),
                revisionArchiveStatus = DesktopCanonicalRevisionArchiveStatus.FAILED,
                revisionArchiveError = "retry authentication",
                pendingRevision = pending,
            ),
            reviewEdits = listOf(edit),
        )
        store.saveRecord(record)
        val prepared = original.copy(
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            projections = original.projections.map {
                it.copy(status = ProjectionStatus.PENDING, completionContractVersion = 5)
            },
        )

        store.save(prepared)

        val loaded = requireNotNull(store.loadRecord(original.ingestionId))
        assertEquals(record.copy(session = prepared), loaded)
        assertEquals(pending, loaded.bundle?.pendingRevision)
        assertEquals("immutable source JSON", loaded.rawJson)
        assertEquals("previous archived snapshot", loaded.canonicalJson)
        assertEquals(listOf(edit), loaded.reviewEdits)
    }

    private fun projection() = ProjectionState(
        projection = IngestionProjection.FITNESS_NUTRITION,
        status = ProjectionStatus.UPLOADED,
        idempotencyKey = "same-import-key",
        remoteId = "existing-nutrition",
        attemptCount = 7,
        updatedAt = "2026-09-26T00:00:00Z",
        metadataJson = "{\"serverImportId\":\"existing-import\"}",
        projectionRevisionSeq = 3,
        projectionPayloadFingerprint = "same-payload-fingerprint",
    )

    private fun session() = IngestionSession(
        ingestionId = "completion-session",
        localDocumentId = "existing-document",
        envelopeStorageKey = "existing-document/ingestion/yeonsik-ocr.json",
        canonicalFingerprint = "existing-canonical-fingerprint",
        reviewStatus = IngestionReviewStatus.READY,
        createdAt = "2026-09-26T00:00:00Z",
        updatedAt = "2026-09-26T00:00:00Z",
        projections = listOf(projection()),
        revisionSeq = 3,
        verifiedCanonicalFingerprint = "existing-canonical-fingerprint",
        verifiedAt = "2026-09-26T00:00:00Z",
        importFingerprint = "existing-import-fingerprint",
    )
}
