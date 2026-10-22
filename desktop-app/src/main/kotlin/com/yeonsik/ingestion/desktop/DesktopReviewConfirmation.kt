package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec

/** Older bundle imports inherited the previous workspace's edit log. Recover only unchanged base snapshots. */
internal fun removeInheritedBundleEdits(record: DesktopSessionRecord): DesktopSessionRecord {
    val metadata = record.bundle ?: return record
    if (record.reviewEdits.isEmpty() || metadata.pendingRevision != null ||
        metadata.revisionArchiveStatus != DesktopCanonicalRevisionArchiveStatus.NOT_STARTED ||
        metadata.archiveCheckpoint.latestRevisionSeq != 0L || metadata.archiveCheckpoint.latestRevisionId != null
    ) return record
    val unchanged = runCatching {
        val original = (ExternalJsonImporter().import(record.rawJson, record.session.localDocumentId)
            as? ExternalJsonImportOutcome.Success)?.result?.canonicalEnvelope ?: return@runCatching false
        val current = YeonsikOcrEnvelopeCodec.decode(record.canonicalJson, record.session.localDocumentId, preservePersistedVerification = true)
        // Verification is recorded independently of edits; comparing facts must ignore its current status.
        YeonsikOcrEnvelopeCodec.canonicalize(original.copy(review = current.review)) ==
            YeonsikOcrEnvelopeCodec.canonicalize(current)
    }.getOrDefault(false)
    return if (unchanged) record.copy(reviewEdits = emptyList()) else record
}

internal fun reviewConfirmationBlocker(state: DesktopUiState, busy: Boolean, revisionReady: Boolean): String? = when {
    busy -> "처리 중입니다. 완료되면 검수를 확정할 수 있습니다."
    state.session == null -> "검토할 자료를 먼저 가져오세요."
    state.draftSaveError != null -> state.draftSaveError
    state.reviewDrafts.isNotEmpty() -> "미적용 입력 ${state.reviewDrafts.size}개 · 적용하거나 입력을 취소하세요."
    state.bundleValidationStatus == DesktopBundleValidationStatus.INVALID ||
        state.bundleMetadata?.validationStatus == DesktopBundleValidationStatus.INVALID -> "번들 오류를 해결한 뒤 다시 가져오세요."
    state.envelope?.let(com.pricetrace.receiptscanner.review.LinkedProductReview::hasConflicts) == true -> "상품 정보의 서로 다른 값을 먼저 확인하세요."
    state.reviewFieldErrors.isNotEmpty() -> "입력 오류 ${state.reviewFieldErrors.size}개를 수정하면 검수를 확정할 수 있습니다."
    state.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVING -> "원본을 보관하고 있습니다. 완료되면 검수를 확정할 수 있습니다."
    state.bundleMetadata != null && state.bundleMetadata.archiveStatus != DesktopEvidenceArchiveStatus.ARCHIVED -> "원본 보관이 완료되지 않았습니다. 원본 보관을 다시 시도하세요."
    !revisionReady -> "수정본 보관이 완료되지 않았습니다. 수정본 보관을 완료하면 검수를 확정할 수 있습니다."
    else -> null
}
