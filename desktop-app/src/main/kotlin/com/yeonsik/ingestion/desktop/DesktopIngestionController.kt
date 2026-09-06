package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.ingestion.ConsumptionVerificationStatus
import com.pricetrace.receiptscanner.ingestion.IngestionArtifactKeys
import com.pricetrace.receiptscanner.ingestion.IngestionEvidenceGate
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionOrchestrator
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

data class DesktopArtifactState(
    val key: String,
    val label: String,
    val verified: Boolean,
    val evidenceReady: Boolean,
    val evidenceIssues: List<String>,
)

data class DesktopUiState(
    val rawJson: String = "",
    val canonicalJson: String = "",
    val schema: String? = null,
    val ingestionId: String? = null,
    val localDocumentId: String? = null,
    val session: IngestionSession? = null,
    val evidence: List<DesktopEvidenceAttachment> = emptyList(),
    val artifacts: List<DesktopArtifactState> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val busy: Boolean = false,
)

/** Coordinates the explicit Desktop flow while keeping all business rules in :core. */
class DesktopIngestionController(
    private val store: DesktopSessionStore = DesktopSessionStore(),
    private val bundle: DesktopProjectionBundle = DesktopProjectionBundle(),
    private val importer: ExternalJsonImporter = ExternalJsonImporter(),
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    private val _state = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private val orchestrator = IngestionOrchestrator(
        store = store,
        identityResolver = null,
        submitters = bundle.submitters,
        now = now,
    )

    fun updateRawJson(value: String) {
        _state.value = _state.value.copy(rawJson = value, error = null)
    }

    suspend fun importJson(value: String = _state.value.rawJson) {
        beginBusy()
        try {
            val localDocumentId = _state.value.localDocumentId ?: newLocalDocumentId()
            val outcome = importer.import(value, localDocumentId)
            when (outcome) {
                is ExternalJsonImportOutcome.Failure -> {
                    _state.value = _state.value.copy(
                        rawJson = value,
                        schema = null,
                        error = "${outcome.error.code}: ${outcome.error.detail.orEmpty()}".trimEnd(),
                        notice = null,
                    )
                }
                is ExternalJsonImportOutcome.Success -> importCanonical(value, localDocumentId, outcome.result.canonicalEnvelope)
            }
        } catch (error: Exception) {
            _state.value = _state.value.copy(rawJson = value, error = error.message ?: error.javaClass.simpleName, notice = null)
        } finally {
            endBusy()
        }
    }

    suspend fun attachEvidence(
        paths: List<Path>,
        type: SourceAttachmentType,
        pageId: String? = null,
    ) {
        beginBusy()
        try {
            val current = _state.value
            val session = current.session ?: error("Import JSON before attaching evidence.")
            val added = paths.map { path -> store.copyEvidence(session.ingestionId, path, type, pageId) }
            val evidence = current.evidence + added
            val localEvidence = evidence.map(::toLocalEvidence)
            val savedSession = session.copy(
                attachments = localEvidence,
                updatedAt = now(),
            )
            store.save(savedSession)
            persistRecord(savedSession, current.rawJson, current.canonicalJson, evidence)
            publish(
                session = savedSession,
                envelope = currentEnvelope(),
                rawJson = current.rawJson,
                canonicalJson = current.canonicalJson,
                evidence = evidence,
                notice = "Evidence attached. Verify is still required.",
                error = null,
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message ?: error.javaClass.simpleName, notice = null)
        } finally {
            endBusy()
        }
    }

    suspend fun verify() {
        beginBusy()
        try {
            val currentState = _state.value
            val currentSession = currentState.session ?: error("Import JSON before verification.")
            val original = currentEnvelope()
            val promoted = promoteForVerification(original)
            val revised = orchestrator.reviseCanonicalDraft(currentSession.ingestionId, promoted)
            if (revised is com.pricetrace.receiptscanner.ingestion.IngestionStartResult.Failure) {
                failWithSession(revised.issues.joinToString(", "))
                return
            }

            var latest = requireNotNull(store.get(currentSession.ingestionId))
            val evidence = currentState.evidence
            val operations = buildList<suspend () -> com.pricetrace.receiptscanner.ingestion.IngestionStartResult> {
                if (promoted.receipt != null) {
                    add { orchestrator.markReceiptVerified(latest.ingestionId, promoted, localEvidence(evidence)) }
                }
                if (promoted.nutrition.isNotEmpty()) {
                    add { orchestrator.markNutritionVerified(latest.ingestionId, promoted, localEvidence(evidence)) }
                }
                if (promoted.consumption.isNotEmpty()) {
                    add { orchestrator.markConsumptionVerified(latest.ingestionId, promoted, localEvidence(evidence)) }
                }
                if (promoted.productCandidates.isNotEmpty()) {
                    add { orchestrator.markProductCandidatesVerified(latest.ingestionId, promoted, localEvidence(evidence)) }
                }
                if (promoted.receipt == null && promoted.nutrition.isEmpty() &&
                    promoted.productCandidates.isEmpty() && promoted.merchantCandidate != null
                ) {
                    add { orchestrator.markUserVerified(latest.ingestionId, promoted, localEvidence(evidence)) }
                }
            }
            for (operation in operations) {
                when (val result = operation()) {
                    is com.pricetrace.receiptscanner.ingestion.IngestionStartResult.Success -> latest = result.session
                    is com.pricetrace.receiptscanner.ingestion.IngestionStartResult.Duplicate -> latest = result.session
                    is com.pricetrace.receiptscanner.ingestion.IngestionStartResult.Failure -> {
                        latest = requireNotNull(store.get(currentSession.ingestionId))
                        persistRecord(latest, currentState.rawJson, YeonsikOcrEnvelopeCodec.encode(promoted), evidence)
                        publish(
                            session = latest,
                            envelope = promoted,
                            rawJson = currentState.rawJson,
                            canonicalJson = YeonsikOcrEnvelopeCodec.encode(promoted),
                            evidence = evidence,
                            notice = null,
                            error = "Verification blocked: ${result.issues.joinToString(", ")}",
                        )
                        return
                    }
                }
            }
            val canonicalJson = YeonsikOcrEnvelopeCodec.encode(promoted)
            persistRecord(latest, currentState.rawJson, canonicalJson, evidence)
            publish(
                session = latest,
                envelope = promoted,
                rawJson = currentState.rawJson,
                canonicalJson = canonicalJson,
                evidence = evidence,
                notice = if (latest.verifiedCanonicalFingerprint != null) {
                    "Verified. Projections are ready for Submit."
                } else {
                    "Some artifacts remain unverified."
                },
                error = null,
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message ?: error.javaClass.simpleName, notice = null)
        } finally {
            endBusy()
        }
    }

    suspend fun submit() {
        beginBusy()
        try {
            val currentState = _state.value
            val session = currentState.session ?: error("Import JSON before submitting.")
            val envelope = currentEnvelope()
            if (session.verifiedCanonicalFingerprint != session.canonicalFingerprint) {
                error("Verify the reviewed artifacts before submitting.")
            }
            val active = session.projections
                .filterNot { it.status == ProjectionStatus.DISABLED }
                .map(ProjectionState::projection)
                .toSet()
            val authenticationErrors = bundle.ensureAuthenticated(active, envelope)
            if (authenticationErrors.isNotEmpty()) {
                _state.value = currentState.copy(
                    error = authenticationErrors.joinToString(" "),
                    notice = null,
                )
                return
            }
            val projections = orchestrator.submitAllReadyProjections(session.ingestionId, envelope)
            val latest = requireNotNull(store.get(session.ingestionId))
            persistRecord(latest, currentState.rawJson, currentState.canonicalJson, currentState.evidence)
            publish(
                session = latest,
                envelope = envelope,
                rawJson = currentState.rawJson,
                canonicalJson = currentState.canonicalJson,
                evidence = currentState.evidence,
                notice = "Projection run finished: ${projectionSummary(projections)}",
                error = null,
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message ?: error.javaClass.simpleName, notice = null)
        } finally {
            endBusy()
        }
    }

    suspend fun loadLatest() {
        beginBusy()
        try {
            val record = store.latestRecord() ?: error("No saved Desktop ingestion session.")
            loadRecord(record, "Loaded latest local session. Retry remains available.")
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message ?: error.javaClass.simpleName, notice = null)
        } finally {
            endBusy()
        }
    }

    suspend fun load(ingestionId: String) {
        beginBusy()
        try {
            val record = store.loadRecord(ingestionId) ?: error("Saved ingestion not found: $ingestionId")
            loadRecord(record, "Loaded local ingestion session.")
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message ?: error.javaClass.simpleName, notice = null)
        } finally {
            endBusy()
        }
    }

    private suspend fun importCanonical(
        rawJson: String,
        localDocumentId: String,
        envelope: YeonsikOcrEnvelope,
    ) {
        val currentState = _state.value
        val currentSession = currentState.session
        val ingestionId = currentSession?.ingestionId ?: newIngestionId()
        val evidence = currentState.evidence
        val result = if (currentSession == null) {
            orchestrator.start(
                ingestionId = ingestionId,
                localDocumentId = localDocumentId,
                envelope = envelope,
                evidence = localEvidence(evidence),
                inputOrigin = InputOrigin.EXTERNAL_JSON,
            )
        } else {
            orchestrator.reviseCanonicalDraft(currentSession.ingestionId, envelope)
        }

        if (result is com.pricetrace.receiptscanner.ingestion.IngestionStartResult.Duplicate && currentSession == null) {
            val existing = store.loadRecord(result.session.ingestionId)
            if (existing != null && existing.canonicalJson.isNotBlank()) {
                loadRecord(existing, "Duplicate fingerprint: loaded existing local session.")
            } else {
                val canonicalJson = YeonsikOcrEnvelopeCodec.encode(envelope)
                persistRecord(result.session, rawJson, canonicalJson, evidence)
                publish(result.session, envelope, rawJson, canonicalJson, evidence, "Duplicate fingerprint found.", null)
            }
            return
        }

        val session = store.get(ingestionId) ?: error("The imported session was not persisted.")
        val canonicalJson = YeonsikOcrEnvelopeCodec.encode(envelope)
        val notice = when (result) {
            is com.pricetrace.receiptscanner.ingestion.IngestionStartResult.Failure ->
                "Parsed and saved. Verification is blocked: ${result.issues.joinToString(", ")}."
            is com.pricetrace.receiptscanner.ingestion.IngestionStartResult.Success ->
                "Parsed and validated. Review the JSON and attach evidence before Verify."
            else -> "Parsed and validated."
        }
        persistRecord(session, rawJson, canonicalJson, evidence)
        publish(session, envelope, rawJson, canonicalJson, evidence, notice, null)
    }

    private fun promoteForVerification(envelope: YeonsikOcrEnvelope): YeonsikOcrEnvelope {
        val confirmedAt = now()
        return envelope.copy(
            nutrition = envelope.nutrition.map { item ->
                when (item) {
                    is IngestionNutrition.ProductLabel -> item.copy(
                        draft = item.draft.asUserVerified(confirmedAt),
                    )
                    else -> item
                }
            },
            consumption = envelope.consumption.map { it.copy(status = ConsumptionVerificationStatus.USER_VERIFIED) },
        )
    }

    private fun currentEnvelope(): YeonsikOcrEnvelope = _state.value.schema?.let {
        _state.value.canonicalJson.takeIf(String::isNotBlank)?.let { canonical ->
            YeonsikOcrEnvelopeCodec.decode(
                value = canonical,
                localDocumentId = _state.value.localDocumentId ?: error("local document id missing"),
                preservePersistedVerification = true,
            )
        }
    } ?: error("No parsed canonical envelope is loaded.")

    private fun loadRecord(record: DesktopSessionRecord, notice: String) {
        require(record.canonicalJson.isNotBlank()) { "Saved session has no canonical JSON." }
        val envelope = YeonsikOcrEnvelopeCodec.decode(
            value = record.canonicalJson,
            localDocumentId = record.session.localDocumentId,
            preservePersistedVerification = true,
        )
        publish(
            session = record.session,
            envelope = envelope,
            rawJson = record.rawJson.ifBlank { record.canonicalJson },
            canonicalJson = record.canonicalJson,
            evidence = record.evidence,
            notice = notice,
            error = null,
        )
    }

    private fun persistRecord(
        session: IngestionSession,
        rawJson: String,
        canonicalJson: String,
        evidence: List<DesktopEvidenceAttachment>,
    ) {
        store.saveRecord(
            DesktopSessionRecord(
                session = session.copy(attachments = evidence.map(::toLocalEvidence)),
                rawJson = rawJson,
                canonicalJson = canonicalJson,
                evidence = evidence,
            ),
        )
    }

    private fun publish(
        session: IngestionSession,
        envelope: YeonsikOcrEnvelope,
        rawJson: String,
        canonicalJson: String,
        evidence: List<DesktopEvidenceAttachment>,
        notice: String?,
        error: String?,
    ) {
        _state.value = DesktopUiState(
            rawJson = rawJson,
            canonicalJson = canonicalJson,
            schema = envelope.schemaVersion,
            ingestionId = session.ingestionId,
            localDocumentId = session.localDocumentId,
            session = session,
            evidence = evidence,
            artifacts = artifactStates(envelope, session, localEvidence(evidence)),
            error = error,
            notice = notice,
            busy = true,
        )
    }

    private fun artifactStates(
        envelope: YeonsikOcrEnvelope,
        session: IngestionSession,
        evidence: List<LocalEvidence>,
    ): List<DesktopArtifactState> = buildList {
        fun addArtifact(key: String, label: String) {
            val gate = IngestionEvidenceGate.evaluate(envelope, evidence, InputOrigin.EXTERNAL_JSON, setOf(key))
            add(
                DesktopArtifactState(
                    key = key,
                    label = label,
                    verified = session.verifiedArtifactFingerprints.containsKey(key),
                    evidenceReady = gate.isAllowed,
                    evidenceIssues = gate.blockingIssues,
                ),
            )
        }
        if (envelope.receipt != null) addArtifact(IngestionArtifactKeys.RECEIPT, "Receipt")
        if (envelope.merchantCandidate != null && envelope.receipt == null) {
            addArtifact(IngestionArtifactKeys.MERCHANT_CANDIDATE, "Merchant candidate")
        }
        envelope.nutrition.forEach { addArtifact(IngestionArtifactKeys.nutrition(it.clientKey), "Nutrition: ${it.clientKey}") }
        envelope.consumption.forEach { addArtifact(IngestionArtifactKeys.consumption(it.clientKey), "Consumption: ${it.clientKey}") }
        envelope.productCandidates.forEach {
            addArtifact(IngestionArtifactKeys.productCandidate(it.clientKey), "Product: ${it.clientKey}")
        }
    }

    private fun localEvidence(evidence: List<DesktopEvidenceAttachment>): List<LocalEvidence> = evidence.map(::toLocalEvidence)

    private fun toLocalEvidence(evidence: DesktopEvidenceAttachment): LocalEvidence = LocalEvidence(
        attachmentId = evidence.attachmentId,
        type = evidence.type,
        fileReadable = Files.isRegularFile(evidence.path) && Files.isReadable(evidence.path),
        pageId = evidence.pageId,
    )

    private fun projectionSummary(projections: List<ProjectionState>): String = projections
        .filterNot { it.status == ProjectionStatus.DISABLED }
        .joinToString(", ") { "${it.projection.wireValue}=${it.status.wireValue}" }

    private fun failWithSession(message: String) {
        _state.value = _state.value.copy(error = message, notice = null)
    }

    private fun beginBusy() {
        _state.value = _state.value.copy(busy = true, error = null)
    }

    private fun endBusy() {
        _state.value = _state.value.copy(busy = false)
    }

    private fun newIngestionId(): String = "desktop-${UUID.randomUUID()}"
    private fun newLocalDocumentId(): String = "desktop-document-${UUID.randomUUID()}"
}
