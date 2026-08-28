package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.verification.VerifiedDraftGate
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
)

sealed interface ProjectionSubmission {
    data class Success(val remoteId: String) : ProjectionSubmission
    data class Failure(val message: String, val retryable: Boolean) : ProjectionSubmission
}

interface IngestionProjectionSubmitter {
    suspend fun submit(request: ProjectionRequest): ProjectionSubmission
}

interface IngestionSessionStore {
    suspend fun get(ingestionId: String): IngestionSession?
    suspend fun findByCanonicalFingerprint(fingerprint: String): IngestionSession?
    suspend fun delete(ingestionId: String)
    suspend fun save(session: IngestionSession)
}

class InMemoryIngestionSessionStore : IngestionSessionStore {
    private val values = linkedMapOf<String, IngestionSession>()
    override suspend fun get(ingestionId: String): IngestionSession? = values[ingestionId]
    override suspend fun findByCanonicalFingerprint(fingerprint: String): IngestionSession? = values.values.firstOrNull { it.canonicalFingerprint == fingerprint }
    override suspend fun delete(ingestionId: String) { values.remove(ingestionId) }
    override suspend fun save(session: IngestionSession) { values[session.ingestionId] = session }
}

sealed interface IngestionStartResult {
    data class Success(val session: IngestionSession) : IngestionStartResult
    data class Duplicate(val session: IngestionSession) : IngestionStartResult
    data class Failure(val issues: List<String>) : IngestionStartResult
}

/** Coordinates independent projections without pretending that three databases share a transaction. */
class IngestionOrchestrator(
    private val store: IngestionSessionStore,
    private val identityResolver: IngestionIdentityResolver,
    private val submitters: Map<IngestionProjection, IngestionProjectionSubmitter>,
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    suspend fun start(
        ingestionId: String,
        localDocumentId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence> = emptyList(),
    ): IngestionStartResult {
        require(ingestionId.isNotBlank() && localDocumentId.isNotBlank())
        val evidenceResult = IngestionEvidenceGate.evaluate(envelope, evidence)
        val canonical = YeonsikOcrEnvelopeJson.canonicalize(envelope)
        val fingerprint = StableIds.sha256("ingestion|$canonical")
        store.findByCanonicalFingerprint(fingerprint)?.let { existing ->
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
            projections = enabledProjections(envelope).map { projection ->
                ProjectionState(
                    projection = projection,
                    status = ProjectionStatus.PENDING,
                    updatedAt = nowValue,
                )
            } + disabledProjections(envelope).map { projection ->
                ProjectionState(projection, ProjectionStatus.DISABLED, updatedAt = nowValue)
            },
        )
        store.save(session)
        return if (evidenceResult.isAllowed) IngestionStartResult.Success(session)
        else IngestionStartResult.Failure(evidenceResult.blockingIssues)
    }

    suspend fun markUserVerified(ingestionId: String, envelope: YeonsikOcrEnvelope, evidence: List<LocalEvidence>): IngestionStartResult {
        val gate = IngestionEvidenceGate.evaluate(envelope, evidence)
        if (!gate.isAllowed) return IngestionStartResult.Failure(gate.blockingIssues)
        val current = store.get(ingestionId) ?: return IngestionStartResult.Failure(listOf("ingestion_not_found"))
        val updated = current.copy(
            reviewStatus = IngestionReviewStatus.READY,
            updatedAt = now(),
            projections = current.projections.map { state ->
                if (state.status == ProjectionStatus.DISABLED || state.status == ProjectionStatus.UPLOADED) state
                else state.copy(status = ProjectionStatus.PENDING, updatedAt = now(), lastError = null)
            },
        )
        store.save(updated)
        return IngestionStartResult.Success(updated)
    }

    suspend fun submitProjection(ingestionId: String, projection: IngestionProjection, envelope: YeonsikOcrEnvelope): ProjectionState {
        val session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        val current = requireNotNull(session.projections.firstOrNull { it.projection == projection }) { "projection_not_configured" }
        if (current.status == ProjectionStatus.DISABLED || current.status == ProjectionStatus.UPLOADED) return current
        require(session.reviewStatus == IngestionReviewStatus.READY) { "projection_not_verified" }
        require(current.status in setOf(ProjectionStatus.PENDING, ProjectionStatus.BLOCKED, ProjectionStatus.FAILED)) { "projection_not_retryable" }
        val resolution = identityResolver.resolve(projection, envelope)
        if (resolution.status != IdentityResolutionStatus.RESOLVED) {
            return persistBlocked(session, projection, current, resolution.message ?: resolution.status.name)
        }
        val payload = projectionPayload(envelope, projection, resolution.ids)
        val key = StableIds.sha256("projection|${projection.wireValue}|$payload")
        val submitter = submitters[projection]
        if (submitter == null) return persistFailure(session, projection, current, "projection_not_configured", retryable = false)
        return when (val result = submitter.submit(ProjectionRequest(ingestionId, projection, payload, resolution.ids, key))) {
            is ProjectionSubmission.Success -> persistSuccess(session, projection, current, key, result.remoteId)
            is ProjectionSubmission.Failure -> persistFailure(session, projection, current, result.message, result.retryable, key)
        }
    }

    suspend fun retryFailed(ingestionId: String, envelope: YeonsikOcrEnvelope): List<ProjectionState> {
        val session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        return session.projections.filter { it.status == ProjectionStatus.FAILED || it.status == ProjectionStatus.BLOCKED }.map {
            submitProjection(ingestionId, it.projection, envelope)
        }
    }

    /** Records a result produced by an existing app publisher without re-building its contract. */
    suspend fun recordProjectionUploaded(
        ingestionId: String,
        projection: IngestionProjection,
        remoteId: String,
        idempotencyKey: String? = null,
    ): ProjectionState {
        val session = requireNotNull(store.get(ingestionId)) { "ingestion_not_found" }
        val current = requireNotNull(session.projections.firstOrNull { it.projection == projection }) { "projection_not_configured" }
        if (current.status == ProjectionStatus.DISABLED || current.status == ProjectionStatus.UPLOADED) return current
        val updated = current.copy(
            status = ProjectionStatus.UPLOADED,
            idempotencyKey = idempotencyKey ?: current.idempotencyKey ?: StableIds.sha256("projection-record|${projection.wireValue}|$remoteId"),
            remoteId = remoteId,
            lastError = null,
            updatedAt = now(),
        )
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updated)))
        return updated
    }

    suspend fun recordProjectionBlocked(
        ingestionId: String,
        projection: IngestionProjection,
        message: String,
    ): ProjectionState {
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


    private fun projectionPayload(envelope: YeonsikOcrEnvelope, projection: IngestionProjection, ids: Map<String, String>): String =
        "${YeonsikOcrEnvelopeJson.canonicalize(envelope)}|projection=${projection.wireValue}|identity=${ids.toSortedMap()}"

    private suspend fun persistSuccess(session: IngestionSession, projection: IngestionProjection, previous: ProjectionState, key: String, remoteId: String): ProjectionState {
        val updatedState = previous.copy(status = ProjectionStatus.UPLOADED, idempotencyKey = key, remoteId = remoteId, updatedAt = now(), lastError = null)
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updatedState)))
        return updatedState
    }

    private suspend fun persistBlocked(session: IngestionSession, projection: IngestionProjection, previous: ProjectionState, message: String): ProjectionState {
        val updatedState = previous.copy(status = ProjectionStatus.BLOCKED, lastError = message, updatedAt = now())
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updatedState)))
        return updatedState
    }
    private suspend fun persistFailure(session: IngestionSession, projection: IngestionProjection, previous: ProjectionState, message: String, retryable: Boolean, key: String? = previous.idempotencyKey): ProjectionState {
        val updatedState = previous.copy(status = ProjectionStatus.FAILED, idempotencyKey = key, attemptCount = previous.attemptCount + 1, lastError = message, updatedAt = now())
        store.save(session.copy(updatedAt = now(), projections = session.projections.replace(updatedState)))
        return updatedState
    }

    private fun List<ProjectionState>.replace(value: ProjectionState): List<ProjectionState> = map { if (it.projection == value.projection) value else it }

    private fun enabledProjections(envelope: YeonsikOcrEnvelope): List<IngestionProjection> =
        (envelope.targets.ifEmpty { inferredTargets(envelope) }).sortedBy(IngestionProjection::wireValue)

    private fun inferredTargets(envelope: YeonsikOcrEnvelope): Set<IngestionProjection> = buildSet {
        if (envelope.receipt != null) {
            add(IngestionProjection.PRICETRACE_RECEIPT)
            add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
            add(IngestionProjection.CASHOS_RECEIPT)
        }
        if (envelope.nutrition.isNotEmpty()) add(IngestionProjection.FITNESS_NUTRITION)
    }

    private fun disabledProjections(envelope: YeonsikOcrEnvelope): List<IngestionProjection> =
        IngestionProjection.entries - enabledProjections(envelope).toSet()
}
