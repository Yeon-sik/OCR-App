package com.pricetrace.receiptscanner.publishing.adapter

import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.nutrition.NutritionLabelValidator
import com.pricetrace.receiptscanner.publishing.model.PublicationNutrient
import com.pricetrace.receiptscanner.publishing.model.PublicationSource

sealed interface PublicationAdaptation {
    data class Ready(val source: PublicationSource) : PublicationAdaptation
    data class Blocked(val errors: List<String>) : PublicationAdaptation
}

/** The only V1 nutrition dependency. Future canonical adapters can live alongside this adapter. */
object NutritionPublicationAdapter {
    fun adapt(draft: NutritionLabelDraft): PublicationAdaptation {
        val errors = buildList {
            if (draft.status != NutritionDraftStatus.USER_VERIFIED) {
                add("영양성분 검수를 먼저 확정하세요.")
            }
            if (draft.documentId.isBlank()) add("원본 문서 식별자가 없습니다.")
            addAll(NutritionLabelValidator.validate(draft).errors)
        }
        if (errors.isNotEmpty()) return PublicationAdaptation.Blocked(errors.distinct())
        return PublicationAdaptation.Ready(
            PublicationSource(
                documentId = draft.documentId,
                productName = draft.productName,
                brand = draft.brand,
                basisAmount = draft.basisAmount,
                basisUnit = draft.basisUnit,
                nutrients = NutritionField.entries.mapNotNull { field ->
                    draft.value(field)?.let { value ->
                        PublicationNutrient(field.wireKey, field.koreanLabel, value, field.canonicalUnit)
                    }
                },
                sourceType = draft.sourceType,
                sourceReference = draft.sourceReference,
                sourceVersion = draft.sourceVersion,
                confirmedAt = draft.confirmedAt,
                // NutritionFieldEvidence supplies page provenance, never an attachment URI.
                evidencePageIds = draft.evidence.values.flatten().map { it.pageId }
                    .filter(String::isNotBlank).distinct(),
            ),
        )
    }
}
