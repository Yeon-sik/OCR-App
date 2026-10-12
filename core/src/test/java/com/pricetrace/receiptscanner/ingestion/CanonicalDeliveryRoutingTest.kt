package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.export.ReceiptV2Json
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CanonicalDeliveryRoutingTest {
    private fun fixture(name: String) = sequenceOf(File("examples", name), File("../examples", name)).first(File::isFile).readText()
    @Test fun nutritionOnlyHasOneDestinationAndReceiptAddsPtAndCashWithoutOptionalCatalogue() {
        val label = YeonsikOcrEnvelopeCodec.decode(fixture("yeonsik-ocr.v2.packaged-product.example.json"), "routing")
        val nutrition = CanonicalProjectionPlanner.deliveryProjections(label)
        assertEquals(setOf(IngestionProjection.FITNESS_NUTRITION), nutrition)
        val receipt = ReceiptV2Json.decode(fixture("receipt.v2.example.json"))
        val purchased = label.copy(receipt = receipt)
        val delivery = CanonicalProjectionPlanner.deliveryProjections(purchased)
        assertEquals(IngestionDestination.entries.toSet(), delivery.map { it.destination() }.toSet())
        assertFalse(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE in delivery)
        assertTrue(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK in delivery)
        assertEquals(setOf(IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.FITNESS_NUTRITION),
            CanonicalProjectionPlanner.dependenciesFor(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK, purchased))
        val receiptOnly = CanonicalProjectionPlanner.deliveryProjections(purchased.copy(nutrition = emptyList()))
        assertEquals(setOf(IngestionDestination.PRICE_TRACE, IngestionDestination.CASH_OS), receiptOnly.map { it.destination() }.toSet())
    }

    @Test fun unsentOldLinkAdoptsRawContractOnlyWithUnchangedDurableApprovalAndCompletedDependencies() = runBlocking {
        val envelope = YeonsikOcrEnvelopeCodec.decode(fixture("yeonsik-ocr.v2.packaged-product.example.json"), "upgrade")
            .copy(receipt = ReceiptV2Json.decode(fixture("receipt.v2.example.json")))
        val store = InMemoryIngestionSessionStore()
        val useCase = CanonicalIngestionUseCase(store)
        val imported = useCase.importJson(YeonsikOcrEnvelopeCodec.encode(envelope), "upgrade") as CanonicalImportResult.Success
        val confirmed = useCase.confirm(imported.session.ingestionId, imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW)
        assertTrue(confirmed.result.toString(), confirmed.result is IngestionStartResult.Success)
        val approved = store.get(imported.session.ingestionId)!!
        val legacy = approved.copy(projections = approved.projections.map { state -> when (state.projection) {
            IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.FITNESS_NUTRITION -> state.copy(status = ProjectionStatus.UPLOADED, remoteId = "saved", attemptCount = 1)
            IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> state.copy(status = ProjectionStatus.BLOCKED,
                lastError = "pricetrace_product_identity_missing", projectionPayloadFingerprint = "old-catalogue-contract")
            else -> state
        } })
        store.save(legacy)
        val recovered = useCase.prepareExistingSession(legacy.ingestionId, confirmed.envelope)!!
        val rawLink = recovered.projections.single { it.projection == IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK }
        assertEquals(ProjectionStatus.PENDING, rawLink.status)
        assertNull(rawLink.lastError)
        assertEquals(legacy.verifiedCanonicalFingerprint, recovered.verifiedCanonicalFingerprint)
        assertEquals(legacy.projections.filter { it.projection != rawLink.projection }, recovered.projections.filter { it.projection != rawLink.projection })
        val attempts = legacy.copy(projections = legacy.projections.map { if (it.projection == rawLink.projection)
            it.copy(attemptCount = 1, idempotencyKey = "accepted-or-attempted-key") else it })
        store.save(attempts)
        assertEquals(attempts.projections.single { it.projection == rawLink.projection },
            useCase.prepareExistingSession(attempts.ingestionId, confirmed.envelope)!!.projections.single { it.projection == rawLink.projection })
        store.save(legacy.copy(verifiedArtifactFingerprints = legacy.verifiedArtifactFingerprints.filterKeys { !it.startsWith("nutrition:") }))
        assertEquals(ProjectionStatus.BLOCKED, useCase.prepareExistingSession(legacy.ingestionId, confirmed.envelope)!!.projections.single { it.projection == rawLink.projection }.status)
    }

    @Test fun completedRawDeliveryRecoversStaleReviewWithoutApprovingOptionalCatalogue() = runBlocking {
        val envelope = YeonsikOcrEnvelopeCodec.decode(fixture("yeonsik-ocr.v2.packaged-product.example.json"), "completed")
            .copy(receipt = ReceiptV2Json.decode(fixture("receipt.v2.example.json")))
        val store = InMemoryIngestionSessionStore()
        val useCase = CanonicalIngestionUseCase(store)
        val imported = useCase.importJson(YeonsikOcrEnvelopeCodec.encode(envelope), "completed") as CanonicalImportResult.Success
        val confirmed = useCase.confirm(imported.session.ingestionId, imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW)
        assertTrue(confirmed.result is IngestionStartResult.Success)
        val approved = store.get(imported.session.ingestionId)!!
        val delivery = CanonicalProjectionPlanner.deliveryProjections(confirmed.envelope)
        val stale = approved.copy(reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            projections = approved.projections.map { state -> when {
                state.projection in delivery -> state.copy(status = ProjectionStatus.UPLOADED,
                    remoteId = "saved-${state.projection}", attemptCount = 1,
                    metadataJson = if (state.projection == IngestionProjection.PRICETRACE_RECEIPT ||
                        state.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                        """{"lines":[{"resolutionStatus":"unresolved_catalog","observationCreated":true,"productId":"product","storeProductId":"store-product","receiptItemId":"item","observationId":"observation"}]}"""
                    else state.metadataJson)
                state.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE -> state.copy(
                    status = ProjectionStatus.BLOCKED, lastError = "catalogue_review_required")
                else -> state
            } })
        store.save(stale)
        val recovered = useCase.prepareExistingSession(stale.ingestionId, confirmed.envelope)!!
        assertEquals(IngestionReviewStatus.READY, recovered.reviewStatus)
        assertEquals(stale.projections, recovered.projections)
        assertEquals(stale.verifiedCanonicalFingerprint, recovered.verifiedCanonicalFingerprint)
        for (unresolved in listOf(
            stale.copy(verifiedCanonicalFingerprint = null),
            stale.copy(verifiedArtifactFingerprints = stale.verifiedArtifactFingerprints.filterKeys { !it.startsWith("nutrition:") }),
            stale.copy(projections = stale.projections.map { if (it.projection == IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK)
                it.copy(status = ProjectionStatus.BLOCKED, lastError = "identity_conflict") else it }),
            stale.copy(projections = stale.projections.map { if (it.projection == IngestionProjection.PRICETRACE_RECEIPT)
                it.copy(metadataJson = """{"merchantResolutionStatus":"needs_user_selection"}""") else it }),
            stale.copy(projections = stale.projections.map { if (it.projection == IngestionProjection.PRICETRACE_RECEIPT)
                it.copy(metadataJson = """{"lines":[{"resolutionStatus":"unresolved_catalog","observationCreated":false}]}""") else it }),
        )) {
            store.save(unresolved)
            assertEquals(IngestionReviewStatus.NEEDS_REVIEW,
                useCase.prepareExistingSession(stale.ingestionId, confirmed.envelope)!!.reviewStatus)
        }
    }
}
