package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.PlaceResolutionStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.nutrition.NutritionLabelJson
import java.time.OffsetDateTime

enum class IdentityResolutionStatus { RESOLVED, AMBIGUOUS, NOT_FOUND }

data class IdentityResolution(
    val status: IdentityResolutionStatus,
    /** IDs returned by a live authority lookup; never copied from the external envelope. */
    val ids: Map<String, String> = emptyMap(),
    val message: String? = null,
)

interface IngestionIdentityResolver {
    suspend fun resolve(projection: IngestionProjection, envelope: YeonsikOcrEnvelope): IdentityResolution
}

data class ProjectionRequest(
    val ingestionId: String,
    val projection: IngestionProjection,
    val canonicalPayload: String,
    val idempotencyKey: String,
    /** Nullable until the PriceTrace authority projection has returned server-owned identity. */
    val resolvedIdentity: ProjectionIdentity? = null,
    val envelope: YeonsikOcrEnvelope? = null,
    val localDocumentId: String? = null,
    /** Projection/domain revision, not the envelope's canonical revision. */
    val revisionSeq: Long = 1,
    val canonicalFingerprint: String? = null,
    /** Durable metadata from already-uploaded dependencies, never source authority input. */
    val dependencyMetadataJson: Map<IngestionProjection, String> = emptyMap(),
)

sealed interface ProjectionSubmission {
    data class Success(
        val remoteId: String,
        val metadataJson: String? = null,
        /** A single network sink may satisfy multiple durable projection targets. */
        val alsoUploaded: Set<IngestionProjection> = emptySet(),
        /** False when the sink accepted the request but did not create this target yet. */
        val primaryUploaded: Boolean = true,
        val primaryPendingReason: String? = null,
    ) : ProjectionSubmission
    data class Failure(val message: String, val retryable: Boolean) : ProjectionSubmission
}

interface IngestionProjectionSubmitter {
    suspend fun submit(request: ProjectionRequest): ProjectionSubmission
}

interface IngestionSessionStore {
    suspend fun get(ingestionId: String): IngestionSession?
    suspend fun findByCanonicalFingerprint(fingerprint: String): IngestionSession?
    suspend fun findByImportFingerprint(fingerprint: String): IngestionSession? = findByCanonicalFingerprint(fingerprint)
    suspend fun delete(ingestionId: String)
    suspend fun save(session: IngestionSession)
}

class InMemoryIngestionSessionStore : IngestionSessionStore {
    private val values = linkedMapOf<String, IngestionSession>()
    override suspend fun get(ingestionId: String): IngestionSession? = values[ingestionId]
    override suspend fun findByCanonicalFingerprint(fingerprint: String): IngestionSession? =
        values.values.firstOrNull { it.canonicalFingerprint == fingerprint }
    override suspend fun findByImportFingerprint(fingerprint: String): IngestionSession? =
        values.values.firstOrNull { it.importFingerprint == fingerprint }
    override suspend fun delete(ingestionId: String) { values.remove(ingestionId) }
    override suspend fun save(session: IngestionSession) { values[session.ingestionId] = session }
}

sealed interface IngestionStartResult {
    data class Success(val session: IngestionSession) : IngestionStartResult
    data class Duplicate(val session: IngestionSession) : IngestionStartResult
    data class Failure(val issues: List<String>) : IngestionStartResult
}

/** Coordinates independent canonical projections without pretending that three databases share a transaction. */
class IngestionOrchestrator(
    private val store: IngestionSessionStore,
    /** Kept for source compatibility; canonical sinks resolve identity server-side. */
    @Suppress("UNUSED_PARAMETER") private val identityResolver: IngestionIdentityResolver? = null,
    private val submitters: Map<IngestionProjection, IngestionProjectionSubmitter>,
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    suspend fun start(
        ingestionId: String,
        localDocumentId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence> = emptyList(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
    ): IngestionStartResult {
        require(ingestionId.isNotBlank() && localDocumentId.isNotBlank())
        val evidenceResult = IngestionEvidenceGate.evaluate(envelope, evidence, inputOrigin)
        val fingerprint = fingerprint(envelope)
        store.findByImportFingerprint(fingerprint)?.let { existing ->
            return IngestionStartResult.Duplicate(existing)
        }
        val nowValue = now()
        val enabled = enabledProjections(envelope)
        val session = IngestionSession(
            ingestionId = ingestionId,
            localDocumentId = localDocumentId,
            envelopeStorageKey = "$localDocumentId/ingestion/yeonsik-ocr.json",
            canonicalFingerprint = fingerprint,
            reviewStatus = if (evidenceResult.isAllowed) envelope.review.status else IngestionReviewStatus.BLOCKED,
            createdAt = nowValue,
            updatedAt = nowValue,
            attachments = evidence,
            revisionSeq = 1,
            importFingerprint = fingerprint,
            projections = enabled.map { projection ->
                ProjectionState(
                    projection = projection,
                    status = ProjectionStatus.PENDING,
                    updatedAt = nowValue,
                    projectionRevisionSeq = 1,
                    projectionPayloadFingerprint = projectionPayloadFingerprint(projection, envelope),
                )
            } + disabledProjections(envelope).map { projection ->
                ProjectionState(projection = projection, status = ProjectionStatus.DISABLED, updatedAt = nowValue)
            },
        )
        store.save(session)
        return if (evidenceResult.isAllowed) IngestionStartResult.Success(session)
        else IngestionStartResult.Failure(evidenceResult.blockingIssues)
    }

    /** Regenerates the persisted canonical revision and invalidates only affected artifact projections. */
    suspend fun reviseCanonicalDraft(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
    ): IngestionStartResult {
        val current = store.get(ingestionId) ?: return IngestionStartResult.Failure(listOf("ingestion_not_found"))
        val nextFingerprint = fingerprint(envelope)
        if (current.canonicalFingerprint == nextFingerprint) return IngestionStartResult.Success(current)
        val nowValue = now()
        val previousArtifacts = current.verifiedArtifactFingerprints
        val nextArtifacts = artifactFingerprints(envelope)
        val affectedArtifactKeys = changedArtifactKeys(previousArtifacts, nextArtifacts)
        val enabled = enabledProjections(envelope).toSet()
        val revised = current.copy(
            canonicalFingerprint = nextFingerprint,
            revisionSeq = current.revisionSeq + 1,
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            verifiedCanonicalFingerprint = null,
            verifiedAt = null,
            verifiedArtifactFingerprints = previousArtifacts.filter { (key, value) -> nextArtifacts[key] == value },
            updatedAt = nowValue,
            projections = current.projections.map { state ->
                val shouldEnable = state.projection in enabled
                when {
                    state.projection == IngestionProjection.FITNESS_MEAL &&
                        !shouldEnable && state.status != ProjectionStatus.DISABLED ->
                        resetPending(state, nowValue).copy(
                            status = ProjectionStatus.DISABLED,
                            lastError = "consumption_artifact_incomplete",
                            projectionPayloadFingerprint = null,
                        )
                    state.projection == IngestionProjection.FITNESS_MEAL &&
                        state.status == ProjectionStatus.DISABLED && shouldEnable ->
                        resetPending(state, nowValue).copy(
                            projectionPayloadFingerprint = projectionPayloadFingerprint(state.projection, envelope),
                        )
                    state.status == ProjectionStatus.DISABLED -> state.copy(updatedAt = nowValue)
                    else -> {
                        val nextProjectionFingerprint = projectionPayloadFingerprint(state.projection, envelope)
                        val payloadChanged = state.projectionPayloadFingerprint?.let {
                            it != nextProjectionFingerprint
                        } ?: (state.idempotencyKey != null &&
                            projectionIsAffected(state.projection, affectedArtifactKeys))
                        if (payloadChanged) {
                            resetForPayloadChange(state, nowValue, nextProjectionFingerprint)
                        } else {
                            state.copy(
                                projectionPayloadFingerprint = state.projectionPayloadFingerprint
                                    ?: nextProjectionFingerprint,
                                updatedAt = nowValue,
                            )
                        }
                    }
                }
            },
        )
        store.save(revised)
        return IngestionStartResult.Success(revised)
    }

    /** Compatibility entry point: receipt imports verify receipt only; nutrition-only imports verify nutrition. */
    suspend fun markUserVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult = when {
        envelope.receipt != null -> markReceiptVerified(ingestionId, envelope, evidence, inputOrigin, verificationBasis, explicitUserConfirmation)
        envelope.purchaseRecords.isNotEmpty() -> markPurchaseRecordsVerified(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = evidence,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
        envelope.nutrition.isNotEmpty() -> markNutritionVerified(ingestionId, envelope, evidence, inputOrigin = inputOrigin, verificationBasis = verificationBasis, explicitUserConfirmation = explicitUserConfirmation)
        envelope.priceObservations.isNotEmpty() -> markPriceObservationsVerified(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = evidence,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
        envelope.productCandidates.isNotEmpty() -> markProductCandidatesVerified(ingestionId, envelope, evidence, inputOrigin = inputOrigin, verificationBasis = verificationBasis, explicitUserConfirmation = explicitUserConfirmation)
        envelope.merchantCandidate != null -> markMerchantCandidateVerified(ingestionId, envelope, evidence, inputOrigin, verificationBasis, explicitUserConfirmation)
        else -> IngestionStartResult.Failure(listOf("no_reviewable_artifact"))
    }

    suspend fun markReceiptVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult = markArtifactsVerified(
        ingestionId = ingestionId,
        envelope = envelope,
        evidence = evidence,
        artifactKeys = buildSet {
            add(IngestionArtifactKeys.RECEIPT)
            if (envelope.receipt != null) add(IngestionArtifactKeys.CASHOS_HINTS)
        },
        inputOrigin = inputOrigin,
        verificationBasis = verificationBasis,
        explicitUserConfirmation = explicitUserConfirmation,
    )

    suspend fun markNutritionVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        nutritionClientKeys: Set<String> = envelope.nutrition.map { it.clientKey }.toSet(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult {
        val requestedKeys = nutritionClientKeys.map(IngestionArtifactKeys::nutrition).toSet()
        if (requestedKeys.isEmpty()) return IngestionStartResult.Failure(listOf("nutrition_artifact_missing"))
        val unreviewedProductLabel = envelope.nutrition
            .filter { IngestionArtifactKeys.nutrition(it.clientKey) in requestedKeys }
            .filterIsInstance<IngestionNutrition.ProductLabel>()
            .any { it.draft.status != NutritionDraftStatus.USER_VERIFIED }
        if (unreviewedProductLabel) {
            return IngestionStartResult.Failure(listOf("nutrition_artifact_not_user_verified"))
        }
        return markArtifactsVerified(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = evidence,
            artifactKeys = requestedKeys,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
    }

    suspend fun markPriceObservationsVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        priceObservationClientKeys: Set<String> = envelope.priceObservations.map { it.clientKey }.toSet(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult {
        val requestedKeys = priceObservationClientKeys.map(IngestionArtifactKeys::priceObservation).toSet()
        if (requestedKeys.isEmpty()) return IngestionStartResult.Failure(listOf("price_observation_artifact_missing"))
        return markArtifactsVerified(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = evidence,
            artifactKeys = requestedKeys,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
    }

    suspend fun markPurchaseRecordsVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        purchaseRecordClientKeys: Set<String> = envelope.purchaseRecords.map { it.clientKey }.toSet(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult {
        val requestedKeys = purchaseRecordClientKeys.map(IngestionArtifactKeys::purchaseRecord).toSet()
        if (requestedKeys.isEmpty()) return IngestionStartResult.Failure(listOf("purchase_record_artifact_missing"))
        return markArtifactsVerified(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = evidence,
            artifactKeys = requestedKeys,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
    }

    suspend fun markConsumptionVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        consumptionClientKeys: Set<String> = envelope.consumption.map { it.clientKey }.toSet(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult {
        val selected = envelope.consumption.filter { it.clientKey in consumptionClientKeys }
        if (selected.isEmpty()) return IngestionStartResult.Failure(listOf("consumption_artifact_missing"))
        if (envelope.schemaVersion in setOf(YEONSIK_OCR_V2_SCHEMA, YEONSIK_OCR_V3_SCHEMA) && selected.any { !it.isCompleteForFitnessMeal() }) {
            return IngestionStartResult.Failure(listOf("consumption_artifact_incomplete"))
        }
        if (selected.any { it.status != ConsumptionVerificationStatus.USER_VERIFIED }) {
            return IngestionStartResult.Failure(listOf("consumption_artifact_not_user_verified"))
        }
        val nutritionKeys = selected.flatMap { it.effectiveNutritionClientKeys }.map(IngestionArtifactKeys::nutrition).toSet()
        return markArtifactsVerified(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = evidence,
            artifactKeys = selected.map { IngestionArtifactKeys.consumption(it.clientKey) }.toSet() + nutritionKeys,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
    }

    suspend fun markProductCandidatesVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        productClientKeys: Set<String> = envelope.productCandidates.map { it.clientKey }.toSet(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult {
        val requestedKeys = productClientKeys.map(IngestionArtifactKeys::productCandidate).toSet()
        if (requestedKeys.isEmpty()) return IngestionStartResult.Failure(listOf("product_candidate_artifact_missing"))
        return markArtifactsVerified(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = evidence,
            artifactKeys = requestedKeys,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
    }

    suspend fun markMerchantCandidateVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        inputOrigin: InputOrigin,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult = markArtifactsVerified(
        ingestionId = ingestionId,
        envelope = envelope,
        evidence = evidence,
        artifactKeys = setOf(IngestionArtifactKeys.MERCHANT_CANDIDATE),
        inputOrigin = inputOrigin,
        verificationBasis = verificationBasis,
        explicitUserConfirmation = explicitUserConfirmation,
    )

    private suspend fun markArtifactsVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        artifactKeys: Set<String>,
        inputOrigin: InputOrigin,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionStartResult {
        val current = store.get(ingestionId) ?: return IngestionStartResult.Failure(listOf("ingestion_not_found"))
        val currentFingerprint = fingerprint(envelope)
        if (current.canonicalFingerprint != currentFingerprint) {
            invalidateVerification(current, envelope, "canonical_fingerprint_mismatch")
            return IngestionStartResult.Failure(listOf("canonical_fingerprint_mismatch"))
        }
        val gate = IngestionEvidenceGate.evaluate(
            envelope = envelope,
            evidence = evidence,
            inputOrigin = inputOrigin,
            artifactKeys = artifactKeys,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
        if (!gate.isAllowed) return IngestionStartResult.Failure(gate.blockingIssues)
        val artifacts = artifactFingerprints(envelope)
        val missingArtifacts = artifactKeys - artifacts.keys
        if (missingArtifacts.isNotEmpty()) {
            return IngestionStartResult.Failure(listOf("verification_artifact_missing"))
        }
        val nowValue = now()
        val mergedArtifactFingerprints = current.verifiedArtifactFingerprints + artifactKeys.associateWith { artifacts.getValue(it) }
        val envelopeFullyVerified = artifacts.keys.all { mergedArtifactFingerprints[it] == artifacts[it] }
        val updated = current.copy(
            reviewStatus = IngestionReviewStatus.READY,
            attachments = evidence,
            verifiedCanonicalFingerprint = currentFingerprint.takeIf { envelopeFullyVerified },
            verifiedAt = nowValue.takeIf { envelopeFullyVerified },
            verifiedArtifactFingerprints = mergedArtifactFingerprints,
            updatedAt = nowValue,
            projections = current.projections.map { state ->
                when {
                    state.status == ProjectionStatus.DISABLED || state.status == ProjectionStatus.UPLOADED -> state
                    projectionIsAffected(state.projection, artifactKeys) -> state.copy(status = ProjectionStatus.PENDING, lastError = null, updatedAt = nowValue)
                    else -> state
                }
            },
        )
        store.save(updated)
        return IngestionStartResult.Success(updated)
    }
    suspend fun submitProjection(
        ingestionId: String,
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): ProjectionState {
        var session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        val current = requireNotNull(session.projections.firstOrNull { it.projection == projection }) { "projection_not_configured" }
        if (current.status == ProjectionStatus.DISABLED || current.status == ProjectionStatus.UPLOADED) return current
        if (fingerprint(envelope) != session.canonicalFingerprint) {
            val invalidated = invalidateVerification(session, envelope, "canonical_fingerprint_mismatch")
            return invalidated.projections.first { it.projection == projection }
        }
        if (projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION &&
            envelope.priceObservations.isNotEmpty() &&
            envelope.priceObservations.any { it.netAmountMinor == null }
        ) {
            return persistBlocked(session, projection, current, "price_observation_net_amount_required")
        }
        if (projection == IngestionProjection.FITNESS_MEAL &&
            envelope.schemaVersion in setOf(YEONSIK_OCR_V2_SCHEMA, YEONSIK_OCR_V3_SCHEMA) &&
            !fitnessMealValuesComplete(envelope)
        ) {
            return persistBlocked(session, projection, current, "consumption_artifact_incomplete")
        }
        if (projection == IngestionProjection.FITNESS_MEAL &&
            envelope.schemaVersion in setOf(YEONSIK_OCR_V2_SCHEMA, YEONSIK_OCR_V3_SCHEMA) &&
            envelope.consumption.any { it.status != ConsumptionVerificationStatus.USER_VERIFIED }
        ) {
            return persistBlocked(session, projection, current, "consumption_artifact_not_user_verified")
        }
        val requiredArtifacts = requiredArtifactKeys(projection, envelope)
        val currentArtifacts = artifactFingerprints(envelope)
        if (requiredArtifacts.isEmpty() || requiredArtifacts.any {
                session.verifiedArtifactFingerprints[it] != currentArtifacts[it]
            }) {
            return persistBlocked(session, projection, current, "projection_not_reverified")
        }
        val dependencies = projectionDependencies(projection, envelope)
        val missingDependencies = dependencies.filter { dependency ->
            session.projections.firstOrNull { it.projection == dependency }?.status != ProjectionStatus.UPLOADED
        }
        if (missingDependencies.isNotEmpty()) {
            return persistBlocked(
                session,
                projection,
                current,
                "dependency_pending:" + missingDependencies.joinToString(",") { it.wireValue },
            )
        }
        val resolvedIdentity = resolvedIdentity(session)
        if (requiresPriceTraceIdentity(projection, envelope) && resolvedIdentity?.priceTrace == null) {
            return persistBlocked(session, projection, current, "pricetrace_identity_missing")
        }
        if (requiresProductCandidateIdentity(projection, envelope) &&
            (resolvedIdentity == null || resolvedIdentity.productCandidates.isEmpty())
        ) {
            return persistBlocked(session, projection, current, "pricetrace_product_identity_missing")
        }
        require(current.status in setOf(ProjectionStatus.PENDING, ProjectionStatus.BLOCKED, ProjectionStatus.FAILED)) {
            "projection_not_retryable"
        }
        val submitter = submitters[projection]
            ?: return persistBlocked(session, projection, current, "projection_not_configured")
        val payloadFingerprint = projectionPayloadFingerprint(projection, envelope)
        if (current.projectionPayloadFingerprint != null &&
            current.projectionPayloadFingerprint != payloadFingerprint
        ) {
            val invalidated = resetForPayloadChange(current, now(), payloadFingerprint).copy(
                status = ProjectionStatus.BLOCKED,
                lastError = "projection_payload_changed",
            )
            store.save(session.copy(updatedAt = now(), projections = session.projections.replace(invalidated)))
            return invalidated
        }
        val projectionRevisionSeq = current.projectionRevisionSeq.coerceAtLeast(1)
        val key = current.idempotencyKey ?: StableIds.sha256(
            "projection|${projection.wireValue}|${session.localDocumentId}|" +
                "projection_revision=${projectionRevisionSeq}|payload=${payloadFingerprint}",
        )
        val attempted = current.copy(
            idempotencyKey = key,
            projectionRevisionSeq = projectionRevisionSeq,
            projectionPayloadFingerprint = payloadFingerprint,
            attemptCount = current.attemptCount + 1,
            lastError = null,
            updatedAt = now(),
        )
        session = session.copy(updatedAt = now(), projections = session.projections.replace(attempted))
        store.save(session)
        val request = ProjectionRequest(
            ingestionId = ingestionId,
            projection = projection,
            canonicalPayload = YeonsikOcrEnvelopeCodec.encode(envelope),
            resolvedIdentity = resolvedIdentity,
            idempotencyKey = key,
            envelope = envelope,
            localDocumentId = session.localDocumentId,
            revisionSeq = projectionRevisionSeq,
            canonicalFingerprint = session.canonicalFingerprint,
            dependencyMetadataJson = session.projections
                .filter { it.status == ProjectionStatus.UPLOADED && it.metadataJson != null }
                .associate { it.projection to it.metadataJson!! },
        )
        return when (val result = submitter.submit(request)) {
            is ProjectionSubmission.Success -> persistSuccess(session, projection, attempted, key, result, envelope)
            is ProjectionSubmission.Failure -> if (result.retryable) {
                persistFailure(session, projection, attempted, result.message, key)
            } else {
                persistBlocked(session, projection, attempted, result.message, key)
            }
        }
    }

    /** Submits retryable/ready projections in dependency order in one user action. */
    suspend fun submitAllReadyProjections(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
    ): List<ProjectionState> {
        val initial = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        val ordered = initial.projections
            .map(ProjectionState::projection)
            .sortedWith(compareBy({ projectionDependencyRank(it) }, { it.wireValue }))
        ordered.forEach { projection ->
            val current = store.get(ingestionId)?.projections?.firstOrNull { it.projection == projection }
                ?: return@forEach
            val retryable = current.status == ProjectionStatus.PENDING ||
                current.status == ProjectionStatus.FAILED ||
                (current.status == ProjectionStatus.BLOCKED && current.lastError.isDependencyRetryable())
            if (retryable) submitProjection(ingestionId, projection, envelope)
        }
        return requireNotNull(store.get(ingestionId)).projections
    }

    /** Submit only user-selected eligible sinks and their required dependencies. */
    suspend fun submitSelectedProjections(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        selectedProjections: Set<IngestionProjection>,
    ): List<ProjectionState> {
        val plan = CanonicalProjectionPlanner.plan(envelope)
        val selected = selectedProjections.intersect(plan.eligible)
        val closure = buildSet {
            fun addWithDependencies(projection: IngestionProjection) {
                if (!add(projection)) return
                projectionDependencies(projection, envelope).forEach(::addWithDependencies)
            }
            selected.forEach(::addWithDependencies)
        }
        closure.sortedWith(compareBy({ projectionDependencyRank(it) }, { it.wireValue })).forEach { projection ->
            val current = store.get(ingestionId)?.projections?.firstOrNull { it.projection == projection }
                ?: return@forEach
            val retryable = current.status == ProjectionStatus.PENDING ||
                current.status == ProjectionStatus.FAILED ||
                (current.status == ProjectionStatus.BLOCKED && current.lastError.isDependencyRetryable())
            if (retryable) submitProjection(ingestionId, projection, envelope)
        }
        return requireNotNull(store.get(ingestionId)).projections
    }

    suspend fun retrySelectedProjections(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        selectedProjections: Set<IngestionProjection>,
    ): List<ProjectionState> = submitSelectedProjections(ingestionId, envelope, selectedProjections)

    suspend fun retryFailed(ingestionId: String, envelope: YeonsikOcrEnvelope): List<ProjectionState> {
        val session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        return session.projections.filter { it.status == ProjectionStatus.FAILED || it.status == ProjectionStatus.BLOCKED }.map {
            submitProjection(ingestionId, it.projection, envelope)
        }
    }

    /** Compatibility hook for legacy publishers; canonical flow uses submitProjection directly. */
    suspend fun recordProjectionUploaded(
        ingestionId: String,
        projection: IngestionProjection,
        remoteId: String,
        idempotencyKey: String? = null,
        metadataJson: String? = null,
    ): ProjectionState {
        val session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        val current = requireNotNull(session.projections.firstOrNull { it.projection == projection }) { "projection_not_configured" }
        if (current.status == ProjectionStatus.DISABLED || current.status == ProjectionStatus.UPLOADED) return current
        val updated = current.copy(
            status = ProjectionStatus.UPLOADED,
            idempotencyKey = idempotencyKey ?: current.idempotencyKey ?: StableIds.sha256("projection-record|${projection.wireValue}|$remoteId"),
            remoteId = remoteId,
            metadataJson = metadataJson ?: current.metadataJson,
            lastError = null,
            updatedAt = now(),
        )
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updated)))
        return updated
    }

    suspend fun recordProjectionBlocked(ingestionId: String, projection: IngestionProjection, message: String): ProjectionState {
        val session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        val current = requireNotNull(session.projections.firstOrNull { it.projection == projection }) { "projection_not_configured" }
        if (current.status == ProjectionStatus.DISABLED || current.status == ProjectionStatus.UPLOADED) return current
        val updated = current.copy(status = ProjectionStatus.BLOCKED, lastError = message, updatedAt = now())
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updated)))
        return updated
    }

    suspend fun recordProjectionFailure(
        ingestionId: String,
        projection: IngestionProjection,
        message: String,
        idempotencyKey: String? = null,
    ): ProjectionState {
        val session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        val current = requireNotNull(session.projections.firstOrNull { it.projection == projection }) { "projection_not_configured" }
        if (current.status == ProjectionStatus.DISABLED || current.status == ProjectionStatus.UPLOADED) return current
        val updated = current.copy(
            status = ProjectionStatus.FAILED,
            idempotencyKey = idempotencyKey ?: current.idempotencyKey,
            attemptCount = current.attemptCount + 1,
            lastError = message,
            updatedAt = now(),
        )
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updated)))
        return updated
    }

    private fun fingerprint(envelope: YeonsikOcrEnvelope): String =
        StableIds.sha256("ingestion|${YeonsikOcrEnvelopeCodec.canonicalize(envelope)}")

    private fun artifactFingerprints(envelope: YeonsikOcrEnvelope): Map<String, String> = buildMap {
        envelope.receipt?.let { receipt ->
            put(
                IngestionArtifactKeys.RECEIPT,
                StableIds.sha256("ingestion-artifact|receipt|${ReceiptV2Json.encodeCanonical(receipt)}"),
            )
        }

        if (envelope.receipt != null) {
            put(
                IngestionArtifactKeys.CASHOS_HINTS,
                StableIds.sha256("ingestion-artifact|cashos-hints|" + cashosHintsInput(envelope)),
            )
        }
        envelope.priceObservations.forEach { observation ->
            put(
                IngestionArtifactKeys.priceObservation(observation.clientKey),
                StableIds.sha256(
                    "ingestion-artifact|price-observation|" + standalonePriceObservationDependency(observation),
                ),
            )
        }
        envelope.purchaseRecords.forEach { record ->
            put(
                IngestionArtifactKeys.purchaseRecord(record.clientKey),
                StableIds.sha256(
                    "ingestion-artifact|purchase-record|" + purchaseRecordPayloadDependency(record),
                ),
            )
        }
        envelope.nutrition.forEach { item ->
            put(
                IngestionArtifactKeys.nutrition(item.clientKey),
                nutritionArtifactFingerprint(envelope, item),
            )
        }
        envelope.consumption.forEach { item ->
            put(
                IngestionArtifactKeys.consumption(item.clientKey),
                StableIds.sha256(
                    "ingestion-artifact|consumption|" +
                        consumptionPayloadDependency(
                            item,
                            includeAmountStatus = envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA ||
                                envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA,
                            includeAuthorityStatus = envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA,
                        ),
                ),
            )
        }
        envelope.productCandidates.forEach { candidate ->
            put(
                IngestionArtifactKeys.productCandidate(candidate.clientKey),
                StableIds.sha256("ingestion-artifact|product-candidate|${productCandidatePayloadDependency(candidate)}"),
            )
        }
        envelope.merchantCandidate?.let {
            put(
                IngestionArtifactKeys.MERCHANT_CANDIDATE,
                StableIds.sha256("ingestion-artifact|merchant|${merchantPayloadDependency(it)}"),
            )
        }
    }

    /**
     * Keep this dependency list aligned with the data used by
     * FitnessCanonicalProjectionSubmitter. In particular, a restaurant estimate uses the
     * receipt merchant name (or merchant candidate when there is no receipt).
     */
    private fun nutritionPayloadDependency(
        envelope: YeonsikOcrEnvelope,
        item: IngestionNutrition,
    ): String = when (item) {
        is IngestionNutrition.ProductLabel -> {
            val product = item.productClientKey?.let { productClientKey ->
                envelope.productCandidates.singleOrNull { candidate ->
                    candidate.clientKey == productClientKey
                }
            }
            "product_label|client_key=" + item.clientKey +
                "|product_client_key=" + (item.productClientKey ?: "<null>") +
                "|product=" + (product?.let(::productCandidatePayloadDependency) ?: "<missing>") +
                "|" + NutritionLabelJson.encode(
                    if (envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA) {
                        item.draft.copy(
                            status = com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus.PARSED,
                            confirmedAt = null,
                        )
                    } else item.draft,
                )
        }
        is IngestionNutrition.RestaurantEstimate -> {
            val artifactEnvelope = YeonsikOcrEnvelope(
                mode = IngestionMode.RESTAURANT,
                source = IngestionSource(producer = "fitness", sourceFiles = emptyList()),
                nutrition = listOf(item),
                schemaVersion = envelope.schemaVersion,
            )
            "restaurant_estimate|client_key=" + item.clientKey +
                "|restaurant_name=" + (fitnessRestaurantName(envelope) ?: "<null>") +
                "|" + YeonsikOcrEnvelopeCodec.encode(artifactEnvelope, canonicalIds = true)
        }

        is IngestionNutrition.RestaurantMenuEstimate -> {
            val artifactEnvelope = YeonsikOcrEnvelope(
                mode = IngestionMode.RESTAURANT,
                source = IngestionSource(producer = "fitness", sourceFiles = emptyList()),
                nutrition = listOf(item),
                schemaVersion = envelope.schemaVersion,
            )
            "restaurant_menu_estimate|client_key=" + item.clientKey +
                "|price_observation=" + item.priceObservationClientKey +
                "|price_observation_payload=" + (
                    envelope.priceObservations.singleOrNull {
                        it.clientKey == item.priceObservationClientKey
                    }?.let(::standalonePriceObservationDependency) ?: "<missing>"
                ) +
                "|restaurant_name=" + (fitnessRestaurantName(envelope) ?: "<null>") +
                "|" + YeonsikOcrEnvelopeCodec.encode(artifactEnvelope, canonicalIds = true)
        }
        is IngestionNutrition.MealComponentEstimate -> {
            val artifactEnvelope = YeonsikOcrEnvelope(
                mode = IngestionMode.RESTAURANT,
                source = IngestionSource(producer = "fitness", sourceFiles = emptyList()),
                nutrition = listOf(item),
                schemaVersion = envelope.schemaVersion,
            )
            "meal_component_estimate|client_key=" + item.clientKey +
                "|restaurant_name=" + (item.reference?.restaurantName ?: fitnessRestaurantName(envelope) ?: "<null>") +
                "|branch_name=" + (item.reference?.branchName ?: "<null>") +
                "|" + YeonsikOcrEnvelopeCodec.encode(artifactEnvelope, canonicalIds = true)
        }
    }

    private fun nutritionArtifactFingerprint(
        envelope: YeonsikOcrEnvelope,
        item: IngestionNutrition,
    ): String = StableIds.sha256(
        "ingestion-artifact|nutrition|" + nutritionPayloadDependency(envelope, item),
    )

    private fun fitnessRestaurantName(envelope: YeonsikOcrEnvelope): String? =
        envelope.receipt?.merchant?.name ?: envelope.merchantCandidate?.name

    private fun projectionPayloadFingerprint(
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): String = StableIds.sha256(
        when (projection) {
            IngestionProjection.PRICETRACE_RECEIPT ->
                "pricetrace-receipt|" + priceTraceReceiptPayload(envelope.receipt)
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION ->
                if (envelope.purchaseRecords.isNotEmpty()) {
                    "pricetrace-purchase-price-v4|" + envelope.purchaseRecords
                        .sortedBy(PurchaseRecord::clientKey)
                        .joinToString("|") { purchaseRecordPayloadDependency(it) }
                } else if (envelope.priceObservations.isNotEmpty()) {
                    "pricetrace-standalone-price|merchant=" + merchantPayloadDependency(envelope.merchantCandidate) +
                        "|products=" + envelope.priceObservations
                        .filter { it.kind == StandalonePriceObservationKind.RETAIL_PURCHASE }
                        .mapNotNull { observation ->
                            envelope.productCandidates.singleOrNull {
                                it.clientKey == observation.productClientKey
                            }
                        }
                        .sortedBy(ProductCandidate::clientKey)
                        .joinToString("|") { productCandidatePayloadDependency(it) } +
                        "|observations=" + envelope.priceObservations
                        .sortedBy(StandalonePriceObservation::clientKey)
                        .joinToString("|", transform = ::standalonePriceObservationDependency)
                } else {
                    "pricetrace-receipt-price|" + priceTraceReceiptPayload(envelope.receipt)
                }
            IngestionProjection.CASHOS_TRANSACTION ->
                "cashos-transaction-v4|" + envelope.purchaseRecords
                    .sortedBy(PurchaseRecord::clientKey)
                    .joinToString("|") { purchaseRecordPayloadDependency(it) } +
                    "|hints=" + cashosHintsInput(envelope)
            IngestionProjection.CASHOS_RECEIPT ->
                "cashos-receipt|" +
                    (envelope.receipt?.let { ReceiptV2Json.encodeCanonical(it) } ?: "<none>") +
                    "|hints=" + cashosHintsInput(envelope) +
                    "|place=" + cashosPlaceDependency(envelope.receipt)
            IngestionProjection.FITNESS_NUTRITION ->
                "fitness|" + envelope.nutrition.joinToString("|") { item ->
                    nutritionPayloadDependency(envelope, item)
                }
            IngestionProjection.FITNESS_MEAL ->
                "fitness-meal|" + envelope.nutrition.joinToString("|") { item ->
                    nutritionPayloadDependency(envelope, item)
                } + "|consumption=" + envelope.consumption.joinToString("|") { consumption ->
                    consumptionPayloadDependency(
                        consumption,
                        includeAmountStatus = envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA ||
                            envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA,
                        includeAuthorityStatus = envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA,
                    )
                }
            IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE ->
                "pricetrace-product-candidate|" + envelope.productCandidates
                    .sortedBy(ProductCandidate::clientKey)
                    .joinToString("|", transform = ::productCandidatePayloadDependency)
            IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK ->
                "fitness-product-nutrition-link|products=" + envelope.productCandidates
                    .sortedBy(ProductCandidate::clientKey)
                    .joinToString("|", transform = ::productCandidatePayloadDependency) +
                    "|nutrition=" + envelope.nutrition.sortedBy(IngestionNutrition::clientKey)
                    .joinToString("|") { item -> nutritionPayloadDependency(envelope, item) }
            IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE ->
                "merchant|" + merchantPayloadDependency(envelope.merchantCandidate)
        },
    )

    private fun priceTraceReceiptPayload(receipt: ReceiptV2?): String =
        receipt?.let { value ->
            val sanitized = value.copy(
                document = value.document.copy(
                    source = value.document.source.copy(sourceImages = emptyList(), rawText = null),
                ),
                payments = value.payments.map { payment -> payment.copy(reference = null) },
            )
            ReceiptV2Json.encodeCanonical(sanitized)
        } ?: "<none>"

    private fun cashosHintsInput(envelope: YeonsikOcrEnvelope): String =
        envelope.classificationHints
            .filterKeys {
                it in setOf(
                    "cashos.category_hint",
                    "cashos.institution_hint",
                    "cashos.payment_method_hint",
                )
            }
            .toSortedMap()
            .entries
            .joinToString("|") { (key, value) -> key + "=" + (value ?: "<null>") }

    private fun cashosPlaceDependency(receipt: ReceiptV2?): String {
        val selected = receipt?.placeResolution?.selectedCandidate
            ?.takeIf { receipt.placeResolution.status == PlaceResolutionStatus.USER_CONFIRMED }
        return selected?.let {
            (it.restaurantId ?: "<null>") + "|" + (it.restaurantLocationId ?: "<null>")
        } ?: "<none>"
    }

    private fun merchantPayloadDependency(merchant: MerchantCandidate?): String =
        merchant?.let {
            listOf(
                it.name,
                it.branchName,
                it.businessKind.wireValue,
                it.businessRegistrationNumber,
                it.address,
                it.phone,
                it.sourceNamespace,
                it.sourceLocationCode,
            ).joinToString("|") { value -> value ?: "<null>" }
        } ?: "<none>"

    private fun consumptionPayloadDependency(
        consumption: IngestionConsumption,
        includeAmountStatus: Boolean,
        includeAuthorityStatus: Boolean,
    ): String {
        val itemDependency = consumption.items.joinToString(",") { item ->
            if (includeAmountStatus) {
                listOf(item.nutritionClientKey, item.amount, item.unit, item.confidence, item.amountStatus)
                    .joinToString("/")
            } else {
                listOf(item.nutritionClientKey, item.amount, item.unit, item.confidence)
                    .joinToString("/")
            }
        }
        return if (includeAuthorityStatus) {
            listOf(
                consumption.clientKey,
                consumption.effectiveNutritionClientKeys.toSortedSet().joinToString(","),
                consumption.consumedAt ?: "<null>",
                consumption.status.wireValue,
                itemDependency,
            ).joinToString("|")
        } else if (includeAmountStatus) {
            listOf(
                consumption.clientKey,
                consumption.effectiveNutritionClientKeys.toSortedSet().joinToString(","),
                consumption.consumedAt ?: "<null>",
                ConsumptionVerificationStatus.UNVERIFIED.wireValue,
                itemDependency,
            ).joinToString("|")
        } else {
            listOf(
                consumption.clientKey,
                consumption.effectiveNutritionClientKeys.toSortedSet().joinToString(","),
                consumption.consumedAt ?: "<null>",
                itemDependency,
            ).joinToString("|")
        }
    }

    private fun standalonePriceObservationDependency(observation: StandalonePriceObservation): String = listOf(
        observation.clientKey,
        observation.kind.wireValue,
        observation.productClientKey,
        observation.itemName,
        observation.observedOn,
        observation.observedAt,
        observation.currency,
        observation.quantity?.let { quantity -> "${quantity.value}/${quantity.unit}" },
        observation.unitPriceAmountMinor,
        observation.grossAmountMinor,
        observation.discountAmountMinor,
        observation.netAmountMinor,
        observation.sourceAttachmentIds.sorted().joinToString(","),
        observation.evidence.sortedWith(compareBy(
            { it.sourceType },
            { it.sourceAttachmentIds.joinToString(",") },
            { it.field },
        )).joinToString(",") { evidence ->
            listOf(
                evidence.sourceType,
                evidence.sourceAttachmentIds.sorted().joinToString(","),
                evidence.field,
                evidence.observedValue,
            ).joinToString("/") { it ?: "<null>" }
        },
        observation.confidence,
    ).joinToString("|") { it?.toString() ?: "<null>" }

    private fun productCandidatePayloadDependency(candidate: ProductCandidate): String = listOf<Any?>(
        candidate.clientKey,
        candidate.productName,
        candidate.brand,
        candidate.subBrand,
        candidate.manufacturer,
        candidate.specification,
        candidate.contentAmount,
        candidate.contentUnit,
        candidate.packageCount,
        candidate.variant,
        candidate.barcodes.joinToString(",") { barcode ->
            listOf(barcode.type, barcode.value).joinToString("/")
        },
        candidate.effectiveSourceAttachmentIds.sorted().joinToString(","),
        candidate.confidence,
        candidate.candidateType,
        candidate.sourceVersion,
        candidate.merchantSku,
        candidate.evidence.sortedWith(compareBy(
            { it.sourceAttachmentIds.joinToString(",") },
            { it.field },
            { it.sourceRef.orEmpty() },
        )).joinToString(",") { evidence ->
            listOf(
                evidence.sourceAttachmentIds.sorted().joinToString(","),
                evidence.source,
                evidence.sourceType,
                evidence.sourceRef,
                evidence.field,
                evidence.observedValue,
                evidence.contentHash,
            ).joinToString("/") { it ?: "<null>" }
        },
    ).joinToString("|") { it?.toString() ?: "<null>" }

    private fun purchaseRecordPayloadDependency(record: PurchaseRecord): String =
        YeonsikOcrV4Json.encodePurchaseRecord(record).toString()

    private fun requiredArtifactKeys(
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): Set<String> = when (projection) {
        IngestionProjection.PRICETRACE_RECEIPT -> if (envelope.receipt != null) {
            setOf(IngestionArtifactKeys.RECEIPT)
        } else emptySet()
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> if (envelope.purchaseRecords.isNotEmpty()) {
            envelope.purchaseRecords.map { IngestionArtifactKeys.purchaseRecord(it.clientKey) }.toSet()
        } else if (envelope.priceObservations.isNotEmpty()) buildSet {
            addAll(envelope.priceObservations.map { IngestionArtifactKeys.priceObservation(it.clientKey) })
            if (envelope.merchantCandidate != null) add(IngestionArtifactKeys.MERCHANT_CANDIDATE)
            envelope.priceObservations.filter {
                it.kind == StandalonePriceObservationKind.RETAIL_PURCHASE
            }.mapNotNull { observation -> observation.productClientKey }
                .forEach { add(IngestionArtifactKeys.productCandidate(it)) }
        } else if (envelope.receipt != null) {
            setOf(IngestionArtifactKeys.RECEIPT)
        } else emptySet()
        IngestionProjection.CASHOS_RECEIPT -> if (envelope.receipt != null) {
            setOf(IngestionArtifactKeys.RECEIPT, IngestionArtifactKeys.CASHOS_HINTS)
        } else emptySet()
        IngestionProjection.CASHOS_TRANSACTION ->
            envelope.purchaseRecords.map { IngestionArtifactKeys.purchaseRecord(it.clientKey) }.toSet()
        IngestionProjection.FITNESS_NUTRITION -> buildSet {
            addAll(envelope.nutrition.map { IngestionArtifactKeys.nutrition(it.clientKey) })
            envelope.nutrition.filterIsInstance<IngestionNutrition.ProductLabel>()
                .mapNotNull { it.productClientKey }
                .forEach { add(IngestionArtifactKeys.productCandidate(it)) }
            envelope.nutrition.filterIsInstance<IngestionNutrition.RestaurantMenuEstimate>()
                .forEach { add(IngestionArtifactKeys.priceObservation(it.priceObservationClientKey)) }
        }
        IngestionProjection.FITNESS_MEAL -> buildSet {
            addAll(envelope.nutrition.map { IngestionArtifactKeys.nutrition(it.clientKey) })
            addAll(envelope.consumption.map { IngestionArtifactKeys.consumption(it.clientKey) })
        }
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE ->
            envelope.productCandidates.map { IngestionArtifactKeys.productCandidate(it.clientKey) }.toSet()
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> buildSet {
            addAll(envelope.productCandidates.map { IngestionArtifactKeys.productCandidate(it.clientKey) })
            addAll(envelope.nutrition.map { IngestionArtifactKeys.nutrition(it.clientKey) })
        }
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE -> if (envelope.merchantCandidate != null && envelope.receipt == null) {
            setOf(IngestionArtifactKeys.MERCHANT_CANDIDATE)
        } else emptySet()
    }

    private fun projectionIsAffected(
        projection: IngestionProjection,
        artifactKeys: Set<String>,
    ): Boolean = when (projection) {
        IngestionProjection.PRICETRACE_RECEIPT -> IngestionArtifactKeys.RECEIPT in artifactKeys
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION ->
            IngestionArtifactKeys.RECEIPT in artifactKeys ||
                IngestionArtifactKeys.MERCHANT_CANDIDATE in artifactKeys ||
                artifactKeys.any {
                    it.startsWith("${IngestionArtifactKeys.PRICE_OBSERVATION}:") ||
                        it.startsWith("${IngestionArtifactKeys.PRODUCT_CANDIDATE}:") ||
                        it.startsWith("${IngestionArtifactKeys.PURCHASE_RECORD}:")
                }
        IngestionProjection.CASHOS_RECEIPT -> IngestionArtifactKeys.RECEIPT in artifactKeys || IngestionArtifactKeys.CASHOS_HINTS in artifactKeys
        IngestionProjection.CASHOS_TRANSACTION -> artifactKeys.any {
            it.startsWith("${IngestionArtifactKeys.PURCHASE_RECORD}:")
        }
        IngestionProjection.FITNESS_NUTRITION -> artifactKeys.any {
            it.startsWith("nutrition:") ||
                it.startsWith("${IngestionArtifactKeys.PRODUCT_CANDIDATE}:") ||
                it.startsWith("${IngestionArtifactKeys.PRICE_OBSERVATION}:")
        }
        IngestionProjection.FITNESS_MEAL -> artifactKeys.any {
            it.startsWith("nutrition:") || it.startsWith("${IngestionArtifactKeys.CONSUMPTION}:")
        }
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE -> artifactKeys.any {
            it.startsWith("${IngestionArtifactKeys.PRODUCT_CANDIDATE}:")
        }
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> artifactKeys.any {
            it.startsWith("${IngestionArtifactKeys.PRODUCT_CANDIDATE}:") || it.startsWith("nutrition:")
        }
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE -> IngestionArtifactKeys.MERCHANT_CANDIDATE in artifactKeys
    }

    private fun changedArtifactKeys(
        previous: Map<String, String>,
        next: Map<String, String>,
    ): Set<String> = (previous.keys + next.keys).filter { previous[it] != next[it] }.toSet()

    private fun resetPending(state: ProjectionState, updatedAt: String): ProjectionState = state.copy(
        status = ProjectionStatus.PENDING,
        idempotencyKey = null,
        remoteId = null,
        attemptCount = 0,
        lastError = null,
        metadataJson = null,
        updatedAt = updatedAt,
    )

    private fun resetForPayloadChange(
        state: ProjectionState,
        updatedAt: String,
        payloadFingerprint: String,
    ): ProjectionState = resetPending(state, updatedAt).copy(
        projectionRevisionSeq = state.projectionRevisionSeq.coerceAtLeast(1) + 1,
        projectionPayloadFingerprint = payloadFingerprint,
    )

    private suspend fun invalidateVerification(
        session: IngestionSession,
        envelope: YeonsikOcrEnvelope,
        reason: String,
    ): IngestionSession {
        val nowValue = now()
        val nextArtifacts = artifactFingerprints(envelope)
        val preserved = session.verifiedArtifactFingerprints.filter { (key, value) -> nextArtifacts[key] == value }
        val affectedArtifactKeys = changedArtifactKeys(session.verifiedArtifactFingerprints, nextArtifacts)
        val updated = session.copy(
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            verifiedCanonicalFingerprint = null,
            verifiedAt = null,
            verifiedArtifactFingerprints = preserved,
            updatedAt = nowValue,
            projections = session.projections.map { state ->
                when {
                    state.status == ProjectionStatus.DISABLED -> state
                    else -> {
                        val nextProjectionFingerprint = projectionPayloadFingerprint(state.projection, envelope)
                        val payloadChanged = state.projectionPayloadFingerprint?.let {
                            it != nextProjectionFingerprint
                        } ?: (state.idempotencyKey != null &&
                            projectionIsAffected(state.projection, affectedArtifactKeys))
                        if (payloadChanged) {
                            resetForPayloadChange(state, nowValue, nextProjectionFingerprint).copy(
                                status = ProjectionStatus.BLOCKED,
                                lastError = reason,
                            )
                        } else {
                            state.copy(
                                projectionPayloadFingerprint = state.projectionPayloadFingerprint
                                    ?: nextProjectionFingerprint,
                            )
                        }
                    }
                }
            },
        )
        store.save(updated)
        return updated
    }
    private suspend fun persistSuccess(
        session: IngestionSession,
        projection: IngestionProjection,
        previous: ProjectionState,
        key: String,
        result: ProjectionSubmission.Success,
        envelope: YeonsikOcrEnvelope,
    ): ProjectionState {
        val nowValue = now()
        val targetStates = session.projections.map { state ->
            when {
                state.status == ProjectionStatus.DISABLED -> state
                state.projection in result.alsoUploaded && state.status == ProjectionStatus.UPLOADED -> state
                state.projection == projection && !result.primaryUploaded -> state.copy(
                    status = ProjectionStatus.BLOCKED,
                    idempotencyKey = key,
                    remoteId = null,
                    projectionRevisionSeq = previous.projectionRevisionSeq,
                    projectionPayloadFingerprint = previous.projectionPayloadFingerprint,
                    metadataJson = result.metadataJson,
                    lastError = result.primaryPendingReason ?: "projection_incomplete",
                    updatedAt = nowValue,
                )
                state.projection == projection || state.projection in result.alsoUploaded -> {
                    val targetPayloadFingerprint = if (state.projection == projection) {
                        previous.projectionPayloadFingerprint
                    } else {
                        projectionPayloadFingerprint(state.projection, envelope)
                    }
                    val targetRevisionSeq = if (state.projection == projection) {
                        previous.projectionRevisionSeq
                    } else {
                        state.projectionRevisionSeq.coerceAtLeast(1)
                    }
                    state.copy(
                        status = ProjectionStatus.UPLOADED,
                        idempotencyKey = key,
                        remoteId = result.remoteId,
                        projectionRevisionSeq = targetRevisionSeq,
                        projectionPayloadFingerprint = targetPayloadFingerprint,
                        metadataJson = result.metadataJson,
                        lastError = null,
                        updatedAt = nowValue,
                    )
                }
                else -> state
            }
        }
        val updatedSession = session.copy(updatedAt = nowValue, projections = targetStates)
        store.save(updatedSession)
        return targetStates.first { it.projection == projection }
    }

    private suspend fun persistBlocked(
        session: IngestionSession,
        projection: IngestionProjection,
        previous: ProjectionState,
        message: String,
        key: String? = previous.idempotencyKey,
    ): ProjectionState {
        val updatedState = previous.copy(
            status = ProjectionStatus.BLOCKED,
            idempotencyKey = key,
            lastError = message,
            updatedAt = now(),
        )
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updatedState)))
        return updatedState
    }

    private suspend fun persistFailure(
        session: IngestionSession,
        projection: IngestionProjection,
        previous: ProjectionState,
        message: String,
        key: String? = previous.idempotencyKey,
    ): ProjectionState {
        val updatedState = previous.copy(
            status = ProjectionStatus.FAILED,
            idempotencyKey = key,
            lastError = message,
            updatedAt = now(),
        )
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updatedState)))
        return updatedState
    }

    private fun List<ProjectionState>.replace(value: ProjectionState): List<ProjectionState> =
        map { if (it.projection == value.projection) value else it }

    private fun enabledProjections(envelope: YeonsikOcrEnvelope): List<IngestionProjection> =
        CanonicalProjectionPlanner.plan(envelope).eligible.sortedBy(IngestionProjection::wireValue)

    /** Shared planning API used by both Android and Desktop review surfaces. */
    fun planProjections(envelope: YeonsikOcrEnvelope): CanonicalProjectionPlan =
        CanonicalProjectionPlanner.plan(envelope)

    private fun disabledProjections(envelope: YeonsikOcrEnvelope): List<IngestionProjection> =
        IngestionProjection.entries - enabledProjections(envelope).toSet()

    private fun fitnessMealValuesComplete(envelope: YeonsikOcrEnvelope): Boolean =
        envelope.schemaVersion in setOf(YEONSIK_OCR_V2_SCHEMA, YEONSIK_OCR_V3_SCHEMA) &&
            envelope.nutrition.isNotEmpty() &&
            envelope.consumption.isNotEmpty() &&
            envelope.consumption.all(IngestionConsumption::isCompleteForFitnessMeal)

    private fun projectionDependencies(
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): Set<IngestionProjection> = CanonicalProjectionPlanner.dependenciesFor(projection, envelope)

    private fun requiresPriceTraceIdentity(
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): Boolean = envelope.receipt != null && projection in setOf(
        IngestionProjection.CASHOS_RECEIPT,
        IngestionProjection.FITNESS_NUTRITION,
        IngestionProjection.FITNESS_MEAL,
    )

    private fun requiresProductCandidateIdentity(
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): Boolean = projection == IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK &&
        envelope.productCandidates.isNotEmpty()

    private fun resolvedIdentity(session: IngestionSession): ProjectionIdentity? {
        val receiptState = session.projections.firstOrNull { it.projection == IngestionProjection.PRICETRACE_RECEIPT }
            ?.takeIf { it.status == ProjectionStatus.UPLOADED }
        val productState = session.projections.firstOrNull { it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE }
            ?.takeIf { it.status == ProjectionStatus.UPLOADED }
        val priceTrace = receiptState?.let { state ->
            PriceTraceIdentityJson.tryDecode(state.metadataJson)
                ?: state.remoteId?.takeIf(String::isNotBlank)?.let(::PriceTraceIdentity)
        }
        val products = productState?.let { state -> PriceTraceProductIdentityJson.tryDecode(state.metadataJson) }.orEmpty()
        return ProjectionIdentity(priceTrace = priceTrace, productCandidates = products)
            .takeIf { it.priceTrace != null || it.productCandidates.isNotEmpty() }
    }

    private fun projectionDependencyRank(projection: IngestionProjection): Int = when (projection) {
        IngestionProjection.PRICETRACE_RECEIPT,
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE,
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE -> 0
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> 1
        IngestionProjection.CASHOS_TRANSACTION -> 1
        IngestionProjection.CASHOS_RECEIPT,
        IngestionProjection.FITNESS_NUTRITION -> 2
        IngestionProjection.FITNESS_MEAL -> 3
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> 4
    }

    private fun String?.isDependencyRetryable(): Boolean = this == null ||
        startsWith("dependency_pending:") || this == "pricetrace_identity_missing" ||
        this == "pricetrace_product_identity_missing"
}
