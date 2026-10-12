package com.pricetrace.receiptscanner.publishing.model

/** Runtime export data, independent of Nutrition DB visibility and publication state. */
data class BlogDraft(
    val source: PublicationSource,
    val title: String,
    val body: String,
    val evidenceImages: List<PublicationEvidenceImage> = emptyList(),
    val generatedImages: List<PublicationGeneratedImage> = emptyList(),
) {
    val fullText: String get() = "$title\n\n$body"
}

/** URI strings keep this boundary usable outside Android. Never inferred from producer labels. */
data class PublicationEvidenceImage(
    val id: String,
    val uri: String,
    val mimeType: String,
    val type: PublicationEvidenceType = PublicationEvidenceType.ORIGINAL_OCR_PAGE,
)

enum class PublicationEvidenceType { ORIGINAL_OCR_PAGE }

data class PublicationGeneratedImage(
    val id: String,
    val uri: String,
    val mimeType: String = "image/png",
    val label: String = "영양성분표",
)

data class PublicationPackage(
    val draft: BlogDraft,
    val notices: List<String> = emptyList(),
)
