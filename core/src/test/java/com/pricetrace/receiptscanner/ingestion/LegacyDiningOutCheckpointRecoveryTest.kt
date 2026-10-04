package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptIdentifier
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.nutrition.NutritionField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDiningOutCheckpointRecoveryTest {
    @Test
    fun legacyImportOnlyCompletionReopensReviewAndRetainsExactRecoverySelectors() = runBlocking {
        val fixture = fixture()
        val original = fixture.store.get(ID)!!
        val originalFitness = original.fitness()
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!

        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, restored.reviewStatus)
        assertEquals(ProjectionStatus.PENDING, restored.fitness().status)
        assertEquals(originalFitness.idempotencyKey, restored.fitness().idempotencyKey)
        assertEquals(originalFitness.remoteId, restored.fitness().remoteId)
        assertEquals(originalFitness.metadataJson, restored.fitness().metadataJson)
        assertEquals(originalFitness.projectionRevisionSeq, restored.fitness().projectionRevisionSeq)
        assertEquals(originalFitness.attemptCount, restored.fitness().attemptCount)
        assertEquals(original.revisionSeq, restored.revisionSeq)
        assertNull(restored.verifiedCanonicalFingerprint)
        assertFalse(restored.verifiedArtifactFingerprints.containsKey(IngestionArtifactKeys.nutrition("food-1")))
        assertTrue(restored.verifiedArtifactFingerprints.containsKey(IngestionArtifactKeys.RECEIPT))
        assertEquals(0, fixture.reader.submits)
        assertEquals(LEGACY_RECEIPT, fixture.reader.reads.single().previousMetadataJson)
        assertEquals("original-pt-key", fixture.reader.reads.single().idempotencyKey)
        assertTrue(restored.pt().metadataJson!!.contains("server-resolution-token"))
    }

    @Test
    fun directUploadedSubmitCannotSilentlySkipLegacyPublicationOrInventApproval() = runBlocking {
        val fixture = fixture(exact = true)
        val state = fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, fixture.envelope)
        assertEquals(ProjectionStatus.BLOCKED, state.status)
        assertEquals("projection_not_reverified", state.lastError)
        assertEquals(0, fixture.requests.size)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun existingPublishedLegacyCheckpointUpgradesWithoutReopeningReview() = runBlocking {
        val fixture = fixture(exact = true, fitnessMetadata = PUBLICATION)
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
        assertEquals(ProjectionStatus.UPLOADED, restored.fitness().status)
        assertEquals(1, restored.fitness().completionContractVersion)
        assertEquals(IngestionReviewStatus.READY, restored.reviewStatus)
        assertNotNull(restored.verifiedCanonicalFingerprint)
        assertEquals(0, fixture.requests.size)
    }

    @Test
    fun partialOrMismatchedPublicationResponseCannotUpgradeLegacyCompletion() = runBlocking {
        for (response in listOf(
            PUBLICATION.replace("\"publication_revision\":1", "\"publication_revision\":0"),
            PUBLICATION.replace(CATALOG, OTHER_CATALOG),
            PUBLICATION.replace("\"visibility\":\"public\"", "\"visibility\":\"private\""),
        )) {
            val fixture = fixture(exact = true, fitnessMetadata = response)
            val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
            assertEquals(ProjectionStatus.PENDING, restored.fitness().status)
            assertEquals(0, restored.fitness().completionContractVersion)
        }
    }

    @Test
    fun unchangedLegacySourceNormalizesOldPayloadFingerprintWithoutRotatingTheKey() = runBlocking {
        val fixture = fixture(exact = true)
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
        assertNotEquals("legacy-payload-fingerprint", restored.fitness().projectionPayloadFingerprint)
        assertEquals("original-fitness-key", restored.fitness().idempotencyKey)
        val repeated = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
        assertEquals(restored.fitness().projectionPayloadFingerprint, repeated.fitness().projectionPayloadFingerprint)
        assertEquals(restored.fitness().projectionRevisionSeq, repeated.fitness().projectionRevisionSeq)
    }

    @Test
    fun actualNutritionEditResetsTheLegacyImportInsteadOfReusingChangedSource() = runBlocking {
        val fixture = fixture(exact = true)
        fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)
        val original = fixture.store.get(ID)!!.fitness()
        val changed = fixture.envelope.copy(nutrition = listOf(
            (fixture.envelope.nutrition.single() as IngestionNutrition.RestaurantEstimate).copy(menuName = "Changed source menu"),
        ))
        val revised = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
        assertNull(revised.session.fitness().idempotencyKey)
        assertNull(revised.session.fitness().metadataJson)
        assertNull(revised.session.fitness().remoteId)
        assertEquals(original.projectionRevisionSeq + 1, revised.session.fitness().projectionRevisionSeq)
    }

    @Test
    fun codecDriftStillReopensLegacyCompletionAndOnlyStoredSnapshotNormalizesItsPayload() = runBlocking {
        val fixture = fixture(exact = true)
        val original = fixture.store.get(ID)!!
        fixture.store.save(original.copy(canonicalFingerprint = "legacy-codec-hash", verifiedCanonicalFingerprint = "legacy-codec-hash"))
        val ordinary = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
        assertEquals(ProjectionStatus.PENDING, ordinary.fitness().status)
        assertEquals("legacy-payload-fingerprint", ordinary.fitness().projectionPayloadFingerprint)
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
        assertNotEquals("legacy-payload-fingerprint", restored.fitness().projectionPayloadFingerprint)
        assertEquals("original-fitness-key", restored.fitness().idempotencyKey)
        assertEquals("legacy-codec-hash", restored.canonicalFingerprint)
        assertNull(restored.verifiedCanonicalFingerprint)
    }

    @Test
    fun changedDraftRevisionDoesNotInvokeRecoveryReadsOrNormalizeChangedPayload() = runBlocking {
        // This checkpoint already has the guard captured from its original accepted source.
        // An entirely legacy checkpoint without that baseline must first be restored, as
        // missingLegacyBaselineRequiresTrustedRestoreAndRemainsGuardedAfterStoreReload verifies.
        val fixture = fixture(exact = true, receiptFactsBaselinePresent = true)
        val before = fixture.store.get(ID)!!
        assertNotNull(before.pt().acceptedReceiptFactsFingerprint)
        val changed = fixture.envelope.copy(nutrition = listOf(
            (fixture.envelope.nutrition.single() as IngestionNutrition.RestaurantEstimate).copy(menuName = "Different evidence fact"),
        ))
        assertEquals(fixture.envelope.receipt, changed.receipt)
        val revised = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
        assertTrue(fixture.reader.reads.isEmpty())
        assertNull(revised.session.fitness().idempotencyKey)
        assertNull(revised.session.fitness().metadataJson)
        assertEquals(before.pt().acceptedReceiptFactsFingerprint, revised.session.pt().acceptedReceiptFactsFingerprint)
    }

    @Test
    fun storedCodecDriftRestoreAndUnchangedConfirmationRetainAcceptedPtAndCashRequests() = runBlocking {
        val fixture = fixture(exact = true)
        val original = fixture.store.get(ID)!!
        val accepted = original.copy(
            canonicalFingerprint = "legacy-codec-hash",
            verifiedCanonicalFingerprint = "legacy-codec-hash",
            projections = original.projections.map { state ->
                when (state.projection) {
                    IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> state.copy(
                        projectionPayloadFingerprint = "legacy-pt-codec-fingerprint", projectionRevisionSeq = 3, attemptCount = 2,
                    )
                    IngestionProjection.CASHOS_RECEIPT -> state.copy(
                        status = ProjectionStatus.UPLOADED, idempotencyKey = "accepted-cash-key", remoteId = "cash-receipt-id",
                        metadataJson = """{"receipt_id":"cash-receipt-id","transaction_id":"cash-transaction-id"}""",
                        projectionPayloadFingerprint = "legacy-cash-codec-fingerprint", projectionRevisionSeq = 5, attemptCount = 4,
                    )
                    else -> state
                }
            },
        )
        fixture.store.save(accepted)
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
        val restoredCash = restored.projections.single { it.projection == IngestionProjection.CASHOS_RECEIPT }
        val originalCash = accepted.projections.single { it.projection == IngestionProjection.CASHOS_RECEIPT }
        assertEquals(originalCash.copy(projectionPayloadFingerprint = restoredCash.projectionPayloadFingerprint), restoredCash)
        assertNotEquals(originalCash.projectionPayloadFingerprint, restoredCash.projectionPayloadFingerprint)

        val useCase = CanonicalIngestionUseCase(
            store = fixture.store,
            submitters = mapOf(IngestionProjection.PRICETRACE_RECEIPT to fixture.reader),
            now = { NOW },
        )
        val confirmation = useCase.confirm(ID, fixture.envelope, EVIDENCE)
        assertTrue(confirmation.result is IngestionStartResult.Success)
        val confirmed = fixture.store.get(ID)!!
        for (projection in listOf(IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.PRICETRACE_PRICE_OBSERVATION, IngestionProjection.CASHOS_RECEIPT)) {
            val before = restored.projections.single { it.projection == projection }
            val after = confirmed.projections.single { it.projection == projection }
            assertEquals(ProjectionStatus.UPLOADED, after.status)
            assertEquals(before.idempotencyKey, after.idempotencyKey)
            assertEquals(before.remoteId, after.remoteId)
            assertEquals(before.metadataJson, after.metadataJson)
            assertEquals(before.attemptCount, after.attemptCount)
            assertEquals(before.projectionRevisionSeq, after.projectionRevisionSeq)
            assertEquals(before.projectionPayloadFingerprint, after.projectionPayloadFingerprint)
        }
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun reviewedLegacyRetryPassesPreviousResponseAndSameKeyToPublicationRecovery() = runBlocking {
        val fixture = fixture(exact = true)
        fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)
        val verified = fixture.orchestrator.markNutritionVerified(ID, fixture.envelope, EVIDENCE)
        assertTrue(verified is IngestionStartResult.Success)
        val state = fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, fixture.envelope)
        assertEquals(ProjectionStatus.UPLOADED, state.status)
        val request = fixture.requests.single()
        assertEquals("original-fitness-key", request.idempotencyKey)
        assertEquals(PRIVATE_IMPORT, request.previousMetadataJson)
        assertTrue(request.recoverCanonicalImport)
        assertEquals(7L, request.revisionSeq)
        assertEquals(1, state.completionContractVersion)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun recoveredPublicationKeepsCurrentFingerprintAcrossRetryRestoreAndUnrelatedCanonicalRevision() = runBlocking {
        for (standalone in listOf(false, true)) {
            val envelope = if (standalone) standaloneEnvelope() else envelope()
            val evidence = if (standalone) STANDALONE_EVIDENCE else EVIDENCE
            val fixture = fixture(exact = true, sourceEnvelope = envelope, localEvidence = evidence)
            fixture.reader.outcome = ProjectionSubmission.Success(RECEIPT, if (standalone) EXACT_STANDALONE else EXACT_RECEIPT)
            val restored = fixture.orchestrator.prepareExistingSession(ID, envelope, restoredSnapshot = true)!!
            assertTrue(fixture.orchestrator.markNutritionVerified(ID, envelope, evidence) is IngestionStartResult.Success)
            val published = fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, envelope)
            assertEquals(ProjectionStatus.UPLOADED, published.status)
            assertEquals(1, published.completionContractVersion)
            assertEquals("original-fitness-key", published.idempotencyKey)

            val reference = IngestionOrchestrator(InMemoryIngestionSessionStore(), submitters = emptyMap(), now = { NOW })
                .start("current-contract-reference", "local", envelope, evidence) as IngestionStartResult.Success
            assertEquals(reference.session.fitness().projectionPayloadFingerprint, published.projectionPayloadFingerprint)
            assertNotEquals(restored.fitness().projectionPayloadFingerprint, published.projectionPayloadFingerprint)
            assertEquals(published, fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, envelope))
            assertEquals(published, fixture.orchestrator.prepareExistingSession(ID, envelope, restoredSnapshot = true)!!.fitness())
            assertEquals(1, fixture.requests.size)

            val noteEdit = envelope.copy(source = envelope.source.copy(userText = "Reviewed source note"))
            val revised = fixture.orchestrator.reviseCanonicalDraft(ID, noteEdit) as IngestionStartResult.Success
            assertEquals(published, revised.session.fitness())
            assertEquals(published, fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, noteEdit))
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.reader.submits)

            val changedNutrition = noteEdit.copy(nutrition = noteEdit.nutrition.map { item ->
                when (item) {
                    is IngestionNutrition.RestaurantEstimate -> item.copy(menuName = "Changed Nutrition source menu")
                    is IngestionNutrition.RestaurantMenuEstimate -> item.copy(menuName = "Changed Nutrition source menu")
                    else -> item
                }
            })
            val changed = fixture.orchestrator.reviseCanonicalDraft(ID, changedNutrition) as IngestionStartResult.Success
            assertEquals(ProjectionStatus.PENDING, changed.session.fitness().status)
            assertNull(changed.session.fitness().idempotencyKey)
            assertNull(changed.session.fitness().metadataJson)
            assertEquals(published.projectionRevisionSeq + 1, changed.session.fitness().projectionRevisionSeq)
        }
    }

    @Test
    fun correctedReceiptMerchantRetainsLegacyImportAndSubmitsPublicationRecovery() = runBlocking {
        val fixture = fixture()
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
        val receipt = fixture.envelope.receipt!!
        val changed = fixture.envelope.copy(receipt = receipt.copy(
            merchant = receipt.merchant.copy(name = "Corrected Restaurant", branchName = "Corrected branch",
                address = "Corrected address", phone = "010-1234-5678"),
        ))
        val revised = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
        assertLegacyImportRetained(restored.fitness(), revised.session.fitness())
        assertEquals(restored.fitness().projectionPayloadFingerprint, revised.session.fitness().projectionPayloadFingerprint)
        assertNull(revised.session.verifiedCanonicalFingerprint)
        assertFalse(revised.session.verifiedArtifactFingerprints.containsKey(IngestionArtifactKeys.nutrition("food-1")))

        fixture.reader.outcome = ProjectionSubmission.Success(RECEIPT, EXACT_RECEIPT)
        assertTrue(fixture.orchestrator.markReceiptVerified(ID, changed, EVIDENCE) is IngestionStartResult.Success)
        assertTrue(fixture.orchestrator.markNutritionVerified(ID, changed, EVIDENCE) is IngestionStartResult.Success)
        val uploaded = fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, changed)
        assertEquals(ProjectionStatus.UPLOADED, uploaded.status)
        val request = fixture.requests.single()
        assertTrue(request.recoverCanonicalImport)
        assertEquals("original-fitness-key", request.idempotencyKey)
        assertEquals(PRIVATE_IMPORT, request.previousMetadataJson)
        assertEquals(7L, request.revisionSeq)
        assertEquals("Corrected Restaurant", request.envelope!!.receipt!!.merchant.name)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun correctedStandaloneMerchantSourceRetainsLegacyImportAndSubmitsPublicationRecovery() = runBlocking {
        val standalone = standaloneEnvelope()
        val evidence = STANDALONE_EVIDENCE
        val fixture = fixture(sourceEnvelope = standalone, localEvidence = evidence)
        val restored = fixture.orchestrator.prepareExistingSession(ID, standalone, restoredSnapshot = true)!!
        val changed = standalone.copy(merchantCandidate = standalone.merchantCandidate!!.copy(
            name = "Corrected Restaurant", branchName = "Corrected branch", address = "Corrected address",
            phone = "010-1234-5678", businessRegistrationNumber = "1234567890",
            sourceNamespace = "reviewed-source", sourceLocationCode = "branch-17",
        ))
        val revised = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
        assertLegacyImportRetained(restored.fitness(), revised.session.fitness())
        assertEquals(restored.fitness().projectionPayloadFingerprint, revised.session.fitness().projectionPayloadFingerprint)

        fixture.reader.outcome = ProjectionSubmission.Success(RECEIPT, EXACT_STANDALONE)
        assertTrue(fixture.orchestrator.markPriceObservationsVerified(ID, changed, evidence) is IngestionStartResult.Success)
        assertTrue(fixture.orchestrator.markNutritionVerified(ID, changed, evidence) is IngestionStartResult.Success)
        val uploaded = fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, changed)
        assertEquals(ProjectionStatus.UPLOADED, uploaded.status)
        val request = fixture.requests.single()
        assertTrue(request.recoverCanonicalImport)
        assertEquals("original-fitness-key", request.idempotencyKey)
        assertEquals(PRIVATE_IMPORT, request.previousMetadataJson)
        assertEquals("Corrected Restaurant", request.envelope!!.merchantCandidate!!.name)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun pendingStandaloneIdentityRejectsPriceDateKeyQuantityAndEvidenceEditsBeforeSaving() = runBlocking {
        for (change in listOf<(StandalonePriceObservation) -> StandalonePriceObservation>(
            { it.copy(netAmountMinor = 11000) },
            { it.copy(unitPriceAmountMinor = 10000) },
            { it.copy(grossAmountMinor = 10000) },
            { it.copy(discountAmountMinor = 0) },
            { it.copy(observedOn = "2026-09-02") },
            { it.copy(observedAt = "2026-09-01T12:00:00+09:00") },
            { it.copy(clientKey = "different-price-key") },
            { it.copy(quantity = StandalonePriceObservationQuantity(2.0, "serving")) },
            { it.copy(evidence = listOf(it.evidence.single().copy(observedValue = "11000"))) },
            { it.copy(sourceAttachmentIds = listOf("different-menu"),
                evidence = listOf(it.evidence.single().copy(sourceAttachmentIds = listOf("different-menu")))) },
        )) {
            val envelope = standaloneEnvelope()
            val fixture = fixture(sourceEnvelope = envelope, localEvidence = STANDALONE_EVIDENCE)
            val before = fixture.orchestrator.prepareExistingSession(ID, envelope, restoredSnapshot = true)!!
            assertNotNull(before.standalonePrice().acceptedReceiptFactsFingerprint)
            val changed = envelope.copy(priceObservations = listOf(change(envelope.priceObservations.single())))
            val result = fixture.orchestrator.reviseCanonicalDraft(ID, changed)
            assertTrue(result is IngestionStartResult.Failure)
            assertEquals(listOf("accepted_standalone_price_source_revision_conflict"), (result as IngestionStartResult.Failure).issues)
            assertEquals(before, fixture.store.get(ID))
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.reader.submits)
        }
    }

    @Test
    fun standaloneUnsupportedCurrencyFailsModelValidationBeforeSaving() = runBlocking {
        val envelope = standaloneEnvelope()
        val fixture = fixture(sourceEnvelope = envelope, localEvidence = STANDALONE_EVIDENCE)
        val before = fixture.orchestrator.prepareExistingSession(ID, envelope, restoredSnapshot = true)!!
        val failure = runCatching { envelope.priceObservations.single().copy(currency = "USD") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, fixture.store.get(ID))
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun standaloneMerchantAndMenuResolutionEditsKeepAcceptedPriceFactsAndRequestKey() = runBlocking {
        val envelope = standaloneEnvelope()
        val fixture = fixture(sourceEnvelope = envelope, localEvidence = STANDALONE_EVIDENCE)
        val before = fixture.orchestrator.prepareExistingSession(ID, envelope, restoredSnapshot = true)!!
        val changed = envelope.copy(
            merchantCandidate = envelope.merchantCandidate!!.copy(name = "Reviewed restaurant", address = "Reviewed address"),
            priceObservations = listOf(envelope.priceObservations.single().copy(itemName = "Reviewed source menu")),
        )
        val result = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
        assertEquals(before.standalonePrice().idempotencyKey, result.session.standalonePrice().idempotencyKey)
        assertEquals(before.standalonePrice().remoteId, result.session.standalonePrice().remoteId)
        assertEquals(before.standalonePrice().metadataJson, result.session.standalonePrice().metadataJson)
        assertEquals(before.standalonePrice().projectionRevisionSeq, result.session.standalonePrice().projectionRevisionSeq)
        assertEquals(before.standalonePrice().acceptedReceiptFactsFingerprint, result.session.standalonePrice().acceptedReceiptFactsFingerprint)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun untrustedChangedStandaloneSnapshotCannotCreateAcceptedPriceBaseline() = runBlocking {
        val envelope = standaloneEnvelope()
        val fixture = fixture(sourceEnvelope = envelope, localEvidence = STANDALONE_EVIDENCE)
        val old = fixture.store.get(ID)!!
        assertNull(old.standalonePrice().acceptedReceiptFactsFingerprint)
        val changed = envelope.copy(priceObservations = listOf(envelope.priceObservations.single().copy(netAmountMinor = 11000)))
        val prepared = fixture.orchestrator.prepareExistingSession(ID, changed)!!
        assertNull(prepared.standalonePrice().acceptedReceiptFactsFingerprint)
        assertEquals(old.fitness().projectionPayloadFingerprint, prepared.fitness().projectionPayloadFingerprint)
        val result = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Failure
        assertEquals(listOf("accepted_standalone_price_source_revision_conflict"), result.issues)
        assertEquals(prepared, fixture.store.get(ID))
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun nutritionValueOrEvidenceEditStillResetsLegacyImportAfterIdentityReview() = runBlocking {
        val base = envelope()
        val originalItem = base.nutrition.single() as IngestionNutrition.RestaurantEstimate
        val original = base.copy(schemaVersion = YEONSIK_OCR_V3_SCHEMA,
            nutrition = listOf(originalItem.copy(estimate = originalItem.estimate.copy(confidenceScore = 0.7))))
        for (change in listOf<(IngestionNutrition.RestaurantEstimate) -> IngestionNutrition.RestaurantEstimate>(
            { item -> item.copy(estimate = item.estimate.copy(nutrients = item.estimate.nutrients + (NutritionField.CALORIES_KCAL to 600.0))) },
            { item -> item.copy(estimate = item.estimate.copy(nutrientProvenance = mapOf(
                NutritionField.CALORIES_KCAL to NutritionNutrientProvenance("estimated", "food_photo", listOf("different-source-photo")),
            ))) },
        )) {
            val fixture = fixture(sourceEnvelope = original)
            val restored = fixture.orchestrator.prepareExistingSession(ID, original, restoredSnapshot = true)!!
            val changed = original.copy(nutrition = listOf(change(original.nutrition.single() as IngestionNutrition.RestaurantEstimate)))
            val revised = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
            assertNull(revised.session.fitness().idempotencyKey)
            assertNull(revised.session.fitness().metadataJson)
            assertNull(revised.session.fitness().remoteId)
            assertEquals(1, revised.session.fitness().completionContractVersion)
            assertEquals(restored.fitness().projectionRevisionSeq + 1, revised.session.fitness().projectionRevisionSeq)
        }
    }

    private fun assertLegacyImportRetained(before: ProjectionState, after: ProjectionState) {
        assertEquals(before.idempotencyKey, after.idempotencyKey)
        assertEquals(before.remoteId, after.remoteId)
        assertEquals(before.metadataJson, after.metadataJson)
        assertEquals(before.projectionRevisionSeq, after.projectionRevisionSeq)
        assertEquals(0, after.completionContractVersion)
    }

    @Test
    fun failedOwnerReadPreservesTokenAndPreventsPublication() = runBlocking {
        val fixture = fixture(exact = true)
        fixture.reader.outcome = ProjectionSubmission.Failure("HTTP 403", retryable = false)
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
        assertEquals(LEGACY_RECEIPT, restored.pt().metadataJson)
        assertTrue(restored.pt().lastError!!.startsWith("pricetrace_checkpoint_read_failed:"))
        fixture.orchestrator.markNutritionVerified(ID, fixture.envelope, EVIDENCE)
        val state = fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, fixture.envelope)
        assertEquals(ProjectionStatus.BLOCKED, state.status)
        assertEquals("pricetrace_checkpoint_refresh_required", state.lastError)
        assertEquals(0, fixture.requests.size)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun cashOnlyRetryUsesAcceptedReceiptEvenWhenNutritionAuthorityRefreshFails() = runBlocking {
        val fixture = fixture()
        fixture.reader.outcome = ProjectionSubmission.Failure("owner getter unavailable", retryable = true)
        val current = fixture.store.get(ID)!!
        fixture.store.save(current.copy(projections = current.projections.map { state ->
            if (state.projection == IngestionProjection.CASHOS_RECEIPT) state.copy(
                status = ProjectionStatus.FAILED, idempotencyKey = "same-cash-retry-key", attemptCount = 2,
                lastError = "temporary CashOS failure",
            ) else state
        }))
        val cashRequests = mutableListOf<ProjectionRequest>()
        val useCase = CanonicalIngestionUseCase(
            store = fixture.store, now = { NOW },
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to fixture.reader,
                IngestionProjection.CASHOS_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                        cashRequests += request
                        return ProjectionSubmission.Success("cash-receipt-id")
                    }
                },
            ),
        )
        val states = useCase.retrySelected(ID, fixture.envelope, setOf(IngestionProjection.CASHOS_RECEIPT))
        assertEquals(ProjectionStatus.UPLOADED, states.single { it.projection == IngestionProjection.CASHOS_RECEIPT }.status)
        assertEquals("same-cash-retry-key", cashRequests.single().idempotencyKey)
        assertEquals(RECEIPT, cashRequests.single().resolvedIdentity!!.priceTrace!!.receiptId)
        assertEquals(0, fixture.requests.size)
        assertEquals(0, fixture.reader.resolutions)
        assertEquals(0, fixture.reader.submits)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, fixture.store.get(ID)!!.reviewStatus)
    }

    @Test
    fun exactSavedResponseAfterMerchantResponseLossSkipsResolvingAcceptedToken() = runBlocking {
        val fixture = fixture(exact = true)
        fixture.reader.outcome = ProjectionSubmission.Success(RECEIPT, EXACT_RECEIPT)
        assertNull(fixture.orchestrator.resolvePendingOcrIdentity(ID, fixture.envelope))
        assertEquals(EXACT_RECEIPT, fixture.store.get(ID)!!.pt().metadataJson)
        assertEquals(0, fixture.reader.resolutions)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun failedResolutionTransportWithSavedExactResponseRestoresAcceptedPtWithoutReingestion() = runBlocking {
        val fixture = fixture(exact = true)
        val previous = fixture.store.get(ID)!!
        val failedPt = previous.pt().copy(
            status = ProjectionStatus.FAILED, metadataJson = NEEDS_REVIEW_RECEIPT,
            attemptCount = 4, lastError = "resolution response lost",
        )
        fixture.store.save(previous.copy(projections = previous.projections.map {
            if (it.projection == IngestionProjection.PRICETRACE_RECEIPT) failedPt else it
        }))

        assertNull(fixture.orchestrator.resolvePendingOcrIdentity(ID, fixture.envelope))
        val restoredPt = fixture.store.get(ID)!!.pt()
        assertEquals(ProjectionStatus.UPLOADED, restoredPt.status)
        assertEquals(failedPt.idempotencyKey, restoredPt.idempotencyKey)
        assertEquals(failedPt.remoteId, restoredPt.remoteId)
        assertEquals(failedPt.projectionRevisionSeq, restoredPt.projectionRevisionSeq)
        assertEquals(failedPt.attemptCount, restoredPt.attemptCount)
        assertEquals(EXACT_RECEIPT, restoredPt.metadataJson)
        assertNull(restoredPt.lastError)
        assertEquals(0, fixture.reader.resolutions)
        assertEquals(0, fixture.reader.submits)

        fixture.orchestrator.markNutritionVerified(ID, fixture.envelope, EVIDENCE)
        val published = fixture.orchestrator.submitProjection(ID, IngestionProjection.FITNESS_NUTRITION, fixture.envelope)
        assertEquals(ProjectionStatus.UPLOADED, published.status)
        assertEquals("original-fitness-key", fixture.requests.single().idempotencyKey)
        assertTrue(fixture.requests.single().recoverCanonicalImport)
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun exactSavedReceiptPromotesFailedPriceSiblingWithoutRecreatingReceipt() = runBlocking {
        val fixture = fixture(exact = true)
        fixture.reader.outcome = ProjectionSubmission.Success(
            RECEIPT, EXACT_RECEIPT, alsoUploaded = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        )
        val previous = fixture.store.get(ID)!!
        val sibling = previous.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.copy(
            status = ProjectionStatus.FAILED, idempotencyKey = "original-price-key", remoteId = null,
            projectionRevisionSeq = 9, attemptCount = 4, lastError = "price response lost",
        )
        fixture.store.save(previous.copy(projections = previous.projections.map {
            if (it.projection == sibling.projection) sibling else it
        }))

        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
        val accepted = restored.projections.single { it.projection == sibling.projection }
        assertEquals(ProjectionStatus.UPLOADED, accepted.status)
        assertEquals(sibling.idempotencyKey, accepted.idempotencyKey)
        assertEquals(sibling.projectionRevisionSeq, accepted.projectionRevisionSeq)
        assertEquals(sibling.attemptCount, accepted.attemptCount)
        assertEquals(RECEIPT, accepted.remoteId)
        assertEquals(EXACT_RECEIPT, accepted.metadataJson)
        assertNull(accepted.lastError)
        assertNotNull(accepted.acceptedReceiptFactsFingerprint)
        assertEquals(restored.pt().acceptedReceiptFactsFingerprint, accepted.acceptedReceiptFactsFingerprint)
        val submitted = fixture.orchestrator.submitProjection(ID, sibling.projection, fixture.envelope)
        assertEquals(ProjectionStatus.UPLOADED, submitted.status)
        assertEquals(0, fixture.reader.submits)
        assertEquals(1, fixture.reader.reads.size)
    }

    @Test
    fun savedReceiptAlsoUploadedCannotEnableDisabledPriceSibling() = runBlocking {
        val fixture = fixture(exact = true)
        fixture.reader.outcome = ProjectionSubmission.Success(
            RECEIPT, EXACT_RECEIPT, alsoUploaded = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        )
        val previous = fixture.store.get(ID)!!
        val disabled = previous.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }
            .copy(status = ProjectionStatus.DISABLED)
        fixture.store.save(previous.copy(projections = previous.projections.map {
            if (it.projection == disabled.projection) disabled else it
        }))
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
        assertEquals(disabled, restored.projections.single { it.projection == disabled.projection })
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun pendingIdentityRejectsFinancialAndDocumentEditsBeforeChangingSession() = runBlocking {
        val receipt = envelope().receipt!!
        val line = receipt.lineItems.single()
        val changedReceipts = listOf(
            receipt.copy(lineItems = listOf(line.copy(quantity = line.quantity!!.copy(value = "2")))),
            receipt.copy(lineItems = listOf(line.copy(unitPriceAmountMinor = 12000))),
            receipt.copy(lineItems = listOf(line.copy(netAmountMinor = 12000))),
            receipt.copy(totals = receipt.totals.copy(grandTotalAmountMinor = 12000)),
            receipt.copy(document = receipt.document.copy(issuedOn = "2026-09-02")),
            receipt.copy(document = receipt.document.copy(id = "another-source-document")),
            receipt.copy(payments = receipt.payments.map { it.copy(amountMinor = 12000) }),
            receipt.copy(lineItems = listOf(line.copy(id = "another-source-line"))),
            receipt.copy(lineItems = listOf(line.copy(identifiers = listOf(ReceiptIdentifier("barcode", "12345678"))))),
        )
        for (changed in changedReceipts) {
            val fixture = fixture()
            val before = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
            assertNotNull(before.pt().acceptedReceiptFactsFingerprint)
            val result = fixture.orchestrator.reviseCanonicalDraft(ID, fixture.envelope.copy(receipt = changed))
            assertEquals(IngestionStartResult.Failure(listOf("accepted_receipt_source_revision_conflict")), result)
            assertEquals(before, fixture.store.get(ID))
            assertEquals(0, fixture.reader.submits)
            assertEquals(0, fixture.reader.resolutions)
        }
    }

    @Test
    fun pendingIdentityAllowsOnlyMerchantMenuSourceAndVerificationEdits() = runBlocking {
        val fixture = fixture()
        val before = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
        val receipt = fixture.envelope.receipt!!
        val changed = fixture.envelope.copy(receipt = receipt.copy(
            merchant = receipt.merchant.copy(name = "Reviewed Restaurant", branchName = "Reviewed branch",
                address = "Reviewed address", phone = "010-1234-5678", businessRegistrationNumber = "1234567890",
                catalogNamespace = "reviewed-menu-source"),
            document = receipt.document.copy(source = receipt.document.source.copy(transcriptionStatus = TranscriptionStatus.PARSED)),
            lineItems = receipt.lineItems.map { it.copy(description = "Reviewed noodles", confidence = ConfidenceLevel.HIGH,
                identifiers = listOf(ReceiptIdentifier("merchant_sku", "MENU-17"))) },
        ))
        val revised = fixture.orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
        assertEquals(before.pt().idempotencyKey, revised.session.pt().idempotencyKey)
        assertEquals(before.pt().remoteId, revised.session.pt().remoteId)
        assertEquals(before.pt().metadataJson, revised.session.pt().metadataJson)
        assertEquals(before.pt().projectionRevisionSeq, revised.session.pt().projectionRevisionSeq)
        assertEquals(before.pt().acceptedReceiptFactsFingerprint, revised.session.pt().acceptedReceiptFactsFingerprint)
        assertLegacyImportRetained(before.fitness(), revised.session.fitness())
        assertEquals(0, fixture.reader.submits)
    }

    @Test
    fun missingLegacyBaselineRequiresTrustedRestoreAndRemainsGuardedAfterStoreReload() = runBlocking {
        val fixture = fixture()
        val ordinary = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
        assertNull(ordinary.pt().acceptedReceiptFactsFingerprint)
        val receipt = fixture.envelope.receipt!!
        val merchantEdit = fixture.envelope.copy(receipt = receipt.copy(merchant = receipt.merchant.copy(address = "Reviewed address")))
        assertEquals(IngestionStartResult.Failure(listOf("accepted_receipt_source_revision_conflict")),
            fixture.orchestrator.reviseCanonicalDraft(ID, merchantEdit))
        assertEquals(ordinary, fixture.store.get(ID))

        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)!!
        val baseline = restored.pt().acceptedReceiptFactsFingerprint
        assertNotNull(baseline)
        val reloaded = IngestionOrchestrator(store = fixture.store, now = { NOW }, submitters = emptyMap())
        val reviewed = reloaded.reviseCanonicalDraft(ID, merchantEdit) as IngestionStartResult.Success
        assertEquals(baseline, reviewed.session.pt().acceptedReceiptFactsFingerprint)
        val beforeFinancialEdit = fixture.store.get(ID)!!
        val financialEdit = merchantEdit.copy(receipt = merchantEdit.receipt!!.copy(
            totals = receipt.totals.copy(grandTotalAmountMinor = 12000),
        ))
        assertEquals(IngestionStartResult.Failure(listOf("accepted_receipt_source_revision_conflict")),
            reloaded.reviseCanonicalDraft(ID, financialEdit))
        assertEquals(beforeFinancialEdit, fixture.store.get(ID))
    }

    @Test
    fun draftFinancialEditBeforeAcceptanceEstablishesNewGuardForPendingReview() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        var submits = 0
        val orchestrator = IngestionOrchestrator(store = store, now = { NOW }, submitters = mapOf(
            IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    submits++
                    return ProjectionSubmission.Success(RECEIPT, NEEDS_REVIEW_RECEIPT, requiresReview = true)
                }
            },
        ))
        val original = envelope()
        val started = orchestrator.start(ID, "local", original, EVIDENCE) as IngestionStartResult.Success
        val receipt = original.receipt!!
        val changed = original.copy(receipt = receipt.copy(
            lineItems = receipt.lineItems.map { it.copy(unitPriceAmountMinor = 12000, grossAmountMinor = 12000, netAmountMinor = 12000) },
            totals = receipt.totals.copy(itemsGrossAmountMinor = 12000, grandTotalAmountMinor = 12000),
            payments = receipt.payments.map { it.copy(amountMinor = 12000) },
        ))
        val revised = orchestrator.reviseCanonicalDraft(ID, changed) as IngestionStartResult.Success
        assertNull(revised.session.pt().acceptedReceiptFactsFingerprint)
        assertTrue(orchestrator.markReceiptVerified(ID, changed, EVIDENCE) is IngestionStartResult.Success)
        val accepted = orchestrator.submitProjection(ID, IngestionProjection.PRICETRACE_RECEIPT, changed)
        assertEquals(ProjectionStatus.UPLOADED, accepted.status)
        assertNotNull(accepted.acceptedReceiptFactsFingerprint)
        assertNotEquals(started.session.pt().acceptedReceiptFactsFingerprint, accepted.acceptedReceiptFactsFingerprint)
        val reviewOnly = changed.copy(review = changed.review.copy(status = IngestionReviewStatus.NEEDS_REVIEW))
        assertTrue(orchestrator.reviseCanonicalDraft(ID, reviewOnly) is IngestionStartResult.Success)
        assertEquals(accepted.acceptedReceiptFactsFingerprint, store.get(ID)!!.pt().acceptedReceiptFactsFingerprint)
        assertEquals(1, submits)
    }

    @Test
    fun newDiningOutProjectionUsesCurrentCompletionVersion() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(store = store, submitters = emptyMap(), now = { NOW })
        val started = orchestrator.start(ID, "local", envelope(), EVIDENCE) as IngestionStartResult.Success
        assertEquals(1, started.session.fitness().completionContractVersion)
        assertNotNull(started.session.pt().acceptedReceiptFactsFingerprint)
        val restored = orchestrator.prepareExistingSession(ID, envelope())!!
        assertEquals(ProjectionStatus.PENDING, restored.fitness().status)
        assertEquals(started.session, restored)
    }

    @Test
    fun exactOwnerResponseRestoresExplicitlyApprovedReviewForReceiptAndStandalone() = runBlocking {
        for (standalone in listOf(false, true)) {
            val envelope = if (standalone) standaloneEnvelope() else envelope()
            val fixture = fixture(exact = true, fitnessMetadata = PUBLICATION, sourceEnvelope = envelope,
                localEvidence = if (standalone) STANDALONE_EVIDENCE else EVIDENCE)
            fixture.reader.outcome = ProjectionSubmission.Success(RECEIPT, if (standalone) EXACT_STANDALONE else EXACT_RECEIPT)
            if (standalone) fixture.orchestrator.markMerchantCandidateVerified(ID, envelope, STANDALONE_EVIDENCE, InputOrigin.EXTERNAL_JSON)
            fixture.store.save(fixture.store.get(ID)!!.copy(reviewStatus = IngestionReviewStatus.NEEDS_REVIEW))
            val restored = fixture.orchestrator.prepareExistingSession(ID, envelope)!!
            assertEquals(IngestionReviewStatus.READY, restored.reviewStatus)
            assertEquals(restored.canonicalFingerprint, restored.verifiedCanonicalFingerprint)
            assertEquals(ProjectionStatus.UPLOADED, restored.fitness().status)
            assertEquals(0, fixture.reader.resolutions)
            assertEquals(0, fixture.reader.submits)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun exactOwnerResponseNeverInventsApprovalForAnUnverifiedRevision() = runBlocking {
        val fixture = fixture(exact = true, fitnessMetadata = PUBLICATION)
        fixture.store.save(fixture.store.get(ID)!!.copy(
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW, verifiedCanonicalFingerprint = null, verifiedAt = null,
            verifiedArtifactFingerprints = emptyMap(),
        ))
        val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, restored.reviewStatus)
        assertNull(restored.verifiedCanonicalFingerprint)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun unknownAuthorityStatusCannotRestoreReadyDespitePriorApprovalAndPublication() = runBlocking {
        for (metadata in listOf(
            EXACT_RECEIPT.replace("\"merchantResolutionStatus\":\"exact\"", "\"merchantResolutionStatus\":\"unknown\""),
            EXACT_RECEIPT.replace("\"resolutionStatus\":\"resolved\"", "\"resolutionStatus\":\"pending\""),
        )) {
            val fixture = fixture(exact = true, fitnessMetadata = PUBLICATION)
            fixture.reader.outcome = ProjectionSubmission.Success(RECEIPT, metadata)
            fixture.store.save(fixture.store.get(ID)!!.copy(reviewStatus = IngestionReviewStatus.NEEDS_REVIEW))
            val restored = fixture.orchestrator.prepareExistingSession(ID, fixture.envelope)!!
            assertEquals(IngestionReviewStatus.NEEDS_REVIEW, restored.reviewStatus)
            assertEquals(ProjectionStatus.PENDING, restored.fitness().status)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun explicitOwnerResolutionClosesReviewAndResumesPublicationWithSameLegacyKey() = runBlocking {
        val fixture = fixture()
        fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)
        fixture.reader.resolutionOutcome = ProjectionSubmission.Success(RECEIPT, RESOLVED_RECEIPT)
        val useCase = recoveryUseCase(fixture)
        val result = useCase.confirm(ID, fixture.envelope, EVIDENCE)
        assertTrue(result.result.toString(), result.result is IngestionStartResult.Success)
        val completed = fixture.store.get(ID)!!
        assertEquals(IngestionReviewStatus.READY, completed.reviewStatus)
        assertEquals(completed.canonicalFingerprint, completed.verifiedCanonicalFingerprint)
        assertEquals(ProjectionStatus.UPLOADED, completed.fitness().status)
        assertEquals("original-fitness-key", completed.fitness().idempotencyKey)
        assertEquals(1, fixture.reader.resolutions)
        assertEquals(1, fixture.requests.size)
        useCase.retrySelected(ID, result.envelope, setOf(IngestionProjection.FITNESS_NUTRITION))
        assertEquals(IngestionReviewStatus.READY, fixture.store.get(ID)!!.reviewStatus)
        assertEquals(1, fixture.reader.resolutions)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun unresolvedOwnerResolutionRetainsReviewAndNeverPublishesNutrition() = runBlocking {
        val fixture = fixture()
        fixture.orchestrator.prepareExistingSession(ID, fixture.envelope, restoredSnapshot = true)
        fixture.reader.resolutionOutcome = ProjectionSubmission.Success(RECEIPT, NEEDS_REVIEW_RECEIPT, requiresReview = true)
        val result = recoveryUseCase(fixture).confirm(ID, fixture.envelope, EVIDENCE)
        assertTrue(result.result is IngestionStartResult.Failure)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, fixture.store.get(ID)!!.reviewStatus)
        assertEquals(result.result.toString(), 1, fixture.reader.resolutions)
        assertTrue(fixture.requests.isEmpty())
    }

    private fun recoveryUseCase(fixture: Fixture) = CanonicalIngestionUseCase(
        store = fixture.store, now = { NOW }, submitters = mapOf(
            IngestionProjection.PRICETRACE_RECEIPT to fixture.reader,
            IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    fixture.requests += request
                    return ProjectionSubmission.Success("food-id", PUBLICATION)
                }
            },
        ),
    )
    private suspend fun fixture(
        exact: Boolean = false,
        fitnessMetadata: String = PRIVATE_IMPORT,
        receiptFactsBaselinePresent: Boolean = false,
        sourceEnvelope: YeonsikOcrEnvelope = envelope(),
        localEvidence: List<LocalEvidence> = EVIDENCE,
    ): Fixture {
        val store = InMemoryIngestionSessionStore()
        val envelope = sourceEnvelope
        val requests = mutableListOf<ProjectionRequest>()
        val reader = Reader(if (envelope.receipt == null) NEEDS_REVIEW_STANDALONE else if (exact) EXACT_RECEIPT else NEEDS_REVIEW_RECEIPT)
        val orchestrator = IngestionOrchestrator(store = store, now = { NOW }, submitters = mapOf(
            IngestionProjection.PRICETRACE_RECEIPT to reader,
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to reader,
            IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                    requests += request
                    return ProjectionSubmission.Success("food-id", PUBLICATION)
                }
            },
        ))
        val started = orchestrator.start(ID, "local", envelope, localEvidence) as IngestionStartResult.Success
        if (envelope.receipt != null) orchestrator.markReceiptVerified(ID, envelope, localEvidence)
        else orchestrator.markPriceObservationsVerified(ID, envelope, localEvidence)
        orchestrator.markNutritionVerified(ID, envelope, localEvidence)
        val verified = store.get(ID)!!
        store.save(verified.copy(projections = started.session.projections.map { state ->
            when (state.projection) {
                IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> if (state.status == ProjectionStatus.DISABLED) state else state.copy(
                    status = ProjectionStatus.UPLOADED, idempotencyKey = "original-pt-key", remoteId = RECEIPT, metadataJson = LEGACY_RECEIPT,
                    acceptedReceiptFactsFingerprint = state.acceptedReceiptFactsFingerprint.takeIf { receiptFactsBaselinePresent },
                )
                IngestionProjection.FITNESS_NUTRITION -> state.copy(
                    status = ProjectionStatus.UPLOADED, idempotencyKey = "original-fitness-key", remoteId = "food-id",
                    metadataJson = fitnessMetadata, projectionRevisionSeq = 7, attemptCount = 3,
                    projectionPayloadFingerprint = "legacy-payload-fingerprint", completionContractVersion = 0,
                )
                else -> state
            }
        }))
        return Fixture(store, orchestrator, envelope, reader, requests)
    }

    private data class Fixture(
        val store: InMemoryIngestionSessionStore,
        val orchestrator: IngestionOrchestrator,
        val envelope: YeonsikOcrEnvelope,
        val reader: Reader,
        val requests: MutableList<ProjectionRequest>,
    )

    private class Reader(metadata: String) : IngestionProjectionSubmitter, OcrProjectionResponseReader, OcrMerchantIdentityResolutionSubmitter {
        var outcome: ProjectionSubmission = ProjectionSubmission.Success(RECEIPT, metadata, requiresReview = metadata == NEEDS_REVIEW_RECEIPT)
        var submits = 0
        var resolutions = 0
        var resolutionOutcome: ProjectionSubmission? = null
        val reads = mutableListOf<ProjectionRequest>()
        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission { submits++; error("PT must not re-ingest") }
        override suspend fun readAcceptedProjection(request: ProjectionRequest): ProjectionSubmission { reads += request; return outcome }
        override suspend fun resolveMerchantIdentity(request: OcrMerchantIdentityResolutionRequest): ProjectionSubmission {
            resolutions++
            val result = resolutionOutcome ?: error("Resolved merchant must not be resolved again")
            // An accepted resolution updates the server's owner-readable saved response.
            if (result is ProjectionSubmission.Success) outcome = result
            return result
        }
    }

    private fun envelope(): YeonsikOcrEnvelope = YeonsikOcrEnvelope(
        mode = IngestionMode.RESTAURANT,
        source = IngestionSource("chatgpt", listOf(SourceAttachment("receipt", SourceAttachmentType.RECEIPT), SourceAttachment("food", SourceAttachmentType.FOOD_PHOTO))),
        receipt = ReceiptV2Json.decode(RECEIPT_JSON, "local"),
        nutrition = listOf(IngestionNutrition.RestaurantEstimate("food-1", "line-1", "Noodles", RestaurantNutritionEstimate(
            nutrients = mapOf(NutritionField.CALORIES_KCAL to 500.0, NutritionField.PROTEIN_GRAMS to 20.0, NutritionField.CARBS_GRAMS to 70.0, NutritionField.FAT_GRAMS to 15.0, NutritionField.SODIUM_MG to 800.0, NutritionField.SATURATED_FAT_GRAMS to 3.0, NutritionField.SUGARS_GRAMS to 5.0),
            estimated = true, confidence = "medium",
        ))),
        links = listOf(IngestionLink("line-1", "food-1")),
        review = IngestionReview(IngestionReviewStatus.READY),
    )

    private fun standaloneEnvelope(): YeonsikOcrEnvelope {
        val original = envelope()
        val estimate = (original.nutrition.single() as IngestionNutrition.RestaurantEstimate).estimate.copy(confidenceScore = 0.7)
        return original.copy(
            receipt = null, links = emptyList(), schemaVersion = YEONSIK_OCR_V3_SCHEMA,
            source = IngestionSource("chatgpt", listOf(SourceAttachment("menu", SourceAttachmentType.MENU_PHOTO))),
            merchantCandidate = MerchantCandidate("Original Restaurant", sourceAttachmentIds = listOf("menu")),
            nutrition = listOf(IngestionNutrition.RestaurantMenuEstimate(
                "food-1", menuName = "Noodles", priceObservationClientKey = "price-menu-1", estimate = estimate,
            )),
            priceObservations = listOf(StandalonePriceObservation(
                clientKey = "price-menu-1", kind = StandalonePriceObservationKind.RESTAURANT_PURCHASE,
                itemName = "Noodles", observedOn = "2026-09-01", netAmountMinor = 10000,
                sourceAttachmentIds = listOf("menu"), confidence = 0.9,
                evidence = listOf(StandalonePriceObservationEvidence("menu_photo", listOf("menu"), "net_amount_minor", "10000")),
            )),
        )
    }

    private fun IngestionSession.fitness() = projections.single { it.projection == IngestionProjection.FITNESS_NUTRITION }
    private fun IngestionSession.pt() = projections.single { it.projection == IngestionProjection.PRICETRACE_RECEIPT }
    private fun IngestionSession.standalonePrice() = projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }

    private companion object {
        const val ID = "legacy-recovery"
        const val NOW = "2026-10-04T00:00:00Z"
        const val RECEIPT = "00000000-0000-4000-8000-000000000001"
        const val RESTAURANT = "00000000-0000-4000-8000-000000000002"
        const val LOCATION = "00000000-0000-4000-8000-000000000003"
        const val MENU = "00000000-0000-4000-8000-000000000004"
        const val CATALOG = "00000000-0000-4000-8000-000000000005"
        const val OTHER_CATALOG = "00000000-0000-4000-8000-000000000006"
        const val LEGACY_RECEIPT = """{"receiptId":"$RECEIPT","merchantResolutionStatus":"needs_user_selection","lines":[]}"""
        const val NEEDS_REVIEW_RECEIPT = """{"receiptId":"$RECEIPT","merchantResolutionStatus":"needs_ocr_resolution","ocrResolution":{"resolutionId":"server-resolution-token","status":"needs_ocr_resolution","reasonCode":"legacy_source_identity_unresolved","requiredSourceFacts":["business_registration_number"]},"lines":[]}"""
        const val EXACT_RECEIPT = """{"receiptId":"$RECEIPT","merchantResolutionStatus":"exact","restaurantId":"$RESTAURANT","restaurantLocationId":"$LOCATION","lines":[{"sourceLineId":"line-1","resolutionStatus":"resolved","restaurantMenuId":"$MENU","catalogProductId":"$CATALOG"}]}"""
        const val NEEDS_REVIEW_STANDALONE = """{"observations":[{"priceObservationClientKey":"price-menu-1","response":{"kind":"restaurant_purchase","merchantResolutionStatus":"needs_ocr_resolution","menuResolutionStatus":"needs_ocr_resolution","ocrResolution":{"resolutionId":"server-standalone-resolution-token","status":"needs_ocr_resolution","requiredSourceFacts":["exact_branch_name_address_and_phone"]}}}]}"""
        val RESOLVED_RECEIPT = EXACT_RECEIPT.dropLast(1) +
            ""","ocrResolution":{"status":"resolved","resolutionId":"server-resolution-token","reasonCode":null,"requiredSourceFacts":[]}}"""
        const val EXACT_STANDALONE = """{"observations":[{"priceObservationClientKey":"price-menu-1","response":{"kind":"restaurant_purchase","merchantResolutionStatus":"exact","menuResolutionStatus":"resolved","authoritativeIds":{"restaurantId":"$RESTAURANT","restaurantLocationId":"$LOCATION","restaurantMenuId":"$MENU","catalogProductId":"$CATALOG"}}}]}"""
        const val PRIVATE_IMPORT = """[[{"canonical_import_id":"00000000-0000-4000-8000-000000000010","nutrition_food_id":"food-id","visibility":"private"}]]"""
        const val PUBLICATION = """[[{"canonical_import_id":"00000000-0000-4000-8000-000000000010","nutrition_food_id":"food-id","visibility":"public","restaurant_id":"$RESTAURANT","restaurant_location_id":"$LOCATION","restaurant_menu_id":"$MENU","catalog_product_id":"$CATALOG","nutrition_link_id":"00000000-0000-4000-8000-000000000011","nutrition_link_revision":1,"food_revision":1,"publication_revision":1,"published_at":"$NOW"}]]"""
        val EVIDENCE = listOf(LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true), LocalEvidence("food", SourceAttachmentType.FOOD_PHOTO, true))
        val STANDALONE_EVIDENCE = listOf(LocalEvidence("menu", SourceAttachmentType.MENU_PHOTO, true))
        const val RECEIPT_JSON = """{"schema_version":"receipt.v2","document":{"id":"source-receipt","type":"receipt","status":"final","issued_on":"2026-09-01","issued_at":null,"currency":"KRW","fulfillment":{"type":"dine_in","evidence":"printed"},"source":{"capture_method":"ocr","original_document_id":null,"source_images":[],"transcription_status":"user_verified","notes":[],"raw_text":null}},"merchant":{"name":"Test Restaurant","branch_name":null,"business_kind":"food_service","retail_channel":"regular","catalog_namespace":null,"merchant_id":null,"business_registration_number":null,"address":null,"phone":null},"line_items":[{"id":"line-1","type":"product","description":"Noodles","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":10000,"gross_amount_minor":10000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":10000,"confidence":"user_verified","tax_rate_percent":null,"food_service":{"role":"main","applies_to_line_id":null}}],"totals":{"items_gross_amount_minor":10000,"discount_amount_minor":0,"fee_amount_minor":0,"tax_amount_minor":0,"tip_amount_minor":0,"rounding_amount_minor":0,"grand_total_amount_minor":10000},"payments":[{"method":"card","amount_minor":10000,"status":"paid","reference":null}]}"""
    }
}
