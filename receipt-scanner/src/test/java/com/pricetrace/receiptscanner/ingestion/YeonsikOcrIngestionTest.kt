package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportErrorCode
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YeonsikOcrIngestionTest {
    @Test
    fun `merchant envelope imports without receipt`() {
        val result = import(merchantJson(), "local-merchant")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        assertEquals(IngestionMode.MERCHANT, envelope.mode)
        assertEquals("Test Mart", envelope.merchantCandidate?.name)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, envelope.review.status)
    }

    @Test
    fun `restaurant envelope keeps receipt links and food service roles separate`() {
        val result = import(restaurantJson(), "local-restaurant")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        assertEquals(3, envelope.receipt?.lineItems?.size)
        assertEquals(
            listOf(FoodServiceRole.MAIN, FoodServiceRole.OPTION, FoodServiceRole.SIDE),
            envelope.receipt?.lineItems?.map { it.foodService?.role },
        )
        assertEquals("line-1", envelope.receipt?.lineItems?.get(1)?.foodService?.appliesToLineId)
        assertEquals(3, envelope.nutrition.size)
        assertTrue(envelope.nutrition.all { it is IngestionNutrition.RestaurantEstimate })
        assertEquals(setOf("line-1", "line-2", "line-3"), envelope.links.map { it.receiptLineId }.toSet())
    }

    @Test
    fun `restaurant receipt-only envelope is accepted`() {
        val value = header("restaurant")
            .replace(
                "\"merchant_candidate\":null",
                "\"merchant_candidate\":{\"name\":\"Test Restaurant\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
            )
            .replace("\"receipt\":null", "\"receipt\":$receiptJson")
        val result = import(value, "local-restaurant-only")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        assertEquals(OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT, result.workflowType)
        assertEquals(0, envelope.nutrition.size)
        assertTrue(envelope.receipt != null)
    }

    @Test
    fun `packaged product envelope delegates label payload to legacy contract`() {
        val result = import(packagedJson(), "local-product")
        val envelope = (result.draft as CanonicalDraft.Envelope).value
        val label = envelope.nutrition.single() as IngestionNutrition.ProductLabel
        assertEquals("Test cereal", label.draft.productName)
        assertEquals(IngestionMode.PACKAGED_PRODUCT, envelope.mode)
    }

    @Test
    fun packagedProductMayIncludeOptionalRetailReceiptAndInferAllTargets() = runBlocking {
        val retailReceipt = receiptJson
            .replace("\"business_kind\":\"food_service\"", "\"business_kind\":\"retail\"")
            .replace(",\"food_service\":{\"role\":\"main\",\"applies_to_line_id\":null}", "")
            .replace(",\"food_service\":{\"role\":\"option\",\"applies_to_line_id\":\"line-1\"}", "")
            .replace(",\"food_service\":{\"role\":\"side\",\"applies_to_line_id\":null}", "")
        val imported = import(
            packagedJson().replace("\"receipt\":null", "\"receipt\":" + retailReceipt),
            "local-product-with-receipt",
        )
        val envelope = (imported.draft as CanonicalDraft.Envelope).value

        assertEquals(IngestionMode.PACKAGED_PRODUCT, envelope.mode)
        assertEquals(OcrWorkflowType.PRICE_TRACE_RECEIPT, imported.workflowType)
        assertTrue(envelope.receipt != null)
        assertEquals(1, envelope.nutrition.size)

        val started = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = emptyMap(),
            now = { "2026-08-27T00:00:00Z" },
        ).start(
            ingestionId = "ingestion-product-with-receipt",
            localDocumentId = "local-product-with-receipt",
            envelope = envelope,
            evidence = listOf(LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true), LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true)),
        ) as IngestionStartResult.Success

        assertEquals(
            setOf(
                IngestionProjection.PRICETRACE_RECEIPT,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.CASHOS_RECEIPT,
            ),
            started.session.projections
                .filter { it.status != ProjectionStatus.DISABLED }
                .map { it.projection }
                .toSet(),
        )
    }
    @Test
    fun `external envelope trust metadata is ignored and fingerprint is local-id independent`() {
        val first = import(merchantJson(extra = "\"user_verified\":true,\"owner_id\":\"attacker\","), "local-a")
        val second = import(merchantJson(), "local-b")
        assertEquals(first.importFingerprint, second.importFingerprint)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, (first.draft as CanonicalDraft.Envelope).value.review.status)
    }

    @Test
    fun cashosInstitutionHintRoundTripsAndLegacyAccountHintIsRejected() {
        val value = merchantJson()
            .replace("\"cashos\":{}", "\"cashos\":{\"institution_hint\":\"bank\"}")
        val imported = import(value, "local-hint")
        val envelope = (imported.draft as CanonicalDraft.Envelope).value

        assertEquals("bank", envelope.classificationHints["cashos.institution_hint"])

        val restored = YeonsikOcrEnvelopeJson.decode(
            YeonsikOcrEnvelopeJson.encode(envelope),
            "local-hint",
        )
        assertEquals("bank", restored.classificationHints["cashos.institution_hint"])
        val legacyInternal = YeonsikOcrEnvelopeJson.encode(
            envelope.copy(classificationHints = mapOf("cashos.account_hint" to "legacy")),
        )
        assertFalse(legacyInternal.contains("account_hint"))

        val legacy = ExternalJsonImporter().import(
            value.replace("\"institution_hint\"", "\"account_hint\""),
            "local-hint-legacy",
        )
        assertTrue(legacy is ExternalJsonImportOutcome.Failure)
    }
    @Test
    fun `restaurant evidence requires readable receipt and food photo`() {
        val envelope = (import(restaurantJson(), "local-evidence").draft as CanonicalDraft.Envelope).value
        assertFalse(IngestionEvidenceGate.evaluate(envelope, emptyList()).isAllowed)
        assertFalse(IngestionEvidenceGate.evaluate(envelope, listOf(LocalEvidence("r", SourceAttachmentType.RECEIPT, true))).isAllowed)
        assertTrue(IngestionEvidenceGate.evaluate(envelope, listOf(
            LocalEvidence("r", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("f", SourceAttachmentType.FOOD_PHOTO, true),
        )).isAllowed)
    }

    @Test
    fun `projection failure is isolated and retry preserves successful projection`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val calls = mutableMapOf<IngestionProjection, Int>()
        val submitters = IngestionProjection.entries.associateWith { projection ->
            object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    calls[projection] = (calls[projection] ?: 0) + 1
                    return if (projection == IngestionProjection.FITNESS_NUTRITION && calls[projection] == 1) {
                        ProjectionSubmission.Failure("fitness unavailable", retryable = true)
                    } else ProjectionSubmission.Success("remote-${projection.wireValue}")
                }
            }
        }
        val resolver = object : IngestionIdentityResolver {
            override suspend fun resolve(projection: IngestionProjection, envelope: YeonsikOcrEnvelope) =
                IdentityResolution(IdentityResolutionStatus.RESOLVED, mapOf("id" to "live-${projection.wireValue}"))
        }
        val orchestrator = IngestionOrchestrator(store, resolver, submitters, now = { "2026-08-27T00:00:00Z" })
        val envelope = (import(restaurantJson(), "local-orchestrator").draft as CanonicalDraft.Envelope).value
        val started = orchestrator.start("ingestion-1", "local-orchestrator", envelope, listOf(
            LocalEvidence("r", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("f", SourceAttachmentType.FOOD_PHOTO, true),
        )) as IngestionStartResult.Success
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, started.session.reviewStatus)
        orchestrator.markUserVerified("ingestion-1", envelope, started.session.attachments)
        orchestrator.markNutritionVerified("ingestion-1", envelope, started.session.attachments)
        val price = orchestrator.submitProjection("ingestion-1", IngestionProjection.PRICETRACE_RECEIPT, envelope)
        val fitness = orchestrator.submitProjection("ingestion-1", IngestionProjection.FITNESS_NUTRITION, envelope)
        assertEquals(ProjectionStatus.UPLOADED, price.status)
        assertEquals(ProjectionStatus.FAILED, fitness.status)
        val retried = orchestrator.retryFailed("ingestion-1", envelope)
        assertEquals(ProjectionStatus.UPLOADED, retried.single().status)
        assertEquals(1, calls[IngestionProjection.PRICETRACE_RECEIPT])
        assertEquals(2, calls[IngestionProjection.FITNESS_NUTRITION])
        assertEquals(fitness.idempotencyKey, retried.single().idempotencyKey)
    }

    @Test
    fun `external decode downgrades but local persisted decode preserves verified revision`() {
        val imported = import(restaurantJson(), "local-persisted")
        val envelope = (imported.draft as CanonicalDraft.Envelope).value
        val verifiedReceipt = requireNotNull(envelope.receipt).copy(
            document = requireNotNull(envelope.receipt).document.copy(
                status = com.pricetrace.receiptscanner.domain.ReceiptStatus.FINAL,
                source = requireNotNull(envelope.receipt).document.source.copy(
                    transcriptionStatus = com.pricetrace.receiptscanner.domain.TranscriptionStatus.USER_VERIFIED,
                ),
            ),
        )
        val encoded = YeonsikOcrEnvelopeJson.encode(envelope.copy(receipt = verifiedReceipt))

        val externalRead = YeonsikOcrEnvelopeJson.decode(encoded, "local-persisted")
        val localRead = YeonsikOcrEnvelopeJson.decode(
            encoded,
            "local-persisted",
            preservePersistedVerification = true,
        )

        assertEquals(
            com.pricetrace.receiptscanner.domain.TranscriptionStatus.PARSED,
            externalRead.receipt?.document?.source?.transcriptionStatus,
        )
        assertEquals(
            com.pricetrace.receiptscanner.domain.TranscriptionStatus.USER_VERIFIED,
            localRead.receipt?.document?.source?.transcriptionStatus,
        )
        assertEquals(
            com.pricetrace.receiptscanner.domain.ReceiptStatus.FINAL,
            localRead.receipt?.document?.status,
        )
    }
    @Test
    fun `same canonical bundle is duplicate regardless of local document id`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(
            store = store,
            identityResolver = object : IngestionIdentityResolver {
                override suspend fun resolve(projection: IngestionProjection, envelope: YeonsikOcrEnvelope) =
                    IdentityResolution(IdentityResolutionStatus.RESOLVED)
            },
            submitters = emptyMap(),
            now = { "2026-08-27T00:00:00Z" },
        )
        val envelope = (import(restaurantJson(), "local-first").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val first = orchestrator.start("ingestion-first", "local-first", envelope, evidence) as IngestionStartResult.Success
        val duplicate = orchestrator.start("ingestion-second", "local-second", envelope, evidence) as IngestionStartResult.Duplicate

        assertEquals(first.session.ingestionId, duplicate.session.ingestionId)
        assertEquals(first.session.canonicalFingerprint, duplicate.session.canonicalFingerprint)
    }

    @Test
    fun `projection target contradicting supplied artifact is rejected`() {
        val value = packagedJson().replace(
            "\"projection_targets\":[]",
            "\"projection_targets\":[\"cashos_receipt\"]",
        )
        val outcome = ExternalJsonImporter().import(value, "local-contradiction")
        assertTrue(outcome is ExternalJsonImportOutcome.Failure)
        assertEquals(
            ExternalJsonImportErrorCode.INVALID_CANONICAL_JSON,
            (outcome as ExternalJsonImportOutcome.Failure).error.code,
        )
    }

    @Test
    fun incompletePriceTraceObservationDoesNotMarkObservationUploaded() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        var receiptCalls = 0
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                        receiptCalls += 1
                        return ProjectionSubmission.Success("remote-receipt")
                    }
                },
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success(
                            remoteId = "remote-receipt",
                            alsoUploaded = setOf(IngestionProjection.PRICETRACE_RECEIPT),
                            primaryUploaded = false,
                            primaryPendingReason = "price_observation_incomplete",
                        )
                },
            ),
            now = { "2026-08-27T00:00:00Z" },
        )
        val envelope = (import(restaurantJson(), "local-observation-state").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        orchestrator.start(
            "ingestion-observation-state",
            "local-observation-state",
            envelope,
            evidence,
        )
        orchestrator.markUserVerified("ingestion-observation-state", envelope, evidence)

        val receiptState = orchestrator.submitProjection(
            "ingestion-observation-state",
            IngestionProjection.PRICETRACE_RECEIPT,
            envelope,
        )
        val receiptKey = receiptState.idempotencyKey
        val observationState = orchestrator.submitProjection(
            "ingestion-observation-state",
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            envelope,
        )

        assertEquals(ProjectionStatus.UPLOADED, receiptState.status)
        assertEquals(ProjectionStatus.BLOCKED, observationState.status)
        assertEquals("price_observation_incomplete", observationState.lastError)
        assertEquals(1, receiptCalls)
        assertEquals(
            receiptKey,
            store.get("ingestion-observation-state")
                ?.projections
                ?.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }
                ?.idempotencyKey,
        )
    }
    @Test
    fun `canonical revision requires re verification before submit`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val requests = mutableListOf<ProjectionRequest>()
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                        requests += request
                        return ProjectionSubmission.Success("remote-receipt")
                    }
                },
            ),
            now = { "2026-08-27T00:00:00Z" },
        )
        val envelope = (import(restaurantJson(), "local-revision").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val started = orchestrator.start("ingestion-revision", "local-revision", envelope, evidence) as IngestionStartResult.Success
        orchestrator.markUserVerified("ingestion-revision", envelope, evidence)
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection("ingestion-revision", IngestionProjection.PRICETRACE_RECEIPT, envelope).status,
        )

        val revisedEnvelope = envelope.copy(
            receipt = requireNotNull(envelope.receipt).copy(
                merchant = requireNotNull(envelope.receipt).merchant.copy(name = "Updated Restaurant"),
            ),
        )
        val revised = orchestrator.reviseCanonicalDraft("ingestion-revision", revisedEnvelope) as IngestionStartResult.Success
        assertEquals(2L, revised.session.revisionSeq)
        assertEquals(null, revised.session.verifiedCanonicalFingerprint)
        assertEquals(ProjectionStatus.PENDING, revised.session.projections.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.status)
        assertEquals(null, revised.session.projections.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.idempotencyKey)

        val blocked = orchestrator.submitProjection("ingestion-revision", IngestionProjection.PRICETRACE_RECEIPT, revisedEnvelope)
        assertEquals(ProjectionStatus.BLOCKED, blocked.status)
        assertEquals("projection_not_reverified", blocked.lastError)
        assertEquals(1, requests.size)

        orchestrator.markUserVerified("ingestion-revision", revisedEnvelope, evidence)
        val retried = orchestrator.submitProjection("ingestion-revision", IngestionProjection.PRICETRACE_RECEIPT, revisedEnvelope)
        assertEquals(ProjectionStatus.UPLOADED, retried.status)
        assertEquals(2, requests.size)
        assertFalse(requests[0].idempotencyKey == requests[1].idempotencyKey)
        assertEquals(2L, requests[1].revisionSeq)
        assertEquals(revised.session.canonicalFingerprint, requests[1].canonicalFingerprint)
        assertEquals(started.session.ingestionId, revised.session.ingestionId)
    }
    @Test
    fun `packaged receipt verification only needs receipt evidence`() = runBlocking {
        val envelope = packagedReceiptEnvelope("local-packaged-evidence")
        val allEvidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true),
        )
        val receiptEvidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, false),
        )
        assertFalse(IngestionEvidenceGate.evaluate(envelope, receiptEvidence).isAllowed)
        assertTrue(
            IngestionEvidenceGate.evaluate(
                envelope,
                receiptEvidence,
                artifactKeys = setOf(IngestionArtifactKeys.RECEIPT, IngestionArtifactKeys.CASHOS_HINTS),
            ).isAllowed,
        )

        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("receipt")
                },
                IngestionProjection.CASHOS_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("cashos")
                },
            ),
        )
        orchestrator.start(
            "ingestion-packaged-evidence",
            "local-packaged-evidence",
            envelope,
            allEvidence,
        )
        assertTrue(
            orchestrator.markReceiptVerified(
                "ingestion-packaged-evidence",
                envelope,
                receiptEvidence,
            ) is IngestionStartResult.Success,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection(
                "ingestion-packaged-evidence",
                IngestionProjection.PRICETRACE_RECEIPT,
                envelope,
            ).status,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection(
                "ingestion-packaged-evidence",
                IngestionProjection.CASHOS_RECEIPT,
                envelope,
            ).status,
        )
        assertEquals(
            ProjectionStatus.BLOCKED,
            orchestrator.submitProjection(
                "ingestion-packaged-evidence",
                IngestionProjection.FITNESS_NUTRITION,
                envelope,
            ).status,
        )
    }

    @Test
    fun `packaged product label verification only needs label evidence`() = runBlocking {
        val parsed = packagedReceiptEnvelope("local-packaged-label-evidence")
        val label = parsed.nutrition.single() as IngestionNutrition.ProductLabel
        val envelope = parsed.copy(
            nutrition = listOf(label.copy(draft = label.draft.asUserVerified("2026-08-30T10:00:00Z"))),
        )
        val allEvidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true),
        )
        val labelEvidence = listOf(
            LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true),
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, false),
        )
        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("fitness")
                },
            ),
        )
        orchestrator.start("ingestion-packaged-label-evidence", "local-packaged-label-evidence", envelope, allEvidence)
        assertTrue(
            orchestrator.markNutritionVerified(
                "ingestion-packaged-label-evidence",
                envelope,
                labelEvidence,
            ) is IngestionStartResult.Success,
        )
        assertEquals(
            ProjectionStatus.BLOCKED,
            orchestrator.submitProjection(
                "ingestion-packaged-label-evidence",
                IngestionProjection.FITNESS_NUTRITION,
                envelope,
            ).status,
        )
        assertEquals(
            "dependency_pending:pricetrace_receipt",
            orchestrator.submitProjection(
                "ingestion-packaged-label-evidence",
                IngestionProjection.FITNESS_NUTRITION,
                envelope,
            ).lastError,
        )
        assertEquals(
            ProjectionStatus.BLOCKED,
            orchestrator.submitProjection(
                "ingestion-packaged-label-evidence",
                IngestionProjection.PRICETRACE_RECEIPT,
                envelope,
            ).status,
        )
    }

    @Test
    fun `restaurant receipt verification only needs receipt evidence`() = runBlocking {
        val envelope = (import(restaurantJson(), "local-restaurant-receipt-evidence").draft as CanonicalDraft.Envelope).value
        val allEvidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val receiptEvidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, false),
        )
        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("receipt")
                },
                IngestionProjection.CASHOS_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("cashos")
                },
            ),
        )
        orchestrator.start("ingestion-restaurant-receipt-evidence", "local-restaurant-receipt-evidence", envelope, allEvidence)
        assertTrue(
            orchestrator.markReceiptVerified(
                "ingestion-restaurant-receipt-evidence",
                envelope,
                receiptEvidence,
            ) is IngestionStartResult.Success,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection(
                "ingestion-restaurant-receipt-evidence",
                IngestionProjection.PRICETRACE_RECEIPT,
                envelope,
            ).status,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection(
                "ingestion-restaurant-receipt-evidence",
                IngestionProjection.CASHOS_RECEIPT,
                envelope,
            ).status,
        )
        assertEquals(
            ProjectionStatus.BLOCKED,
            orchestrator.submitProjection(
                "ingestion-restaurant-receipt-evidence",
                IngestionProjection.FITNESS_NUTRITION,
                envelope,
            ).status,
        )
    }

    @Test
    fun `receipt verification does not unlock restaurant nutrition until nutrition is reviewed`() = runBlocking {
        val envelope = (import(restaurantJson(), "local-artifact-separation").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val calls = mutableMapOf<IngestionProjection, Int>()
        val submitters = mapOf(
            IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    calls[request.projection] = (calls[request.projection] ?: 0) + 1
                    return ProjectionSubmission.Success("receipt")
                }
            },
            IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    calls[request.projection] = (calls[request.projection] ?: 0) + 1
                    return ProjectionSubmission.Success("food")
                }
            },
        )
        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = submitters,
        )
        orchestrator.start("ingestion-artifact-separation", "local-artifact-separation", envelope, evidence)
        orchestrator.markReceiptVerified("ingestion-artifact-separation", envelope, evidence)

        val blockedFitness = orchestrator.submitProjection(
            "ingestion-artifact-separation",
            IngestionProjection.FITNESS_NUTRITION,
            envelope,
        )
        assertEquals(ProjectionStatus.BLOCKED, blockedFitness.status)
        assertEquals("projection_not_reverified", blockedFitness.lastError)
        assertEquals(null, calls[IngestionProjection.FITNESS_NUTRITION])

        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection(
                "ingestion-artifact-separation",
                IngestionProjection.PRICETRACE_RECEIPT,
                envelope,
            ).status,
        )

        orchestrator.markNutritionVerified("ingestion-artifact-separation", envelope, evidence)
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection(
                "ingestion-artifact-separation",
                IngestionProjection.FITNESS_NUTRITION,
                envelope,
            ).status,
        )
        assertEquals(1, calls[IngestionProjection.PRICETRACE_RECEIPT])
        assertEquals(1, calls[IngestionProjection.FITNESS_NUTRITION])
    }

    @Test
    fun `receipt and product label must be reviewed independently before both projections`() = runBlocking {
        val parsed = packagedReceiptEnvelope("local-packaged-review")
        val label = parsed.nutrition.single() as IngestionNutrition.ProductLabel
        val envelope = parsed.copy(
            nutrition = listOf(label.copy(draft = label.draft.asUserVerified("2026-08-30T10:00:00Z"))),
        )
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true),
        )
        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission = ProjectionSubmission.Success("receipt")
                },
                IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission = ProjectionSubmission.Success("food")
                },
            ),
        )
        orchestrator.start("ingestion-packaged-review", "local-packaged-review", envelope, evidence)
        orchestrator.markReceiptVerified("ingestion-packaged-review", envelope, evidence)
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection("ingestion-packaged-review", IngestionProjection.PRICETRACE_RECEIPT, envelope).status,
        )
        assertEquals(
            ProjectionStatus.BLOCKED,
            orchestrator.submitProjection("ingestion-packaged-review", IngestionProjection.FITNESS_NUTRITION, envelope).status,
        )

        orchestrator.markNutritionVerified("ingestion-packaged-review", envelope, evidence)
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection("ingestion-packaged-review", IngestionProjection.FITNESS_NUTRITION, envelope).status,
        )
    }
    @Test
    fun `changing nutrition invalidates only fitness projection verification`() = runBlocking {
        val parsed = packagedReceiptEnvelope("local-selective-revision")
        val parsedLabel = parsed.nutrition.single() as IngestionNutrition.ProductLabel
        val envelope = parsed.copy(
            nutrition = listOf(parsedLabel.copy(draft = parsedLabel.draft.asUserVerified("2026-08-30T10:00:00Z"))),
        )
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true),
        )
        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission = ProjectionSubmission.Success("receipt")
                },
                IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission = ProjectionSubmission.Success("food")
                },
            ),
        )
        orchestrator.start("ingestion-selective-revision", "local-selective-revision", envelope, evidence)
        orchestrator.markReceiptVerified("ingestion-selective-revision", envelope, evidence)
        orchestrator.markNutritionVerified("ingestion-selective-revision", envelope, evidence)
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection("ingestion-selective-revision", IngestionProjection.PRICETRACE_RECEIPT, envelope).status,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            orchestrator.submitProjection("ingestion-selective-revision", IngestionProjection.FITNESS_NUTRITION, envelope).status,
        )

        val changed = envelope.copy(
            nutrition = listOf(
                parsedLabel.copy(
                    draft = parsedLabel.draft.asUserVerified("2026-08-30T10:00:00Z")
                        .withNutrient(NutritionField.CALORIES_KCAL, 381.0),
                ),
            ),
        )
        val revised = orchestrator.reviseCanonicalDraft("ingestion-selective-revision", changed) as IngestionStartResult.Success
        assertEquals(
            ProjectionStatus.UPLOADED,
            revised.session.projections.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.status,
        )
        assertEquals(
            ProjectionStatus.PENDING,
            revised.session.projections.first { it.projection == IngestionProjection.FITNESS_NUTRITION }.status,
        )
        assertTrue(revised.session.verifiedArtifactFingerprints.containsKey(IngestionArtifactKeys.RECEIPT))
        assertFalse(revised.session.verifiedArtifactFingerprints.keys.any { it.startsWith("nutrition:") })
    }

    @Test
    fun cashosRetryKeepsProjectionIdentityAcrossNutritionOnlyEditAndRotatesOnReceiptEdit() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val requests = mutableListOf<ProjectionRequest>()
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("price-trace")
                },
                IngestionProjection.CASHOS_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                        requests += request
                        return if (requests.size == 1) {
                            ProjectionSubmission.Failure("temporary", retryable = true)
                        } else {
                            ProjectionSubmission.Success("cashos")
                        }
                    }
                },
            ),
        )
        val envelope = (import(restaurantJson(), "cashos-identity").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val receiptEvidence = listOf(LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true))
        orchestrator.start("ingestion-cashos-identity", "cashos-identity", envelope, evidence)
        orchestrator.markReceiptVerified("ingestion-cashos-identity", envelope, receiptEvidence)
        orchestrator.submitProjection(
            "ingestion-cashos-identity",
            IngestionProjection.PRICETRACE_RECEIPT,
            envelope,
        )

        val first = orchestrator.submitProjection(
            "ingestion-cashos-identity",
            IngestionProjection.CASHOS_RECEIPT,
            envelope,
        )
        assertEquals(ProjectionStatus.FAILED, first.status)
        assertEquals(1L, first.projectionRevisionSeq)
        assertTrue(first.idempotencyKey != null)

        val changedNutrition = envelope.copy(
            nutrition = listOf(
                (envelope.nutrition.first() as IngestionNutrition.RestaurantEstimate)
                    .copy(menuName = "Updated Noodles"),
            ) + envelope.nutrition.drop(1),
        )
        val nutritionRevision = orchestrator.reviseCanonicalDraft(
            "ingestion-cashos-identity",
            changedNutrition,
        ) as IngestionStartResult.Success
        val retained = nutritionRevision.session.projections.first {
            it.projection == IngestionProjection.CASHOS_RECEIPT
        }
        assertEquals(ProjectionStatus.FAILED, retained.status)
        assertEquals(first.idempotencyKey, retained.idempotencyKey)
        assertEquals(1L, retained.projectionRevisionSeq)

        val retried = orchestrator.submitProjection(
            "ingestion-cashos-identity",
            IngestionProjection.CASHOS_RECEIPT,
            changedNutrition,
        )
        assertEquals(ProjectionStatus.UPLOADED, retried.status)
        assertEquals(first.idempotencyKey, requests[1].idempotencyKey)
        assertEquals(1L, requests[1].revisionSeq)

        val receipt = requireNotNull(changedNutrition.receipt)
        val changedReceipt = changedNutrition.copy(
            receipt = receipt.copy(
                totals = receipt.totals.copy(grandTotalAmountMinor = 14000),
            ),
        )
        val receiptRevision = orchestrator.reviseCanonicalDraft(
            "ingestion-cashos-identity",
            changedReceipt,
        ) as IngestionStartResult.Success
        val rotated = receiptRevision.session.projections.first {
            it.projection == IngestionProjection.CASHOS_RECEIPT
        }
        assertEquals(ProjectionStatus.PENDING, rotated.status)
        assertEquals(null, rotated.idempotencyKey)
        assertEquals(2L, rotated.projectionRevisionSeq)

        orchestrator.markReceiptVerified("ingestion-cashos-identity", changedReceipt, receiptEvidence)
        orchestrator.submitProjection(
            "ingestion-cashos-identity",
            IngestionProjection.PRICETRACE_RECEIPT,
            changedReceipt,
        )
        val receiptRetry = orchestrator.submitProjection(
            "ingestion-cashos-identity",
            IngestionProjection.CASHOS_RECEIPT,
            changedReceipt,
        )
        assertEquals(ProjectionStatus.UPLOADED, receiptRetry.status)
        assertFalse(requests[2].idempotencyKey == requests[0].idempotencyKey)
        assertEquals(2L, requests[2].revisionSeq)
    }

    @Test
    fun productLabelFitnessRetryKeepsIdentityAcrossUnrelatedReceiptEdit() = runBlocking {
        val parsed = packagedReceiptEnvelope("fitness-identity")
        val label = parsed.nutrition.single() as IngestionNutrition.ProductLabel
        val envelope = parsed.copy(
            nutrition = listOf(label.copy(draft = label.draft.asUserVerified("2026-08-30T10:00:00Z"))),
        )
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true),
        )
        val labelEvidence = listOf(LocalEvidence("label", SourceAttachmentType.NUTRITION_LABEL, true))
        val requests = mutableListOf<ProjectionRequest>()
        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("price-trace")
                },
                IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                        requests += request
                        return if (requests.size == 1) {
                            ProjectionSubmission.Failure("temporary", retryable = true)
                        } else {
                            ProjectionSubmission.Success("fitness")
                        }
                    }
                },
            ),
        )
        orchestrator.start("ingestion-fitness-identity", "fitness-identity", envelope, evidence)
        orchestrator.markReceiptVerified(
            "ingestion-fitness-identity",
            envelope,
            listOf(LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true)),
        )
        orchestrator.submitProjection(
            "ingestion-fitness-identity",
            IngestionProjection.PRICETRACE_RECEIPT,
            envelope,
        )
        orchestrator.markNutritionVerified("ingestion-fitness-identity", envelope, labelEvidence)
        val first = orchestrator.submitProjection(
            "ingestion-fitness-identity",
            IngestionProjection.FITNESS_NUTRITION,
            envelope,
        )
        assertEquals(ProjectionStatus.FAILED, first.status)
        assertEquals(1L, first.projectionRevisionSeq)

        val receipt = requireNotNull(envelope.receipt)
        val changedReceipt = envelope.copy(
            receipt = receipt.copy(
                totals = receipt.totals.copy(grandTotalAmountMinor = 14000),
            ),
        )
        val revised = orchestrator.reviseCanonicalDraft(
            "ingestion-fitness-identity",
            changedReceipt,
        ) as IngestionStartResult.Success
        val retained = revised.session.projections.first {
            it.projection == IngestionProjection.FITNESS_NUTRITION
        }
        assertEquals(ProjectionStatus.FAILED, retained.status)
        assertEquals(first.idempotencyKey, retained.idempotencyKey)
        assertEquals(1L, retained.projectionRevisionSeq)

        orchestrator.markReceiptVerified(
            "ingestion-fitness-identity",
            changedReceipt,
            listOf(LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true)),
        )
        orchestrator.submitProjection(
            "ingestion-fitness-identity",
            IngestionProjection.PRICETRACE_RECEIPT,
            changedReceipt,
        )
        val retried = orchestrator.submitProjection(
            "ingestion-fitness-identity",
            IngestionProjection.FITNESS_NUTRITION,
            changedReceipt,
        )
        assertEquals(ProjectionStatus.UPLOADED, retried.status)
        assertEquals(first.idempotencyKey, requests[1].idempotencyKey)
        assertEquals(1L, requests[1].revisionSeq)
    }

    @Test
    fun restaurantEstimateIdentityEditRequiresFitnessReverificationButTotalEditDoesNot() = runBlocking {
        val envelope = (import(restaurantJson(), "restaurant-identity").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val foodEvidence = listOf(LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true))
        val requests = mutableListOf<ProjectionRequest>()
        val orchestrator = IngestionOrchestrator(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission =
                        ProjectionSubmission.Success("price-trace")
                },
                IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                        requests += request
                        return ProjectionSubmission.Success("fitness")
                    }
                },
            ),
        )
        orchestrator.start("ingestion-restaurant-identity", "restaurant-identity", envelope, evidence)
        orchestrator.markReceiptVerified(
            "ingestion-restaurant-identity",
            envelope,
            listOf(LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true)),
        )
        orchestrator.submitProjection(
            "ingestion-restaurant-identity",
            IngestionProjection.PRICETRACE_RECEIPT,
            envelope,
        )
        orchestrator.markNutritionVerified("ingestion-restaurant-identity", envelope, foodEvidence)
        val first = orchestrator.submitProjection(
            "ingestion-restaurant-identity",
            IngestionProjection.FITNESS_NUTRITION,
            envelope,
        )
        assertEquals(ProjectionStatus.UPLOADED, first.status)

        val receipt = requireNotNull(envelope.receipt)
        val renamed = envelope.copy(
            receipt = receipt.copy(
                merchant = receipt.merchant.copy(name = "Renamed Restaurant"),
            ),
        )
        val renamedRevision = orchestrator.reviseCanonicalDraft(
            "ingestion-restaurant-identity",
            renamed,
        ) as IngestionStartResult.Success
        val invalidated = renamedRevision.session.projections.first {
            it.projection == IngestionProjection.FITNESS_NUTRITION
        }
        assertEquals(ProjectionStatus.PENDING, invalidated.status)
        assertEquals(null, invalidated.idempotencyKey)
        assertEquals(2L, invalidated.projectionRevisionSeq)

        orchestrator.markNutritionVerified("ingestion-restaurant-identity", renamed, foodEvidence)
        orchestrator.markReceiptVerified(
            "ingestion-restaurant-identity",
            renamed,
            listOf(LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true)),
        )
        orchestrator.submitProjection(
            "ingestion-restaurant-identity",
            IngestionProjection.PRICETRACE_RECEIPT,
            renamed,
        )
        val renamedUpload = orchestrator.submitProjection(
            "ingestion-restaurant-identity",
            IngestionProjection.FITNESS_NUTRITION,
            renamed,
        )
        assertEquals(ProjectionStatus.UPLOADED, renamedUpload.status)
        assertEquals("Renamed Restaurant", requests[1].envelope?.receipt?.merchant?.name)

        val renamedReceipt = requireNotNull(renamed.receipt)
        val totalOnly = renamed.copy(
            receipt = renamedReceipt.copy(
                totals = renamedReceipt.totals.copy(grandTotalAmountMinor = 14000),
            ),
        )
        val totalRevision = orchestrator.reviseCanonicalDraft(
            "ingestion-restaurant-identity",
            totalOnly,
        ) as IngestionStartResult.Success
        val afterTotalEdit = totalRevision.session.projections.first {
            it.projection == IngestionProjection.FITNESS_NUTRITION
        }
        assertEquals(ProjectionStatus.UPLOADED, afterTotalEdit.status)
        assertEquals(renamedUpload.idempotencyKey, afterTotalEdit.idempotencyKey)
        assertEquals(renamedUpload.projectionRevisionSeq, afterTotalEdit.projectionRevisionSeq)
        assertEquals(
            renamedUpload.projectionPayloadFingerprint,
            afterTotalEdit.projectionPayloadFingerprint,
        )
    }

    @Test
    fun priceTraceFailureRetryUnlocksDownstreamAndDoesNotResendUploadedProjection() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val calls = mutableMapOf<IngestionProjection, Int>()
        val order = mutableListOf<IngestionProjection>()
        val requests = mutableMapOf<IngestionProjection, MutableList<ProjectionRequest>>()
        val identityJson = """
            {
              "schemaVersion":"verified-receipt-ingestion.v2",
              "receiptId":"pt-receipt-1",
              "storeId":"store-1",
              "restaurantId":"restaurant-1",
              "restaurantLocationId":"location-1",
              "lines":[
                {
                  "sourceLineId":"line-1",
                  "receiptItemId":"line-1",
                  "productId":"product-1",
                  "storeProductId":"store-product-1",
                  "catalogProductId":"catalog-1",
                  "restaurantMenuId":"menu-1"
                }
              ]
            }
        """.trimIndent()
        val submitters = mapOf(
            IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    order += request.projection
                    val count = (calls[request.projection] ?: 0) + 1
                    calls[request.projection] = count
                    return if (count == 1) {
                        ProjectionSubmission.Failure("PriceTrace temporary outage", retryable = true)
                    } else {
                        ProjectionSubmission.Success("pt-receipt-1", metadataJson = identityJson)
                    }
                }
            },
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    order += request.projection
                    calls[request.projection] = (calls[request.projection] ?: 0) + 1
                    requests.getOrPut(request.projection) { mutableListOf() } += request
                    return ProjectionSubmission.Success("pt-receipt-1", metadataJson = identityJson)
                }
            },
            IngestionProjection.CASHOS_RECEIPT to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    order += request.projection
                    val count = (calls[request.projection] ?: 0) + 1
                    calls[request.projection] = count
                    requests.getOrPut(request.projection) { mutableListOf() } += request
                    return if (count == 1) {
                        ProjectionSubmission.Failure("CashOS temporary outage", retryable = true)
                    } else {
                        ProjectionSubmission.Success("cashos-receipt-1")
                    }
                }
            },
            IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    order += request.projection
                    calls[request.projection] = (calls[request.projection] ?: 0) + 1
                    requests.getOrPut(request.projection) { mutableListOf() } += request
                    return ProjectionSubmission.Success("fitness-nutrition-1")
                }
            },
        )
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = submitters,
            now = { "2026-09-02T00:00:00Z" },
        )
        val envelope = (import(restaurantJson(), "cross-projection").draft as CanonicalDraft.Envelope).value
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        val started = orchestrator.start("ingestion-cross-projection", "cross-projection", envelope, evidence)
        assertTrue(started is IngestionStartResult.Success)
        orchestrator.markReceiptVerified("ingestion-cross-projection", envelope, evidence)
        orchestrator.markNutritionVerified("ingestion-cross-projection", envelope, evidence)

        val first = orchestrator.submitAllReadyProjections("ingestion-cross-projection", envelope)
        assertEquals(
            ProjectionStatus.FAILED,
            first.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.status,
        )
        assertEquals(
            ProjectionStatus.BLOCKED,
            first.first { it.projection == IngestionProjection.CASHOS_RECEIPT }.status,
        )
        assertEquals(0, calls[IngestionProjection.CASHOS_RECEIPT] ?: 0)
        assertEquals(0, calls[IngestionProjection.FITNESS_NUTRITION] ?: 0)

        val second = orchestrator.submitAllReadyProjections("ingestion-cross-projection", envelope)
        assertEquals(
            ProjectionStatus.UPLOADED,
            second.first { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.status,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            second.first { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.status,
        )
        assertEquals(
            ProjectionStatus.FAILED,
            second.first { it.projection == IngestionProjection.CASHOS_RECEIPT }.status,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            second.first { it.projection == IngestionProjection.FITNESS_NUTRITION }.status,
        )
        assertEquals(
            PriceTraceIdentity(
                receiptId = "pt-receipt-1",
                storeId = "store-1",
                restaurantId = "restaurant-1",
                restaurantLocationId = "location-1",
                lines = listOf(
                    PriceTraceLineIdentity(
                        sourceLineId = "line-1",
                        receiptItemId = "line-1",
                        productId = "product-1",
                        storeProductId = "store-product-1",
                        catalogProductId = "catalog-1",
                        restaurantMenuId = "menu-1",
                    ),
                ),
            ),
            requests.getValue(IngestionProjection.CASHOS_RECEIPT).single().resolvedIdentity?.priceTrace,
        )
        assertEquals(
            "store-1",
            requests.getValue(IngestionProjection.FITNESS_NUTRITION).single()
                .resolvedIdentity?.priceTrace?.storeId,
        )
        assertEquals(
            listOf(
                IngestionProjection.PRICETRACE_RECEIPT,
                IngestionProjection.PRICETRACE_RECEIPT,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
                IngestionProjection.CASHOS_RECEIPT,
                IngestionProjection.FITNESS_NUTRITION,
            ),
            order,
        )

        val third = orchestrator.submitAllReadyProjections("ingestion-cross-projection", envelope)
        assertEquals(
            ProjectionStatus.UPLOADED,
            third.first { it.projection == IngestionProjection.CASHOS_RECEIPT }.status,
        )
        assertEquals(2, calls[IngestionProjection.CASHOS_RECEIPT])
        assertEquals(1, calls[IngestionProjection.FITNESS_NUTRITION])
        assertEquals(2, calls[IngestionProjection.PRICETRACE_RECEIPT])
        assertEquals(1, calls[IngestionProjection.PRICETRACE_PRICE_OBSERVATION])
        assertEquals(
            listOf(
                IngestionProjection.PRICETRACE_RECEIPT,
                IngestionProjection.PRICETRACE_RECEIPT,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
                IngestionProjection.CASHOS_RECEIPT,
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.CASHOS_RECEIPT,
            ),
            order,
        )
    }

    @Test
    fun fitnessMealRequiresAnExplicitVerifiedConsumptionArtifact() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        var mealCalls = 0
        val identityJson = """{"receiptId":"pt-receipt-meal","storeId":"store-meal","lines":[]}"""
        val configured = setOf(
            IngestionProjection.PRICETRACE_RECEIPT,
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            IngestionProjection.CASHOS_RECEIPT,
            IngestionProjection.FITNESS_NUTRITION,
            IngestionProjection.FITNESS_MEAL,
        )
        val submitters = configured.associateWith { projection ->
            object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    if (projection == IngestionProjection.FITNESS_MEAL) mealCalls += 1
                    return ProjectionSubmission.Success(
                        remoteId = projection.wireValue,
                        metadataJson = if (projection == IngestionProjection.PRICETRACE_RECEIPT) identityJson else null,
                    )
                }
            }
        }
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = submitters,
            now = { "2026-09-02T00:00:00Z" },
        )
        val envelope = (import(restaurantJson(), "consumption-gate").draft as CanonicalDraft.Envelope).value.copy(
            consumption = listOf(
                IngestionConsumption(
                    clientKey = "consumption-1",
                    nutritionClientKeys = setOf("food-1"),
                ),
            ),
        )
        val evidence = listOf(
            LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true),
            LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true),
        )
        orchestrator.start("ingestion-consumption-gate", "consumption-gate", envelope, evidence)
        orchestrator.markReceiptVerified("ingestion-consumption-gate", envelope, evidence)
        orchestrator.markNutritionVerified("ingestion-consumption-gate", envelope, evidence)

        val blocked = orchestrator.submitAllReadyProjections("ingestion-consumption-gate", envelope)
            .first { it.projection == IngestionProjection.FITNESS_MEAL }
        assertEquals(ProjectionStatus.BLOCKED, blocked.status)
        assertEquals("projection_not_reverified", blocked.lastError)
        assertEquals(0, mealCalls)

        val verifiedEnvelope = envelope.copy(
            consumption = envelope.consumption.map {
                it.copy(status = ConsumptionVerificationStatus.USER_VERIFIED)
            },
        )
        val revised = orchestrator.reviseCanonicalDraft(
            "ingestion-consumption-gate",
            verifiedEnvelope,
        )
        assertTrue(revised is IngestionStartResult.Success)
        assertTrue(
            orchestrator.markConsumptionVerified(
                "ingestion-consumption-gate",
                verifiedEnvelope,
                evidence,
            ) is IngestionStartResult.Success,
        )

        val uploaded = orchestrator.submitAllReadyProjections("ingestion-consumption-gate", verifiedEnvelope)
            .first { it.projection == IngestionProjection.FITNESS_MEAL }
        assertEquals(ProjectionStatus.UPLOADED, uploaded.status)
        assertEquals(1, mealCalls)
    }

    private fun packagedReceiptEnvelope(localId: String): YeonsikOcrEnvelope {
        val retailReceipt = receiptJson
            .replace("\"business_kind\":\"food_service\"", "\"business_kind\":\"retail\"")
            .replace(",\"food_service\":{\"role\":\"main\",\"applies_to_line_id\":null}", "")
            .replace(",\"food_service\":{\"role\":\"option\",\"applies_to_line_id\":\"line-1\"}", "")
            .replace(",\"food_service\":{\"role\":\"side\",\"applies_to_line_id\":null}", "")
        return (import(packagedJson().replace("\"receipt\":null", "\"receipt\":" + retailReceipt), localId).draft as CanonicalDraft.Envelope).value
    }
    private fun import(
        value: String,
        localId: String,
        workflow: OcrWorkflowType? = null,
    ) = when (val outcome = ExternalJsonImporter().import(value, localId, workflow)) {
        is ExternalJsonImportOutcome.Success -> outcome.result
        is ExternalJsonImportOutcome.Failure -> error(outcome.error)
    }

    private fun header(mode: String, extra: String = "") = """
        {$extra"schema_version":"yeonsik-ocr.v1","mode":"$mode",
        "source":{"producer":"chatgpt","source_files":[],"user_text":null},
        "merchant_candidate":null,"receipt":null,"nutrition":[],
        "classification_hints":{"cashos":{}},"links":[],"projection_targets":[],
        "review":{"status":"ready","blocking_issues":[],"warnings":[]}}
    """.trimIndent()

    private fun merchantJson(extra: String = "") = header("merchant", extra).replace(
        "\"merchant_candidate\":null", "\"merchant_candidate\":{\"name\":\"Test Mart\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
    )

    private fun restaurantJson() = header("restaurant")
        .replace(
            "\"merchant_candidate\":null",
            "\"merchant_candidate\":{\"name\":\"Test Restaurant\",\"branch_name\":null,\"address\":null,\"phone\":null,\"business_registration_number\":null,\"source_attachment_ids\":[]}",
        )
        .replace("\"receipt\":null", "\"receipt\":$receiptJson")
        .replace(
            "\"nutrition\":[]",
            """"nutrition":[{"client_key":"food-1","kind":"restaurant_estimate","line_id":"line-1","menu_name":"Noodles","payload":null,"estimate":$estimateJson},{"client_key":"food-2","kind":"restaurant_estimate","line_id":"line-2","menu_name":"Egg","payload":null,"estimate":$estimateJson},{"client_key":"food-3","kind":"restaurant_estimate","line_id":"line-3","menu_name":"Kimchi","payload":null,"estimate":$estimateJson}]""",
        )
        .replace(
            "\"links\":[]",
            """"links":[{"receipt_line_id":"line-1","nutrition_client_key":"food-1"},{"receipt_line_id":"line-2","nutrition_client_key":"food-2"},{"receipt_line_id":"line-3","nutrition_client_key":"food-3"}]""",
        )

    private fun packagedJson() = header("packaged_product").replace("\"nutrition\":[]", "\"nutrition\":[{\"client_key\":\"label-1\",\"kind\":\"product_label\",\"line_id\":null,\"menu_name\":null,\"payload\":$nutritionJson,\"estimate\":null}]")

    private val receiptJson = """
        {"schema_version":"receipt.v2","document":{"id":"remote-receipt","type":"receipt","status":"final","issued_on":"2026-08-27","issued_at":null,"currency":"KRW","fulfillment":{"type":"dine_in","evidence":"printed"},"source":{"capture_method":"ocr","original_document_id":null,"source_images":[],"transcription_status":"user_verified","notes":[],"raw_text":null}},"merchant":{"name":"Test Restaurant","branch_name":null,"business_kind":"food_service","retail_channel":"regular","catalog_namespace":null,"merchant_id":null,"business_registration_number":null,"address":null,"phone":null},"line_items":[{"id":"line-1","type":"product","description":"Noodles","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":10000,"gross_amount_minor":10000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":10000,"confidence":"user_verified","tax_rate_percent":null,"food_service":{"role":"main","applies_to_line_id":null}},{"id":"line-2","type":"product","description":"Egg","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":2000,"gross_amount_minor":2000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":2000,"confidence":"user_verified","tax_rate_percent":null,"food_service":{"role":"option","applies_to_line_id":"line-1"}},{"id":"line-3","type":"product","description":"Kimchi","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":1000,"gross_amount_minor":1000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":1000,"confidence":"user_verified","tax_rate_percent":null,"food_service":{"role":"side","applies_to_line_id":null}}],"totals":{"items_gross_amount_minor":13000,"discount_amount_minor":0,"fee_amount_minor":0,"tax_amount_minor":0,"tip_amount_minor":0,"rounding_amount_minor":0,"grand_total_amount_minor":13000},"payments":[{"method":"card","amount_minor":13000,"status":"paid","reference":null}]}
    """.trimIndent()
    private val estimateJson = """{"estimated":true,"confidence":"medium","nutrients":{"calories_kcal":500.0,"protein_grams":20.0,"carbs_grams":70.0,"fat_grams":15.0,"sodium_mg":800.0,"saturated_fat_grams":3.0,"sugars_grams":5.0,"fiber_grams":null,"added_sugars_grams":null,"trans_fat_grams":null,"cholesterol_mg":null},"ranges":{"calories_kcal":{"min":400.0,"point":500.0,"max":600.0}}}"""
    private val nutritionJson = """{"schema_version":"fitness-nutrition-draft.v1","document_id":"remote-label","parser_version":"test","status":"user_verified","confirmed_at":"2026-01-01T00:00:00Z","name":"Test cereal","brand":"Brand","kind":"external_menu","category":"cereal","basis_amount":100.0,"basis_unit":"g","prep_state":"unspecified","cooking_method":"unspecified","nutrients":{"calories_kcal":380.0,"protein_grams":10.0,"carbs_grams":70.0,"fat_grams":5.0,"sodium_mg":100.0,"saturated_fat_grams":1.0,"sugars_grams":12.0,"fiber_grams":null,"added_sugars_grams":null,"trans_fat_grams":null,"cholesterol_mg":null},"source_type":"product_label_ocr","source_reference":"ocr-document:remote-label","source_version":"v1","data_version":2,"visibility":"private","parse_warnings":[],"evidence":{}}"""
}
