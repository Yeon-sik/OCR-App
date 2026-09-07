package com.pricetrace.receiptocr

import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.CanonicalImportResult
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlan
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
import java.util.UUID

data class AndroidCanonicalJsonValidatorState(
    val rawJson: String = "",
    val canonicalJson: String = "",
    val localDocumentId: String? = null,
    val ingestionId: String? = null,
    val envelope: YeonsikOcrEnvelope? = null,
    val session: IngestionSession? = null,
    val plan: CanonicalProjectionPlan? = null,
    val selectedProjections: Set<IngestionProjection> = emptySet(),
    val verificationBasis: VerificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/** Android-only adapter; validation, confirmation, routing, retry, and idempotency stay in Core. */
class AndroidCanonicalJsonValidator(
    private val useCase: CanonicalIngestionUseCase,
    private val newLocalDocumentId: () -> String = { "android-json-${UUID.randomUUID()}" },
    private val newIngestionId: () -> String = { "android-ingestion-${UUID.randomUUID()}" },
) {
    suspend fun importJson(
        rawJson: String,
        previous: AndroidCanonicalJsonValidatorState,
    ): AndroidCanonicalJsonValidatorState {
        val localDocumentId = previous.localDocumentId ?: newLocalDocumentId()
        val ingestionId = previous.ingestionId ?: newIngestionId()
        return when (val result = useCase.importJson(
            value = rawJson,
            localDocumentId = localDocumentId,
            ingestionId = ingestionId,
        )) {
            is CanonicalImportResult.Failure -> previous.copy(
                rawJson = rawJson,
                error = result.error?.let { "${it.code}: ${it.detail.orEmpty()}" }
                    ?: result.issues.joinToString(", "),
                notice = null,
            )
            is CanonicalImportResult.Success -> {
                val plan = useCase.plan(result.envelope)
                val selected = previous.selectedProjections.intersect(plan.eligible).ifEmpty { plan.eligible }
                previous.copy(
                    rawJson = rawJson,
                    canonicalJson = YeonsikOcrEnvelopeCodec.encode(result.envelope),
                    localDocumentId = localDocumentId,
                    ingestionId = result.session.ingestionId,
                    envelope = result.envelope,
                    session = result.session,
                    plan = plan,
                    selectedProjections = selected,
                    error = null,
                    notice = "JSON을 파싱하고 canonical 초안을 저장했습니다. 내용을 확인한 뒤 확정하세요.",
                )
            }
        }
    }

    suspend fun confirm(
        state: AndroidCanonicalJsonValidatorState,
    ): AndroidCanonicalJsonValidatorState {
        val envelope = requireNotNull(state.envelope) { "Import JSON before confirmation." }
        val ingestionId = requireNotNull(state.ingestionId) { "Ingestion id is missing." }
        val confirmation = useCase.confirm(
            ingestionId = ingestionId,
            envelope = envelope,
            verificationBasis = state.verificationBasis,
        )
        val result = confirmation.result
        val session = when (result) {
            is IngestionStartResult.Success -> result.session
            is IngestionStartResult.Duplicate -> result.session
            is IngestionStartResult.Failure -> state.session
        }
        val plan = useCase.plan(confirmation.envelope)
        return state.copy(
            canonicalJson = YeonsikOcrEnvelopeCodec.encode(confirmation.envelope),
            envelope = confirmation.envelope,
            session = session,
            plan = plan,
            selectedProjections = state.selectedProjections.intersect(plan.eligible),
            error = (result as? IngestionStartResult.Failure)?.issues?.joinToString(", "),
            notice = if (result is IngestionStartResult.Failure) null else {
                "확정 완료(${state.verificationBasis.name}). 제출할 projection을 선택하세요."
            },
        )
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
        val envelope = requireNotNull(state.envelope) { "Import JSON before submitting." }
        val ingestionId = requireNotNull(state.ingestionId) { "Ingestion id is missing." }
        val plan = useCase.plan(envelope)
        val selected = state.selectedProjections.intersect(plan.eligible)
        require(selected.isNotEmpty()) { "Select at least one eligible projection." }
        val projections = useCase.submitSelected(ingestionId, envelope, selected)
        val session = useCase.session(ingestionId)
        return state.copy(
            session = session,
            plan = plan,
            error = null,
            notice = "Projection 처리 완료: " + projections
                .filter { it.projection in selected }
                .joinToString(", ") { "${it.projection.wireValue}=${it.status.wireValue}" },
        )
    }
}
