package com.pricetrace.receiptocr

import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.CanonicalImportResult
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlan
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveCheckpoint
import com.pricetrace.receiptscanner.ingestion.EvidenceArchivePort
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveRequest
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveResult
import com.pricetrace.receiptscanner.ingestion.EvidenceVerificationEventResult
import com.pricetrace.receiptscanner.ingestion.YeonsikBundle
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleManifestCodec
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleMaterializer
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class AndroidCanonicalJsonValidatorState(
    val rawJson: String = "",
    val canonicalJson: String = "",
    val localDocumentId: String? = null,
    val ingestionId: String? = null,
    val envelope: YeonsikOcrEnvelope? = null,
    val session: IngestionSession? = null,
    val plan: CanonicalProjectionPlan? = null,
    /** Local evidence is supplied by the Android capture layer; JSON alone never creates it. */
    val evidence: List<LocalEvidence> = emptyList(),
    val selectedProjections: Set<IngestionProjection> = emptySet(),
    val verificationBasis: VerificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
    val bundle: AndroidBundleState? = null,
    /** Keeps a rejected bundle visible even when no trusted manifest metadata exists. */
    val bundleValidationStatus: AndroidBundleValidationStatus? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

enum class AndroidBundleValidationStatus { VALID, INVALID }
enum class AndroidEvidenceArchiveStatus { NOT_STARTED, ARCHIVING, ARCHIVED, FAILED }

data class AndroidBundleState(
    val sourceName: String,
    val canonicalSha256: String,
    val manifestJson: String,
    val evidencePaths: Map<String, String>,
    val validationStatus: AndroidBundleValidationStatus = AndroidBundleValidationStatus.VALID,
    val archiveStatus: AndroidEvidenceArchiveStatus = AndroidEvidenceArchiveStatus.NOT_STARTED,
    val archiveCheckpoint: EvidenceArchiveCheckpoint = EvidenceArchiveCheckpoint(),
    val verificationEventRecorded: Boolean = false,
    val archiveError: String? = null,
) {
    val manifestSha256: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(manifestJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    val bundleFingerprint: String
        get() = com.pricetrace.receiptscanner.ingestion.bundleFingerprintFor(canonicalSha256, manifestSha256)
}

/** Android-only adapter; validation, confirmation, routing, retry, and idempotency stay in Core. */
class AndroidCanonicalJsonValidator(
    private val useCase: CanonicalIngestionUseCase,
    private val evidenceArchivePort: EvidenceArchivePort? = null,
    private val bundleRoot: File? = null,
    private val newLocalDocumentId: () -> String = { "android-json-${UUID.randomUUID()}" },
    private val newIngestionId: () -> String = { "android-ingestion-${UUID.randomUUID()}" },
    private val bundleStateStore: AndroidBundleStateStore? = null,
) {
    suspend fun importBundle(
        input: InputStream,
        sourceName: String,
        previous: AndroidCanonicalJsonValidatorState,
    ): AndroidCanonicalJsonValidatorState {
        val localDocumentId = newLocalDocumentId().replace("android-json-", "android-bundle-")
        val ingestionId = newIngestionId()
        val root = requireNotNull(bundleRoot) { "Android bundle storage is not configured." }.resolve(ingestionId)
        val materializer = AndroidBundleMaterializer(root)
        return try {
            val importedBundle = YeonsikBundleReader.read(input, localDocumentId, materializer)
            val evidence = importedBundle.manifest.evidence.map { item ->
                LocalEvidence(item.sourceFileId, item.type, materializer.fileFor(item.path).canRead())
            }
            when (val result = useCase.importJson(
                value = importedBundle.canonicalJson,
                localDocumentId = localDocumentId,
                ingestionId = ingestionId,
                evidence = evidence,
                bundleFingerprint = importedBundle.bundleFingerprint,
            )) {
                is CanonicalImportResult.Failure -> {
                    materializer.abort()
                    bundleStateStore?.clearActive()
                    previous.copy(
                        rawJson = importedBundle.canonicalJson,
                        canonicalJson = "",
                        localDocumentId = null,
                        ingestionId = null,
                        envelope = null,
                        session = null,
                        plan = null,
                        evidence = emptyList(),
                        selectedProjections = emptySet(),
                        bundle = null,
                        bundleValidationStatus = AndroidBundleValidationStatus.INVALID,
                        error = result.error?.detail ?: result.issues.joinToString(", "),
                        notice = null,
                    )
                }
                is CanonicalImportResult.Success -> {
                    if (result.startResult is IngestionStartResult.Duplicate) {
                        materializer.abort()
                        val restored = restoreBundleState(result.session.ingestionId)
                            ?: previous.takeIf { bundleStateStore == null && it.ingestionId == result.session.ingestionId && it.bundle != null }
                        return restored?.copy(
                            notice = "Duplicate bundle fingerprint: 기존 immutable session을 복구했습니다.",
                            error = null,
                        ) ?: previous.copy(
                            error = "Duplicate bundle session is missing its durable recovery record.",
                            notice = null,
                        )
                    }
                    val plan = useCase.plan(result.envelope)
                    val state = previous.copy(
                        rawJson = importedBundle.canonicalJson,
                        canonicalJson = YeonsikOcrEnvelopeCodec.encode(result.envelope),
                        localDocumentId = localDocumentId,
                        ingestionId = result.session.ingestionId,
                        envelope = result.envelope,
                        session = result.session,
                        plan = plan,
                        evidence = evidence,
                        selectedProjections = plan.eligible,
                        verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
                        bundle = AndroidBundleState(
                            sourceName = sourceName,
                            canonicalSha256 = importedBundle.manifest.canonicalSha256,
                            manifestJson = importedBundle.manifestJson,
                            evidencePaths = importedBundle.manifest.evidence.associate {
                                it.sourceFileId to materializer.fileFor(it.path).absolutePath
                            },
                        ),
                        bundleValidationStatus = AndroidBundleValidationStatus.VALID,
                        error = null,
                        notice = "Bundle 검증 및 evidence 자동 binding 완료. archive를 시작합니다.",
                    )
                    persistBundleState(state)
                    archiveBundle(state)
                }
            }
        } catch (error: Exception) {
            materializer.abort()
            bundleStateStore?.clearActive()
            previous.copy(
                canonicalJson = "",
                localDocumentId = null,
                ingestionId = null,
                envelope = null,
                session = null,
                plan = null,
                evidence = emptyList(),
                selectedProjections = emptySet(),
                bundle = null,
                bundleValidationStatus = AndroidBundleValidationStatus.INVALID,
                error = "Bundle invalid: ${error.message ?: error.javaClass.simpleName}",
                notice = null,
            )
        }
    }

    suspend fun archiveBundle(state: AndroidCanonicalJsonValidatorState): AndroidCanonicalJsonValidatorState {
        val bundleState = requireNotNull(state.bundle) { "Import a .yeonsik bundle first." }
        val port = requireNotNull(evidenceArchivePort) { "Evidence archive is not configured." }
        val envelope = requireNotNull(state.envelope)
        val request = EvidenceArchiveRequest(
            bundle = YeonsikBundle(
                manifest = YeonsikBundleManifestCodec.decode(bundleState.manifestJson),
                manifestJson = bundleState.manifestJson,
                canonicalJson = state.rawJson,
                envelope = envelope,
                manifestSha256 = bundleState.manifestSha256,
            ),
            openEvidence = { sourceFileId ->
                val path = bundleState.evidencePaths[sourceFileId]
                    ?: error("Bundle evidence is missing locally: $sourceFileId")
                File(path).inputStream()
            },
        )
        val archiving = bundleState.copy(archiveStatus = AndroidEvidenceArchiveStatus.ARCHIVING, archiveError = null)
        val archivingState = state.copy(bundle = archiving, error = null)
        persistBundleState(archivingState)
        return try {
            when (val result = port.archive(request, archiving.archiveCheckpoint)) {
                is EvidenceArchiveResult.Success -> archivingState.copy(
                    bundle = archiving.copy(
                        archiveStatus = AndroidEvidenceArchiveStatus.ARCHIVED,
                        archiveCheckpoint = result.checkpoint,
                    ),
                    error = null,
                    notice = "Evidence archive 완료. 내용을 검수한 뒤 확정하세요.",
                ).also(::persistBundleState)
                is EvidenceArchiveResult.Failure -> archivingState.copy(
                    bundle = archiving.copy(
                        archiveStatus = AndroidEvidenceArchiveStatus.FAILED,
                        archiveCheckpoint = result.checkpoint,
                        archiveError = result.issue,
                    ),
                    error = "Evidence archive 실패: ${result.issue}",
                    notice = "로컬 bundle은 보존되었습니다. Archive / Retry만 다시 실행하세요.",
                ).also(::persistBundleState)
            }
        } catch (error: Exception) {
            archivingState.copy(
                bundle = archiving.copy(
                    archiveStatus = AndroidEvidenceArchiveStatus.FAILED,
                    archiveError = error.message ?: error.javaClass.simpleName,
                ),
                error = error.message ?: error.javaClass.simpleName,
                notice = "로컬 bundle은 보존되었습니다. Archive / Retry만 다시 실행하세요.",
            ).also(::persistBundleState)
        }
    }

    suspend fun importJson(
        rawJson: String,
        previous: AndroidCanonicalJsonValidatorState,
        startNewIngestion: Boolean = false,
    ): AndroidCanonicalJsonValidatorState {
        val bundleActive = previous.bundle != null || previous.bundleValidationStatus != null
        if (!startNewIngestion && bundleActive) {
            return previous.copy(
                error = "Bundle canonical JSON is read-only. JSON import starts a separate ingestion.",
                notice = null,
            )
        }
        if (startNewIngestion) bundleStateStore?.clearActive()
        val base = if (startNewIngestion) previous.copy(
            canonicalJson = "",
            localDocumentId = null,
            ingestionId = null,
            envelope = null,
            session = null,
            plan = null,
            evidence = emptyList(),
            selectedProjections = emptySet(),
            bundle = null,
            bundleValidationStatus = null,
        ) else previous
        val localDocumentId = if (startNewIngestion) newLocalDocumentId() else base.localDocumentId ?: newLocalDocumentId()
        val ingestionId = if (startNewIngestion) newIngestionId() else base.ingestionId ?: newIngestionId()
        return when (val result = useCase.importJson(
            value = rawJson,
            localDocumentId = localDocumentId,
            ingestionId = ingestionId,
            evidence = if (startNewIngestion) emptyList() else base.evidence,
        )) {
            is CanonicalImportResult.Failure -> base.copy(
                rawJson = rawJson,
                bundle = null,
                bundleValidationStatus = null,
                error = result.error?.let { "${it.code}: ${it.detail.orEmpty()}" }
                    ?: result.issues.joinToString(", "),
                notice = null,
            )
            is CanonicalImportResult.Success -> {
                val plan = useCase.plan(result.envelope)
                val selected = base.selectedProjections.intersect(plan.eligible).ifEmpty { plan.eligible }
                base.copy(
                    rawJson = rawJson,
                    canonicalJson = YeonsikOcrEnvelopeCodec.encode(result.envelope),
                    localDocumentId = localDocumentId,
                    ingestionId = result.session.ingestionId,
                    envelope = result.envelope,
                    session = result.session,
                    plan = plan,
                    evidence = base.evidence,
                    bundle = null,
                    bundleValidationStatus = null,
                    selectedProjections = selected,
                    error = null,
                    notice = "JSON을 파싱하고 canonical 초안을 저장했습니다. 내용을 확인한 뒤 확정하세요.",
                )
            }
        }
    }

    /** Restores durable bundle metadata and re-validates all paths before exposing the bundle again. */
    suspend fun restoreBundleState(ingestionId: String? = null): AndroidCanonicalJsonValidatorState? {
        val recovery = if (ingestionId == null) {
            bundleStateStore?.loadActive()
        } else {
            bundleStateStore?.load(ingestionId)
        } ?: return null
        require(recovery.ingestionId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "unsafe recovery ingestion id" }
        val root = requireNotNull(bundleRoot) { "Android bundle storage is not configured." }
            .resolve(recovery.ingestionId).canonicalFile
        val manifest = YeonsikBundleManifestCodec.decode(recovery.bundle.manifestJson)
        require(manifest.canonicalSha256 == recovery.bundle.canonicalSha256) { "recovery canonical hash mismatch" }
        require(recovery.bundle.bundleFingerprint ==
            com.pricetrace.receiptscanner.ingestion.bundleFingerprintFor(
                manifest.canonicalSha256,
                recovery.bundle.manifestSha256,
            )) { "recovery bundle fingerprint mismatch" }
        recovery.bundle.archiveCheckpoint.bundleFingerprint?.let {
            require(it == recovery.bundle.bundleFingerprint) { "recovery checkpoint belongs to a different bundle" }
        }
        val evidence = manifest.evidence.map { item ->
            val persistedPath = requireNotNull(recovery.bundle.evidencePaths[item.sourceFileId]) {
                "recovery evidence path is missing: ${item.sourceFileId}"
            }
            val path = File(persistedPath).canonicalFile
            require(path.path.startsWith(root.path + File.separator)) { "recovery evidence path escapes bundle root" }
            LocalEvidence(item.sourceFileId, item.type, path.canRead())
        }
        val session = useCase.session(recovery.ingestionId) ?: return null
        val envelope = useCase.strictDecode(
            recovery.canonicalJson,
            recovery.localDocumentId,
            preservePersistedVerification = true,
        )
        val plan = useCase.plan(envelope)
        val recoveredBundle = if (recovery.bundle.archiveStatus == AndroidEvidenceArchiveStatus.ARCHIVING) {
            recovery.bundle.copy(
                archiveStatus = AndroidEvidenceArchiveStatus.FAILED,
                archiveError = "Archive interrupted; retry is available after process recovery.",
            )
        } else recovery.bundle
        val restored = AndroidCanonicalJsonValidatorState(
            rawJson = recovery.rawJson,
            canonicalJson = recovery.canonicalJson,
            localDocumentId = recovery.localDocumentId,
            ingestionId = recovery.ingestionId,
            envelope = envelope,
            session = session,
            plan = plan,
            evidence = evidence,
            selectedProjections = recovery.selectedProjections.intersect(plan.eligible),
            verificationBasis = recovery.verificationBasis,
            bundle = recoveredBundle,
            bundleValidationStatus = recoveredBundle.validationStatus,
            notice = if (recoveredBundle.archiveStatus == AndroidEvidenceArchiveStatus.FAILED) {
                "저장된 bundle을 복구했습니다. Archive / Retry로 중단된 작업을 재개하세요."
            } else "저장된 bundle 상태를 복구했습니다.",
        )
        persistBundleState(restored)
        return restored
    }

    private fun persistBundleState(state: AndroidCanonicalJsonValidatorState) {
        val bundle = state.bundle ?: return
        val localDocumentId = state.localDocumentId ?: return
        val ingestionId = state.ingestionId ?: return
        val store = bundleStateStore ?: return
        check(store.save(
            AndroidBundleRecoveryState(
                rawJson = state.rawJson,
                canonicalJson = state.canonicalJson,
                localDocumentId = localDocumentId,
                ingestionId = ingestionId,
                selectedProjections = state.selectedProjections,
                verificationBasis = state.verificationBasis,
                bundle = bundle,
            ),
        )) { "Android bundle recovery state could not be persisted" }
    }

    suspend fun confirm(
        state: AndroidCanonicalJsonValidatorState,
    ): AndroidCanonicalJsonValidatorState {
        require(state.bundleValidationStatus != AndroidBundleValidationStatus.INVALID) { "Bundle is invalid." }
        val envelope = requireNotNull(state.envelope) { "Import JSON before confirmation." }
        val ingestionId = requireNotNull(state.ingestionId) { "Ingestion id is missing." }
        val confirmation = useCase.confirm(
            ingestionId = ingestionId,
            envelope = envelope,
            evidence = state.evidence,
            verificationBasis = state.verificationBasis,
            requireArchivedEvidence = state.bundle != null,
            evidenceArchiveComplete = state.bundle?.archiveStatus == AndroidEvidenceArchiveStatus.ARCHIVED,
        )
        val result = confirmation.result
        val session = when (result) {
            is IngestionStartResult.Success -> result.session
            is IngestionStartResult.Duplicate -> result.session
            is IngestionStartResult.Failure -> state.session
        }
        val plan = useCase.plan(confirmation.envelope)
        var bundleState = state.bundle
        var eventError: String? = null
        if (result !is IngestionStartResult.Failure && bundleState != null && !bundleState.verificationEventRecorded) {
            val artifactId = bundleState.archiveCheckpoint.canonicalArtifactId
            if (artifactId == null) {
                eventError = "Evidence canonical artifact id is missing."
            } else {
                when (val event = requireNotNull(evidenceArchivePort).recordVerification(
                    artifactId,
                    state.verificationBasis,
                    "verified",
                    emptyList(),
                )) {
                    EvidenceVerificationEventResult.Success -> {
                        bundleState = bundleState.copy(verificationEventRecorded = true, archiveError = null)
                    }
                    is EvidenceVerificationEventResult.Failure -> {
                        eventError = event.issue
                        bundleState = bundleState.copy(verificationEventRecorded = false, archiveError = event.issue)
                    }
                }
            }
        }
        val confirmed = state.copy(
            canonicalJson = YeonsikOcrEnvelopeCodec.encodePersisted(confirmation.envelope),
            envelope = confirmation.envelope,
            session = session,
            plan = plan,
            bundle = bundleState,
            selectedProjections = state.selectedProjections.intersect(plan.eligible),
            error = (result as? IngestionStartResult.Failure)?.issues?.joinToString(", ") ?: eventError,
            notice = if (result is IngestionStartResult.Failure || eventError != null) null else {
                "확정 완료(${state.verificationBasis.name}). 제출할 projection을 선택하세요."
            },
        )
        persistBundleState(confirmed)
        return confirmed
    }

    suspend fun submit(
        state: AndroidCanonicalJsonValidatorState,
    ): AndroidCanonicalJsonValidatorState = submitInternal(state)

    suspend fun retry(
        state: AndroidCanonicalJsonValidatorState,
    ): AndroidCanonicalJsonValidatorState = submitInternal(state)

    private suspend fun submitInternal(
        state: AndroidCanonicalJsonValidatorState,
    ): AndroidCanonicalJsonValidatorState {
        require(state.bundleValidationStatus != AndroidBundleValidationStatus.INVALID) { "Bundle is invalid." }
        val envelope = requireNotNull(state.envelope) { "Import JSON before submitting." }
        state.bundle?.let { bundle ->
            require(bundle.validationStatus == AndroidBundleValidationStatus.VALID) { "Bundle is invalid." }
            require(bundle.archiveStatus == AndroidEvidenceArchiveStatus.ARCHIVED) {
                "Archive / Retry must complete before Submit."
            }
            require(bundle.verificationEventRecorded) { "Verification event must be archived before Submit." }
        }
        val ingestionId = requireNotNull(state.ingestionId) { "Ingestion id is missing." }
        val plan = useCase.plan(envelope)
        val selected = state.selectedProjections.intersect(plan.eligible)
        require(selected.isNotEmpty()) { "Select at least one eligible projection." }
        val projections = useCase.submitSelected(ingestionId, envelope, selected)
        val session = useCase.session(ingestionId)
        val submitted = state.copy(
            session = session,
            plan = plan,
            error = null,
            notice = "Projection 처리 완료: " + projections
                .filter { it.projection in selected }
                .joinToString(", ") { "${it.projection.wireValue}=${it.status.wireValue}" },
        )
        persistBundleState(submitted)
        if (state.bundle != null && selected.all { projection ->
                session?.projections?.firstOrNull { it.projection == projection }?.status == ProjectionStatus.UPLOADED
            }) {
            check(bundleStateStore?.clearActive() != false) { "Completed Android bundle could not be cleared from active recovery" }
        }
        return submitted
    }
}

private class AndroidBundleMaterializer(private val root: File) : YeonsikBundleMaterializer {
    override fun open(relativePath: String): OutputStream {
        val target = fileFor(relativePath)
        target.parentFile?.mkdirs()
        return target.outputStream()
    }

    fun fileFor(relativePath: String): File {
        require(relativePath.startsWith("evidence/") && ".." !in relativePath && '\\' !in relativePath) {
            "unsafe bundle evidence path"
        }
        val target = root.resolve(relativePath.removePrefix("evidence/")).canonicalFile
        require(target.path.startsWith(root.canonicalFile.path + File.separator)) {
            "bundle evidence escapes app-private storage"
        }
        return target
    }

    override fun abort() {
        if (root.exists()) root.deleteRecursively()
    }
}
