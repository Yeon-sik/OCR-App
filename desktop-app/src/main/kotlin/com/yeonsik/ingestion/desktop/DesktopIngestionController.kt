package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.CanonicalImportResult
import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.IngestionArtifactKeys
import com.pricetrace.receiptscanner.ingestion.IngestionEvidenceGate
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    private val _state = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private val useCase = CanonicalIngestionUseCase(
        store = store,
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
            val result = useCase.importJson(
                value = value,
                localDocumentId = localDocumentId,
                ingestionId = _state.value.ingestionId ?: newIngestionId(),
                evidence = localEvidence(_state.value.evidence),
                inputOrigin = InputOrigin.EXTERNAL_JSON,
            )
            when (result) {
                is CanonicalImportResult.Failure -> {
                    _state.value = _state.value.copy(
                        rawJson = value,
                        schema = null,
                        error = result.error?.let { "${it.code}: ${it.detail.orEmpty()}" }
                            ?: result.issues.joinToString(", "),
                        notice = null,
                    )
                }
                is CanonicalImportResult.Success -> importCanonical(value, result)
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
            val envelope = currentEnvelope()
            val isV4Purchase = envelope.schemaVersion == com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V4_SCHEMA
            val v4SourceIds = if (isV4Purchase) {
                envelope.source.sourceFiles.filter { it.type == type }.map { it.id }
            } else {
                emptyList()
            }
            val usedIds = current.evidence.map { it.attachmentId }.toMutableSet()
            if (isV4Purchase) {
                require(v4SourceIds.isNotEmpty()) {
                    "No V4 source file is declared for evidence type ${type.wireValue}."
                }
                require(paths.size <= v4SourceIds.count { it !in usedIds }) {
                    "Too many evidence files for V4 source type ${type.wireValue}."
                }
            }
            val added = paths.map { path ->
                val logicalId = if (isV4Purchase) {
                    v4SourceIds.firstOrNull { it !in usedIds }
                        ?: error("No unused V4 source file is available for evidence type ${type.wireValue}.")
                } else {
                    null
                }
                val copied = store.copyEvidence(session.ingestionId, path, type, pageId, logicalId)
                usedIds += copied.attachmentId
                copied
            }
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

    suspend fun verify(
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
    ) {
        beginBusy()
        try {
            val currentState = _state.value
            val currentSession = currentState.session ?: error("Import JSON before verification.")
            val confirmation = useCase.confirm(
                ingestionId = currentSession.ingestionId,
                envelope = currentEnvelope(),
                evidence = localEvidence(currentState.evidence),
                inputOrigin = InputOrigin.EXTERNAL_JSON,
                verificationBasis = verificationBasis,
            )
            val latest = when (val result = confirmation.result) {
                is IngestionStartResult.Success -> result.session
                is IngestionStartResult.Duplicate -> result.session
                is IngestionStartResult.Failure -> {
                    val saved = store.get(currentSession.ingestionId) ?: currentSession
                    val canonicalJson = YeonsikOcrEnvelopeCodec.encodePersisted(confirmation.envelope)
                    persistRecord(saved, currentState.rawJson, canonicalJson, currentState.evidence)
                    publish(
                        session = saved,
                        envelope = confirmation.envelope,
                        rawJson = currentState.rawJson,
                        canonicalJson = canonicalJson,
                        evidence = currentState.evidence,
                        notice = null,
                        error = "Verification blocked: ${result.issues.joinToString(", ")}",
                    )
                    return
                }
            }
            val canonicalJson = YeonsikOcrEnvelopeCodec.encodePersisted(confirmation.envelope)
            persistRecord(latest, currentState.rawJson, canonicalJson, currentState.evidence)
            publish(
                session = latest,
                envelope = confirmation.envelope,
                rawJson = currentState.rawJson,
                canonicalJson = canonicalJson,
                evidence = currentState.evidence,
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

    suspend fun submit(selectedProjections: Set<IngestionProjection> = emptySet()) {
        beginBusy()
        try {
            val currentState = _state.value
            val session = currentState.session ?: error("Import JSON before submitting.")
            val envelope = currentEnvelope()
            if (session.verifiedCanonicalFingerprint != session.canonicalFingerprint) {
                error("Verify the reviewed artifacts before submitting.")
            }
            val plan = useCase.plan(envelope)
            val selected = if (selectedProjections.isEmpty()) plan.eligible else {
                selectedProjections.intersect(plan.eligible)
            }
            if (selected.isEmpty()) error("No eligible projection selected.")
            val authenticationTargets = selected + selected.flatMap { plan.dependencies[it].orEmpty() }
            val authenticationErrors = bundle.ensureAuthenticated(authenticationTargets, envelope)
            if (authenticationErrors.isNotEmpty()) {
                _state.value = currentState.copy(
                    error = authenticationErrors.joinToString(" "),
                    notice = null,
                )
                return
            }
            val projections = useCase.submitSelected(session.ingestionId, envelope, selected)
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

    suspend fun retry(selectedProjections: Set<IngestionProjection> = emptySet()) = submit(selectedProjections)

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
        result: CanonicalImportResult.Success,
    ) {
        val currentState = _state.value
        val envelope = result.envelope
        val evidence = currentState.evidence

        if (result.startResult is IngestionStartResult.Duplicate && currentState.session == null) {
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

        val session = result.session
        val canonicalJson = YeonsikOcrEnvelopeCodec.encode(envelope)
        val startResult = result.startResult
        val notice = when (startResult) {
            is IngestionStartResult.Failure ->
                "Parsed and saved. Verification is blocked: ${startResult.issues.joinToString(", ")}."
            is IngestionStartResult.Success ->
                "Parsed and validated. Review the JSON and attach evidence before Verify."
            else -> "Parsed and validated."
        }
        persistRecord(session, rawJson, canonicalJson, evidence)
        publish(session, envelope, rawJson, canonicalJson, evidence, notice, null)
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
            val basis = envelope.review.verificationBasis
            val gate = IngestionEvidenceGate.evaluate(
                envelope = envelope,
                evidence = evidence,
                inputOrigin = InputOrigin.EXTERNAL_JSON,
                artifactKeys = setOf(key),
                verificationBasis = basis,
                explicitUserConfirmation = basis == VerificationBasis.MANUAL_CANONICAL_REVIEW,
            )
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
        envelope.priceObservations.forEach {
            addArtifact(IngestionArtifactKeys.priceObservation(it.clientKey), "Price: ${it.clientKey}")
        }
        envelope.nutrition.forEach { addArtifact(IngestionArtifactKeys.nutrition(it.clientKey), "Nutrition: ${it.clientKey}") }
        envelope.consumption.forEach { addArtifact(IngestionArtifactKeys.consumption(it.clientKey), "Consumption: ${it.clientKey}") }
        envelope.productCandidates.forEach {
            addArtifact(IngestionArtifactKeys.productCandidate(it.clientKey), "Product: ${it.clientKey}")
        }
        envelope.purchaseRecords.forEach {
            addArtifact(IngestionArtifactKeys.purchaseRecord(it.clientKey), "Purchase: ${it.platform}")
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
        .joinToString(", ") { state ->
            val base = "${state.projection.wireValue}=${state.status.wireValue}"
            if (state.projection != IngestionProjection.PRICETRACE_PRICE_OBSERVATION) {
                base
            } else {
                val metadata = state.metadataJson?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                }
                val sourceSaved = (metadata?.get("sourceSaved") as? JsonPrimitive)
                    ?.contentOrNull ?: "unknown"
                val observationCreated = (metadata?.get("observationCreated") as? JsonPrimitive)
                    ?.contentOrNull ?: "unknown"
                "$base(source_saved=$sourceSaved,observation_created=$observationCreated)"
            }
        }

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
