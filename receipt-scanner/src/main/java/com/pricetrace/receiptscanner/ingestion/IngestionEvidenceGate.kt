package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.verification.VerifiedDraftGate

data class LocalEvidence(
    val attachmentId: String,
    val type: SourceAttachmentType,
    val fileReadable: Boolean,
    val pageId: String? = null,
)

data class IngestionEvidenceResult(
    val isAllowed: Boolean,
    val blockingIssues: List<String> = emptyList(),
)

/** Keeps the existing gate and adds mode-specific evidence requirements for integrated imports. */
object IngestionEvidenceGate {
    fun evaluate(
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
    ): IngestionEvidenceResult {
        val base = VerifiedDraftGate.evaluate(
            inputOrigin = inputOrigin,
            localPageCount = evidence.size,
            allLocalPageFilesReadable = evidence.isNotEmpty() && evidence.all(LocalEvidence::fileReadable),
        )
        if (!base.isAllowed) {
            return IngestionEvidenceResult(false, listOf(base.failure!!.name.lowercase()))
        }
        val types = evidence.filter(LocalEvidence::fileReadable).map(LocalEvidence::type).toSet()
        val required = when (envelope.mode) {
            IngestionMode.MERCHANT -> emptySet()
            IngestionMode.RESTAURANT -> setOf(SourceAttachmentType.RECEIPT, SourceAttachmentType.FOOD_PHOTO)
            IngestionMode.PACKAGED_PRODUCT -> setOf(SourceAttachmentType.NUTRITION_LABEL)
        }
        val missing = required - types
        return IngestionEvidenceResult(missing.isEmpty(), missing.map { "${it.wireValue}_image_required" })
    }
}
