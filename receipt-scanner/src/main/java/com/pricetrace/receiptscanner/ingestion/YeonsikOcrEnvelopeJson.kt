package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelJson
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Strict codec for the integrated envelope. It deliberately delegates nested legacy contracts. */
object YeonsikOcrEnvelopeJson {
    private val cashosHintKeys = setOf("category_hint", "institution_hint", "payment_method_hint")
    private val json = Json { prettyPrint = true; explicitNulls = true; ignoreUnknownKeys = false }

    /**
     * External callers use the default downgrade path. The preservation flag is only for bytes
     * already written by OCR App into its local app-owned storage after import/verification.
     */
    fun decode(
        value: String,
        localDocumentId: String,
        preservePersistedVerification: Boolean = false,
    ): YeonsikOcrEnvelope {
        require(localDocumentId.isNotBlank())
        val root = json.parseToJsonElement(value).jsonObject
        require((root.keys subtract TRUST_KEYS) in setOf(TOP_LEVEL_KEYS, TOP_LEVEL_KEYS - "projection_targets")) { "Unexpected or missing envelope keys" }
        require(root.string("schema_version") == YEONSIK_OCR_SCHEMA)
        val mode = IngestionMode.fromWireValue(root.string("mode"))
        val source = decodeSource(root.objectValue("source"))
        val receipt = root["receipt"]?.takeUnless { it == JsonNull }?.let { element ->
            val encoded = json.encodeToString(JsonElement.serializer(), element)
            if (preservePersistedVerification) {
                ReceiptV2Json.decode(encoded, localDocumentId)
            } else {
                val nested = ExternalJsonImporter().import(encoded, localDocumentId)
                val result = (nested as? ExternalJsonImportOutcome.Success)?.result
                    ?: error("receipt must be a valid receipt.v2 payload")
                (result.draft as CanonicalDraft.Receipt).value
            }
        }
        val nutrition = root.arrayValue("nutrition").map { element ->
            decodeNutrition(element, preservePersistedVerification)
        }
        val merchant = root["merchant_candidate"]?.takeUnless { it == JsonNull }?.let { decodeMerchant(it.jsonObject) }
        val links = root.arrayValue("links").map { decodeLink(it.jsonObject) }
        val targets = root["projection_targets"]?.jsonArray?.map { IngestionProjection.fromWireValue(it.jsonPrimitive.content) }?.toSet().orEmpty()
        val hints = decodeHints(root.objectValue("classification_hints"))
        validateMode(mode, merchant, receipt, nutrition, links)
        validateTargets(merchant, targets, receipt, nutrition)
        // External review state is descriptive only. A local user must perform verification again.
        return YeonsikOcrEnvelope(
            mode = mode,
            source = source,
            merchantCandidate = merchant,
            receipt = receipt,
            nutrition = nutrition,
            classificationHints = hints,
            links = links,
            targets = targets,
            review = IngestionReview(
                status = IngestionReviewStatus.NEEDS_REVIEW,
                blockingIssues = listOf("source_image_required"),
                warnings = root.objectValue("review").arrayValue("warnings").strings(),
            ),
        )
    }

    fun encode(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String = json.encodeToString(
        JsonElement.serializer(),
        toJson(envelope, canonicalIds),
    )

    fun canonicalize(envelope: YeonsikOcrEnvelope): String = encode(envelope, canonicalIds = true)

    private fun toJson(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive(YEONSIK_OCR_SCHEMA))
        put("mode", JsonPrimitive(envelope.mode.wireValue))
        put("source", sourceJson(envelope.source))
        put("merchant_candidate", envelope.merchantCandidate?.let(::merchantJson) ?: JsonNull)
        put(
            "receipt",
            envelope.receipt?.let { receipt ->
                val element = json.parseToJsonElement(ReceiptV2Json.encodeCanonical(receipt))
                if (!canonicalIds) element else element.jsonObject.withDocumentId("__receipt__")
            } ?: JsonNull,
        )
        put("nutrition", JsonArray(envelope.nutrition.map { item -> nutritionJson(item, canonicalIds) }))
        put("classification_hints", classificationHintsJson(envelope.classificationHints))
        put("links", JsonArray(envelope.links.map(::linkJson)))
        put("projection_targets", JsonArray(envelope.targets.sortedBy(IngestionProjection::wireValue).map { JsonPrimitive(it.wireValue) }))
        put("review", reviewJson(envelope.review))
    }

    private fun nutritionJson(item: IngestionNutrition, canonicalIds: Boolean): JsonObject = buildJsonObject {
        put("client_key", JsonPrimitive(item.clientKey))
        when (item) {
            is IngestionNutrition.ProductLabel -> {
                put("kind", JsonPrimitive("product_label"))
                put("line_id", item.lineId?.let(::JsonPrimitive) ?: JsonNull)
                val payload = json.parseToJsonElement(NutritionLabelJson.encode(
                    if (canonicalIds) item.draft.copy(documentId = "__nutrition__") else item.draft,
                ))
                put("payload", payload)
                put("estimate", JsonNull)
                put("menu_name", JsonNull)
            }
            is IngestionNutrition.RestaurantEstimate -> {
                put("kind", JsonPrimitive("restaurant_estimate"))
                put("line_id", JsonPrimitive(item.lineId))
                put("menu_name", JsonPrimitive(item.menuName))
                put("payload", JsonNull)
                put("estimate", estimateJson(item.estimate))
            }
        }
    }

    private fun estimateJson(estimate: RestaurantNutritionEstimate): JsonObject = buildJsonObject {
        put("estimated", JsonPrimitive(estimate.estimated))
        put("confidence", estimate.confidenceScore?.let(::JsonPrimitive) ?: JsonPrimitive(estimate.confidence))
        put("nutrients", JsonObject(NutritionField.entries.associate { field ->
            field.wireKey to (estimate.nutrients[field]?.let(::JsonPrimitive) ?: JsonNull)
        }))
        put("ranges", JsonObject(estimate.ranges.mapKeys { it.key.wireKey }.mapValues { (_, range) ->
            buildJsonObject {
                put("min", range.min?.let(::JsonPrimitive) ?: JsonNull)
                put("point", range.point?.let(::JsonPrimitive) ?: JsonNull)
                put("max", range.max?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("provenance", provenanceJson(estimate))
    }

    private fun provenanceJson(estimate: RestaurantNutritionEstimate): JsonObject = JsonObject(estimate.nutrientProvenance.map { (field, provenance) ->
        field.wireKey to buildJsonObject {
            put("value_status", JsonPrimitive(provenance.valueStatus))
            put("source_type", JsonPrimitive(provenance.sourceType))
            put("evidence_refs", JsonArray(provenance.evidenceRefs.map(::JsonPrimitive)))
        }
    }.toMap())

    private fun decodeProvenance(root: JsonObject): Map<NutritionField, NutritionNutrientProvenance> = root.map { (key, value) ->
        val field = NutritionField.fromWireKey(key) ?: error("Unsupported nutrition provenance: $key")
        val item = value.jsonObject
        requireKeys(item, setOf("value_status", "source_type", "evidence_refs"))
        field to NutritionNutrientProvenance(item.string("value_status"), item.string("source_type"), item.arrayValue("evidence_refs").strings())
    }.toMap()

    private fun decodeNutrition(element: JsonElement, preservePersistedVerification: Boolean): IngestionNutrition {
        val root = element.jsonObject
        requireKeys(root, setOf("client_key", "kind", "line_id", "menu_name", "payload", "estimate"))
        val clientKey = root.string("client_key")
        return when (root.string("kind")) {
            "product_label" -> {
                require(root["line_id"] == JsonNull)
                require(root["menu_name"] == JsonNull)
                val payload = root["payload"]?.takeUnless { it == JsonNull } ?: error("product_label payload required")
                val encoded = json.encodeToString(JsonElement.serializer(), payload)
                if (preservePersistedVerification) {
                    IngestionNutrition.ProductLabel(clientKey, NutritionLabelJson.decode(encoded))
                } else {
                    val imported = ExternalJsonImporter().import(
                        encoded,
                        "envelope-$clientKey",
                        OcrWorkflowType.FITNESS_NUTRITION,
                    )
                    val result = (imported as? ExternalJsonImportOutcome.Success)?.result
                        ?: error("product_label payload must be fitness-nutrition-draft.v1")
                    IngestionNutrition.ProductLabel(clientKey, (result.draft as CanonicalDraft.Nutrition).value)
                }
            }
            "restaurant_estimate" -> {
                val lineId = root.string("line_id")
                val menuName = root.string("menu_name")
                require(root["payload"] == JsonNull)
                IngestionNutrition.RestaurantEstimate(clientKey, lineId, menuName, decodeEstimate(root.objectValue("estimate")))
            }
            else -> error("Unsupported nutrition kind")
        }
    }

    private fun decodeEstimate(root: JsonObject): RestaurantNutritionEstimate {
        val legacyKeys = setOf("estimated", "confidence", "nutrients", "ranges")
        val canonicalKeys = legacyKeys + "provenance"
        require(root.keys == legacyKeys || root.keys == canonicalKeys) { "Unexpected estimate keys" }
        require(root.boolean("estimated")) { "restaurant nutrition must be explicitly estimated" }
        val confidenceElement = root["confidence"] ?: error("confidence is required")
        val confidenceScore = (confidenceElement as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
        val confidence = confidenceElement.jsonPrimitive.content
        val nutrientsRoot = root.objectValue("nutrients")
        require(nutrientsRoot.keys == NutritionField.entries.map { it.wireKey }.toSet())
        val nutrients = NutritionField.entries.associateWith { field -> nutrientsRoot.nullableNumber(field.wireKey) }
        val ranges = root.objectValue("ranges").map { (key, value) ->
            val field = NutritionField.fromWireKey(key) ?: error("Unsupported nutrition range: $key")
            field to decodeRange(value.jsonObject)
        }.toMap()
        val nutrientProvenance = root["provenance"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::decodeProvenance).orEmpty()
        return RestaurantNutritionEstimate(
            nutrients = nutrients,
            estimated = root.boolean("estimated"),
            confidence = confidence,
            confidenceScore = confidenceScore,
            ranges = ranges,
            nutrientProvenance = nutrientProvenance,
        )
    }

    private fun decodeRange(root: JsonObject): NutritionRange {
        requireKeys(root, setOf("min", "point", "max"))
        return NutritionRange(root.nullableNumber("min"), root.nullableNumber("point"), root.nullableNumber("max"))
    }

    private fun decodeSource(root: JsonObject): IngestionSource {
        requireKeys(root, setOf("producer", "source_files", "user_text"))
        val producer = root.string("producer")
        require(producer == "chatgpt" || producer == "ocr_app") { "unsupported envelope producer" }
        return IngestionSource(producer, root.arrayValue("source_files").map { element ->
            val item = element.jsonObject
            requireKeys(item, setOf("id", "type", "label"))
            SourceAttachment(item.string("id"), SourceAttachmentType.fromWireValue(item.string("type")), item.nullableString("label"))
        }, root.nullableString("user_text"))
    }

    private fun decodeMerchant(root: JsonObject): MerchantCandidate {
        val baseKeys = setOf("name", "branch_name", "address", "phone", "business_registration_number", "source_attachment_ids")
        val extendedKeys = baseKeys + setOf("business_kind", "source_namespace", "source_location_code")
        require(root.keys == baseKeys || root.keys == extendedKeys) { "Unexpected merchant candidate keys" }
        return MerchantCandidate(
            name = root.string("name"),
            businessKind = root["business_kind"]?.takeUnless { it == JsonNull }?.let { com.pricetrace.receiptscanner.domain.BusinessKind.entries.first { kind -> kind.wireValue == it.jsonPrimitive.content } } ?: com.pricetrace.receiptscanner.domain.BusinessKind.UNKNOWN,
            branchName = root.nullableString("branch_name"),
            address = root.nullableString("address"),
            phone = root.nullableString("phone"),
            businessRegistrationNumber = root.nullableString("business_registration_number"),
            sourceAttachmentIds = root.arrayValue("source_attachment_ids").strings(),
            sourceNamespace = root["source_namespace"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull,
            sourceLocationCode = root["source_location_code"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun decodeLink(root: JsonObject): IngestionLink {
        requireKeys(root, setOf("receipt_line_id", "nutrition_client_key"))
        return IngestionLink(root.string("receipt_line_id"), root.string("nutrition_client_key"))
    }

    private fun decodeHints(root: JsonObject): Map<String, String?> {
        require(root.keys subtract setOf("cashos") == emptySet<String>())
        val cashos = root.objectValue("cashos")
        require(cashos.keys subtract cashosHintKeys == emptySet<String>())
        return cashos.mapKeys { "cashos.${it.key}" }.mapValues { (_, value) -> value.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull }
    }

    private fun validateMode(
        mode: IngestionMode,
        merchant: MerchantCandidate?,
        receipt: ReceiptV2?,
        nutrition: List<IngestionNutrition>,
        links: List<IngestionLink>,
    ) {
        require(nutrition.map { it.clientKey }.distinct().size == nutrition.size) {
            "nutrition client_key values must be unique"
        }
        require(links.map { it.nutritionClientKey }.distinct().size == links.size) {
            "each nutrition artifact may have at most one link"
        }
        require(links.all { link ->
            val line = receipt?.lineItems?.singleOrNull { it.id == link.receiptLineId }
            val item = nutrition.singleOrNull { it.clientKey == link.nutritionClientKey }
            line != null && item?.lineId == line.id
        }) {
            "links must reference an existing receipt line and its nutrition artifact"
        }
        when (mode) {
            IngestionMode.MERCHANT -> require(
                merchant != null &&
                    nutrition.isEmpty() &&
                    links.isEmpty() &&
                    (receipt == null || receipt.merchant.businessKind != com.pricetrace.receiptscanner.domain.BusinessKind.FOOD_SERVICE),
            )
            IngestionMode.RESTAURANT -> require(
                merchant != null &&
                    receipt != null &&
                    receipt.merchant.businessKind == com.pricetrace.receiptscanner.domain.BusinessKind.FOOD_SERVICE &&
                    (nutrition.isEmpty() || (
                        nutrition.all { it is IngestionNutrition.RestaurantEstimate } &&
                        nutrition.all { item -> links.any { it.nutritionClientKey == item.clientKey && it.receiptLineId == item.lineId } }
                    )),
            )
            IngestionMode.PACKAGED_PRODUCT -> require(
                merchant == null &&
                    (receipt == null || receipt.merchant.businessKind != BusinessKind.FOOD_SERVICE) &&
                    nutrition.isNotEmpty() &&
                    nutrition.all { it is IngestionNutrition.ProductLabel } &&
                    links.isEmpty(),
            )
        }
    }
    private fun validateTargets(
        merchant: MerchantCandidate?,
        targets: Set<IngestionProjection>,
        receipt: ReceiptV2?,
        nutrition: List<IngestionNutrition>,
    ) {
        val available = buildSet {
            if (receipt != null) {
                add(IngestionProjection.PRICETRACE_RECEIPT)
                add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                add(IngestionProjection.CASHOS_RECEIPT)
            }
            if (nutrition.isNotEmpty()) add(IngestionProjection.FITNESS_NUTRITION)
            if (merchant != null && receipt == null) add(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE)
        }
        require(targets.all { it in available }) {
            "Projection target is incompatible with the supplied artifacts."
        }
    }
    private fun classificationHintsJson(hints: Map<String, String?>): JsonObject = buildJsonObject {
        put("cashos", JsonObject(
            hints.filterKeys {
                it.startsWith("cashos.") && it.removePrefix("cashos.") in cashosHintKeys
            }
                .mapKeys { (key, _) -> key.removePrefix("cashos.") }
                .mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull },
        ))
    }

    private fun sourceJson(source: IngestionSource) = buildJsonObject {
        put("producer", JsonPrimitive(source.producer)); put("source_files", JsonArray(source.sourceFiles.map {
            buildJsonObject { put("id", JsonPrimitive(it.id)); put("type", JsonPrimitive(it.type.wireValue)); put("label", it.label?.let(::JsonPrimitive) ?: JsonNull) }
        })); put("user_text", source.userText?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun merchantJson(value: MerchantCandidate) = buildJsonObject {
        put("name", JsonPrimitive(value.name)); put("business_kind", JsonPrimitive(value.businessKind.wireValue)); put("branch_name", value.branchName?.let(::JsonPrimitive) ?: JsonNull); put("address", value.address?.let(::JsonPrimitive) ?: JsonNull); put("phone", value.phone?.let(::JsonPrimitive) ?: JsonNull); put("business_registration_number", value.businessRegistrationNumber?.let(::JsonPrimitive) ?: JsonNull); put("source_attachment_ids", JsonArray(value.sourceAttachmentIds.map(::JsonPrimitive))); put("source_namespace", value.sourceNamespace?.let(::JsonPrimitive) ?: JsonNull); put("source_location_code", value.sourceLocationCode?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun linkJson(value: IngestionLink) = buildJsonObject { put("receipt_line_id", JsonPrimitive(value.receiptLineId)); put("nutrition_client_key", JsonPrimitive(value.nutritionClientKey)) }
    private fun reviewJson(value: IngestionReview) = buildJsonObject { put("status", JsonPrimitive(value.status.wireValue)); put("blocking_issues", JsonArray(value.blockingIssues.map(::JsonPrimitive))); put("warnings", JsonArray(value.warnings.map(::JsonPrimitive))) }

    private fun JsonObject.withDocumentId(id: String): JsonObject {
        val document = objectValue("document")
        return JsonObject(toMutableMap().apply { put("document", JsonObject(document.toMutableMap().apply { put("id", JsonPrimitive(id)) })) })
    }
    private fun requireKeys(root: JsonObject, expected: Set<String>) { require(root.keys == expected) { "Unexpected or missing JSON keys: ${root.keys subtract expected} / ${expected subtract root.keys}" } }
    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) ?: error("$key must be a non-empty string")
    private fun JsonObject.nullableString(key: String): String? = this[key].takeUnless { it == null || it == JsonNull }?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull ?: error("$key must be a string or null") }
    private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: error("$key must be boolean")
    private fun JsonObject.nullableNumber(key: String): Double? = this[key].takeUnless { it == null || it == JsonNull }?.let { (it as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) ?: error("$key must be a finite number or null") }
    private fun JsonObject.objectValue(key: String): JsonObject = this[key]?.jsonObject ?: error("$key must be an object")
    private fun JsonObject.arrayValue(key: String): JsonArray = this[key]?.jsonArray ?: error("$key must be an array")
    private fun JsonArray.strings(): List<String> = map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf(String::isNotBlank) ?: error("array must contain strings") }
    private val TOP_LEVEL_KEYS = setOf("schema_version", "mode", "source", "merchant_candidate", "receipt", "nutrition", "classification_hints", "links", "projection_targets", "review")
    private val TRUST_KEYS = setOf("user_verified", "confirmed_at", "owner_id", "confirmed_by", "created_at", "updated_at", "published_at", "publisher_status", "revision", "remote_id", "server_id")
}
