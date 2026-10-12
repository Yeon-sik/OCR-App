package com.pricetrace.receiptscanner.publishing.model

/** A platform-neutral snapshot. Publication never writes back into its nutrition source. */
data class PublicationSource(
    val documentId: String,
    val productName: String,
    val brand: String?,
    val basisAmount: Double?,
    val basisUnit: String,
    val nutrients: List<PublicationNutrient>,
    val sourceType: String,
    val sourceReference: String,
    val sourceVersion: String,
    val confirmedAt: String?,
    val evidenceAttachmentIds: List<String> = emptyList(),
    val evidencePageIds: List<String> = emptyList(),
)

data class PublicationNutrient(
    val key: String,
    val label: String,
    val value: Double,
    val unit: String,
)
