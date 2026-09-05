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
        /**
         * When present, evaluate only the evidence needed by these reviewed artifacts. The
         * default remains the full-envelope gate for callers that intentionally verify the whole
         * bundle at once.
         */
        artifactKeys: Set<String>? = null,
    ): IngestionEvidenceResult {
        val requiredTypes = requiredEvidenceTypes(envelope, artifactKeys)
        val scopedEvidence = if (artifactKeys == null) {
            evidence
        } else {
            evidence.filter { it.type in requiredTypes }
        }
        val base = VerifiedDraftGate.evaluate(
            inputOrigin = inputOrigin,
            localPageCount = scopedEvidence.size,
            allLocalPageFilesReadable = scopedEvidence.isNotEmpty() && scopedEvidence.all(LocalEvidence::fileReadable),
        )
        if (!base.isAllowed) {
            return IngestionEvidenceResult(false, listOf(base.failure!!.name.lowercase()))
        }
        val types = scopedEvidence.filter(LocalEvidence::fileReadable).map(LocalEvidence::type).toSet()
        val missing = requiredTypes - types
        return IngestionEvidenceResult(missing.isEmpty(), missing.map { "${it.wireValue}_image_required" })
    }

    private fun requiredEvidenceTypes(
        envelope: YeonsikOcrEnvelope,
        artifactKeys: Set<String>?,
    ): Set<SourceAttachmentType> = buildSet {
        if (artifactKeys == null || IngestionArtifactKeys.RECEIPT in artifactKeys) {
            if (envelope.receipt != null) add(SourceAttachmentType.RECEIPT)
        }
        envelope.nutrition
            .filter { artifactKeys == null || IngestionArtifactKeys.nutrition(it.clientKey) in artifactKeys }
            .forEach { item ->
                when (item) {
                    is IngestionNutrition.ProductLabel -> add(SourceAttachmentType.NUTRITION_LABEL)
                    is IngestionNutrition.RestaurantEstimate -> add(SourceAttachmentType.FOOD_PHOTO)
                }
            }
        if (artifactKeys == null || artifactKeys.any { it.startsWith("${IngestionArtifactKeys.CONSUMPTION}:") }) {
            if (envelope.consumption.isNotEmpty()) add(SourceAttachmentType.FOOD_PHOTO)
        }
    }
}
