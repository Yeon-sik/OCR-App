package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.NutritionNutrientProvenance
import com.pricetrace.receiptscanner.ingestion.RestaurantNutritionEstimate
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.NutritionRange
import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.nutrition.NutritionLabelValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

const val NUTRITION_LABEL_V1 = "nutrition-label.v1"
const val FOOD_ESTIMATE_V1 = "food-estimate.v1"

sealed interface NutritionCanonicalImportOutcome {
    data class Success(
        val response: CanonicalNutritionImportResponse,
        val rawResponse: String,
    ) : NutritionCanonicalImportOutcome

    data class Failure(
        val reason: NutritionGatewayFailure,
        val message: String? = null,
    ) : NutritionCanonicalImportOutcome
}

data class CanonicalNutrientProvenance(
    val value: Double,
    val valueStatus: String,
    val sourceType: String,
    val evidenceRefs: List<String>,
)

data class CanonicalNutritionImportPayload(
    val idempotencyKey: String,
    val inputContract: String,
    val sourceDocumentRef: String,
    val foodName: String,
    val brand: String?,
    val category: String,
    val basisAmount: Double,
    val basisUnit: String,
    val requiredNutrients: Map<String, Double>,
    val nutrientProvenance: Map<String, CanonicalNutrientProvenance>,
    val optionalNutrients: Map<String, Double> = emptyMap(),
    val provenance: JsonObject = JsonObject(emptyMap()),
    val estimationEvidence: JsonObject? = null,
    val priceTraceIdentity: JsonObject? = null,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(inputContract == NUTRITION_LABEL_V1 || inputContract == FOOD_ESTIMATE_V1)
        require(sourceDocumentRef.isNotBlank())
        require(foodName.isNotBlank())
        require(basisAmount.isFinite() && basisAmount > 0)
        require(requiredNutrients.keys == REQUIRED_NUTRIENTS)
        require(nutrientProvenance.keys == REQUIRED_NUTRIENTS)
        requiredNutrients.values.forEach { require(it.isFinite() && it >= 0) }
        nutrientProvenance.forEach { (key, item) ->
            require(item.value == requiredNutrients.getValue(key))
            require(item.valueStatus in VALUE_STATUSES)
            require(item.sourceType in SOURCE_TYPES)
            require(item.evidenceRefs.isNotEmpty())
        }
        if (inputContract == NUTRITION_LABEL_V1) {
            require(nutrientProvenance.values.all { it.valueStatus == "observed" && it.sourceType == "product_label_ocr" })
            require(estimationEvidence == null)
        } else {
            require(nutrientProvenance.values.any { it.valueStatus == "estimated" })
            val confidence = (estimationEvidence?.get("confidence") as? JsonPrimitive)?.doubleOrNull
            require(confidence != null && confidence in 0.0..1.0)
        }
    }

    fun toRpcJson(): String = CanonicalNutritionImportJson.encode(this)

    companion object {
        val REQUIRED_NUTRIENTS = NutritionField.requiredFields.map(NutritionField::wireKey).toSet()
        val VALUE_STATUSES = setOf("observed", "estimated")
        val SOURCE_TYPES = setOf("product_label_ocr", "food_image_estimate", "menu_reference", "manual")
    }
}

data class CanonicalNutritionImportResponse(
    val canonicalImportId: String,
    val idempotentReplay: Boolean,
    val nutritionFoodId: String,
    val inputContract: String,
    val projectionSourceType: String,
    val projectionImportId: String?,
    val catalogProductId: String?,
    val estimationEvidenceId: String?,
    val visibility: String,
)

object CanonicalNutritionImportJson {
    private val json = Json { explicitNulls = true; ignoreUnknownKeys = false }

    fun encode(payload: CanonicalNutritionImportPayload): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("p_idempotency_key", JsonPrimitive(payload.idempotencyKey))
            put("p_input_contract", JsonPrimitive(payload.inputContract))
            put("p_source_document_ref", JsonPrimitive(payload.sourceDocumentRef))
            put("p_food_name", JsonPrimitive(payload.foodName))
            put("p_brand", payload.brand?.let(::JsonPrimitive) ?: JsonNull)
            put("p_category", JsonPrimitive(payload.category))
            put("p_basis_amount", JsonPrimitive(payload.basisAmount))
            put("p_basis_unit", JsonPrimitive(payload.basisUnit))
            put("p_required_nutrients", numberMap(payload.requiredNutrients))
            put("p_nutrient_provenance", JsonObject(payload.nutrientProvenance.mapValues { (_, item) ->
                buildJsonObject {
                    put("value", JsonPrimitive(item.value))
                    put("value_status", JsonPrimitive(item.valueStatus))
                    put("source_type", JsonPrimitive(item.sourceType))
                    put("evidence_refs", JsonArray(item.evidenceRefs.map(::JsonPrimitive)))
                }
            }))
            put("p_optional_nutrients", numberMap(payload.optionalNutrients))
            put("p_provenance", payload.provenance)
            put("p_user_verified", JsonPrimitive(true))
            put("p_pricetrace_identity", payload.priceTraceIdentity ?: JsonNull)
            put("p_estimation_evidence", payload.estimationEvidence ?: JsonNull)
        },
    )

    fun decodeResponse(value: String): CanonicalNutritionImportResponse {
        val root = json.parseToJsonElement(value)
        val row = when (root) {
            is JsonObject -> root
            else -> root.jsonArray.single().jsonObject
        }
        fun requiredString(key: String): String = (row[key] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("Missing Nutrition canonical response field: $key")
        fun optionalString(key: String): String? = when (val item = row[key]) {
            null, JsonNull -> null
            else -> (item as? JsonPrimitive)?.contentOrNull ?: error("Invalid Nutrition response field: $key")
        }
        fun requiredBoolean(key: String): Boolean = (row[key] as? JsonPrimitive)?.contentOrNull
            ?.toBooleanStrictOrNull() ?: error("Missing Nutrition canonical response field: $key")
        return CanonicalNutritionImportResponse(
            canonicalImportId = requiredString("canonical_import_id"),
            idempotentReplay = requiredBoolean("idempotent_replay"),
            nutritionFoodId = requiredString("nutrition_food_id"),
            inputContract = requiredString("input_contract"),
            projectionSourceType = requiredString("projection_source_type"),
            projectionImportId = optionalString("projection_import_id"),
            catalogProductId = optionalString("catalog_product_id"),
            estimationEvidenceId = optionalString("estimation_evidence_id"),
            visibility = requiredString("visibility"),
        )
    }

    private fun numberMap(values: Map<String, Double>): JsonObject = JsonObject(values.mapValues { (_, value) -> JsonPrimitive(value) })
}

object CanonicalNutritionPayloadFactory {
    fun fromProductLabel(
        localDocumentId: String,
        revisionSeq: Long,
        idempotencyKey: String,
        draft: NutritionLabelDraft,
        @Suppress("UNUSED_PARAMETER") envelopeVerified: Boolean = false,
    ): CanonicalNutritionImportPayload {
        // Kept for source compatibility; a session/envelope flag cannot authorize a parsed draft.
        require(draft.status == NutritionDraftStatus.USER_VERIFIED) {
            "nutrition_label_not_verified"
        }
        require(NutritionLabelValidator.validate(draft).isReadyForUpload) { "nutrition_label_incomplete" }
        val sourceRef = sourceRef(localDocumentId, revisionSeq, draft.documentId)
        val required = NutritionField.requiredFields.associate { field -> field.wireKey to requireNotNull(draft.value(field)) }
        val provenance = NutritionField.requiredFields.associate { field ->
            field.wireKey to CanonicalNutrientProvenance(
                value = required.getValue(field.wireKey),
                valueStatus = "observed",
                sourceType = "product_label_ocr",
                evidenceRefs = evidenceRefs(sourceRef, draft, field),
            )
        }
        val optional = NutritionField.entries.filterNot(NutritionField::required).mapNotNull { field ->
            draft.value(field)?.let { field.wireKey to it }
        }.toMap()
        return CanonicalNutritionImportPayload(
            idempotencyKey = idempotencyKey,
            inputContract = NUTRITION_LABEL_V1,
            sourceDocumentRef = sourceRef,
            foodName = draft.productName.trim(),
            brand = draft.brand?.trim()?.takeIf(String::isNotEmpty),
            category = draft.category,
            basisAmount = requireNotNull(draft.basisAmount),
            basisUnit = draft.basisUnit,
            requiredNutrients = required,
            nutrientProvenance = provenance,
            optionalNutrients = optional,
            provenance = buildJsonObject {
                put("parser_version", JsonPrimitive(draft.parserVersion))
                put("estimated", JsonPrimitive(false))
            },
        )
    }

    fun fromRestaurantEstimate(
        localDocumentId: String,
        revisionSeq: Long,
        idempotencyKey: String,
        restaurantName: String,
        item: IngestionNutrition.RestaurantEstimate,
    ): CanonicalNutritionImportPayload {
        val estimate = item.estimate
        require(estimate.estimated) { "restaurant_estimate_required" }
        val confidence = estimate.confidenceScore ?: estimate.confidence.toDoubleOrNull()
        require(confidence != null && confidence in 0.0..1.0) { "numeric_estimation_confidence_required" }
        val sourceRef = sourceRef(localDocumentId, revisionSeq, item.clientKey)
        val required = NutritionField.requiredFields.associate { field ->
            field.wireKey to requireNotNull(estimate.nutrients[field]) { "missing_estimate_${field.wireKey}" }
        }
        val provenance = NutritionField.requiredFields.associate { field ->
            val declared = estimate.nutrientProvenance[field]
                ?: error("missing_provenance_${field.wireKey}")
            field.wireKey to CanonicalNutrientProvenance(
                value = required.getValue(field.wireKey),
                valueStatus = declared.valueStatus,
                sourceType = declared.sourceType,
                evidenceRefs = declared.evidenceRefs.ifEmpty { listOf(sourceRef + "/photo") },
            )
        }
        val estimationEvidence = buildJsonObject {
            put("confidence", JsonPrimitive(confidence))
            put("range", JsonObject(estimate.ranges.map { (field, range) -> field.wireKey to rangeJson(range) }.toMap()))
        }
        val optional = NutritionField.entries.filterNot(NutritionField::required).mapNotNull { field ->
            estimate.nutrients[field]?.let { field.wireKey to it }
        }.toMap()
        return CanonicalNutritionImportPayload(
            idempotencyKey = idempotencyKey,
            inputContract = FOOD_ESTIMATE_V1,
            sourceDocumentRef = sourceRef,
            foodName = item.menuName.trim(),
            brand = restaurantName.trim().takeIf(String::isNotEmpty),
            category = "recipe",
            basisAmount = 1.0,
            basisUnit = "serving",
            requiredNutrients = required,
            nutrientProvenance = provenance,
            optionalNutrients = optional,
            provenance = buildJsonObject {
                put("restaurant_name", JsonPrimitive(restaurantName))
                put("estimated", JsonPrimitive(true))
            },
            estimationEvidence = estimationEvidence,
        )
    }

    private fun sourceRef(localDocumentId: String, revisionSeq: Long, artifactId: String): String =
        "ocr-app://ingestion/${safe(localDocumentId)}/revision/$revisionSeq/nutrition/${safe(artifactId)}"

    private fun evidenceRefs(sourceRef: String, draft: NutritionLabelDraft, field: NutritionField): List<String> =
        draft.evidence[field.wireKey].orEmpty().map { evidence ->
            "$sourceRef/region/${safe(evidence.pageId)}/${safe(evidence.ocrLineId)}"
        }.ifEmpty { listOf("$sourceRef/review/${field.wireKey}") }

    private fun rangeJson(range: NutritionRange): JsonObject = buildJsonObject {
        put("min", range.min?.let(::JsonPrimitive) ?: JsonNull)
        put("point", range.point?.let(::JsonPrimitive) ?: JsonNull)
        put("max", range.max?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
