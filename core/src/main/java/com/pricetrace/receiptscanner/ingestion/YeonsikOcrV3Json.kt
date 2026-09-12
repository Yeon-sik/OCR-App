package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
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

/** Strict codec for yeonsik-ocr.v3. v1/v2 codecs remain separate and unchanged. */
object YeonsikOcrV3Json {
    private val json = Json { prettyPrint = true; explicitNulls = true; ignoreUnknownKeys = false }

    fun decode(value: String, localDocumentId: String, preservePersistedVerification: Boolean = false): YeonsikOcrEnvelope {
        require(localDocumentId.isNotBlank())
        val root = json.parseToJsonElement(value).jsonObject
        require(root.keys == TOP_LEVEL_KEYS) {
            "Unexpected or missing v3 envelope keys: ${root.keys subtract TOP_LEVEL_KEYS} / ${TOP_LEVEL_KEYS - root.keys}"
        }
        require(root.string("schema_version") == YEONSIK_OCR_V3_SCHEMA)
        val mode = IngestionMode.fromWireValue(root.string("mode"))
        val source = decodeSource(root.objectValue("source"))
        val receipt = root["receipt"]?.takeUnless { it == JsonNull }?.let { element ->
            val encoded = json.encodeToString(JsonElement.serializer(), element)
            if (preservePersistedVerification) {
                ReceiptV2Json.decode(encoded, localDocumentId)
            } else {
                val imported = ExternalJsonImporter().import(encoded, localDocumentId)
                val result = imported as? ExternalJsonImportOutcome.Success
                    ?: error("receipt must be a valid receipt.v2 payload")
                (result.result.draft as CanonicalDraft.Receipt).value
            }
        }
        val productCandidates = root.arrayValue("product_candidates").map {
            decodeProductCandidate(it, source.userText)
        }
        val priceObservations = root.arrayValue("price_observations").map {
            decodePriceObservation(it)
        }
        val nutrition = root.arrayValue("nutrition").map {
            decodeNutrition(it, localDocumentId, preservePersistedVerification)
        }
        val consumption = root.arrayValue("consumption").map { decodeConsumption(it.jsonObject, preservePersistedVerification) }
        val merchant = root["merchant_candidate"]?.takeUnless { it == JsonNull }?.let { decodeMerchant(it.jsonObject) }
        val links = root.arrayValue("links").map { decodeLink(it.jsonObject) }
        // Targets are parsed for type safety only. CanonicalProjectionPlanner owns routing.
        val targets = root.arrayValue("projection_targets").map {
            IngestionProjection.fromWireValue(it.jsonPrimitive.content)
        }.toSet()
        val hints = decodeHints(root.objectValue("classification_hints"))
        val review = decodeReview(root.objectValue("review"), preservePersistedVerification)
        validateEnvelope(mode, source, merchant, receipt, productCandidates, priceObservations, nutrition, consumption, links)
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
            review = review,
            productCandidates = productCandidates,
            schemaVersion = YEONSIK_OCR_V3_SCHEMA,
            priceObservations = priceObservations,
        )
    }

    fun encode(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String {
        return encodeDraft(envelope, canonicalIds)
    }

    /** Encodes the producer-safe v3 draft shape; verification authority is internal-only. */
    fun encodeDraft(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String {
        require(envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA) {
            "yeonsik-ocr.v3 codec cannot encode ${envelope.schemaVersion}"
        }
        return json.encodeToString(
            JsonElement.serializer(),
            toJson(envelope, canonicalIds, includeAuthorityFields = false, forFingerprint = false),
        )
    }

    /** Encodes the local persisted snapshot, including Core-owned verification state. */
    fun encodePersisted(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String {
        require(envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA) {
            "yeonsik-ocr.v3 codec cannot encode ${envelope.schemaVersion}"
        }
        return json.encodeToString(
            JsonElement.serializer(),
            toJson(envelope, canonicalIds, includeAuthorityFields = true, forFingerprint = false),
        )
    }

    fun canonicalize(envelope: YeonsikOcrEnvelope): String {
        require(envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA) {
            "yeonsik-ocr.v3 codec cannot encode ${envelope.schemaVersion}"
        }
        return json.encodeToString(
            JsonElement.serializer(),
            toJson(envelope, canonicalIds = true, includeAuthorityFields = false, forFingerprint = true),
        )
    }

    /** Re-checks cross-artifact invariants after a local review edit. */
    fun validate(envelope: YeonsikOcrEnvelope) {
        require(envelope.schemaVersion == YEONSIK_OCR_V3_SCHEMA) {
            "yeonsik-ocr.v3 validation requires a v3 envelope"
        }
        validateEnvelope(
            mode = envelope.mode,
            source = envelope.source,
            merchant = envelope.merchantCandidate,
            receipt = envelope.receipt,
            productCandidates = envelope.productCandidates,
            priceObservations = envelope.priceObservations,
            nutrition = envelope.nutrition,
            consumption = envelope.consumption,
            links = envelope.links,
        )
    }

    private fun toJson(
        envelope: YeonsikOcrEnvelope,
        canonicalIds: Boolean,
        includeAuthorityFields: Boolean,
        forFingerprint: Boolean,
    ): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive(YEONSIK_OCR_V3_SCHEMA))
        put("mode", JsonPrimitive(envelope.mode.wireValue))
        put("source", sourceJson(envelope.source))
        put("merchant_candidate", envelope.merchantCandidate?.let(::merchantJson) ?: JsonNull)
        put("receipt", envelope.receipt?.let { receipt ->
            val element = json.parseToJsonElement(ReceiptV2Json.encodeCanonical(receipt))
            if (!canonicalIds) element else element.jsonObject.withDocumentId("__receipt__")
        } ?: JsonNull)
        put("product_candidates", JsonArray(envelope.productCandidates.map {
            productCandidateJson(it, envelope.source.userText)
        }))
        put("price_observations", JsonArray(envelope.priceObservations.map(::priceObservationJson)))
        put("nutrition", JsonArray(envelope.nutrition.map {
            nutritionJson(it, canonicalIds, includeAuthorityFields)
        }))
        put("consumption", JsonArray(envelope.consumption.map {
            consumptionJson(it, includeAuthorityFields)
        }))
        put("classification_hints", classificationHintsJson(envelope.classificationHints))
        put("links", JsonArray(envelope.links.map(::linkJson)))
        put("projection_targets", JsonArray(envelope.targets.sortedBy(IngestionProjection::wireValue).map { JsonPrimitive(it.wireValue) }))
        put("review", reviewJson(envelope.review, includeAuthorityFields, forFingerprint))
    }

    private fun productCandidateJson(
        value: ProductCandidate,
        sourceUserText: String?,
    ): JsonObject = buildJsonObject {
        put("client_key", JsonPrimitive(value.clientKey))
        put("product_name", JsonPrimitive(value.productName))
        put("merchant_sku", value.merchantSku?.let(::JsonPrimitive) ?: JsonNull)
        put("brand_name", value.brandName?.let(::JsonPrimitive) ?: JsonNull)
        put("sub_brand_name", value.subBrandName?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer_name", value.manufacturerName?.let(::JsonPrimitive) ?: JsonNull)
        put("variant_name", value.variantName?.let(::JsonPrimitive) ?: JsonNull)
        put("specification_text", value.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("content_amount", value.contentAmount?.let(::JsonPrimitive) ?: JsonNull)
        put("content_unit", value.contentUnit?.let(::JsonPrimitive) ?: JsonNull)
        put("package_count", value.packageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("barcodes", JsonArray(value.barcodes.map { barcode -> buildJsonObject {
            put("scheme", JsonPrimitive(barcode.scheme)); put("value", JsonPrimitive(barcode.value))
        }}))
        put("source_attachment_ids", JsonArray(value.effectiveSourceAttachmentIds.map(::JsonPrimitive)))
        put("evidence", JsonArray(value.evidence.map { evidence ->
            productEvidenceJson(evidence, sourceUserText)
        }))
        put("confidence", JsonPrimitive(value.confidence))
    }

    private fun productEvidenceJson(
        value: ProductCandidateEvidence,
        sourceUserText: String?,
    ): JsonObject {
        val sourceRef = value.sourceRef?.takeIf(String::isNotBlank)
            ?: value.source?.takeIf(String::isNotBlank)
            ?: resolveProductEvidenceSourceRef(
                value.sourceType,
                value.sourceAttachmentIds,
                sourceUserText,
            )
        require(!sourceRef.isNullOrBlank()) { "product candidate evidence source_ref is required" }
        return buildJsonObject {
            put("source_type", JsonPrimitive(value.sourceType))
            put("source_ref", JsonPrimitive(sourceRef))
            put("field", JsonPrimitive(value.field))
            put("observed_value", value.observedValue?.let(::JsonPrimitive) ?: JsonNull)
            put("content_hash", value.contentHash?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun priceObservationJson(value: StandalonePriceObservation): JsonObject = buildJsonObject {
        put("client_key", JsonPrimitive(value.clientKey))
        put("kind", JsonPrimitive(value.kind.wireValue))
        put("product_client_key", value.productClientKey?.let(::JsonPrimitive) ?: JsonNull)
        put("item_name", value.itemName?.let(::JsonPrimitive) ?: JsonNull)
        put("observed_on", value.observedOn?.let(::JsonPrimitive) ?: JsonNull)
        put("observed_at", value.observedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("currency", JsonPrimitive(value.currency))
        put("quantity", value.quantity?.let { quantity ->
            buildJsonObject {
                put("value", JsonPrimitive(quantity.value))
                put("unit", JsonPrimitive(quantity.unit))
            }
        } ?: JsonNull)
        put("unit_price_amount_minor", value.unitPriceAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("gross_amount_minor", value.grossAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("discount_amount_minor", value.discountAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("net_amount_minor", value.netAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("source_attachment_ids", JsonArray(value.sourceAttachmentIds.map(::JsonPrimitive)))
        put("evidence", JsonArray(value.evidence.map { evidence ->
            buildJsonObject {
                put("source_type", JsonPrimitive(evidence.sourceType))
                put("source_attachment_ids", JsonArray(evidence.sourceAttachmentIds.map(::JsonPrimitive)))
                put("field", JsonPrimitive(evidence.field))
                put("observed_value", evidence.observedValue?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("confidence", JsonPrimitive(value.confidence))
    }

    private fun nutritionJson(
        item: IngestionNutrition,
        canonicalIds: Boolean,
        includeAuthorityFields: Boolean,
    ): JsonObject = buildJsonObject {
        put("client_key", JsonPrimitive(item.clientKey))
        put("kind", JsonPrimitive(nutritionKind(item)))
        put("line_id", item.lineId?.let(::JsonPrimitive) ?: JsonNull)
        put("menu_name", when (item) {
            is IngestionNutrition.ProductLabel -> JsonNull
            is IngestionNutrition.RestaurantEstimate -> JsonPrimitive(item.menuName)
            is IngestionNutrition.RestaurantMenuEstimate -> JsonPrimitive(item.menuName)
            is IngestionNutrition.MealComponentEstimate -> JsonPrimitive(item.menuName)
        })
        put("component_role", when (item) {
            is IngestionNutrition.MealComponentEstimate -> JsonPrimitive(item.componentRole)
            else -> JsonNull
        })
        put("payload", when (item) {
            is IngestionNutrition.ProductLabel -> json.parseToJsonElement(NutritionLabelJson.encode(
                (if (includeAuthorityFields) item.draft else item.draft.copy(
                    status = com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus.PARSED,
                    confirmedAt = null,
                )).let { draft -> if (canonicalIds) draft.copy(documentId = "__nutrition__") else draft },
            ))
            else -> JsonNull
        })
        put("estimate", when (item) {
            is IngestionNutrition.ProductLabel -> JsonNull
            is IngestionNutrition.RestaurantEstimate -> estimateJson(item.estimate)
            is IngestionNutrition.RestaurantMenuEstimate -> estimateJson(item.estimate)
            is IngestionNutrition.MealComponentEstimate -> estimateJson(item.estimate)
        })
        put("price_observation_client_key", when (item) {
            is IngestionNutrition.RestaurantMenuEstimate -> JsonPrimitive(item.priceObservationClientKey)
            else -> JsonNull
        })
        put("product_client_key", when (item) {
            is IngestionNutrition.ProductLabel -> item.productClientKey?.let(::JsonPrimitive) ?: JsonNull
            else -> JsonNull
        })
    }

    private fun nutritionKind(item: IngestionNutrition): String = when (item) {
        is IngestionNutrition.ProductLabel -> "product_label"
        is IngestionNutrition.RestaurantEstimate -> "restaurant_estimate"
        is IngestionNutrition.RestaurantMenuEstimate -> "restaurant_menu_estimate"
        is IngestionNutrition.MealComponentEstimate -> "meal_component_estimate"
    }

    private fun estimateJson(estimate: RestaurantNutritionEstimate): JsonObject = buildJsonObject {
        require(estimate.estimated) { "v3 nutrition estimates must be marked estimated" }
        val confidence = estimate.confidenceScore ?: estimate.confidence.toDoubleOrNull()
        require(confidence != null && confidence.isFinite() && confidence in 0.0..1.0) {
            "v3 nutrition estimate confidence must be numeric and between 0 and 1"
        }
        put("estimated", JsonPrimitive(true))
        put("confidence", JsonPrimitive(confidence))
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
        put("provenance", JsonObject(estimate.nutrientProvenance.map { (field, provenance) ->
            field.wireKey to buildJsonObject {
                put("value_status", JsonPrimitive(provenance.valueStatus))
                put("source_type", JsonPrimitive(provenance.sourceType))
                put("evidence_refs", JsonArray(provenance.evidenceRefs.map(::JsonPrimitive)))
            }
        }.toMap()))
    }

    private fun decodeProductCandidate(
        element: JsonElement,
        sourceUserText: String?,
    ): ProductCandidate {
        val root = element.jsonObject
        // merchant_sku is an optional source fact: producers may omit it when it was
        // not observed; the canonical encoder writes explicit null for stable output.
        requireKeysAllowingOptional(
            root,
            PRODUCT_CANDIDATE_KEYS - setOf("merchant_sku", "evidence"),
            setOf("merchant_sku", "evidence"),
        )
        val sourceAttachmentIds = root.arrayValue("source_attachment_ids").strings()
        val barcodes = root.arrayValue("barcodes").map { barcodeElement ->
            val barcode = barcodeElement.jsonObject
            requireKeys(barcode, BARCODE_KEYS)
            ProductCandidateBarcode(type = barcode.string("scheme"), value = barcode.string("value"))
        }
        val productName = root.string("product_name")
        val merchantSku = root.nullableString("merchant_sku")
        val brand = root.nullableString("brand_name")
        val subBrand = root.nullableString("sub_brand_name")
        val manufacturer = root.nullableString("manufacturer_name")
        val variant = root.nullableString("variant_name")
        val specification = root.nullableString("specification_text")
        val evidence = root["evidence"]?.let {
            decodeProductEvidence(it, sourceAttachmentIds, sourceUserText)
        } ?: if (sourceAttachmentIds.isNotEmpty()) {
            productFacts(
                sourceAttachmentIds = sourceAttachmentIds,
                sourceType = "product_photo",
                sourceRef = sourceAttachmentIds.first(),
                productName = productName,
                merchantSku = merchantSku,
                brand = brand,
                subBrand = subBrand,
                manufacturer = manufacturer,
                variant = variant,
                specification = specification,
                barcodes = barcodes,
            )
        } else {
            productFacts(
                sourceAttachmentIds = emptyList(),
                sourceType = "user_statement",
                sourceRef = userStatementSourceRef(sourceUserText)
                    ?: error("product candidate evidence requires source_attachment_ids or source.user_text"),
                productName = productName,
                merchantSku = merchantSku,
                brand = brand,
                subBrand = subBrand,
                manufacturer = manufacturer,
                variant = variant,
                specification = specification,
                barcodes = barcodes,
            )
        }
        return ProductCandidate(
            clientKey = root.string("client_key"), productName = productName, brand = brand,
            subBrand = subBrand, manufacturer = manufacturer, specification = specification,
            merchantSku = merchantSku,
            contentAmount = root.nullableNumber("content_amount"),
            contentUnit = root.nullableString("content_unit"),
            packageCount = root.nullableNumber("package_count")?.toPositiveInt("package_count"),
            variant = variant, barcodes = barcodes,
            evidence = evidence,
            sourceAttachmentIds = sourceAttachmentIds, confidence = root.number("confidence"),
        )
    }

    private fun decodeProductEvidence(
        element: JsonElement,
        sourceAttachmentIds: List<String>,
        sourceUserText: String?,
    ): List<ProductCandidateEvidence> = element.jsonArray.map { evidenceElement ->
        val root = evidenceElement.jsonObject
        requireKeysAllowingOptional(
            root,
            PRODUCT_EVIDENCE_KEYS - setOf("source_ref", "content_hash"),
            setOf("source_ref", "content_hash"),
        )
        val sourceType = root.string("source_type")
        val sourceRef = root.nullableString("source_ref")
            ?: resolveProductEvidenceSourceRef(sourceType, sourceAttachmentIds, sourceUserText)
            ?: error("product candidate evidence source_ref or source.user_text is required")
        ProductCandidateEvidence(
            sourceAttachmentIds = sourceAttachmentIds,
            sourceType = sourceType,
            sourceRef = sourceRef,
            field = root.string("field"),
            observedValue = root.nullableString("observed_value"),
            contentHash = root.nullableString("content_hash"),
        )
    }

    private fun resolveProductEvidenceSourceRef(
        sourceType: String,
        sourceAttachmentIds: List<String>,
        sourceUserText: String?,
    ): String? = when {
        sourceType == "user_statement" -> userStatementSourceRef(sourceUserText)
        sourceAttachmentIds.isNotEmpty() -> sourceAttachmentIds.first()
        else -> null
    }

    private fun productFacts(
        sourceAttachmentIds: List<String>,
        sourceType: String,
        sourceRef: String,
        productName: String,
        merchantSku: String?,
        brand: String?,
        subBrand: String?,
        manufacturer: String?,
        variant: String?,
        specification: String?,
        barcodes: List<ProductCandidateBarcode>,
    ): List<ProductCandidateEvidence> = buildList {
        add("product_name" to productName)
        merchantSku?.let { add("merchant_sku" to it) }
        brand?.let { add("brand_name" to it) }
        subBrand?.let { add("sub_brand_name" to it) }
        manufacturer?.let { add("manufacturer_name" to it) }
        variant?.let { add("variant_name" to it) }
        specification?.let { add("specification_text" to it) }
        if (barcodes.isNotEmpty()) add("barcodes" to barcodes.joinToString(",") { "${it.scheme}:${it.value}" })
    }.map { (field, observedValue) -> ProductCandidateEvidence(
        sourceAttachmentIds = sourceAttachmentIds,
        sourceType = sourceType,
        sourceRef = sourceRef,
        field = field,
        observedValue = observedValue,
    ) }

    private fun userStatementSourceRef(sourceUserText: String?): String? =
        sourceUserText?.trim()?.takeIf(String::isNotBlank)?.let {
            "user-statement:sha256:" + StableIds.sha256(it)
        }

    private fun decodePriceObservation(
        element: JsonElement,
    ): StandalonePriceObservation {
        val root = element.jsonObject
        requireKeys(root, PRICE_OBSERVATION_KEYS)
        val quantity = root["quantity"]?.takeUnless { it == JsonNull }?.let { quantityElement ->
            val quantityRoot = quantityElement.jsonObject
            requireKeys(quantityRoot, PRICE_QUANTITY_KEYS)
            StandalonePriceObservationQuantity(
                value = quantityRoot.number("value"),
                unit = quantityRoot.string("unit"),
            )
        }
        val sourceAttachmentIds = root.arrayValue("source_attachment_ids").strings()
        return StandalonePriceObservation(
            clientKey = root.string("client_key"),
            kind = StandalonePriceObservationKind.fromWireValue(root.string("kind")),
            productClientKey = root.nullableString("product_client_key"), itemName = root.nullableString("item_name"),
            observedOn = root.nullableString("observed_on"), observedAt = root.nullableString("observed_at"),
            currency = root.string("currency"),
            quantity = quantity,
            unitPriceAmountMinor = root.nullableLong("unit_price_amount_minor"),
            grossAmountMinor = root.nullableLong("gross_amount_minor"),
            discountAmountMinor = root.nullableLong("discount_amount_minor"),
            netAmountMinor = root.nullableLong("net_amount_minor"),
            sourceAttachmentIds = sourceAttachmentIds,
            evidence = root.arrayValue("evidence").map { evidenceElement ->
                decodePriceObservationEvidence(evidenceElement)
            },
            confidence = root.number("confidence"),
        )
    }

    private fun decodePriceObservationEvidence(
        element: JsonElement,
    ): StandalonePriceObservationEvidence {
        val root = element.jsonObject
        requireKeys(root, PRICE_OBSERVATION_EVIDENCE_KEYS)
        return StandalonePriceObservationEvidence(
            sourceType = root.string("source_type"),
            sourceAttachmentIds = root.arrayValue("source_attachment_ids").strings(),
            field = root.string("field"),
            observedValue = root.nullableString("observed_value"),
        )
    }

    private fun decodeNutrition(
        element: JsonElement, localDocumentId: String, preservePersistedVerification: Boolean,
    ): IngestionNutrition {
        val root = element.jsonObject
        requireKeys(root, NUTRITION_KEYS)
        val clientKey = root.string("client_key")
        val kind = root.string("kind")
        val lineId = root.nullableString("line_id")
        val componentRole = root.nullableString("component_role")
        val productClientKey = root.nullableString("product_client_key")
        return when (kind) {
            "product_label" -> {
                require(lineId == null && root["menu_name"] == JsonNull && root["estimate"] == JsonNull)
                require(componentRole == null && root["price_observation_client_key"] == JsonNull)
                val payload = root["payload"]?.takeUnless { it == JsonNull } ?: error("product_label payload required")
                val encoded = json.encodeToString(JsonElement.serializer(), payload)
                val draft = if (preservePersistedVerification) NutritionLabelJson.decode(encoded) else {
                    val imported = ExternalJsonImporter().import(encoded, "envelope-$clientKey", OcrWorkflowType.FITNESS_NUTRITION)
                    val result = imported as? ExternalJsonImportOutcome.Success
                        ?: error("product_label payload must be fitness-nutrition-draft.v1")
                    (result.result.draft as CanonicalDraft.Nutrition).value
                }
                IngestionNutrition.ProductLabel(clientKey, draft, productClientKey = productClientKey)
            }
            "restaurant_estimate" -> {
                require(lineId != null && root["menu_name"] != JsonNull && root["estimate"] != JsonNull)
                require(
                    componentRole == null && root["payload"] == JsonNull &&
                        root["price_observation_client_key"] == JsonNull && root["product_client_key"] == JsonNull,
                )
                IngestionNutrition.RestaurantEstimate(clientKey, lineId, root.string("menu_name"), decodeEstimate(root.objectValue("estimate")))
            }
            "restaurant_menu_estimate" -> {
                require(lineId == null && root["menu_name"] != JsonNull && root["estimate"] != JsonNull)
                require(
                    componentRole == null && root["payload"] == JsonNull && root["product_client_key"] == JsonNull,
                )
                IngestionNutrition.RestaurantMenuEstimate(
                    clientKey = clientKey, menuName = root.string("menu_name"),
                    priceObservationClientKey = root.string("price_observation_client_key"),
                    estimate = decodeEstimate(root.objectValue("estimate")),
                )
            }
            "meal_component_estimate" -> {
                require(root["menu_name"] != JsonNull && root["estimate"] != JsonNull && componentRole == "complimentary_side")
                require(
                    root["payload"] == JsonNull && root["price_observation_client_key"] == JsonNull &&
                        root["product_client_key"] == JsonNull,
                )
                IngestionNutrition.MealComponentEstimate(
                    clientKey = clientKey, lineId = lineId, menuName = root.string("menu_name"),
                    estimate = decodeEstimate(root.objectValue("estimate"), requireFoodImageProvenance = true),
                    componentRole = componentRole,
                )
            }
            else -> error("Unsupported v3 nutrition kind: $kind")
        }
    }

    private fun decodeEstimate(root: JsonObject, requireFoodImageProvenance: Boolean = false): RestaurantNutritionEstimate {
        requireKeys(root, ESTIMATE_KEYS)
        require(root.boolean("estimated"))
        val confidence = root.number("confidence")
        val nutrientsRoot = root.objectValue("nutrients")
        require(nutrientsRoot.keys == NutritionField.entries.map { it.wireKey }.toSet())
        val nutrients = NutritionField.entries.associateWith { field -> nutrientsRoot.nullableNumber(field.wireKey) }
        NutritionField.requiredFields.forEach { field -> require(nutrients[field] != null) { "missing_estimate_${field.wireKey}" } }
        val ranges = root.objectValue("ranges").map { (key, value) ->
            val field = NutritionField.fromWireKey(key) ?: error("Unsupported nutrition range: $key")
            val range = value.jsonObject
            requireKeys(range, RANGE_KEYS)
            field to NutritionRange(range.nullableNumber("min"), range.nullableNumber("point"), range.nullableNumber("max"))
        }.toMap()
        val provenance = root.objectValue("provenance").map { (key, value) ->
            val field = NutritionField.fromWireKey(key) ?: error("Unsupported nutrition provenance: $key")
            val item = value.jsonObject
            requireKeys(item, PROVENANCE_KEYS)
            val decoded = NutritionNutrientProvenance(
                valueStatus = item.string("value_status"), sourceType = item.string("source_type"),
                evidenceRefs = item.arrayValue("evidence_refs").strings(),
            )
            require(decoded.evidenceRefs.isNotEmpty())
            if (requireFoodImageProvenance) require(decoded.sourceType == "food_image_estimate")
            field to decoded
        }.toMap()
        NutritionField.requiredFields.forEach { require(it in provenance) { "missing_provenance_${it.wireKey}" } }
        return RestaurantNutritionEstimate(
            nutrients = nutrients, estimated = true, confidence = confidence.toString(), confidenceScore = confidence,
            ranges = ranges, nutrientProvenance = provenance,
        )
    }

    private fun decodeConsumption(root: JsonObject, preservePersistedVerification: Boolean): IngestionConsumption {
        requireKeys(root, CONSUMPTION_KEYS)
        val items = root.arrayValue("items").map { element ->
            val item = element.jsonObject
            requireKeys(item, CONSUMPTION_ITEM_KEYS)
            IngestionConsumptionItem(
                nutritionClientKey = item.string("nutrition_client_key"), amount = item.nullableNumber("amount"),
                unit = item.nullableString("unit"), confidence = item.number("confidence"),
                amountStatus = item.string("amount_status"),
            )
        }
        val declaredStatus = ConsumptionVerificationStatus.fromWireValue(root.string("status"))
        return IngestionConsumption(
            clientKey = root.string("client_key"), consumedAt = root.nullableString("consumed_at"),
            status = if (preservePersistedVerification) declaredStatus else ConsumptionVerificationStatus.UNVERIFIED,
            items = items,
        )
    }

    private fun decodeSource(root: JsonObject): IngestionSource {
        requireKeys(root, SOURCE_KEYS)
        val producer = root.string("producer")
        require(producer == "chatgpt" || producer == "ocr_app") { "unsupported envelope producer" }
        val files = root.arrayValue("source_files").map { element ->
            val item = element.jsonObject
            requireKeys(item, SOURCE_FILE_KEYS)
            SourceAttachment(item.string("id"), SourceAttachmentType.fromWireValue(item.string("type")), item.nullableString("label"))
        }
        require(files.map { it.id }.distinct().size == files.size)
        return IngestionSource(producer, files, root.nullableString("user_text"))
    }

    private fun decodeMerchant(root: JsonObject): MerchantCandidate {
        requireKeys(root, MERCHANT_KEYS)
        return MerchantCandidate(
            name = root.string("name"),
            businessKind = BusinessKind.entries.first { it.wireValue == root.string("business_kind") },
            branchName = root.nullableString("branch_name"), address = root.nullableString("address"),
            phone = root.nullableString("phone"), businessRegistrationNumber = root.nullableString("business_registration_number"),
            sourceAttachmentIds = root.arrayValue("source_attachment_ids").strings(),
            sourceNamespace = root.nullableString("source_namespace"), sourceLocationCode = root.nullableString("source_location_code"),
        )
    }

    private fun decodeLink(root: JsonObject): IngestionLink {
        requireKeys(root, LINK_KEYS)
        return IngestionLink(root.string("receipt_line_id"), root.string("nutrition_client_key"))
    }

    private fun decodeHints(root: JsonObject): Map<String, String?> {
        require(root.keys subtract setOf("cashos") == emptySet<String>())
        val cashos = root.objectValue("cashos")
        require(cashos.keys subtract CASHOS_HINT_KEYS == emptySet<String>())
        return cashos.mapKeys { "cashos.${it.key}" }
            .mapValues { (_, value) -> value.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull }
    }

    private fun decodeReview(root: JsonObject, preservePersistedVerification: Boolean): IngestionReview {
        require(root.keys == REVIEW_KEYS || root.keys == REVIEW_KEYS_WITHOUT_AUTHORITY_FIELDS)
        val declaredStatus = IngestionReviewStatus.entries.firstOrNull { it.wireValue == root.string("status") }
            ?: error("Unsupported v3 review status")
        val declaredBasis = root["verification_basis"]?.takeUnless { it == JsonNull }
            ?.let { value ->
                require(value is JsonPrimitive && value.isString) { "verification_basis must be a string" }
                VerificationBasis.fromWireValue(value.content)
            }
        root["user_verified"]?.let { value ->
            require(value is JsonPrimitive && !value.isString && value.content.toBooleanStrictOrNull() != null) {
                "user_verified must be boolean"
            }
        }
        // Authority fields are parsed for shape only. External JSON cannot authorize a projection.
        return IngestionReview(
            status = if (preservePersistedVerification) declaredStatus else IngestionReviewStatus.NEEDS_REVIEW,
            blockingIssues = if (preservePersistedVerification) root.arrayValue("blocking_issues").strings()
            else emptyList(),
            warnings = root.arrayValue("warnings").strings(),
            verificationBasis = if (preservePersistedVerification) {
                declaredBasis ?: VerificationBasis.SOURCE_EVIDENCE
            } else VerificationBasis.SOURCE_EVIDENCE,
        )
    }

    private fun validateEnvelope(
        mode: IngestionMode,
        source: IngestionSource,
        merchant: MerchantCandidate?,
        receipt: com.pricetrace.receiptscanner.domain.ReceiptV2?,
        productCandidates: List<ProductCandidate>,
        priceObservations: List<StandalonePriceObservation>,
        nutrition: List<IngestionNutrition>,
        consumption: List<IngestionConsumption>,
        links: List<IngestionLink>,
    ) {
        val sourceUserText = source.userText?.takeIf(String::isNotBlank)
        require(productCandidates.map { it.clientKey }.distinct().size == productCandidates.size)
        productCandidates.forEach { candidate ->
            // Attachment ids are producer facts. Their local readability/type is evaluated by
            // IngestionEvidenceGate, so a manual review can proceed when the original image is absent.
            require(candidate.effectiveSourceAttachmentIds.all(String::isNotBlank)) {
                "product candidate evidence must reference non-empty source attachments"
            }
            candidate.evidence.forEach { evidence ->
                if (evidence.sourceType == "user_statement") {
                    require(!evidence.sourceRef.isNullOrBlank() || sourceUserText != null) {
                        "user_statement evidence requires source.user_text or source_ref"
                    }
                }
            }
        }
        require(priceObservations.map { it.clientKey }.distinct().size == priceObservations.size)
        require(receipt == null || priceObservations.isEmpty()) {
            "v3 standalone price observations cannot be combined with receipt"
        }
        if (priceObservations.isNotEmpty()) {
            require(merchant != null) { "standalone price observations require merchant_candidate" }
            priceObservations.filter { it.kind == StandalonePriceObservationKind.RETAIL_PURCHASE }.forEach { observation ->
                require(productCandidates.any { it.clientKey == observation.productClientKey }) {
                    "retail price observation must reference a product candidate"
                }
            }
        }
        val productKeys = productCandidates.map { it.clientKey }.toSet()
        nutrition.filterIsInstance<IngestionNutrition.ProductLabel>().forEach { item ->
            if (item.productClientKey != null) {
                require(item.productClientKey in productKeys) {
                    "product label must reference an existing product candidate"
                }
            }
        }
        if (productCandidates.isNotEmpty() && nutrition.any { it is IngestionNutrition.ProductLabel }) {
            require(nutrition.filterIsInstance<IngestionNutrition.ProductLabel>().all {
                it.productClientKey != null && it.productClientKey in productKeys
            }) {
                "packaged product labels must declare product_client_key when product candidates are present"
            }
        }
        require(nutrition.map { it.clientKey }.distinct().size == nutrition.size)
        require(consumption.map { it.clientKey }.distinct().size == consumption.size)
        require(consumption.all { item ->
            item.items.isNotEmpty() && item.effectiveNutritionClientKeys.all { key -> nutrition.any { it.clientKey == key } }
        }) { "consumption must reference existing nutrition artifacts" }
        require(links.map { it.nutritionClientKey }.distinct().size == links.size)
        require(links.all { link ->
            val line = receipt?.lineItems?.singleOrNull { it.id == link.receiptLineId }
            val item = nutrition.singleOrNull { it.clientKey == link.nutritionClientKey }
            line != null && item?.lineId == line.id && item !is IngestionNutrition.MealComponentEstimate
        }) { "links must reference a receipt line and a non-component nutrition artifact" }
        nutrition.filterIsInstance<IngestionNutrition.RestaurantEstimate>().forEach { item ->
            require(receipt != null && links.any { it.nutritionClientKey == item.clientKey && it.receiptLineId == item.lineId }) {
                "restaurant estimates must retain their receipt line link"
            }
        }
        nutrition.filterIsInstance<IngestionNutrition.RestaurantMenuEstimate>().forEach { item ->
            require(receipt == null) { "restaurant menu estimates are standalone when no receipt exists" }
            val observation = priceObservations.singleOrNull { it.clientKey == item.priceObservationClientKey }
            require(observation?.kind == StandalonePriceObservationKind.RESTAURANT_PURCHASE) {
                "restaurant menu estimate must link a restaurant price observation"
            }
        }
        nutrition.filterIsInstance<IngestionNutrition.MealComponentEstimate>().forEach { item ->
            if (item.lineId != null) require(receipt?.lineItems?.any { it.id == item.lineId } == true)
        }
        when (mode) {
            IngestionMode.MERCHANT -> require(
                merchant != null && receipt == null && priceObservations.isEmpty() && nutrition.isEmpty() &&
                    productCandidates.isEmpty() && consumption.isEmpty() && links.isEmpty(),
            )
            IngestionMode.RESTAURANT -> require(
                (merchant != null || receipt != null) &&
                    receipt?.merchant?.businessKind?.let { it == BusinessKind.FOOD_SERVICE } != false &&
                    productCandidates.isEmpty() && nutrition.all {
                        it is IngestionNutrition.RestaurantEstimate ||
                            it is IngestionNutrition.RestaurantMenuEstimate ||
                            it is IngestionNutrition.MealComponentEstimate
                    } && priceObservations.all { it.kind == StandalonePriceObservationKind.RESTAURANT_PURCHASE },
            )
            IngestionMode.PACKAGED_PRODUCT -> require(
                receipt?.merchant?.businessKind != BusinessKind.FOOD_SERVICE &&
                    nutrition.all { it is IngestionNutrition.ProductLabel } &&
                    priceObservations.all { it.kind == StandalonePriceObservationKind.RETAIL_PURCHASE } &&
                    (nutrition.isNotEmpty() || productCandidates.isNotEmpty() || priceObservations.isNotEmpty()) &&
                    links.isEmpty(),
            )
            IngestionMode.PURCHASE -> error("purchase mode requires yeonsik-ocr.v4")
        }
    }

    private fun sourceJson(source: IngestionSource) = buildJsonObject {
        put("producer", JsonPrimitive(source.producer))
        put("source_files", JsonArray(source.sourceFiles.map {
            buildJsonObject {
                put("id", JsonPrimitive(it.id)); put("type", JsonPrimitive(it.type.wireValue))
                put("label", it.label?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("user_text", source.userText?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun merchantJson(value: MerchantCandidate) = buildJsonObject {
        put("name", JsonPrimitive(value.name)); put("business_kind", JsonPrimitive(value.businessKind.wireValue))
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

    private fun consumptionJson(value: IngestionConsumption, includeAuthorityFields: Boolean) = buildJsonObject {
        put("client_key", JsonPrimitive(value.clientKey))
        put("consumed_at", value.consumedAt?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "status",
            JsonPrimitive(
                if (includeAuthorityFields) value.status.wireValue
                else ConsumptionVerificationStatus.UNVERIFIED.wireValue,
            ),
        )
        put("items", JsonArray(value.items.map { item -> buildJsonObject {
            put("nutrition_client_key", JsonPrimitive(item.nutritionClientKey))
            put("amount", item.amount?.let(::JsonPrimitive) ?: JsonNull)
            put("unit", item.unit?.let(::JsonPrimitive) ?: JsonNull)
            put("confidence", JsonPrimitive(item.confidence)); put("amount_status", JsonPrimitive(item.amountStatus))
        }}))
    }

    private fun classificationHintsJson(hints: Map<String, String?>): JsonObject = buildJsonObject {
        put("cashos", JsonObject(
            hints.filterKeys { it.startsWith("cashos.") && it.removePrefix("cashos.") in CASHOS_HINT_KEYS }
                .mapKeys { (key, _) -> key.removePrefix("cashos.") }
                .mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull },
        ))
    }

    private fun reviewJson(
        value: IngestionReview,
        includeAuthorityFields: Boolean,
        forFingerprint: Boolean,
    ) = buildJsonObject {
        put(
            "status",
            JsonPrimitive(
                if (includeAuthorityFields) value.status.wireValue
                else IngestionReviewStatus.NEEDS_REVIEW.wireValue,
            ),
        )
        put(
            "blocking_issues",
            JsonArray(
                if (forFingerprint) emptyList()
                else value.blockingIssues.map(::JsonPrimitive),
            ),
        )
        put(
            "warnings",
            JsonArray(
                if (forFingerprint) emptyList()
                else value.warnings.map(::JsonPrimitive),
            ),
        )
        if (includeAuthorityFields) {
            put("verification_basis", JsonPrimitive(value.verificationBasis.wireValue))
            put("user_verified", JsonPrimitive(value.status == IngestionReviewStatus.READY))
        }
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

    private fun requireKeysAllowingOptional(
        root: JsonObject,
        required: Set<String>,
        optional: Set<String>,
    ) {
        val allowed = required + optional
        require(root.keys subtract allowed == emptySet<String>() && required subtract root.keys == emptySet<String>()) {
            "Unexpected or missing JSON keys: ${root.keys subtract allowed} / ${required subtract root.keys}"
        }
    }

    private fun Double.toPositiveInt(key: String): Int {
        require(isFinite() && this % 1.0 == 0.0 && this > 0 && this <= Int.MAX_VALUE.toDouble())
        return toInt()
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("$key must be a non-empty string")

    private fun JsonObject.nullableString(key: String): String? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: error("$key must be a string or null") }

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()
            ?: error("$key must be boolean")

    private fun JsonObject.number(key: String): Double =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf(Double::isFinite)
            ?: error("$key must be a finite number")

    private fun JsonObject.long(key: String): Long {
        val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }
            ?: error("$key must be an integer")
        return primitive.content.toLongOrNull() ?: error("$key must be an integer")
    }

    private fun JsonObject.nullableLong(key: String): Long? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let { (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.content?.toLongOrNull()
            ?: error("$key must be an integer or null") }

    private fun JsonObject.nullableNumber(key: String): Double? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let { (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.doubleOrNull
            ?.takeIf(Double::isFinite) ?: error("$key must be a finite number or null") }

    private fun JsonObject.objectValue(key: String): JsonObject = this[key]?.jsonObject
        ?: error("$key must be an object")

    private fun JsonObject.arrayValue(key: String): JsonArray = this[key]?.jsonArray
        ?: error("$key must be an array")

    private fun JsonArray.strings(): List<String> = map {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("array must contain non-empty strings")
    }

    private val TOP_LEVEL_KEYS = setOf(
        "schema_version", "mode", "source", "merchant_candidate", "receipt", "product_candidates",
        "price_observations", "nutrition", "consumption", "classification_hints", "links",
        "projection_targets", "review",
    )
    private val SOURCE_KEYS = setOf("producer", "source_files", "user_text")
    private val SOURCE_FILE_KEYS = setOf("id", "type", "label")
    private val MERCHANT_KEYS = setOf(
        "name", "business_kind", "branch_name", "address", "phone", "business_registration_number",
        "source_attachment_ids", "source_namespace", "source_location_code",
    )
    private val PRODUCT_CANDIDATE_KEYS = setOf(
        "client_key", "product_name", "merchant_sku", "brand_name", "sub_brand_name", "manufacturer_name", "variant_name",
        "specification_text", "content_amount", "content_unit", "package_count", "barcodes",
        "source_attachment_ids", "evidence", "confidence",
    )
    private val PRODUCT_EVIDENCE_KEYS = setOf(
        "source_type", "source_ref", "field", "observed_value", "content_hash",
    )
    private val BARCODE_KEYS = setOf("scheme", "value")
    private val PRICE_OBSERVATION_KEYS = setOf(
        "client_key", "kind", "product_client_key", "item_name", "observed_on", "observed_at", "quantity",
        "currency", "unit_price_amount_minor", "gross_amount_minor", "discount_amount_minor",
        "net_amount_minor", "source_attachment_ids", "evidence", "confidence",
    )
    private val PRICE_QUANTITY_KEYS = setOf("value", "unit")
    private val PRICE_OBSERVATION_EVIDENCE_KEYS = setOf(
        "source_type", "source_attachment_ids", "field", "observed_value",
    )
    private val NUTRITION_KEYS = setOf(
        "client_key", "kind", "line_id", "menu_name", "component_role", "payload", "estimate",
        "price_observation_client_key", "product_client_key",
    )
    private val CONSUMPTION_KEYS = setOf("client_key", "consumed_at", "items", "status")
    private val CONSUMPTION_ITEM_KEYS = setOf("nutrition_client_key", "amount", "unit", "confidence", "amount_status")
    private val LINK_KEYS = setOf("receipt_line_id", "nutrition_client_key")
    private val ESTIMATE_KEYS = setOf("estimated", "confidence", "nutrients", "ranges", "provenance")
    private val RANGE_KEYS = setOf("min", "point", "max")
    private val PROVENANCE_KEYS = setOf("value_status", "source_type", "evidence_refs")
    private val REVIEW_KEYS = setOf("status", "blocking_issues", "warnings", "verification_basis", "user_verified")
    private val REVIEW_KEYS_WITHOUT_AUTHORITY_FIELDS = setOf("status", "blocking_issues", "warnings")
    private val CASHOS_HINT_KEYS = setOf("category_hint", "institution_hint", "payment_method_hint")
}
