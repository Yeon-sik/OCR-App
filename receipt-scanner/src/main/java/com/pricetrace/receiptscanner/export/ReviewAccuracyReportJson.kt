package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.domain.ReviewAccuracySummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializes the accuracy report for sharing with reviewers of this project.
 *
 * The report carries counts and rates only. Merchant names, addresses, product names and the OCR text
 * they were compared against never enter it, so a shared report cannot leak a receipt.
 */
object ReviewAccuracyReportJson {
    private val prettyJson = Json { prettyPrint = true }

    fun encode(
        summary: ReviewAccuracySummary,
        generatedAt: String,
        engineName: String,
        engineVersion: String,
    ): String = prettyJson.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            linkedMapOf(
                "report_version" to JsonPrimitive(REPORT_VERSION),
                "generated_at" to JsonPrimitive(generatedAt),
                "ocr_engine" to JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(engineName),
                        "version" to JsonPrimitive(engineVersion),
                    ),
                ),
                "parser_versions" to JsonArray(summary.parserVersions.map(::JsonPrimitive)),
                "sample_count" to JsonPrimitive(summary.sampleCount),
                "line_item_count" to JsonPrimitive(summary.lineItemCount),
                "corrections_per_receipt" to JsonPrimitive(summary.correctionsPerReceipt),
                "median_review_seconds" to (
                    summary.medianReviewSeconds?.let(::JsonPrimitive) ?: JsonNull
                    ),
                "timed_sample_count" to JsonPrimitive(summary.timedSampleCount),
                "measurement_basis" to JsonPrimitive(MEASUREMENT_BASIS),
                "fields" to JsonArray(
                    summary.fields.map { field ->
                        JsonObject(
                            linkedMapOf(
                                "group" to JsonPrimitive(field.group.name),
                                "label" to JsonPrimitive(field.group.label),
                                "scope" to JsonPrimitive(field.group.scope.name.lowercase()),
                                "observed_count" to JsonPrimitive(field.observedCount),
                                "corrected_count" to JsonPrimitive(field.correctedCount),
                                "misread_count" to JsonPrimitive(field.misreadCount),
                                "missed_count" to JsonPrimitive(field.missedCount),
                                "spurious_count" to JsonPrimitive(field.spuriousCount),
                                "error_rate" to (field.errorRate?.let(::JsonPrimitive) ?: JsonNull),
                                "average_edit_distance" to (
                                    field.averageEditDistance?.let(::JsonPrimitive) ?: JsonNull
                                    ),
                            ),
                        )
                    },
                ),
            ),
        ),
    )

    const val REPORT_VERSION = "review-accuracy.v1"
    private const val MEASUREMENT_BASIS =
        "Derived from user review corrections on confirmed receipts. Errors the reviewer did not " +
            "notice are counted as correct, so these rates are a lower bound."
}
