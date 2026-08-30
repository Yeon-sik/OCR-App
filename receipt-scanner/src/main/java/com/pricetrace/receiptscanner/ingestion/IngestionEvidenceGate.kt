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

/** Keeps the existing gate and derives evidence requirements from the artifacts actually present. */
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
        val required = buildSet {
            if (envelope.receipt != null) add(SourceAttachmentType.RECEIPT)
            if (envelope.nutrition.any { it is IngestionNutrition.ProductLabel }) {
                add(SourceAttachmentType.NUTRITION_LABEL)
            }
            if (envelope.nutrition.any { it is IngestionNutrition.RestaurantEstimate }) {
                add(SourceAttachmentType.FOOD_PHOTO)
            }
        }
        val missing = required - types
        return IngestionEvidenceResult(missing.isEmpty(), missing.map { "${it.wireValue}_image_required" })
    }
}
