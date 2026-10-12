package com.pricetrace.receiptscanner.publishing.evidence

import com.pricetrace.receiptscanner.publishing.model.PublicationEvidenceImage

/** Implementations resolve actual storage records. An ID or producer label is never a path. */
fun interface PublicationEvidenceResolver {
    suspend fun resolve(
        documentId: String,
        attachmentIds: List<String>,
        pageIds: List<String>,
    ): PublicationEvidenceResolution
}

data class PublicationEvidenceResolution(
    val images: List<PublicationEvidenceImage> = emptyList(),
    val notices: List<String> = emptyList(),
)
