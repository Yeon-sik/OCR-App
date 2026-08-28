package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.input.InputOrigin
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

    /** Regenerates the persisted canonical revision and invalidates all prior verification/projections. */
    suspend fun reviseCanonicalDraft(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
    ): IngestionStartResult {
        val current = store.get(ingestionId) ?: return IngestionStartResult.Failure(listOf("ingestion_not_found"))
        val nextFingerprint = fingerprint(envelope)
        if (current.canonicalFingerprint == nextFingerprint) return IngestionStartResult.Success(current)
        val nowValue = now()
        val revised = current.copy(
            canonicalFingerprint = nextFingerprint,
            revisionSeq = current.revisionSeq + 1,
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            verifiedCanonicalFingerprint = null,
            verifiedAt = null,
            updatedAt = nowValue,
            projections = current.projections.map { state ->
                if (state.status == ProjectionStatus.DISABLED) state.copy(updatedAt = nowValue)
                else state.copy(
                    status = ProjectionStatus.PENDING,
                    idempotencyKey = null,
                    remoteId = null,
                    attemptCount = 0,
                    lastError = null,
                    metadataJson = null,
                    updatedAt = nowValue,
                )
            },
        )
        store.save(revised)
        return IngestionStartResult.Success(revised)
    }

    suspend fun markUserVerified(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
    ): IngestionStartResult {
        val current = store.get(ingestionId) ?: return IngestionStartResult.Failure(listOf("ingestion_not_found"))
        val currentFingerprint = fingerprint(envelope)
        if (current.canonicalFingerprint != currentFingerprint) {
            invalidateVerification(current, "canonical_fingerprint_mismatch")
            return IngestionStartResult.Failure(listOf("canonical_fingerprint_mismatch"))
        }
        val gate = IngestionEvidenceGate.evaluate(envelope, evidence, inputOrigin)
        if (!gate.isAllowed) return IngestionStartResult.Failure(gate.blockingIssues)
        val nowValue = now()
        val updated = current.copy(
            reviewStatus = IngestionReviewStatus.READY,
            attachments = evidence,
            verifiedCanonicalFingerprint = currentFingerprint,
            verifiedAt = nowValue,
            updatedAt = nowValue,
            projections = current.projections.map { state ->
                if (state.status == ProjectionStatus.DISABLED || state.status == ProjectionStatus.UPLOADED) state
                else state.copy(status = ProjectionStatus.PENDING, lastError = null, updatedAt = nowValue)
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
            val invalidated = invalidateVerification(session, "canonical_fingerprint_mismatch")
            return invalidated.projections.first { it.projection == projection }
        }
        if (session.reviewStatus != IngestionReviewStatus.READY ||
            session.verifiedCanonicalFingerprint != session.canonicalFingerprint) {
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

    private suspend fun invalidateVerification(session: IngestionSession, reason: String): IngestionSession {
        val nowValue = now()
        val updated = session.copy(
            reviewStatus = IngestionReviewStatus.NEEDS_REVIEW,
            verifiedCanonicalFingerprint = null,
            verifiedAt = null,
            updatedAt = nowValue,
            projections = session.projections.map { state ->
                if (state.status == ProjectionStatus.DISABLED) state
                else state.copy(status = ProjectionStatus.BLOCKED, lastError = reason, updatedAt = nowValue)
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
            if (state.status != ProjectionStatus.DISABLED && (state.projection == projection || state.projection in result.alsoUploaded)) {
                state.copy(
                    status = ProjectionStatus.UPLOADED,
                    idempotencyKey = key,
                    remoteId = result.remoteId,
                    metadataJson = result.metadataJson,
                    lastError = null,
                    updatedAt = nowValue,
                )
            } else state
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
