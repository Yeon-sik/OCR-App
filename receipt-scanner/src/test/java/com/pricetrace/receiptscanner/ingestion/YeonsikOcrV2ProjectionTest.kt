package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YeonsikOcrV2ProjectionTest {
    @Test
    fun `product resolution and nutrition creation unblock retryable catalog link`() = runBlocking {
        val imported = ExternalJsonImporter().import(
            readExample("yeonsik-ocr.v2.packaged-product.example.json"),
            "local-v2-projection",
        ) as ExternalJsonImportOutcome.Success
        val sourceEnvelope = (imported.result.draft as CanonicalDraft.Envelope).value
        val envelope = sourceEnvelope.copy(
            nutrition = sourceEnvelope.nutrition.map { nutrition ->
                when (nutrition) {
                    is IngestionNutrition.ProductLabel -> nutrition.copy(
                        draft = nutrition.draft.copy(status = NutritionDraftStatus.USER_VERIFIED),
                    )
                    else -> nutrition
                }
            },
            consumption = sourceEnvelope.consumption.map { consumption ->
                consumption.copy(status = ConsumptionVerificationStatus.USER_VERIFIED)
            },
        )
        val store = InMemoryIngestionSessionStore()
        val requests = mutableListOf<ProjectionRequest>()
        val submitters = mapOf(
            IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to submitter { request ->
                requests += request
                ProjectionSubmission.Success(
                    remoteId = "candidate-1",
                    metadataJson = """{"products":[{"clientKey":"product-1","catalogProductId":"catalog-1","productRevision":null}]}""",
                )
            },
            IngestionProjection.FITNESS_NUTRITION to submitter { request ->
                requests += request
                ProjectionSubmission.Success(
                    remoteId = "food-1",
                    metadataJson = """[{"nutrition_food_id":"food-1"}]""",
                )
            },
            IngestionProjection.FITNESS_MEAL to submitter { request ->
                requests += request
                ProjectionSubmission.Success("meal-1")
            },
            IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK to submitter { request ->
                requests += request
                ProjectionSubmission.Success("link-1")
            },
        )
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = submitters,
            now = { "2026-09-06T00:00:00Z" },
        )
        val evidence = listOf(
            LocalEvidence("product-photo-1", SourceAttachmentType.PRODUCT_PHOTO, true),
            LocalEvidence("nutrition-label-1", SourceAttachmentType.NUTRITION_LABEL, true),
            LocalEvidence("meal-photo-1", SourceAttachmentType.FOOD_PHOTO, true),
        )

        val started = orchestrator.start(
            ingestionId = "ingestion-v2-projection",
            localDocumentId = "local-v2-projection",
            envelope = envelope,
            evidence = evidence,
        )
        assertTrue(started is IngestionStartResult.Success)
        assertTrue(
            orchestrator.markNutritionVerified("ingestion-v2-projection", envelope, evidence)
                is IngestionStartResult.Success,
        )
        assertTrue(
            orchestrator.markProductCandidatesVerified("ingestion-v2-projection", envelope, evidence)
                is IngestionStartResult.Success,
        )
        assertTrue(
            orchestrator.markConsumptionVerified("ingestion-v2-projection", envelope, evidence)
                is IngestionStartResult.Success,
        )

        val blockedLink = orchestrator.submitProjection(
            "ingestion-v2-projection",
            IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
            envelope,
        )
        assertEquals(ProjectionStatus.BLOCKED, blockedLink.status)
        assertEquals(
            "dependency_pending:pricetrace_product_candidate,fitness_nutrition",
            blockedLink.lastError,
        )

        val finalStates = orchestrator.submitAllReadyProjections("ingestion-v2-projection", envelope)
        assertEquals(
            setOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.FITNESS_MEAL,
                IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
            ),
            finalStates.filter { it.status == ProjectionStatus.UPLOADED }.map { it.projection }.toSet(),
        )
        val linkRequest = requests.last { it.projection == IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK }
        assertEquals("catalog-1", linkRequest.resolvedIdentity?.productCandidates?.get("product-1")?.catalogProductId)
        assertEquals(
            """[{"nutrition_food_id":"food-1"}]""",
            linkRequest.dependencyMetadataJson[IngestionProjection.FITNESS_NUTRITION],
        )
    }

    private fun submitter(
        block: suspend (ProjectionRequest) -> ProjectionSubmission,
    ): IngestionProjectionSubmitter = object : IngestionProjectionSubmitter {
        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission = block(request)
    }

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }
}
