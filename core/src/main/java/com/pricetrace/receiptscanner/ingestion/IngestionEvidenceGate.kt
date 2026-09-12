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
        verificationBasis: VerificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        explicitUserConfirmation: Boolean = false,
    ): IngestionEvidenceResult {
        val domainIssues = CanonicalEnvelopeValidator.validate(envelope)
        if (domainIssues.isNotEmpty()) return IngestionEvidenceResult(false, domainIssues)
        val conflictedPurchaseRecords = envelope.purchaseRecords.filter { record ->
            artifactKeys == null ||
                IngestionArtifactKeys.purchaseRecord(record.clientKey) in artifactKeys
        }
        val purchaseConflictIssues = conflictedPurchaseRecords.flatMap { record ->
            record.conflictFields().map { field ->
                "purchase_evidence_conflict:${record.clientKey}:$field"
            }
        }.distinct()
        if (purchaseConflictIssues.isNotEmpty()) {
            return IngestionEvidenceResult(false, purchaseConflictIssues)
        }
        val selectedProductCandidates = envelope.productCandidates.filter { candidate ->
            artifactKeys == null ||
                IngestionArtifactKeys.productCandidate(candidate.clientKey) in artifactKeys
        }
        if (envelope.schemaVersion == YEONSIK_OCR_V4_SCHEMA) {
            val candidateConflictIssues = selectedProductCandidates.flatMap { candidate ->
                candidate.conflictFields().map { field ->
                    "product_candidate_conflict:${candidate.clientKey}:$field"
                }
            }.distinct()
            if (candidateConflictIssues.isNotEmpty()) {
                return IngestionEvidenceResult(false, candidateConflictIssues)
            }
        }
        if (verificationBasis == VerificationBasis.MANUAL_CANONICAL_REVIEW) {
            return if (explicitUserConfirmation) {
                IngestionEvidenceResult(isAllowed = true)
            } else {
                IngestionEvidenceResult(false, listOf("manual_canonical_confirmation_required"))
            }
        }
        val evidenceResults = listOfNotNull(
            evaluatePurchaseEvidence(envelope, evidence, artifactKeys),
            if (envelope.schemaVersion == YEONSIK_OCR_V4_SCHEMA) {
                evaluateProductCandidateEvidence(envelope, evidence, artifactKeys)
            } else {
                null
            },
        )
        evidenceResults.firstOrNull { !it.isAllowed }?.let { return it }
        evidenceResults.firstOrNull()?.let { return it }
        val requiredTypes = requiredEvidenceTypes(envelope, artifactKeys)
        val scopedEvidence = if (artifactKeys == null || requiredTypes.isEmpty()) {
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

    /**
     * Purchase evidence is not a receipt image: a history screenshot and/or a user statement
     * can support the same canonical record. Each referenced screenshot still has to be locally
     * readable; a free-text statement never fabricates a local attachment.
     */
    private fun evaluatePurchaseEvidence(
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        artifactKeys: Set<String>?,
    ): IngestionEvidenceResult? {
        val selected = envelope.purchaseRecords.filter { record ->
            artifactKeys == null ||
                IngestionArtifactKeys.purchaseRecord(record.clientKey) in artifactKeys
        }
        if (selected.isEmpty()) return null
        val localById = evidence.associateBy(LocalEvidence::attachmentId)
        val referenced = selected.flatMap { record ->
            record.evidence.flatMap { it.sourceAttachmentIds }
        }.distinct()
        val missing = referenced.filter { it !in localById }
        if (missing.isNotEmpty()) {
            return IngestionEvidenceResult(
                false,
                missing.map { "purchase_evidence_attachment_required:$it" },
            )
        }
        val unreadable = referenced.filter { localById[it]?.fileReadable != true }
        if (unreadable.isNotEmpty()) {
            return IngestionEvidenceResult(
                false,
                unreadable.map { "purchase_evidence_attachment_unreadable:$it" },
            )
        }
        val hasUserStatement = selected.any { record ->
            record.evidence.any { item ->
                item.sourceType == "user_statement" && !envelope.source.userText.isNullOrBlank()
            }
        }
        if (referenced.isEmpty() && !hasUserStatement) {
            return IngestionEvidenceResult(false, listOf("purchase_evidence_required"))
        }
        return IngestionEvidenceResult(true)
    }

    /**
     * V4 candidates may be identified by an order-history screenshot. The candidate's own
     * source facts, not a generic product-photo requirement, determine the local evidence gate.
     */
    private fun evaluateProductCandidateEvidence(
        envelope: YeonsikOcrEnvelope,
        evidence: List<LocalEvidence>,
        artifactKeys: Set<String>?,
    ): IngestionEvidenceResult? {
        val selected = envelope.productCandidates.filter { candidate ->
            artifactKeys == null ||
                IngestionArtifactKeys.productCandidate(candidate.clientKey) in artifactKeys
        }
        if (selected.isEmpty()) return null
        val localById = evidence.associateBy(LocalEvidence::attachmentId)
        val referenced = selected.flatMap(ProductCandidate::effectiveSourceAttachmentIds).distinct()
        val missing = referenced.filter { it !in localById }
        if (missing.isNotEmpty()) {
            return IngestionEvidenceResult(
                false,
                missing.map { "product_candidate_evidence_attachment_required:$it" },
            )
        }
        val unreadable = referenced.filter { localById[it]?.fileReadable != true }
        if (unreadable.isNotEmpty()) {
            return IngestionEvidenceResult(
                false,
                unreadable.map { "product_candidate_evidence_attachment_unreadable:$it" },
            )
        }
        val hasUserStatement = selected.any { candidate ->
            candidate.evidence.any { item ->
                item.sourceType == "user_statement" && !envelope.source.userText.isNullOrBlank()
            }
        }
        if (referenced.isEmpty() && !hasUserStatement) {
            return IngestionEvidenceResult(false, listOf("product_candidate_evidence_required"))
        }
        return IngestionEvidenceResult(true)
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
                    is IngestionNutrition.RestaurantMenuEstimate -> add(SourceAttachmentType.MENU_PHOTO)
                    is IngestionNutrition.MealComponentEstimate -> add(SourceAttachmentType.FOOD_PHOTO)
                }
            }
        if (artifactKeys == null || artifactKeys.any { it.startsWith("${IngestionArtifactKeys.CONSUMPTION}:") }) {
            if (envelope.consumption.isNotEmpty()) add(SourceAttachmentType.FOOD_PHOTO)
        }
        if (envelope.schemaVersion != YEONSIK_OCR_V4_SCHEMA &&
            (artifactKeys == null || artifactKeys.any { it.startsWith("${IngestionArtifactKeys.PRODUCT_CANDIDATE}:") })
        ) {
            if (envelope.productCandidates.isNotEmpty()) add(SourceAttachmentType.PRODUCT_PHOTO)
        }
        envelope.priceObservations
            .filter { observation -> artifactKeys == null || IngestionArtifactKeys.priceObservation(observation.clientKey) in artifactKeys }
            .forEach { observation ->
                add(
                    when (observation.kind) {
                        StandalonePriceObservationKind.RETAIL_PURCHASE -> SourceAttachmentType.PRODUCT_PHOTO
                        StandalonePriceObservationKind.RESTAURANT_PURCHASE -> SourceAttachmentType.MENU_PHOTO
                    },
                )
            }
    }
}
