package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.ExternalJsonImportError
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.input.InputOrigin
import java.time.OffsetDateTime

sealed interface CanonicalImportResult {
    data class Success(
        val imported: com.pricetrace.receiptscanner.importer.ExternalJsonImportResult,
        val envelope: YeonsikOcrEnvelope,
        val session: IngestionSession,
        val startResult: IngestionStartResult,
    ) : CanonicalImportResult

    data class Failure(
        val error: ExternalJsonImportError? = null,
        val issues: List<String> = emptyList(),
    ) : CanonicalImportResult
}

data class CanonicalArtifactValidationResult(
    val isAllowed: Boolean,
    val issues: List<String> = emptyList(),
)

data class CanonicalConfirmationResult(
    val result: IngestionStartResult,
    val envelope: YeonsikOcrEnvelope,
)

/**
 * Platform-neutral canonical ingestion use-case. Android and Desktop call this class for the
 * same strict import, review confirmation, projection planning, and retry semantics.
 */
class CanonicalIngestionUseCase(
    private val store: IngestionSessionStore,
    submitters: Map<IngestionProjection, IngestionProjectionSubmitter> = emptyMap(),
    private val importer: ExternalJsonImporter = ExternalJsonImporter(),
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    private val orchestrator = IngestionOrchestrator(
        store = store,
        identityResolver = null,
        submitters = submitters,
        now = now,
    )

    suspend fun importJson(
        value: String,
        localDocumentId: String,
        ingestionId: String? = null,
        evidence: List<LocalEvidence> = emptyList(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
    ): CanonicalImportResult {
        val imported = when (val outcome = importer.import(value, localDocumentId)) {
            is ExternalJsonImportOutcome.Failure -> return CanonicalImportResult.Failure(outcome.error)
            is ExternalJsonImportOutcome.Success -> outcome.result
        }
        val envelope = imported.canonicalEnvelope
        val actualIngestionId = ingestionId ?: "ingestion-${localDocumentId}"
        val current = store.get(actualIngestionId)
        val startResult = if (current == null) {
            orchestrator.start(
                ingestionId = actualIngestionId,
                localDocumentId = localDocumentId,
                envelope = envelope,
                evidence = evidence,
                inputOrigin = inputOrigin,
            )
        } else {
            orchestrator.reviseCanonicalDraft(actualIngestionId, envelope)
        }
        val session = when (startResult) {
            is IngestionStartResult.Duplicate -> startResult.session
            else -> store.get(actualIngestionId)
        } ?: return CanonicalImportResult.Failure(issues = listOf("ingestion_session_not_persisted"))
        return CanonicalImportResult.Success(imported, envelope, session, startResult)
    }

    fun strictDecode(
        value: String,
        localDocumentId: String,
        preservePersistedVerification: Boolean = false,
    ): YeonsikOcrEnvelope = YeonsikOcrEnvelopeCodec.decode(value, localDocumentId, preservePersistedVerification)

    fun validate(
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence> = emptyList(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
        artifactKeys: Set<String>? = null,
    ): CanonicalArtifactValidationResult {
        val result = IngestionEvidenceGate.evaluate(
            envelope = envelope,
            evidence = evidence,
            inputOrigin = inputOrigin,
            artifactKeys = artifactKeys,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = explicitUserConfirmation,
        )
        return CanonicalArtifactValidationResult(result.isAllowed, result.blockingIssues)
    }

    fun plan(envelope: YeonsikOcrEnvelope): CanonicalProjectionPlan =
        CanonicalProjectionPlanner.plan(envelope)

    suspend fun session(ingestionId: String): IngestionSession? = store.get(ingestionId)

    /** Promotes only after explicit review; producer review/user_verified fields never reach here. */
    suspend fun confirm(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence> = emptyList(),
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        requireArchivedEvidence: Boolean = false,
        evidenceArchiveComplete: Boolean = false,
    ): CanonicalConfirmationResult {
        if (requireArchivedEvidence && verificationBasis == VerificationBasis.SOURCE_EVIDENCE && !evidenceArchiveComplete) {
            return CanonicalConfirmationResult(
                IngestionStartResult.Failure(listOf("evidence_archive_required")),
                envelope,
            )
        }
        val validation = validate(
            envelope = envelope,
            evidence = evidence,
            inputOrigin = inputOrigin,
            verificationBasis = verificationBasis,
            explicitUserConfirmation = true,
        )
        if (!validation.isAllowed) {
            return CanonicalConfirmationResult(IngestionStartResult.Failure(validation.issues), envelope)
        }
        val confirmedAt = now()
        val promoted = envelope.copy(
            nutrition = envelope.nutrition.map { item ->
                when (item) {
                    is IngestionNutrition.ProductLabel -> item.copy(
                        draft = item.draft.asUserVerified(confirmedAt),
                    )
                    else -> item
                }
            },
            consumption = envelope.consumption.map {
                it.copy(status = ConsumptionVerificationStatus.USER_VERIFIED)
            },
            review = envelope.review.copy(
                status = IngestionReviewStatus.READY,
                blockingIssues = emptyList(),
                verificationBasis = verificationBasis,
            ),
        )
        val revised = orchestrator.reviseCanonicalDraft(ingestionId, promoted)
        if (revised is IngestionStartResult.Failure) return CanonicalConfirmationResult(revised, promoted)

        var latest = store.get(ingestionId)
            ?: return CanonicalConfirmationResult(
                IngestionStartResult.Failure(listOf("ingestion_not_found")),
                promoted,
            )
        val operations = buildList<suspend () -> IngestionStartResult> {
            if (promoted.receipt != null) {
                add {
                    orchestrator.markReceiptVerified(
                        ingestionId, promoted, evidence, inputOrigin, verificationBasis, true,
                    )
                }
            }
            if (promoted.purchaseRecords.isNotEmpty()) {
                add {
                    orchestrator.markPurchaseRecordsVerified(
                        ingestionId, promoted, evidence, inputOrigin = inputOrigin,
                        verificationBasis = verificationBasis, explicitUserConfirmation = true,
                    )
                }
            }
            if (promoted.priceObservations.isNotEmpty()) {
                add {
                    orchestrator.markPriceObservationsVerified(
                        ingestionId, promoted, evidence, inputOrigin = inputOrigin,
                        verificationBasis = verificationBasis, explicitUserConfirmation = true,
                    )
                }
            }
            if (promoted.nutrition.isNotEmpty()) {
                add {
                    orchestrator.markNutritionVerified(
                        ingestionId, promoted, evidence, inputOrigin = inputOrigin,
                        verificationBasis = verificationBasis, explicitUserConfirmation = true,
                    )
                }
            }
            if (promoted.consumption.isNotEmpty()) {
                add {
                    orchestrator.markConsumptionVerified(
                        ingestionId, promoted, evidence, inputOrigin = inputOrigin,
                        verificationBasis = verificationBasis, explicitUserConfirmation = true,
                    )
                }
            }
            if (promoted.productCandidates.isNotEmpty()) {
                add {
                    orchestrator.markProductCandidatesVerified(
                        ingestionId, promoted, evidence, inputOrigin = inputOrigin,
                        verificationBasis = verificationBasis, explicitUserConfirmation = true,
                    )
                }
            }
            if (promoted.merchantCandidate != null) {
                add {
                    orchestrator.markMerchantCandidateVerified(
                        ingestionId, promoted, evidence, inputOrigin,
                        verificationBasis, true,
                    )
                }
            }
        }
        for (operation in operations) {
            when (val result = operation()) {
                is IngestionStartResult.Success -> latest = result.session
                is IngestionStartResult.Duplicate -> latest = result.session
                is IngestionStartResult.Failure -> return CanonicalConfirmationResult(result, promoted)
            }
        }
        return CanonicalConfirmationResult(IngestionStartResult.Success(latest), promoted)
    }

    suspend fun submitSelected(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        selectedProjections: Set<IngestionProjection>,
    ): List<ProjectionState> = orchestrator.submitSelectedProjections(ingestionId, envelope, selectedProjections)

    suspend fun retrySelected(
        ingestionId: String,
        envelope: YeonsikOcrEnvelope,
        selectedProjections: Set<IngestionProjection>,
    ): List<ProjectionState> = orchestrator.retrySelectedProjections(ingestionId, envelope, selectedProjections)
}

typealias CanonicalIngestionController = CanonicalIngestionUseCase
