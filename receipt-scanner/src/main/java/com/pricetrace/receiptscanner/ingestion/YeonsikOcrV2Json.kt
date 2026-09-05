package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelJson
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
import java.time.OffsetDateTime

/** Strict codec for yeonsik-ocr.v2. v1 remains implemented by YeonsikOcrEnvelopeJson. */
object YeonsikOcrV2Json {
    private val cashosHintKeys = setOf("category_hint", "institution_hint", "payment_method_hint")
    private val json = Json { prettyPrint = true; explicitNulls = true; ignoreUnknownKeys = false }

    fun decode(
        value: String,
        localDocumentId: String,
        preservePersistedVerification: Boolean = false,
    ): YeonsikOcrEnvelope {
        require(localDocumentId.isNotBlank())
        val root = json.parseToJsonElement(value).jsonObject
        require(root.keys.containsAll(REQUIRED_TOP_LEVEL_KEYS)) {
            "Missing v2 envelope keys: ${REQUIRED_TOP_LEVEL_KEYS - root.keys}"
        }
        require(root.keys subtract ALLOWED_TOP_LEVEL_KEYS == emptySet<String>()) {
            "Unexpected v2 envelope keys: ${root.keys subtract ALLOWED_TOP_LEVEL_KEYS}"
        }
        require(root.string("schema_version") == YEONSIK_OCR_V2_SCHEMA)

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
        val nutrition = root.arrayValue("nutrition").map { decodeNutrition(it, preservePersistedVerification) }
        val productCandidates = root.arrayValue("product_candidates").map(::decodeProductCandidate)
        val consumption = root["consumption"]?.jsonArray?.map {
            decodeConsumption(it.jsonObject, preservePersistedVerification)
        }.orEmpty()
        val merchant = root["merchant_candidate"]?.takeUnless { it == JsonNull }
            ?.let { decodeMerchant(it.jsonObject) }
        val links = root.arrayValue("links").map { decodeLink(it.jsonObject) }
        val targets = root["projection_targets"]?.jsonArray
            ?.map { IngestionProjection.fromWireValue(it.jsonPrimitive.content) }
            ?.toSet()
            .orEmpty()
        val hints = decodeHints(root.objectValue("classification_hints"))
        val externalReview = decodeReview(root.objectValue("review"))
        validateEnvelope(mode, source, merchant, receipt, nutrition, productCandidates, consumption, links)
        validateTargets(merchant, targets, receipt, nutrition, productCandidates, consumption)

        return YeonsikOcrEnvelope(
            mode = mode,
            source = source,
            merchantCandidate = merchant,
            receipt = receipt,
            nutrition = nutrition,
            consumption = consumption,
            classificationHints = hints,
            links = links,
            targets = targets,
            review = IngestionReview(
                status = IngestionReviewStatus.NEEDS_REVIEW,
                blockingIssues = listOf("source_image_required"),
                warnings = externalReview.warnings,
            ),
            productCandidates = productCandidates,
            schemaVersion = YEONSIK_OCR_V2_SCHEMA,
        )
    }

    fun encode(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String {
        require(envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA) {
            "yeonsik-ocr.v2 codec cannot encode ${envelope.schemaVersion}"
        }
        return json.encodeToString(JsonElement.serializer(), toJson(envelope, canonicalIds))
    }

    fun canonicalize(envelope: YeonsikOcrEnvelope): String = encode(envelope, canonicalIds = true)

    private fun toJson(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive(YEONSIK_OCR_V2_SCHEMA))
        put("mode", JsonPrimitive(envelope.mode.wireValue))
        put("source", sourceJson(envelope.source))
        put("merchant_candidate", envelope.merchantCandidate?.let(::merchantJson) ?: JsonNull)
        put("receipt", envelope.receipt?.let { receipt ->
            val element = json.parseToJsonElement(ReceiptV2Json.encodeCanonical(receipt))
            if (!canonicalIds) element else element.jsonObject.withDocumentId("__receipt__")
        } ?: JsonNull)
        put("nutrition", JsonArray(envelope.nutrition.map { nutritionJson(it, canonicalIds) }))
        put("product_candidates", JsonArray(envelope.productCandidates.map(::productCandidateJson)))
        put("consumption", JsonArray(envelope.consumption.map(::consumptionJson)))
        put("classification_hints", classificationHintsJson(envelope.classificationHints))
        put("links", JsonArray(envelope.links.map(::linkJson)))
        put("projection_targets", JsonArray(envelope.targets.sortedBy(IngestionProjection::wireValue).map { JsonPrimitive(it.wireValue) }))
        put("review", reviewJson(envelope.review))
    }

    private fun productCandidateJson(value: ProductCandidate): JsonObject = buildJsonObject {
        put("client_key", JsonPrimitive(value.clientKey))
        put("product_name", JsonPrimitive(value.productName))
        put("brand", value.effectiveBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", value.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("specification", value.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("content_amount", value.contentAmount?.let(::JsonPrimitive) ?: JsonNull)
        put("content_unit", value.contentUnit?.let(::JsonPrimitive) ?: JsonNull)
        put("package_count", value.packageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("variant", value.variant?.let(::JsonPrimitive) ?: JsonNull)
        put("barcode", value.barcode?.let(::JsonPrimitive) ?: JsonNull)
        put("ean", value.ean?.let(::JsonPrimitive) ?: JsonNull)
        put("upc", value.upc?.let(::JsonPrimitive) ?: JsonNull)
        put("candidate_type", JsonPrimitive(value.candidateType))
        put("source_version", value.sourceVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("evidence", JsonArray(value.evidence.map { evidence -> buildJsonObject {
            put("source_attachment_ids", JsonArray(evidence.sourceAttachmentIds.map(::JsonPrimitive)))
            put("source", evidence.source?.let(::JsonPrimitive) ?: JsonNull)
            put("source_type", JsonPrimitive(evidence.sourceType))
            put("source_ref", JsonPrimitive(evidence.sourceRef ?: evidence.source ?: evidence.sourceAttachmentIds.first()))
            put("field", JsonPrimitive(evidence.field))
            put("observed_value", evidence.observedValue?.let(::JsonPrimitive) ?: JsonNull)
            put("content_hash", evidence.contentHash?.let(::JsonPrimitive) ?: JsonNull)
        }}))
    }

    private fun nutritionJson(item: IngestionNutrition, canonicalIds: Boolean): JsonObject = when (item) {
        is IngestionNutrition.ProductLabel -> buildJsonObject {
            put("client_key", JsonPrimitive(item.clientKey))
            put("kind", JsonPrimitive("product_label"))
            put("line_id", item.lineId?.let(::JsonPrimitive) ?: JsonNull)
            put("menu_name", JsonNull)
            put("payload", json.parseToJsonElement(NutritionLabelJson.encode(
                if (canonicalIds) item.draft.copy(documentId = "__nutrition__") else item.draft,
            )))
            put("estimate", JsonNull)
        }
        is IngestionNutrition.RestaurantEstimate -> buildJsonObject {
            put("client_key", JsonPrimitive(item.clientKey))
            put("kind", JsonPrimitive("restaurant_estimate"))
            put("line_id", JsonPrimitive(item.lineId))
            put("menu_name", JsonPrimitive(item.menuName))
            put("payload", JsonNull)
            put("estimate", estimateJson(item.estimate))
        }
        is IngestionNutrition.MealComponentEstimate -> buildJsonObject {
            put("client_key", JsonPrimitive(item.clientKey))
            put("kind", JsonPrimitive("meal_component_estimate"))
            put("line_id", item.lineId?.let(::JsonPrimitive) ?: JsonNull)
            put("menu_name", JsonPrimitive(item.menuName))
            put("payload", JsonNull)
            put("estimate", estimateJson(item.estimate))
            put("restaurant_name", item.reference?.restaurantName?.let(::JsonPrimitive) ?: JsonNull)
            put("branch_name", item.reference?.branchName?.let(::JsonPrimitive) ?: JsonNull)
            put("restaurant_menu_id", JsonNull)
        }
    }

    private fun estimateJson(estimate: RestaurantNutritionEstimate): JsonObject = buildJsonObject {
        put("estimated", JsonPrimitive(estimate.estimated))
        put("confidence", estimate.confidenceScore?.let(::JsonPrimitive) ?: JsonPrimitive(estimate.confidence))
        put("nutrients", JsonObject(NutritionField.entries.associate { field ->
            field.wireKey to (estimate.nutrients[field]?.let(::JsonPrimitive) ?: JsonNull)
        }))
        put("ranges", JsonObject(estimate.ranges.mapKeys { it.key.wireKey }.mapValues { (_, range) -> buildJsonObject {
            put("min", range.min?.let(::JsonPrimitive) ?: JsonNull)
            put("point", range.point?.let(::JsonPrimitive) ?: JsonNull)
            put("max", range.max?.let(::JsonPrimitive) ?: JsonNull)
        }}))
        put("provenance", JsonObject(estimate.nutrientProvenance.map { (field, provenance) ->
            field.wireKey to buildJsonObject {
                put("value_status", JsonPrimitive(provenance.valueStatus))
                put("source_type", JsonPrimitive(provenance.sourceType))
                put("evidence_refs", JsonArray(provenance.evidenceRefs.map(::JsonPrimitive)))
            }
        }.toMap()))
    }

    private fun decodeProductCandidate(element: JsonElement): ProductCandidate {
        val root = element.jsonObject
        requireKeys(root, PRODUCT_CANDIDATE_KEYS)
        val evidence = root.arrayValue("evidence").map { evidenceElement ->
            val item = evidenceElement.jsonObject
            requireKeys(item, EVIDENCE_KEYS)
            ProductCandidateEvidence(
                sourceAttachmentIds = item.arrayValue("source_attachment_ids").strings(),
                source = item.nullableString("source"),
                sourceType = item.string("source_type"),
                sourceRef = item.string("source_ref"),
                field = item.string("field"),
                observedValue = item.nullableString("observed_value"),
                contentHash = item.nullableString("content_hash"),
            )
        }
        return ProductCandidate(
            clientKey = root.string("client_key"),
            productName = root.string("product_name"),
            brand = root.nullableString("brand"),
            manufacturer = root.nullableString("manufacturer"),
            specification = root.nullableString("specification"),
            contentAmount = root.nullableNumber("content_amount"),
            contentUnit = root.nullableString("content_unit"),
            packageCount = root.nullableNumber("package_count")?.let { value ->
                require(value % 1.0 == 0.0 && value > 0)
                value.toInt()
            },
            variant = root.nullableString("variant"),
            barcode = root.nullableString("barcode"),
            ean = root.nullableString("ean"),
            upc = root.nullableString("upc"),
            evidence = evidence,
            candidateType = root.string("candidate_type"),
            sourceVersion = root.nullableString("source_version"),
        )
    }

    private fun decodeNutrition(element: JsonElement, preservePersistedVerification: Boolean): IngestionNutrition {
        val root = element.jsonObject
        val kind = root.string("kind")
        requireKeys(root, if (kind == "meal_component_estimate") {
            MEAL_COMPONENT_NUTRITION_KEYS
        } else {
            NUTRITION_KEYS
        })
        val clientKey = root.string("client_key")
        return when (kind) {
            "product_label" -> {
                require(root["line_id"] == JsonNull)
                require(root["menu_name"] == JsonNull)
                require(root["estimate"] == JsonNull)
                val payload = root["payload"]?.takeUnless { it == JsonNull }
                    ?: error("product_label payload required")
                val encoded = json.encodeToString(JsonElement.serializer(), payload)
                val draft = if (preservePersistedVerification) {
                    NutritionLabelJson.decode(encoded)
                } else {
                    val imported = ExternalJsonImporter().import(
                        encoded,
                        "envelope-$clientKey",
                        OcrWorkflowType.FITNESS_NUTRITION,
                    )
                    val result = (imported as? ExternalJsonImportOutcome.Success)?.result
                        ?: error("product_label payload must be fitness-nutrition-draft.v1")
                    (result.draft as CanonicalDraft.Nutrition).value
                }
                IngestionNutrition.ProductLabel(clientKey, draft)
            }
            "restaurant_estimate" -> {
                require(root["line_id"] != JsonNull)
                require(root["payload"] == JsonNull)
                require(root["estimate"] != JsonNull)
                IngestionNutrition.RestaurantEstimate(
                    clientKey = clientKey,
                    lineId = root.string("line_id"),
                    menuName = root.string("menu_name"),
                    estimate = decodeEstimate(root.objectValue("estimate")),
                )
            }
            "meal_component_estimate" -> {
                require(root["payload"] == JsonNull)
                require(root["estimate"] != JsonNull)
                require(root.nullableString("restaurant_menu_id") == null) {
                    "meal_component_estimate cannot assert a PriceTrace restaurant_menu_id"
                }
                IngestionNutrition.MealComponentEstimate(
                    clientKey = clientKey,
                    lineId = root.nullableString("line_id"),
                    menuName = root.string("menu_name"),
                    estimate = decodeEstimate(root.objectValue("estimate")),
                    reference = MealComponentReference(
                        restaurantName = root.nullableString("restaurant_name"),
                        branchName = root.nullableString("branch_name"),
                    ),
                )
            }
            else -> error("Unsupported v2 nutrition kind: $kind")
        }
    }

    private fun decodeConsumption(root: JsonObject, preservePersistedVerification: Boolean): IngestionConsumption {
        require(root.keys == CONSUMPTION_KEYS || root.keys == CONSUMPTION_KEYS - "status")
        val consumedAt = root.nullableString("consumed_at")
        require(consumedAt != null) { "v2 consumption requires the actual consumed_at meal time" }
        runCatching { OffsetDateTime.parse(consumedAt) }
            .getOrElse { error("consumed_at must be an ISO-8601 offset date-time") }
        val items = root.arrayValue("items").map { element ->
            val item = element.jsonObject
            requireKeys(item, CONSUMPTION_ITEM_KEYS)
            IngestionConsumptionItem(
                nutritionClientKey = item.string("nutrition_client_key"),
                amount = item.number("amount"),
                unit = item.string("unit"),
                confidence = item.number("confidence").also { require(it in 0.0..1.0) },
            )
        }
        require(items.isNotEmpty()) { "v2 consumption must contain at least one item" }
        val status = root["status"]?.let { ConsumptionVerificationStatus.fromWireValue(it.jsonPrimitive.content) }
            ?: ConsumptionVerificationStatus.UNVERIFIED
        return IngestionConsumption(
            clientKey = root.string("client_key"),
            nutritionClientKeys = items.map { it.nutritionClientKey }.toSet(),
            consumedAt = consumedAt,
            status = status.takeIf { preservePersistedVerification } ?: ConsumptionVerificationStatus.UNVERIFIED,
            items = items,
        )
    }

    private fun decodeEstimate(root: JsonObject): RestaurantNutritionEstimate {
        val allowed = ESTIMATE_KEYS + "provenance"
        require(root.keys == ESTIMATE_KEYS || root.keys == allowed) { "Unexpected estimate keys" }
        require(root.boolean("estimated")) { "nutrition estimate must be explicitly estimated" }
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
        val provenance = root["provenance"]?.takeUnless { it == JsonNull}
            ?.jsonObject?.map { (key, value) ->
                val field = NutritionField.fromWireKey(key) ?: error("Unsupported nutrition provenance: $key")
                val item = value.jsonObject
                requireKeys(item, PROVENANCE_KEYS)
                field to NutritionNutrientProvenance(
                    valueStatus = item.string("value_status"),
                    sourceType = item.string("source_type"),
                    evidenceRefs = item.arrayValue("evidence_refs").strings(),
                )
            }?.toMap().orEmpty()
        return RestaurantNutritionEstimate(
            nutrients = nutrients,
            estimated = true,
            confidence = confidence,
            confidenceScore = confidenceScore,
            ranges = ranges,
            nutrientProvenance = provenance,
        )
    }

    private fun decodeRange(root: JsonObject): NutritionRange {
        requireKeys(root, RANGE_KEYS)
        return NutritionRange(root.nullableNumber("min"), root.nullableNumber("point"), root.nullableNumber("max"))
    }

    private fun decodeSource(root: JsonObject): IngestionSource {
        requireKeys(root, SOURCE_KEYS)
        val producer = root.string("producer")
        require(producer == "chatgpt" || producer == "ocr_app") { "unsupported envelope producer" }
        val files = root.arrayValue("source_files").map { element ->
            val item = element.jsonObject
            requireKeys(item, SOURCE_FILE_KEYS)
            SourceAttachment(
                id = item.string("id"),
                type = SourceAttachmentType.fromWireValue(item.string("type")),
                label = item.nullableString("label"),
            )
        }
        require(files.map { it.id }.distinct().size == files.size) { "source attachment IDs must be unique" }
        return IngestionSource(producer, files, root.nullableString("user_text"))
    }

    private fun decodeMerchant(root: JsonObject): MerchantCandidate {
        requireKeys(root, MERCHANT_KEYS)
        return MerchantCandidate(
            name = root.string("name"),
            businessKind = BusinessKind.entries.first { it.wireValue == root.string("business_kind") },
            branchName = root.nullableString("branch_name"),
            address = root.nullableString("address"),
            phone = root.nullableString("phone"),
            businessRegistrationNumber = root.nullableString("business_registration_number"),
            sourceAttachmentIds = root.arrayValue("source_attachment_ids").strings(),
            sourceNamespace = root.nullableString("source_namespace"),
            sourceLocationCode = root.nullableString("source_location_code"),
        )
    }

    private fun decodeLink(root: JsonObject): IngestionLink {
        requireKeys(root, LINK_KEYS)
        return IngestionLink(root.string("receipt_line_id"), root.string("nutrition_client_key"))
    }

    private fun decodeHints(root: JsonObject): Map<String, String?> {
        require(root.keys subtract setOf("cashos") == emptySet<String>())
        val cashos = root.objectValue("cashos")
        require(cashos.keys subtract cashosHintKeys == emptySet<String>())
        return cashos.mapKeys { "cashos.${it.key}" }
            .mapValues { (_, value) -> value.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull }
    }

    private fun decodeReview(root: JsonObject): IngestionReview {
        requireKeys(root, REVIEW_KEYS)
        // The producer's review status is descriptive input only. It is still parsed so malformed
        // or unknown status values cannot silently cross the validation boundary.
        IngestionReviewStatus.entries.firstOrNull { it.wireValue == root.string("status") }
            ?: error("Unsupported v2 review status")
        return IngestionReview(
            status = IngestionReviewStatus.NEEDS_REVIEW,
            blockingIssues = root.arrayValue("blocking_issues").strings(),
            warnings = root.arrayValue("warnings").strings(),
        )
    }

    private fun validateEnvelope(
        mode: IngestionMode,
        source: IngestionSource,
        merchant: MerchantCandidate?,
        receipt: ReceiptV2?,
        nutrition: List<IngestionNutrition>,
        productCandidates: List<ProductCandidate>,
        consumption: List<IngestionConsumption>,
        links: List<IngestionLink>,
    ) {
        val sourceIds = source.sourceFiles.map { it.id }.toSet()
        require(productCandidates.map { it.clientKey }.distinct().size == productCandidates.size) {
            "product candidate client_key values must be unique"
        }
        productCandidates.forEach { candidate ->
            require(candidate.clientKey.isNotBlank() && candidate.productName.isNotBlank())
            require(candidate.evidence.isNotEmpty()) { "product candidates require evidence" }
            candidate.evidence.forEach { evidence ->
                require(evidence.sourceAttachmentIds.isNotEmpty()) { "product candidate evidence requires a source" }
                require(evidence.sourceAttachmentIds.all { it in sourceIds }) {
                    "product candidate evidence must reference source attachments"
                }
                require(evidence.sourceAttachmentIds.any { id ->
                    source.sourceFiles.any { file -> file.id == id && file.type == SourceAttachmentType.PRODUCT_PHOTO }
                }) { "product candidates require a PRODUCT_PHOTO source attachment" }
            }
        }
        require(nutrition.map { it.clientKey }.distinct().size == nutrition.size) {
            "nutrition client_key values must be unique"
        }
        require(links.map { it.nutritionClientKey }.distinct().size == links.size) {
            "each nutrition artifact may have at most one link"
        }
        require(links.all { link ->
            val line = receipt?.lineItems?.singleOrNull { it.id == link.receiptLineId }
            val item = nutrition.singleOrNull { it.clientKey == link.nutritionClientKey }
            line != null && item?.lineId == line.id && item !is IngestionNutrition.MealComponentEstimate
        }) { "links must reference a receipt line and a non-component nutrition artifact" }
        require(consumption.map { it.clientKey }.distinct().size == consumption.size) {
            "consumption client_key values must be unique"
        }
        require(consumption.all { item ->
            item.items.isNotEmpty() && item.consumedAt != null && item.effectiveNutritionClientKeys.all { key ->
                nutrition.any { nutritionItem -> nutritionItem.clientKey == key }
            }
        }) { "consumption must contain item-level references to existing nutrition artifacts" }

        nutrition.filterIsInstance<IngestionNutrition.RestaurantEstimate>().forEach { item ->
            require(links.any { it.nutritionClientKey == item.clientKey && it.receiptLineId == item.lineId }) {
                "restaurant estimates must retain their receipt line link"
            }
        }
        nutrition.filterIsInstance<IngestionNutrition.MealComponentEstimate>().forEach { item ->
            if (item.lineId != null) {
                require(receipt?.lineItems?.any { it.id == item.lineId } == true) {
                    "linked meal components must reference an existing receipt line"
                }
                require(links.any { it.nutritionClientKey == item.clientKey && it.receiptLineId == item.lineId }) {
                    "linked meal components must retain their receipt line link"
                }
            }
        }

        when (mode) {
            IngestionMode.MERCHANT -> require(
                merchant != null && receipt?.merchant?.businessKind != BusinessKind.FOOD_SERVICE &&
                    nutrition.isEmpty() && productCandidates.isEmpty() && consumption.isEmpty() && links.isEmpty(),
            )
            IngestionMode.RESTAURANT -> require(
                merchant != null && receipt != null &&
                    receipt.merchant.businessKind == BusinessKind.FOOD_SERVICE &&
                    productCandidates.isEmpty() &&
                    nutrition.all { it is IngestionNutrition.RestaurantEstimate || it is IngestionNutrition.MealComponentEstimate },
            )
            IngestionMode.PACKAGED_PRODUCT -> require(
                merchant == null && receipt?.merchant?.businessKind != BusinessKind.FOOD_SERVICE &&
                    (nutrition.isNotEmpty() || productCandidates.isNotEmpty()) &&
                    nutrition.all { it is IngestionNutrition.ProductLabel } && links.isEmpty(),
            )
        }
    }

    private fun validateTargets(
        merchant: MerchantCandidate?,
        targets: Set<IngestionProjection>,
        receipt: ReceiptV2?,
        nutrition: List<IngestionNutrition>,
        productCandidates: List<ProductCandidate>,
        consumption: List<IngestionConsumption>,
    ) {
        val available = buildSet {
            if (receipt != null) {
                add(IngestionProjection.PRICETRACE_RECEIPT)
                add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                add(IngestionProjection.CASHOS_RECEIPT)
            }
            if (productCandidates.isNotEmpty()) add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
            if (nutrition.isNotEmpty()) add(IngestionProjection.FITNESS_NUTRITION)
            if (nutrition.isNotEmpty() && consumption.isNotEmpty()) add(IngestionProjection.FITNESS_MEAL)
            if (productCandidates.isNotEmpty() && nutrition.isNotEmpty()) {
                add(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK)
            }
            if (merchant != null && receipt == null) add(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE)
        }
        require(targets.all { it in available }) { "Projection target is incompatible with v2 artifacts." }
    }

    private fun sourceJson(source: IngestionSource) = buildJsonObject {
        put("producer", JsonPrimitive(source.producer))
        put("source_files", JsonArray(source.sourceFiles.map {
            buildJsonObject {
                put("id", JsonPrimitive(it.id))
                put("type", JsonPrimitive(it.type.wireValue))
                put("label", it.label?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("user_text", source.userText?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun merchantJson(value: MerchantCandidate) = buildJsonObject {
        put("name", JsonPrimitive(value.name))
        put("business_kind", JsonPrimitive(value.businessKind.wireValue))
        put("branch_name", value.branchName?.let(::JsonPrimitive) ?: JsonNull)
        put("address", value.address?.let(::JsonPrimitive) ?: JsonNull)
        put("phone", value.phone?.let(::JsonPrimitive) ?: JsonNull)
        put("business_registration_number", value.businessRegistrationNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("source_attachment_ids", JsonArray(value.sourceAttachmentIds.map(::JsonPrimitive)))
        put("source_namespace", value.sourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
        put("source_location_code", value.sourceLocationCode?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun linkJson(value: IngestionLink) = buildJsonObject {
        put("receipt_line_id", JsonPrimitive(value.receiptLineId))
        put("nutrition_client_key", JsonPrimitive(value.nutritionClientKey))
    }

    private fun consumptionJson(value: IngestionConsumption) = buildJsonObject {
        require(value.items.isNotEmpty()) { "v2 consumption must contain item-level values" }
        require(value.consumedAt != null) { "v2 consumption must contain consumed_at" }
        put("client_key", JsonPrimitive(value.clientKey))
        put("consumed_at", JsonPrimitive(value.consumedAt))
        put("items", JsonArray(value.items.map { item ->
            buildJsonObject {
                put("nutrition_client_key", JsonPrimitive(item.nutritionClientKey))
                put("amount", JsonPrimitive(item.amount))
                put("unit", JsonPrimitive(item.unit))
                put("confidence", JsonPrimitive(item.confidence))
            }
        }))
        put("status", JsonPrimitive(value.status.wireValue))
    }

    private fun classificationHintsJson(hints: Map<String, String?>): JsonObject = buildJsonObject {
        put("cashos", JsonObject(
            hints.filterKeys { it.startsWith("cashos.") && it.removePrefix("cashos.") in cashosHintKeys }
                .mapKeys { (key, _) -> key.removePrefix("cashos.") }
                .mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull },
        ))
    }

    private fun reviewJson(value: IngestionReview) = buildJsonObject {
        put("status", JsonPrimitive(value.status.wireValue))
        put("blocking_issues", JsonArray(value.blockingIssues.map(::JsonPrimitive)))
        put("warnings", JsonArray(value.warnings.map(::JsonPrimitive)))
    }

    private fun JsonObject.withDocumentId(id: String): JsonObject {
        val document = objectValue("document")
        return JsonObject(toMutableMap().apply {
            put("document", JsonObject(document.toMutableMap().apply { put("id", JsonPrimitive(id)) }))
        })
    }

    private fun requireKeys(root: JsonObject, expected: Set<String>) {
        require(root.keys == expected) {
            "Unexpected or missing JSON keys: ${root.keys subtract expected} / ${expected subtract root.keys}"
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("$key must be a non-empty string")

    private fun JsonObject.nullableString(key: String): String? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: error("$key must be a string or null") }

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: error("$key must be boolean")

    private fun JsonObject.number(key: String): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
            ?: error("$key must be a finite number")

    private fun JsonObject.nullableNumber(key: String): Double? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let { (it as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
            ?: error("$key must be a finite number or null") }

    private fun JsonObject.objectValue(key: String): JsonObject = this[key]?.jsonObject
        ?: error("$key must be an object")

    private fun JsonObject.arrayValue(key: String): JsonArray = this[key]?.jsonArray
        ?: error("$key must be an array")

    private fun JsonArray.strings(): List<String> = map {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("array must contain non-empty strings")
    }

    private val REQUIRED_TOP_LEVEL_KEYS = setOf(
        "schema_version", "mode", "source", "merchant_candidate", "receipt", "nutrition",
        "product_candidates", "consumption", "classification_hints", "links", "projection_targets", "review",
    )
    private val ALLOWED_TOP_LEVEL_KEYS = REQUIRED_TOP_LEVEL_KEYS
    private val SOURCE_KEYS = setOf("producer", "source_files", "user_text")
    private val SOURCE_FILE_KEYS = setOf("id", "type", "label")
    private val MERCHANT_KEYS = setOf(
        "name", "business_kind", "branch_name", "address", "phone", "business_registration_number",
        "source_attachment_ids", "source_namespace", "source_location_code",
    )
    private val PRODUCT_CANDIDATE_KEYS = setOf(
        "client_key", "product_name", "brand", "manufacturer", "specification",
        "content_amount", "content_unit", "package_count", "variant", "barcode", "ean", "upc",
        "candidate_type", "source_version", "evidence",
    )
    private val EVIDENCE_KEYS = setOf(
        "source_attachment_ids", "source", "source_type", "source_ref", "field", "observed_value", "content_hash",
    )
    private val NUTRITION_KEYS = setOf("client_key", "kind", "line_id", "menu_name", "payload", "estimate")
    private val MEAL_COMPONENT_NUTRITION_KEYS = NUTRITION_KEYS + setOf("restaurant_name", "branch_name", "restaurant_menu_id")
    private val CONSUMPTION_KEYS = setOf("client_key", "consumed_at", "items", "status")
    private val CONSUMPTION_ITEM_KEYS = setOf("nutrition_client_key", "amount", "unit", "confidence")
    private val LINK_KEYS = setOf("receipt_line_id", "nutrition_client_key")
    private val ESTIMATE_KEYS = setOf("estimated", "confidence", "nutrients", "ranges")
    private val RANGE_KEYS = setOf("min", "point", "max")
    private val PROVENANCE_KEYS = setOf("value_status", "source_type", "evidence_refs")
    private val REVIEW_KEYS = setOf("status", "blocking_issues", "warnings")
}
