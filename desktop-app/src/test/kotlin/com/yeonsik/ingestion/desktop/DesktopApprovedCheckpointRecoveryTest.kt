package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual live recovery only after the owner explicitly confirms the saved source facts. */
class DesktopApprovedCheckpointRecoveryTest {
    @Test
    fun approvedExistingReviewResumesPublicationThroughOcrController() = runBlocking {
        val ingestionId = System.getenv("YEONSIK_APPROVED_CHECKPOINT_ID").orEmpty()
        assumeTrue("Requires explicit owner review approval and YEONSIK_APPROVED_CHECKPOINT_ID", ingestionId.isNotBlank())
        val store = DesktopSessionStore()
        val original = requireNotNull(store.loadRecord(ingestionId))
        val previous = original.session.projections.associateBy { it.projection }
        val controller = DesktopIngestionController(store)
        controller.load(ingestionId)
        assertNull("OCR load: ${controller.state.value.error}", controller.state.value.error)
        controller.verify(VerificationBasis.SOURCE_EVIDENCE)
        assertNull("OCR approved review: ${controller.state.value.error}", controller.state.value.error)
        controller.submit(setOf(IngestionProjection.FITNESS_NUTRITION))
        assertNull("OCR send: ${controller.state.value.error}", controller.state.value.error)
        val completed = requireNotNull(controller.state.value.session)
        assertEquals(IngestionReviewStatus.READY, completed.reviewStatus)
        val next = completed.projections.associateBy { it.projection }
        assertEquals("Nutrition recovery: ${next[IngestionProjection.FITNESS_NUTRITION]?.lastError}",
            ProjectionStatus.UPLOADED, next[IngestionProjection.FITNESS_NUTRITION]?.status)
        assertEquals(1, next[IngestionProjection.FITNESS_NUTRITION]?.completionContractVersion)
        assertEquals(previous[IngestionProjection.FITNESS_NUTRITION]?.idempotencyKey,
            next[IngestionProjection.FITNESS_NUTRITION]?.idempotencyKey)
        assertEquals(previous[IngestionProjection.PRICETRACE_RECEIPT]?.remoteId,
            next[IngestionProjection.PRICETRACE_RECEIPT]?.remoteId)
        assertEquals(previous[IngestionProjection.CASHOS_RECEIPT]?.idempotencyKey,
            next[IngestionProjection.CASHOS_RECEIPT]?.idempotencyKey)
        assertEquals(ProjectionStatus.UPLOADED, next[IngestionProjection.CASHOS_RECEIPT]?.status)
        // A second send is an exact completed replay and must leave durable identities unchanged.
        controller.submit(setOf(IngestionProjection.FITNESS_NUTRITION))
        assertNull(controller.state.value.error)
        val replay = requireNotNull(controller.state.value.session)
        assertEquals(next[IngestionProjection.FITNESS_NUTRITION], replay.projections.single {
            it.projection == IngestionProjection.FITNESS_NUTRITION
        })
    }
}
