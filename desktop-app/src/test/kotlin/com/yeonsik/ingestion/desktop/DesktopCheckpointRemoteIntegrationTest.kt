package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** Opt-in owner-authenticated HTTP read. The user's session is copied; no ingestion/publication is sent. */
class DesktopCheckpointRemoteIntegrationTest {
    @Test
    fun savedLegacyCheckpointRecoversServerResolutionWithoutReingestion() = runBlocking {
        val selected = System.getenv("YEONSIK_CHECKPOINT_INTEGRATION_RECORD").orEmpty()
        assumeTrue("Set YEONSIK_CHECKPOINT_INTEGRATION_RECORD for owner HTTP validation", selected.isNotBlank())
        val source = Paths.get(selected)
        val sourceSnapshot = Files.readString(source)
        val directory = Files.createTempDirectory("ocr-checkpoint-owner-http")
        Files.copy(source, directory.resolve(source.fileName))
        val store = DesktopSessionStore(directory)
        val original = requireNotNull(store.latestRecord())
        val envelope = YeonsikOcrEnvelopeCodec.decode(original.canonicalJson,
            original.session.localDocumentId, preservePersistedVerification = true)
        val bundle = DesktopProjectionBundle()
        assertTrue(bundle.ensureAuthenticated(setOf(IngestionProjection.PRICETRACE_RECEIPT), envelope).isEmpty())
        val orchestrator = IngestionOrchestrator(store = store, submitters = bundle.submitters)
        val prepared = requireNotNull(orchestrator.prepareExistingSession(
            original.session.ingestionId, envelope, restoredSnapshot = true))
        val before = original.session.projections.associateBy { it.projection }
        val after = prepared.projections.associateBy { it.projection }
        assertEquals(before[IngestionProjection.CASHOS_RECEIPT], after[IngestionProjection.CASHOS_RECEIPT])
        assertEquals(before[IngestionProjection.FITNESS_NUTRITION]?.idempotencyKey,
            after[IngestionProjection.FITNESS_NUTRITION]?.idempotencyKey)
        assertEquals(ProjectionStatus.PENDING, after[IngestionProjection.FITNESS_NUTRITION]?.status)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, prepared.reviewStatus)
        val pt = requireNotNull(after[IngestionProjection.PRICETRACE_RECEIPT])
        assertNull(pt.lastError)
        assertNotNull(PriceTraceIdentityJson.ocrResolution(pt.metadataJson)?.resolutionId)
        val loaded = requireNotNull(store.loadRecord(prepared.ingestionId))
        assertEquals(original.canonicalJson, loaded.canonicalJson)
        assertEquals(original.bundle, loaded.bundle)
        assertEquals(original.reviewEdits, loaded.reviewEdits)
        assertEquals(sourceSnapshot, Files.readString(source))
    }
}
