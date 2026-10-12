package com.pricetrace.receiptscanner.ingestion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanonicalVerificationPersistenceTest {
    @Test
    fun confirmedV1AndV2SnapshotsRetainApprovalThroughEncodeDecodeAndRestore() = runBlocking {
        for (name in listOf("yeonsik-ocr.packaged-product.example.json", "yeonsik-ocr.v2.packaged-product.text-lookup.example.json")) {
            val raw = fixture(name)
            val documentId = "verified-$name"
            val decoded = YeonsikOcrEnvelopeCodec.decode(raw, documentId)
            val evidence = decoded.source.sourceFiles.map { LocalEvidence(it.id, it.type, true) }
            val store = InMemoryIngestionSessionStore()
            val requests = mutableListOf<ProjectionRequest>()
            val submitter = object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    requests += request
                    return ProjectionSubmission.Success("test-remote")
                }
            }
            val useCase = CanonicalIngestionUseCase(
                store, submitters = mapOf(
                    IngestionProjection.FITNESS_NUTRITION to submitter,
                    IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to submitter,
                ), now = { "2026-10-12T00:00:00Z" },
            )
            val imported = useCase.importJson(raw, documentId, evidence = evidence) as CanonicalImportResult.Success
            val confirmation = useCase.confirm(imported.session.ingestionId, imported.envelope, evidence)
            assertTrue(confirmation.result.toString(), confirmation.result is IngestionStartResult.Success)
            val confirmed = requireNotNull(store.get(imported.session.ingestionId))
            assertEquals(confirmed.canonicalFingerprint, confirmed.verifiedCanonicalFingerprint)

            val persisted = YeonsikOcrEnvelopeCodec.encodePersisted(confirmation.envelope)
            val restored = YeonsikOcrEnvelopeCodec.decode(persisted, documentId, preservePersistedVerification = true)
            assertEquals(name, IngestionReviewStatus.READY, restored.review.status)
            assertEquals(name, YeonsikOcrEnvelopeCodec.canonicalize(confirmation.envelope), YeonsikOcrEnvelopeCodec.canonicalize(restored))
            val restoredSession = requireNotNull(useCase.prepareExistingSession(confirmed.ingestionId, restored, restoredSnapshot = true))
            assertEquals(confirmed.verifiedCanonicalFingerprint, restoredSession.verifiedCanonicalFingerprint)
            assertEquals(confirmed.verifiedArtifactFingerprints, restoredSession.verifiedArtifactFingerprints)
            val selected = if (restored.productCandidates.isEmpty()) IngestionProjection.FITNESS_NUTRITION
                else IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE
            val projections = useCase.submitSelected(confirmed.ingestionId, restored, setOf(selected))
            assertEquals(name, ProjectionStatus.UPLOADED, projections.single { it.projection == selected }.status)
            assertEquals(1, requests.size)
            useCase.retrySelected(confirmed.ingestionId, restored, setOf(selected))
            assertEquals(1, requests.size)

            // The same bytes received as an external import never convey local approval.
            val external = YeonsikOcrEnvelopeCodec.decode(persisted, documentId)
            assertEquals(IngestionReviewStatus.NEEDS_REVIEW, external.review.status)
            external.nutrition.filterIsInstance<IngestionNutrition.ProductLabel>().forEach {
                assertTrue(it.draft.status != com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus.USER_VERIFIED)
            }
        }
    }

    private fun fixture(name: String): String = sequenceOf(File("examples", name), File("../examples", name))
        .first(File::isFile).readText()
}
