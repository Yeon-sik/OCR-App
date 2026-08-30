package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
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
    val resolvedIdentity: Map<String, String>,
    val idempotencyKey: String,
    val envelope: YeonsikOcrEnvelope? = null,
    val localDocumentId: String? = null,
    val revisionSeq: Long = 1,
    val canonicalFingerprint: String? = null,
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
            projections = enabledProjections(envelope).map { projection ->
                ProjectionState(projection = projection, status = ProjectionStatus.PENDING, updatedAt = nowValue)
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
        val resetAll = previousArtifacts.isEmpty() && current.projections.any { it.status != ProjectionStatus.DISABLED }
        val revised = current.copy(
            canonicalFingerprint = nextFingerprint,
            revisionSeq = current.revisionSeq + 1,
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            verifiedCanonicalFingerprint = null,
            verifiedAt = null,
            verifiedArtifactFingerprints = previousArtifacts.filter { (key, value) -> nextArtifacts[key] == value },
            updatedAt = nowValue,
            projections = current.projections.map { state ->
                when {
                    state.status == ProjectionStatus.DISABLED -> state.copy(updatedAt = nowValue)
                    resetAll || projectionIsAffected(state.projection, affectedArtifactKeys) -> resetPending(state, nowValue)
                    else -> state.copy(updatedAt = nowValue)
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
    ): IngestionStartResult = when {
        envelope.receipt != null -> markReceiptVerified(ingestionId, envelope, evidence, inputOrigin)
        envelope.nutrition.isNotEmpty() -> markNutritionVerified(ingestionId, envelope, evidence, inputOrigin = inputOrigin)
        envelope.merchantCandidate != null -> markMerchantCandidateVerified(ingestionId, envelope, evidence, inputOrigin)
        else -> IngestionStartResult.Failure(listOf("no_reviewable_artifact"))
    }

    suspend fun markReceiptVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
    ): IngestionStartResult = markArtifactsVerified(
        ingestionId = ingestionId,
        envelope = envelope,
        evidence = evidence,
        artifactKeys = buildSet {
            add(IngestionArtifactKeys.RECEIPT)
            if (envelope.receipt != null) add(IngestionArtifactKeys.CASHOS_HINTS)
        },
        inputOrigin = inputOrigin,
    )

    suspend fun markNutritionVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        nutritionClientKeys: Set<String> = envelope.nutrition.map { it.clientKey }.toSet(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
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
        )
    }

    private suspend fun markMerchantCandidateVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        inputOrigin: InputOrigin,
    ): IngestionStartResult = markArtifactsVerified(
        ingestionId = ingestionId,
        envelope = envelope,
        evidence = evidence,
        artifactKeys = setOf(IngestionArtifactKeys.MERCHANT_CANDIDATE),
        inputOrigin = inputOrigin,
    )

    private suspend fun markArtifactsVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        artifactKeys: Set<String>,
        inputOrigin: InputOrigin,
    ): IngestionStartResult {
        val current = store.get(ingestionId) ?: return IngestionStartResult.Failure(listOf("ingestion_not_found"))
        val currentFingerprint = fingerprint(envelope)
        if (current.canonicalFingerprint != currentFingerprint) {
            invalidateVerification(current, envelope, "canonical_fingerprint_mismatch")
            return IngestionStartResult.Failure(listOf("canonical_fingerprint_mismatch"))
        }
        val gate = IngestionEvidenceGate.evaluate(envelope, evidence, inputOrigin)
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
        val requiredArtifacts = requiredArtifactKeys(projection, envelope)
        val currentArtifacts = artifactFingerprints(envelope)
        if (requiredArtifacts.isEmpty() || requiredArtifacts.any {
                session.verifiedArtifactFingerprints[it] != currentArtifacts[it]
            }) {
            return persistBlocked(session, projection, current, "projection_not_reverified")
        }
        require(current.status in setOf(ProjectionStatus.PENDING, ProjectionStatus.BLOCKED, ProjectionStatus.FAILED)) {
            "projection_not_retryable"
        }
        val submitter = submitters[projection]
            ?: return persistBlocked(session, projection, current, "projection_not_configured")
        val key = current.idempotencyKey ?: StableIds.sha256(
            "projection|${projection.wireValue}|${session.localDocumentId}|revision=${session.revisionSeq}|${session.canonicalFingerprint}",
        )
        val attempted = current.copy(
            idempotencyKey = key,
            attemptCount = current.attemptCount + 1,
            lastError = null,
            updatedAt = now(),
        )
        session = session.copy(updatedAt = now(), projections = session.projections.replace(attempted))
        store.save(session)
        val request = ProjectionRequest(
            ingestionId = ingestionId,
            projection = projection,
            canonicalPayload = YeonsikOcrEnvelopeJson.encode(envelope),
            resolvedIdentity = emptyMap(),
            idempotencyKey = key,
            envelope = envelope,
            localDocumentId = session.localDocumentId,
            revisionSeq = session.revisionSeq,
            canonicalFingerprint = session.canonicalFingerprint,
        )
        return when (val result = submitter.submit(request)) {
            is ProjectionSubmission.Success -> persistSuccess(session, projection, attempted, key, result)
            is ProjectionSubmission.Failure -> if (result.retryable) {
                persistFailure(session, projection, attempted, result.message, key)
            } else {
                persistBlocked(session, projection, attempted, result.message, key)
            }
        }
    }
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
        StableIds.sha256("ingestion|${YeonsikOcrEnvelopeJson.canonicalize(envelope)}")

    private fun artifactFingerprints(envelope: YeonsikOcrEnvelope): Map<String, String> = buildMap {
        envelope.receipt?.let { receipt ->
            put(
                IngestionArtifactKeys.RECEIPT,
                StableIds.sha256("ingestion-artifact|receipt|${ReceiptV2Json.encodeCanonical(receipt)}"),
            )
        }

        if (envelope.receipt != null) {
            val hints = envelope.classificationHints
                .filterKeys {
                    it in setOf(
                        "cashos.category_hint",
                        "cashos.institution_hint",
                        "cashos.payment_method_hint",
                    )
                }
                .toSortedMap()
                .entries
                .joinToString("|") { (key, value) -> "$key=${value ?: "<null>"}" }
            put(
                IngestionArtifactKeys.CASHOS_HINTS,
                StableIds.sha256("ingestion-artifact|cashos-hints|$hints"),
            )
        }
        envelope.nutrition.forEach { item ->
            val artifactEnvelope = envelope.copy(
                merchantCandidate = null,
                receipt = null,
                nutrition = listOf(item),
                classificationHints = emptyMap(),
                links = envelope.links.filter { it.nutritionClientKey == item.clientKey },
                targets = emptySet(),
                review = IngestionReview(IngestionReviewStatus.NEEDS_REVIEW),
            )
            put(
                IngestionArtifactKeys.nutrition(item.clientKey),
                StableIds.sha256("ingestion-artifact|nutrition|${YeonsikOcrEnvelopeJson.canonicalize(artifactEnvelope)}"),
            )
        }
        envelope.merchantCandidate?.let {
            val artifactEnvelope = envelope.copy(
                merchantCandidate = it,
                receipt = null,
                nutrition = emptyList(),
                classificationHints = emptyMap(),
                links = emptyList(),
                targets = emptySet(),
                review = IngestionReview(IngestionReviewStatus.NEEDS_REVIEW),
            )
            put(
                IngestionArtifactKeys.MERCHANT_CANDIDATE,
                StableIds.sha256("ingestion-artifact|merchant|${YeonsikOcrEnvelopeJson.canonicalize(artifactEnvelope)}"),
            )
        }
    }

    private fun requiredArtifactKeys(
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): Set<String> = when (projection) {
        IngestionProjection.PRICETRACE_RECEIPT,
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> if (envelope.receipt != null) {
            setOf(IngestionArtifactKeys.RECEIPT)
        } else emptySet()
        IngestionProjection.CASHOS_RECEIPT -> if (envelope.receipt != null) {
            setOf(IngestionArtifactKeys.RECEIPT, IngestionArtifactKeys.CASHOS_HINTS)
        } else emptySet()
        IngestionProjection.FITNESS_NUTRITION -> envelope.nutrition.map { IngestionArtifactKeys.nutrition(it.clientKey) }.toSet()
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE -> if (envelope.merchantCandidate != null && envelope.receipt == null) {
            setOf(IngestionArtifactKeys.MERCHANT_CANDIDATE)
        } else emptySet()
    }

    private fun projectionIsAffected(
        projection: IngestionProjection,
        artifactKeys: Set<String>,
    ): Boolean = when (projection) {
        IngestionProjection.PRICETRACE_RECEIPT,
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> IngestionArtifactKeys.RECEIPT in artifactKeys
        IngestionProjection.CASHOS_RECEIPT -> IngestionArtifactKeys.RECEIPT in artifactKeys || IngestionArtifactKeys.CASHOS_HINTS in artifactKeys
        IngestionProjection.FITNESS_NUTRITION -> artifactKeys.any { it.startsWith("nutrition:") }
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

    private fun resetBlocked(state: ProjectionState, updatedAt: String, reason: String): ProjectionState = resetPending(state, updatedAt).copy(
        status = ProjectionStatus.BLOCKED,
        lastError = reason,
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
        val resetAll = session.verifiedArtifactFingerprints.isEmpty() && session.projections.any { it.status != ProjectionStatus.DISABLED }
        val updated = session.copy(
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            verifiedCanonicalFingerprint = null,
            verifiedAt = null,
            verifiedArtifactFingerprints = preserved,
            updatedAt = nowValue,
            projections = session.projections.map { state ->
                when {
                    state.status == ProjectionStatus.DISABLED -> state
                    resetAll || projectionIsAffected(state.projection, affectedArtifactKeys) -> resetBlocked(state, nowValue, reason)
                    else -> state
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
                    metadataJson = result.metadataJson,
                    lastError = result.primaryPendingReason ?: "projection_incomplete",
                    updatedAt = nowValue,
                )
                state.projection == projection || state.projection in result.alsoUploaded -> state.copy(
                    status = ProjectionStatus.UPLOADED,
                    idempotencyKey = key,
                    remoteId = result.remoteId,
                    metadataJson = result.metadataJson,
                    lastError = null,
                    updatedAt = nowValue,
                )
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
        (envelope.targets.ifEmpty { inferredTargets(envelope) }).sortedBy(IngestionProjection::wireValue)

    private fun inferredTargets(envelope: YeonsikOcrEnvelope): Set<IngestionProjection> = buildSet {
        if (envelope.receipt != null) {
            add(IngestionProjection.PRICETRACE_RECEIPT)
            add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
            add(IngestionProjection.CASHOS_RECEIPT)
        }
        if (envelope.merchantCandidate != null && envelope.receipt == null) {
            add(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE)
        }
        if (envelope.nutrition.isNotEmpty()) add(IngestionProjection.FITNESS_NUTRITION)
    }

    private fun disabledProjections(envelope: YeonsikOcrEnvelope): List<IngestionProjection> =
        IngestionProjection.entries - enabledProjections(envelope).toSet()
}
