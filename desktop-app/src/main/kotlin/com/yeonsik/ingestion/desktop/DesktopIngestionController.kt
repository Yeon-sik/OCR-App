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
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveRequest
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveResult
import com.pricetrace.receiptscanner.ingestion.EvidenceVerificationEventResult
import com.pricetrace.receiptscanner.ingestion.YeonsikBundle
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleManifestCodec
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleReader
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
    val bundleMetadata: DesktopBundleMetadata? = null,
    /** Keeps a rejected bundle visible even when no trusted manifest metadata exists. */
    val bundleValidationStatus: DesktopBundleValidationStatus? = null,
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
        if (_state.value.bundleMetadata != null || _state.value.bundleValidationStatus != null) {
            _state.value = _state.value.copy(
                error = "Bundle canonical JSON is read-only. Open JSON to start a separate ingestion.",
                notice = null,
            )
        } else {
            _state.value = _state.value.copy(rawJson = value, error = null)
        }
    }

    suspend fun importBundle(path: Path) {
        beginBusy()
        var archiveAfterImport = false
        try {
            val localDocumentId = newLocalDocumentId()
            val ingestionId = newIngestionId()
            val materializer = store.bundleMaterializer(ingestionId)
            val importedBundle = Files.newInputStream(path).use { input ->
                YeonsikBundleReader.read(input, localDocumentId, materializer)
            }
            val evidence = importedBundle.manifest.evidence.map { item ->
                DesktopEvidenceAttachment(
                    attachmentId = item.sourceFileId,
                    type = item.type,
                    path = materializer.pathFor(item.path),
                )
            }
            val result = useCase.importJson(
                value = importedBundle.canonicalJson,
                localDocumentId = localDocumentId,
                ingestionId = ingestionId,
                evidence = localEvidence(evidence),
                inputOrigin = InputOrigin.EXTERNAL_JSON,
                bundleFingerprint = importedBundle.bundleFingerprint,
            )
            when (result) {
                is CanonicalImportResult.Failure -> error(
                    result.error?.let { "${it.code}: ${it.detail.orEmpty()}" }
                        ?: result.issues.joinToString(", "),
                )
                is CanonicalImportResult.Success -> {
                    if (result.startResult is IngestionStartResult.Duplicate) {
                        materializer.abort()
                        val existing = store.loadRecord(result.session.ingestionId)
                            ?: error("Duplicate bundle session is missing its durable record.")
                        loadRecord(existing, "Duplicate bundle fingerprint: loaded existing immutable session.")
                        archiveAfterImport = existing.bundle?.let {
                            it.archiveStatus != DesktopEvidenceArchiveStatus.ARCHIVED
                        } == true
                    } else {
                        val metadata = DesktopBundleMetadata(
                        sourcePath = path.toAbsolutePath().normalize().toString(),
                        canonicalSha256 = importedBundle.manifest.canonicalSha256,
                        manifestJson = importedBundle.manifestJson,
                        validationStatus = DesktopBundleValidationStatus.VALID,
                        archiveStatus = DesktopEvidenceArchiveStatus.NOT_STARTED,
                        )
                        val canonicalJson = YeonsikOcrEnvelopeCodec.encode(result.envelope)
                        persistRecord(result.session, importedBundle.canonicalJson, canonicalJson, evidence, metadata)
                        publish(
                            result.session,
                            result.envelope,
                            importedBundle.canonicalJson,
                            canonicalJson,
                            evidence,
                            "Bundle validated and evidence bound. Starting archive.",
                            null,
                            metadata,
                        )
                        archiveAfterImport = true
                    }
                }
            }
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                canonicalJson = "",
                schema = null,
                ingestionId = null,
                localDocumentId = null,
                session = null,
                evidence = emptyList(),
                artifacts = emptyList(),
                error = "Bundle invalid: ${error.message ?: error.javaClass.simpleName}",
                notice = null,
                bundleMetadata = null,
                bundleValidationStatus = DesktopBundleValidationStatus.INVALID,
            )
        } finally {
            endBusy()
        }
        if (archiveAfterImport) archiveEvidence()
    }

    suspend fun archiveEvidence() {
        beginBusy()
        try {
            val current = _state.value
            val session = current.session ?: error("Import a .yeonsik bundle before archiving.")
            val metadata = current.bundleMetadata ?: error("No bundle archive is available.")
            val archiving = metadata.copy(archiveStatus = DesktopEvidenceArchiveStatus.ARCHIVING, archiveError = null)
            persistRecord(session, current.rawJson, current.canonicalJson, current.evidence, archiving)
            _state.value = current.copy(bundleMetadata = archiving, busy = true, error = null)
            val envelope = currentEnvelope()
            val importedBundle = YeonsikBundle(
                manifest = YeonsikBundleManifestCodec.decode(metadata.manifestJson),
                manifestJson = metadata.manifestJson,
                canonicalJson = current.rawJson,
                envelope = envelope,
                manifestSha256 = metadata.manifestSha256,
            )
            val result = bundle.evidenceArchivePort.archive(
                EvidenceArchiveRequest(importedBundle) { sourceFileId ->
                    val attachment = current.evidence.singleOrNull { it.attachmentId == sourceFileId }
                        ?: error("Bundle evidence is missing locally: $sourceFileId")
                    Files.newInputStream(attachment.path)
                },
                metadata.archiveCheckpoint,
            )
            val updated = when (result) {
                is EvidenceArchiveResult.Success -> metadata.copy(
                    archiveStatus = DesktopEvidenceArchiveStatus.ARCHIVED,
                    archiveCheckpoint = result.checkpoint,
                    archiveError = null,
                )
                is EvidenceArchiveResult.Failure -> metadata.copy(
                    archiveStatus = DesktopEvidenceArchiveStatus.FAILED,
                    archiveCheckpoint = result.checkpoint,
                    archiveError = result.issue,
                )
            }
            persistRecord(session, current.rawJson, current.canonicalJson, current.evidence, updated)
            publish(
                session, envelope, current.rawJson, current.canonicalJson, current.evidence,
                if (updated.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED) "Evidence archive completed."
                else null,
                updated.archiveError,
                updated,
            )
        } catch (error: Exception) {
            val current = _state.value
            val failed = current.bundleMetadata?.copy(
                archiveStatus = DesktopEvidenceArchiveStatus.FAILED,
                archiveError = error.message ?: error.javaClass.simpleName,
            )
            if (failed != null && current.session != null) {
                persistRecord(current.session, current.rawJson, current.canonicalJson, current.evidence, failed)
            }
            _state.value = current.copy(bundleMetadata = failed, error = failed?.archiveError, notice = null)
        } finally {
            endBusy()
        }
    }

    /** Opens a standalone JSON import and always gives it a new ingestion identity. */
    suspend fun importJson(value: String = _state.value.rawJson) = importJsonInternal(value, startNewIngestion = true)

    /** Parses the editor draft in-place; a bundle can never be converted through this path. */
    suspend fun parseJson() = importJsonInternal(_state.value.rawJson, startNewIngestion = false)

    private suspend fun importJsonInternal(value: String, startNewIngestion: Boolean) {
        beginBusy()
        try {
            val current = _state.value
            val bundleActive = current.bundleMetadata != null || current.bundleValidationStatus != null
            if (!startNewIngestion && bundleActive) {
                _state.value = current.copy(
                    error = "Bundle canonical JSON is read-only. Open JSON to start a separate ingestion.",
                    notice = null,
                )
                return
            }
            if (startNewIngestion) {
                _state.value = current.copy(
                    rawJson = value,
                    canonicalJson = "",
                    schema = null,
                    ingestionId = null,
                    localDocumentId = null,
                    session = null,
                    evidence = emptyList(),
                    artifacts = emptyList(),
                    bundleMetadata = null,
                    bundleValidationStatus = null,
                    error = null,
                    notice = null,
                )
            }
            val state = _state.value
            val localDocumentId = if (startNewIngestion) newLocalDocumentId()
            else state.localDocumentId ?: newLocalDocumentId()
            val ingestionId = if (startNewIngestion) newIngestionId()
            else state.ingestionId ?: newIngestionId()
            val result = useCase.importJson(
                value = value,
                localDocumentId = localDocumentId,
                ingestionId = ingestionId,
                evidence = if (startNewIngestion) emptyList() else localEvidence(state.evidence),
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
                requireArchivedEvidence = currentState.bundleMetadata != null,
                evidenceArchiveComplete = currentState.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED,
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
            var bundleMetadata = currentState.bundleMetadata
            if (bundleMetadata != null) {
                val artifactId = bundleMetadata.archiveCheckpoint.canonicalArtifactId
                    ?: error("Evidence archive is missing the canonical artifact id.")
                when (val event = bundle.evidenceArchivePort.recordVerification(
                    artifactId,
                    verificationBasis,
                    "verified",
                    emptyList(),
                )) {
                    EvidenceVerificationEventResult.Success -> {
                        bundleMetadata = bundleMetadata.copy(verificationEventRecorded = true)
                    }
                    is EvidenceVerificationEventResult.Failure -> {
                        bundleMetadata = bundleMetadata.copy(
                            verificationEventRecorded = false,
                            archiveError = event.issue,
                        )
                    }
                }
            }
            persistRecord(latest, currentState.rawJson, canonicalJson, currentState.evidence, bundleMetadata)
            publish(
                session = latest,
                envelope = confirmation.envelope,
                rawJson = currentState.rawJson,
                canonicalJson = canonicalJson,
                evidence = currentState.evidence,
                notice = if (latest.verifiedCanonicalFingerprint != null &&
                    (bundleMetadata == null || bundleMetadata.verificationEventRecorded)
                ) {
                    "Verified. Projections are ready for Submit."
                } else {
                    "Verification event is not archived; Submit remains blocked."
                },
                error = bundleMetadata?.archiveError,
                bundleMetadata = bundleMetadata,
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
            require(currentState.bundleValidationStatus != DesktopBundleValidationStatus.INVALID) { "Bundle is invalid." }
            val session = currentState.session ?: error("Import JSON before submitting.")
            val envelope = currentEnvelope()
            currentState.bundleMetadata?.let { metadata ->
                require(metadata.validationStatus == DesktopBundleValidationStatus.VALID) { "Bundle is invalid." }
                require(metadata.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED) {
                    "Archive / Retry must complete before Submit."
                }
                require(metadata.verificationEventRecorded) { "Verification event must be archived before Submit." }
            }
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
            bundleMetadata = record.bundle,
        )
    }

    private fun persistRecord(
        session: IngestionSession,
        rawJson: String,
        canonicalJson: String,
        evidence: List<DesktopEvidenceAttachment>,
        bundleMetadata: DesktopBundleMetadata? = _state.value.bundleMetadata,
    ) {
        store.saveRecord(
            DesktopSessionRecord(
                session = session.copy(attachments = evidence.map(::toLocalEvidence)),
                rawJson = rawJson,
                canonicalJson = canonicalJson,
                evidence = evidence,
                bundle = bundleMetadata,
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
        bundleMetadata: DesktopBundleMetadata? = _state.value.bundleMetadata,
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
            bundleMetadata = bundleMetadata,
            bundleValidationStatus = bundleMetadata?.validationStatus,
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
