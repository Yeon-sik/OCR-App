package com.pricetrace.receiptscanner.importer

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.nutrition.FITNESS_NUTRITION_DATA_VERSION
import com.pricetrace.receiptscanner.nutrition.FITNESS_NUTRITION_DRAFT_SCHEMA
import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.nutrition.NutritionLabelJson
import com.pricetrace.receiptscanner.nutrition.NutritionContract
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReview
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.MerchantCandidate
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeJson
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface CanonicalDraft {
    data class Receipt(val value: ReceiptV2) : CanonicalDraft
    data class Nutrition(val value: NutritionLabelDraft) : CanonicalDraft
    data class Envelope(val value: YeonsikOcrEnvelope) : CanonicalDraft
}

enum class ExternalJsonImportErrorCode {
    EMPTY_INPUT,
    INVALID_JSON,
    MISSING_SCHEMA,
    UNSUPPORTED_SCHEMA,
    WORKFLOW_MISMATCH,
    INVALID_CANONICAL_JSON,
    INVALID_LOCAL_DOCUMENT_ID,
}

data class ExternalJsonImportError(
    val code: ExternalJsonImportErrorCode,
    val detail: String? = null,
)

sealed interface ExternalJsonImportOutcome {
    data class Success(val result: ExternalJsonImportResult) : ExternalJsonImportOutcome
    data class Failure(val error: ExternalJsonImportError) : ExternalJsonImportOutcome
}

data class ExternalJsonImportResult(
    val draft: CanonicalDraft,
    val workflowType: OcrWorkflowType,
    val inputOrigin: InputOrigin = InputOrigin.EXTERNAL_JSON,
    val localDocumentId: String,
    val upstreamDocumentId: String?,
    val importFingerprint: String,
) {

    /** Legacy receipt.v2/nutrition imports are adapted into the same canonical envelope. */
    val canonicalEnvelope: YeonsikOcrEnvelope
        get() = when (val value = draft) {
            is CanonicalDraft.Envelope -> value.value
            is CanonicalDraft.Receipt -> YeonsikOcrEnvelope(
                mode = if (value.value.merchant.businessKind == BusinessKind.FOOD_SERVICE) IngestionMode.RESTAURANT else IngestionMode.MERCHANT,
                source = IngestionSource("chatgpt", emptyList()),
                merchantCandidate = value.value.merchant.name?.let { name -> MerchantCandidate(
                    name = name,
                    branchName = value.value.merchant.branchName,
                    address = value.value.merchant.address,
                    phone = value.value.merchant.phone,
                    businessRegistrationNumber = value.value.merchant.businessRegistrationNumber,
                    businessKind = value.value.merchant.businessKind,
                ) },
                receipt = value.value,
                targets = setOf(
                    IngestionProjection.PRICETRACE_RECEIPT,
                    IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
                    IngestionProjection.CASHOS_RECEIPT,
                ),
                review = IngestionReview(IngestionReviewStatus.NEEDS_REVIEW),
            )
            is CanonicalDraft.Nutrition -> YeonsikOcrEnvelope(
                mode = IngestionMode.PACKAGED_PRODUCT,
                source = IngestionSource("chatgpt", emptyList()),
                nutrition = listOf(IngestionNutrition.ProductLabel(value.value.documentId, value.value)),
                targets = setOf(IngestionProjection.FITNESS_NUTRITION),
                review = IngestionReview(IngestionReviewStatus.NEEDS_REVIEW),
            )
        }
}

/**
 * Converts only the two app-owned canonical JSON contracts into existing domain drafts.
 *
 * This class intentionally has no Android, Room, network, or publisher dependency. The fingerprint
 * is derived from the sanitized canonical draft, not the raw JSON bytes: formatting and discarded
 * external trust metadata therefore cannot create a different import identity.
 */
class ExternalJsonImporter(
    private val json: Json = STRICT_JSON,
) {
    fun import(
        value: String,
        localDocumentId: String,
        workflowType: OcrWorkflowType? = null,
    ): ExternalJsonImportOutcome {
        if (localDocumentId.isBlank()) {
            return ExternalJsonImportOutcome.Failure(
                ExternalJsonImportError(ExternalJsonImportErrorCode.INVALID_LOCAL_DOCUMENT_ID),
            )
        }
        if (value.isBlank()) {
            return ExternalJsonImportOutcome.Failure(
                ExternalJsonImportError(ExternalJsonImportErrorCode.EMPTY_INPUT),
            )
        }

        val root = try {
            json.parseToJsonElement(value).jsonObject
        } catch (error: Exception) {
            return ExternalJsonImportOutcome.Failure(
                ExternalJsonImportError(
                    ExternalJsonImportErrorCode.INVALID_JSON,
                    error.message,
                ),
            )
        }
        val schema = (root["schema_version"] as? JsonPrimitive)?.contentOrNull
            ?: return ExternalJsonImportOutcome.Failure(
                ExternalJsonImportError(ExternalJsonImportErrorCode.MISSING_SCHEMA),
            )

        return try {
            when (schema) {
                ReceiptV2.SCHEMA_VERSION -> importReceipt(root, localDocumentId, workflowType)
                FITNESS_NUTRITION_DRAFT_SCHEMA -> importNutrition(root, localDocumentId, workflowType)
                YEONSIK_OCR_SCHEMA -> importEnvelope(value, localDocumentId, workflowType)
                YEONSIK_OCR_V2_SCHEMA -> importEnvelopeV2(value, localDocumentId, workflowType)
                else -> ExternalJsonImportOutcome.Failure(
                    ExternalJsonImportError(
                        ExternalJsonImportErrorCode.UNSUPPORTED_SCHEMA,
                        schema,
                    ),
                )
            }
        } catch (error: Exception) {
            ExternalJsonImportOutcome.Failure(
                ExternalJsonImportError(
                    ExternalJsonImportErrorCode.INVALID_CANONICAL_JSON,
                    error.message,
                ),
            )
        }
    }


    private fun importEnvelope(
        value: String,
        localDocumentId: String,
        workflowType: OcrWorkflowType?,
    ): ExternalJsonImportOutcome {
        val envelope = YeonsikOcrEnvelopeJson.decode(value, localDocumentId)
        val expectedWorkflow = envelope.receipt?.let {
            if (it.merchant.businessKind == BusinessKind.FOOD_SERVICE) OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT
            else OcrWorkflowType.PRICE_TRACE_RECEIPT
        } ?: if (envelope.nutrition.isNotEmpty()) OcrWorkflowType.FITNESS_NUTRITION else OcrWorkflowType.PRICE_TRACE_MERCHANT
        if (workflowType != null && workflowType != expectedWorkflow) return workflowMismatch(YEONSIK_OCR_SCHEMA, workflowType)
        val fingerprint = StableIds.sha256("external-json|$YEONSIK_OCR_SCHEMA|${YeonsikOcrEnvelopeJson.canonicalize(envelope)}")
        return ExternalJsonImportOutcome.Success(ExternalJsonImportResult(
            draft = CanonicalDraft.Envelope(envelope),
            workflowType = expectedWorkflow,
            localDocumentId = localDocumentId,
            upstreamDocumentId = "envelope-${fingerprint.take(24)}",
            importFingerprint = fingerprint,
        ))
    }

    private fun importEnvelopeV2(
        value: String,
        localDocumentId: String,
        workflowType: OcrWorkflowType?,
    ): ExternalJsonImportOutcome {
        val envelope = YeonsikOcrV2Json.decode(value, localDocumentId)
        val expectedWorkflow = when {
            envelope.receipt != null && envelope.receipt.merchant.businessKind == BusinessKind.FOOD_SERVICE ->
                OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT
            envelope.receipt != null -> OcrWorkflowType.PRICE_TRACE_RECEIPT
            envelope.merchantCandidate != null && envelope.nutrition.isEmpty() && envelope.productCandidates.isEmpty() ->
                OcrWorkflowType.PRICE_TRACE_MERCHANT
            else -> OcrWorkflowType.FITNESS_NUTRITION
        }
        if (workflowType != null && workflowType != expectedWorkflow) {
            return workflowMismatch(YEONSIK_OCR_V2_SCHEMA, workflowType)
        }
        val fingerprint = StableIds.sha256(
            "external-json|$YEONSIK_OCR_V2_SCHEMA|${YeonsikOcrV2Json.canonicalize(envelope)}",
        )
        return ExternalJsonImportOutcome.Success(ExternalJsonImportResult(
            draft = CanonicalDraft.Envelope(envelope),
            workflowType = expectedWorkflow,
            localDocumentId = localDocumentId,
            upstreamDocumentId = "envelope-${fingerprint.take(24)}",
            importFingerprint = fingerprint,
        ))
    }
    private fun importReceipt(
        root: JsonObject,
        localDocumentId: String,
        workflowType: OcrWorkflowType?,
    ): ExternalJsonImportOutcome {
        requireKeysAllowingDiscarded(root, RECEIPT_KEYS, EXTERNAL_TRUST_KEYS)

        // ReceiptV2Json remains the source of truth for the existing receipt.v2 wire contract.
        // Only explicitly listed top-level trust metadata is removed before that strict decoder.
        val decoded = ReceiptV2Json.decode(encode(root.withoutKeys(EXTERNAL_TRUST_KEYS)), localDocumentId)
        val inferredWorkflow = if (decoded.merchant.businessKind == BusinessKind.FOOD_SERVICE) OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT else OcrWorkflowType.PRICE_TRACE_RECEIPT
        if (workflowType != null && workflowType != inferredWorkflow) return workflowMismatch(ReceiptV2.SCHEMA_VERSION, workflowType)
        val upstreamDocumentId = decoded.document.id
        val sanitized = decoded.copy(
            document = decoded.document.copy(
                // Preserve the upstream receipt.v2 source ID; localDocumentId is stored separately.
                id = decoded.document.id,
                localDocumentId = localDocumentId,
                status = ReceiptStatus.DRAFT,
                source = decoded.document.source.copy(
                    transcriptionStatus = TranscriptionStatus.PARSED,
                    sourceImages = emptyList(),
                ),
            ),
            lineItems = decoded.lineItems.map { item ->
                item.copy(
                    confidence = item.confidence.takeUnless { it == ConfidenceLevel.USER_VERIFIED }
                        ?: ConfidenceLevel.LOW,
                )
            },
        )
        val fingerprintDraft = sanitized.copy(
            document = sanitized.document.copy(
                id = upstreamDocumentId,
                localDocumentId = null,
            ),
        )
        return ExternalJsonImportOutcome.Success(
            ExternalJsonImportResult(
                draft = CanonicalDraft.Receipt(sanitized),
                workflowType = inferredWorkflow,
                localDocumentId = localDocumentId,
                upstreamDocumentId = upstreamDocumentId,
                importFingerprint = fingerprint(
                    schema = ReceiptV2.SCHEMA_VERSION,
                    workflowType = inferredWorkflow,
                    canonicalJson = ReceiptV2Json.encodeCanonical(fingerprintDraft),
                ),
            ),
        )
    }

    private fun importNutrition(
        root: JsonObject,
        localDocumentId: String,
        workflowType: OcrWorkflowType?,
    ): ExternalJsonImportOutcome {
        if (workflowType != null && workflowType != OcrWorkflowType.FITNESS_NUTRITION) {
            return workflowMismatch(FITNESS_NUTRITION_DRAFT_SCHEMA, workflowType)
        }
        validateNutritionShape(root)
        val decoded = NutritionLabelJson.decode(encode(root.withoutKeys(EXTERNAL_TRUST_KEYS)))
        val upstreamDocumentId = decoded.documentId.requireNonBlank("document_id")
        val sanitized = decoded.copy(
            documentId = localDocumentId,
            status = NutritionDraftStatus.PARSED,
            confirmedAt = null,
        )
        val fingerprintDraft = sanitized.copy(documentId = upstreamDocumentId)
        return ExternalJsonImportOutcome.Success(
            ExternalJsonImportResult(
                draft = CanonicalDraft.Nutrition(sanitized),
                workflowType = OcrWorkflowType.FITNESS_NUTRITION,
                localDocumentId = localDocumentId,
                upstreamDocumentId = upstreamDocumentId,
                importFingerprint = fingerprint(
                    schema = FITNESS_NUTRITION_DRAFT_SCHEMA,
                    workflowType = OcrWorkflowType.FITNESS_NUTRITION,
                    canonicalJson = canonicalizeJson(json.parseToJsonElement(NutritionLabelJson.encode(fingerprintDraft))),
                ),
            ),
        )
    }

    private fun validateNutritionShape(root: JsonObject) {
        requireKeysAllowingDiscarded(root, NUTRITION_KEYS, EXTERNAL_TRUST_KEYS)
        require(root.requiredString("schema_version") == FITNESS_NUTRITION_DRAFT_SCHEMA)
        root.requiredString("document_id")
        root.requiredString("parser_version")
        root.requiredString("name")
        root.requiredNullableString("brand")
        root.requiredString("category")
        root.requiredNullableNumber("basis_amount")
        root.requiredString("basis_unit")
        require(root.requiredString("kind") == NutritionContract.KIND_EXTERNAL_MENU)
        require(root.requiredString("prep_state") == NutritionContract.PREP_UNSPECIFIED)
        require(root.requiredString("cooking_method") == NutritionContract.COOKING_UNSPECIFIED)
        require(root.requiredString("source_type") == NutritionContract.SOURCE_TYPE)
        root.requiredString("source_reference")
        root.requiredString("source_version")
        require(root.requiredInt("data_version") == FITNESS_NUTRITION_DATA_VERSION)
        require(root.requiredString("visibility") == NutritionContract.VISIBILITY_PRIVATE)
        root.requiredNullableString("confirmed_at")
        require(root.requiredString("status") in NutritionDraftStatus.entries.map(NutritionDraftStatus::wireValue))
        root.requiredStringArray("parse_warnings")

        val nutrients = root.requiredObject("nutrients")
        require(nutrients.keys == NutritionField.entries.map(NutritionField::wireKey).toSet()) {
            "Unexpected or missing nutrition fields"
        }
        NutritionField.entries.forEach { field -> nutrients.requiredNullableNumber(field.wireKey) }

        val evidence = root.requiredObject("evidence")
        evidence.forEach { (field, entries) ->
            require(NutritionField.fromWireKey(field) != null || field == "basis") {
                "Unsupported nutrition evidence field: $field"
            }
            entries.jsonArray.forEach { entryElement ->
                val entry = entryElement.jsonObject
                require(entry.keys == setOf("ocr_line_id", "page_id", "raw_text", "confidence")) {
                    "Unexpected nutrition evidence keys"
                }
                entry.requiredString("ocr_line_id")
                entry.requiredString("page_id")
                entry.requiredString("raw_text")
                entry.requiredNullableNumber("confidence")
            }
        }
    }

    private fun workflowMismatch(schema: String, workflowType: OcrWorkflowType) =
        ExternalJsonImportOutcome.Failure(
            ExternalJsonImportError(
                ExternalJsonImportErrorCode.WORKFLOW_MISMATCH,
                "$schema is not compatible with ${workflowType.wireValue}",
            ),
        )

    private fun fingerprint(
        schema: String,
        workflowType: OcrWorkflowType,
        canonicalJson: String,
    ): String = StableIds.sha256(
        "external-json|$schema|${workflowType.wireValue}|$canonicalJson",
    )

    private fun encode(element: JsonElement): String =
        json.encodeToString(JsonElement.serializer(), element)

    private fun canonicalizeJson(element: JsonElement): String = encode(
        when (element) {
            is JsonObject -> JsonObject(
                element.entries.sortedBy { it.key }.associate { (key, value) ->
                    key to json.parseToJsonElement(canonicalizeJson(value))
                },
            )
            is JsonArray -> JsonArray(element.map { json.parseToJsonElement(canonicalizeJson(it)) })
            else -> element
        },
    )

    private fun JsonObject.withoutKeys(keys: Set<String>): JsonObject = JsonObject(
        filterKeys { it !in keys },
    )

    private fun requireKeysAllowingDiscarded(
        root: JsonObject,
        canonicalKeys: Set<String>,
        discardedKeys: Set<String>,
    ) {
        require(root.keys subtract (canonicalKeys + discardedKeys) == emptySet<String>()) {
            "Unexpected JSON keys: ${root.keys subtract (canonicalKeys + discardedKeys)}"
        }
        require(canonicalKeys subtract root.keys == emptySet<String>()) {
            "Missing JSON keys: ${canonicalKeys subtract root.keys}"
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("$key must be a string")

    private fun JsonObject.requiredNullableString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("$key must be a string or null")
    }

    private fun JsonObject.requiredNullableNumber(key: String): Double? = when (val value = this[key]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
            ?: error("$key must be a finite number or null")
    }

    private fun JsonObject.requiredInt(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("$key must be an integer")

    private fun JsonObject.requiredStringArray(key: String): List<String> =
        requiredArray(key).map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: error("$key must contain strings") }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        this[key]?.jsonArray ?: error("$key must be an array")

    private fun JsonObject.requiredObject(key: String): JsonObject =
        this[key]?.jsonObject ?: error("$key must be an object")

    private fun String.requireNonBlank(label: String): String = trim().takeIf(String::isNotEmpty)
        ?: error("$label must not be blank")

    private companion object {
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = true
        }

        val RECEIPT_WORKFLOWS = setOf(
            OcrWorkflowType.PRICE_TRACE_RECEIPT,
            OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT,
        )

        val RECEIPT_KEYS = setOf(
            "schema_version",
            "document",
            "merchant",
            "line_items",
            "totals",
            "payments",
        )

        val NUTRITION_KEYS = setOf(
            "schema_version",
            "document_id",
            "parser_version",
            "status",
            "confirmed_at",
            "name",
            "brand",
            "kind",
            "category",
            "basis_amount",
            "basis_unit",
            "prep_state",
            "cooking_method",
            "nutrients",
            "source_type",
            "source_reference",
            "source_version",
            "data_version",
            "visibility",
            "parse_warnings",
            "evidence",
        )

        // These fields are accepted only so an upstream producer cannot turn them into trust.
        // They are removed before canonical decoding and never reach either domain model.
        val EXTERNAL_TRUST_KEYS = setOf(
            "user_verified",
            "confirmed_at",
            "owner_id",
            "confirmed_by",
            "created_at",
            "updated_at",
            "published_at",
            "publisher_status",
            "revision",
            "id",
            "deleted_at",
        )
    }
}
