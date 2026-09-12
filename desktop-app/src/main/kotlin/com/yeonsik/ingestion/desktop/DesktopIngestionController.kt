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
                error = "번들의 정본 JSON은 읽기 전용입니다. 별도 수집을 시작하려면 JSON을 여세요.",
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
                            ?: error("중복 번들 세션의 영속 레코드를 찾을 수 없습니다.")
                        loadRecord(existing, "중복 번들 지문을 확인해 기존의 변경 불가 세션을 불러왔습니다.")
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
                            "번들 검증 및 증거 연결이 완료되었습니다. 보관을 시작합니다.",
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
                error = "번들이 유효하지 않습니다: ${error.message ?: error.javaClass.simpleName}",
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
            val session = current.session ?: error("보관하려면 .yeonsik 번들을 먼저 가져오세요.")
            val metadata = current.bundleMetadata ?: error("보관할 번들이 없습니다.")
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
                        ?: error("번들 증거를 로컬에서 찾을 수 없습니다: $sourceFileId")
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
                if (updated.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED) "증거 보관이 완료되었습니다."
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
                    error = "번들의 정본 JSON은 읽기 전용입니다. 별도 수집을 시작하려면 JSON을 여세요.",
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
            val session = current.session ?: error("증거를 추가하려면 JSON을 먼저 가져오세요.")
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
                    "V4 원본 파일이 ${DesktopUiLabels.sourceAttachmentType(type)} 유형으로 선언되지 않았습니다."
                }
                require(paths.size <= v4SourceIds.count { it !in usedIds }) {
                    "V4 원본 유형 ${DesktopUiLabels.sourceAttachmentType(type)}에 증거 파일이 너무 많습니다."
                }
            }
            val added = paths.map { path ->
                val logicalId = if (isV4Purchase) {
                    v4SourceIds.firstOrNull { it !in usedIds }
                        ?: error("${DesktopUiLabels.sourceAttachmentType(type)} 유형에 사용할 수 있는 V4 원본 파일이 없습니다.")
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
                notice = "증거가 추가되었습니다. 검수를 완료해야 합니다.",
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
            val currentSession = currentState.session ?: error("검수하려면 JSON을 먼저 가져오세요.")
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
                        error = "검수가 차단되었습니다: ${result.issues.joinToString(", ")}",
                    )
                    return
                }
            }
            val canonicalJson = YeonsikOcrEnvelopeCodec.encodePersisted(confirmation.envelope)
            var bundleMetadata = currentState.bundleMetadata
            if (bundleMetadata != null) {
                val artifactId = bundleMetadata.archiveCheckpoint.canonicalArtifactId
                    ?: error("증거 보관에 정본 자료 ID가 없습니다.")
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
                    "검수가 완료되었습니다. 전송할 수 있습니다."
                } else {
                    "검수 이벤트가 보관되지 않아 전송이 차단되었습니다."
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
            require(currentState.bundleValidationStatus != DesktopBundleValidationStatus.INVALID) { "번들이 유효하지 않습니다." }
            val session = currentState.session ?: error("전송하려면 JSON을 먼저 가져오세요.")
            val envelope = currentEnvelope()
            currentState.bundleMetadata?.let { metadata ->
                require(metadata.validationStatus == DesktopBundleValidationStatus.VALID) { "번들이 유효하지 않습니다." }
                require(metadata.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED) {
                    "보관 / 재시도를 완료해야 전송할 수 있습니다."
                }
                require(metadata.verificationEventRecorded) { "전송 전에 검수 이벤트를 보관해야 합니다." }
            }
            if (session.verifiedCanonicalFingerprint != session.canonicalFingerprint) {
                error("전송하려면 검수한 자료를 먼저 검수 완료 처리하세요.")
            }
            val plan = useCase.plan(envelope)
            val selected = if (selectedProjections.isEmpty()) plan.eligible else {
                selectedProjections.intersect(plan.eligible)
            }
            if (selected.isEmpty()) error("전송할 수 있는 대상이 선택되지 않았습니다.")
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
                notice = "전송 작업이 끝났습니다: ${projectionSummary(projections)}",
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
            val record = store.latestRecord() ?: error("저장된 데스크톱 수집 세션이 없습니다.")
            loadRecord(record, "최신 로컬 세션을 불러왔습니다. 재시도할 수 있습니다.")
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message ?: error.javaClass.simpleName, notice = null)
        } finally {
            endBusy()
        }
    }

    suspend fun load(ingestionId: String) {
        beginBusy()
        try {
            val record = store.loadRecord(ingestionId) ?: error("저장된 수집을 찾을 수 없습니다: $ingestionId")
            loadRecord(record, "로컬 수집 세션을 불러왔습니다.")
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
                loadRecord(existing, "중복 지문을 확인해 기존 로컬 세션을 불러왔습니다.")
            } else {
                val canonicalJson = YeonsikOcrEnvelopeCodec.encode(envelope)
                persistRecord(result.session, rawJson, canonicalJson, evidence)
                publish(result.session, envelope, rawJson, canonicalJson, evidence, "중복 지문을 확인했습니다.", null)
            }
            return
        }

        val session = result.session
        val canonicalJson = YeonsikOcrEnvelopeCodec.encode(envelope)
        val startResult = result.startResult
        val notice = when (startResult) {
            is IngestionStartResult.Failure ->
                "파싱 후 저장했지만 검수가 차단되었습니다: ${startResult.issues.joinToString(", ")}."
            is IngestionStartResult.Success ->
                "파싱 및 검증이 완료되었습니다. JSON을 검토하고 증거를 추가한 뒤 검수 완료 처리하세요."
            else -> "파싱 및 검증이 완료되었습니다."
        }
        persistRecord(session, rawJson, canonicalJson, evidence)
        publish(session, envelope, rawJson, canonicalJson, evidence, notice, null)
    }

    private fun currentEnvelope(): YeonsikOcrEnvelope = _state.value.schema?.let {
        _state.value.canonicalJson.takeIf(String::isNotBlank)?.let { canonical ->
            YeonsikOcrEnvelopeCodec.decode(
                value = canonical,
                localDocumentId = _state.value.localDocumentId ?: error("로컬 문서 ID가 없습니다."),
                preservePersistedVerification = true,
            )
        }
    } ?: error("파싱된 정본 자료가 없습니다.")

    private fun loadRecord(record: DesktopSessionRecord, notice: String) {
        require(record.canonicalJson.isNotBlank()) { "저장된 세션에 정본 JSON이 없습니다." }
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
        if (envelope.receipt != null) addArtifact(IngestionArtifactKeys.RECEIPT, "영수증")
        if (envelope.merchantCandidate != null && envelope.receipt == null) {
            addArtifact(IngestionArtifactKeys.MERCHANT_CANDIDATE, "상점 후보")
        }
        envelope.priceObservations.forEach {
            addArtifact(IngestionArtifactKeys.priceObservation(it.clientKey), "가격 관측: ${it.clientKey}")
        }
        envelope.nutrition.forEach { addArtifact(IngestionArtifactKeys.nutrition(it.clientKey), "영양 정보: ${it.clientKey}") }
        envelope.consumption.forEach { addArtifact(IngestionArtifactKeys.consumption(it.clientKey), "섭취 기록: ${it.clientKey}") }
        envelope.productCandidates.forEach {
            addArtifact(IngestionArtifactKeys.productCandidate(it.clientKey), "상품: ${it.clientKey}")
        }
        envelope.purchaseRecords.forEach {
            addArtifact(IngestionArtifactKeys.purchaseRecord(it.clientKey), "구매: ${it.platform}")
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
            val base = "${DesktopUiLabels.projection(state.projection)}=${DesktopUiLabels.projectionStatus(state.status)}"
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
                val sourceSavedLabel = booleanMetadataLabel(sourceSaved)
                val observationCreatedLabel = booleanMetadataLabel(observationCreated)
                "$base (원본 저장: $sourceSavedLabel, 관측 생성: $observationCreatedLabel)"
            }
        }

    private fun booleanMetadataLabel(value: String): String = when (value.lowercase()) {
        "true" -> "예"
        "false" -> "아니오"
        else -> "알 수 없음"
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
