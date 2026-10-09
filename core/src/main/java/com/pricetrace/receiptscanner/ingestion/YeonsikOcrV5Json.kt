package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.nutrition.NutritionField
import kotlinx.serialization.json.*

/** Additive composition of V4 purchase facts and V2 food estimates. ZIP and sink contracts stay unchanged. */
object YeonsikOcrV5Json {
    private val json = Json { prettyPrint = true; explicitNulls = true }
    private val topKeys = setOf(
        "schema_version", "mode", "source", "merchant_candidate", "receipt", "product_candidates",
        "price_observations", "purchase_records", "nutrition", "consumption", "classification_hints",
        "links", "projection_targets", "review",
    )
    private val linkKeys = setOf("purchase_record_client_key", "purchase_line_key", "nutrition_client_key")
    private val sourceTypes = setOf(SourceAttachmentType.ORDER_HISTORY, SourceAttachmentType.PAYMENT_HISTORY, SourceAttachmentType.FOOD_PHOTO)

    fun decode(value: String, localDocumentId: String, preservePersistedVerification: Boolean = false): YeonsikOcrEnvelope {
        require(localDocumentId.isNotBlank())
        val root = json.parseToJsonElement(value).jsonObject
        require(root.keys == topKeys) { "Unexpected or missing v5 envelope keys" }
        require(root.string("schema_version") == YEONSIK_OCR_V5_SCHEMA)
        require(root.string("mode") == IngestionMode.RESTAURANT_PURCHASE.wireValue)
        require(root["merchant_candidate"] == JsonNull && root["receipt"] == JsonNull)
        require(root.getValue("product_candidates").jsonArray.isEmpty())
        require(root.getValue("price_observations").jsonArray.isEmpty())
        val records = root.getValue("purchase_records").jsonArray.map { YeonsikOcrV4Json.decodePurchaseRecord(it.jsonObject) }
        return YeonsikOcrEnvelope(
            schemaVersion = YEONSIK_OCR_V5_SCHEMA,
            mode = IngestionMode.RESTAURANT_PURCHASE,
            source = YeonsikOcrV2Json.decodeSource(root.getValue("source").jsonObject),
            purchaseRecords = records,
            nutrition = root.getValue("nutrition").jsonArray.map { YeonsikOcrV2Json.decodeNutrition(it, preservePersistedVerification) },
            consumption = root.getValue("consumption").jsonArray.map { YeonsikOcrV2Json.decodeConsumption(it.jsonObject, preservePersistedVerification) },
            classificationHints = YeonsikOcrV2Json.decodeHints(root.getValue("classification_hints").jsonObject),
            purchaseNutritionLinks = root.getValue("links").jsonArray.map {
                val link = it.jsonObject
                require(link.keys == linkKeys) { "Unexpected or missing v5 link keys" }
                PurchaseNutritionLink(link.string("purchase_record_client_key"), link.string("purchase_line_key"), link.string("nutrition_client_key"))
            },
            targets = root.getValue("projection_targets").jsonArray.map {
                require(it is JsonPrimitive && it.isString)
                IngestionProjection.fromWireValue(it.content)
            }.toSet(),
            review = YeonsikOcrV4Json.decodeReview(root.getValue("review").jsonObject, emptyList(), records, preservePersistedVerification),
        ).also(::validate)
    }

    fun validate(envelope: YeonsikOcrEnvelope) {
        require(envelope.schemaVersion == YEONSIK_OCR_V5_SCHEMA && envelope.mode == IngestionMode.RESTAURANT_PURCHASE)
        require(envelope.merchantCandidate == null && envelope.receipt == null)
        require(envelope.productCandidates.isEmpty() && envelope.priceObservations.isEmpty() && envelope.links.isEmpty())
        require(envelope.source.producer in setOf("chatgpt", "ocr_app"))
        val files = envelope.source.sourceFiles
        require(files.all { it.id.isNotBlank() && it.type in sourceTypes }) { "v5 source files must be order_history, payment_history or food_photo" }
        require(files.map { it.id }.distinct().size == files.size) { "source attachment IDs must be unique" }
        require(envelope.purchaseRecords.isNotEmpty()) { "v5 requires purchase records" }
        require(envelope.purchaseRecords.all { it.purchaseKind == PurchaseKind.RESTAURANT }) { "v5 only supports restaurant purchases" }
        YeonsikOcrV4Json.validate(purchaseEnvelope(envelope))
        require(envelope.nutrition.isNotEmpty()) { "v5 requires food estimates; purchase-only input uses v4" }
        require(envelope.nutrition.map { it.clientKey }.distinct().size == envelope.nutrition.size) { "nutrition keys must be unique" }
        envelope.nutrition.forEach { item ->
            require(item is IngestionNutrition.RestaurantEstimate && item.lineId == null && item.clientKey.isNotBlank() && item.menuName.isNotBlank()) {
                "v5 requires restaurant_estimate with null line_id"
            }
            // Reuse the V2 estimate field/provenance validator on both imports and local revisions.
            YeonsikOcrV2Json.decodeNutrition(YeonsikOcrV2Json.nutritionJson(item, false), true)
            require(NutritionField.requiredFields.all { field ->
                item.estimate.nutrientProvenance[field]?.sourceType == "food_image_estimate"
            }) { "v5 restaurant estimates require food_image_estimate provenance" }
            val refs = foodEvidenceIds(item)
            require(refs.isNotEmpty() && refs.all { id -> files.any { it.id == id && it.type == SourceAttachmentType.FOOD_PHOTO } }) {
                "v5 restaurant estimates must reference actual food_photo evidence"
            }
        }
        val links = envelope.purchaseNutritionLinks
        require(links.map { it.nutritionClientKey }.toSet() == envelope.nutrition.map { it.clientKey }.toSet()) { "every v5 nutrition requires an exact purchase line link" }
        require(links.map { it.nutritionClientKey }.distinct().size == links.size) { "duplicate or conflicting nutrition link" }
        require(links.map { it.purchaseRecordClientKey to it.purchaseLineKey }.distinct().size == links.size) { "duplicate or conflicting purchase line link" }
        links.forEach { link ->
            val record = envelope.purchaseRecords.singleOrNull { it.clientKey == link.purchaseRecordClientKey }
            require(record?.lineItems?.count { it.lineKey == link.purchaseLineKey } == 1) { "dangling or ambiguous purchase line link" }
        }
        require(envelope.consumption.map { it.clientKey }.distinct().size == envelope.consumption.size) { "consumption keys must be unique" }
        envelope.consumption.forEach { consumption ->
            YeonsikOcrV2Json.decodeConsumption(YeonsikOcrV2Json.consumptionJson(consumption), true)
            require(consumption.effectiveNutritionClientKeys.all { key -> envelope.nutrition.any { it.clientKey == key } }) { "dangling consumption nutrition reference" }
            require(!envelope.source.userText.isNullOrBlank()) { "purchase facts cannot create consumption; explicit source.user_text required" }
            require(consumption.consumedAt == null || envelope.source.userText.contains(consumption.consumedAt)) {
                "consumed_at must be explicitly present in source.user_text"
            }
        }
        require(envelope.targets.all { it in setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, IngestionProjection.CASHOS_TRANSACTION, IngestionProjection.FITNESS_NUTRITION, IngestionProjection.FITNESS_MEAL) }) { "incompatible v5 projection target" }
    }

    internal fun purchaseEnvelope(envelope: YeonsikOcrEnvelope): YeonsikOcrEnvelope = envelope.copy(
        schemaVersion = YEONSIK_OCR_V4_SCHEMA, mode = IngestionMode.PURCHASE,
        source = envelope.source.copy(sourceFiles = envelope.source.sourceFiles.filter { it.type != SourceAttachmentType.FOOD_PHOTO }),
        nutrition = emptyList(), consumption = emptyList(), purchaseNutritionLinks = emptyList(),
    )

    fun foodEvidenceIds(item: IngestionNutrition.RestaurantEstimate): Set<String> =
        item.estimate.nutrientProvenance.values.flatMap { it.evidenceRefs }.toSet()

    fun encodeDraft(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String = encode(envelope, canonicalIds, false, false)
    fun encodePersisted(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String = encode(envelope, canonicalIds, true, false)
    fun canonicalize(envelope: YeonsikOcrEnvelope): String = encode(envelope, true, false, true)

    private fun encode(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean, persisted: Boolean, fingerprint: Boolean): String {
        validate(envelope)
        val purchase = purchaseEnvelope(envelope)
        val base = json.parseToJsonElement(when {
            fingerprint -> YeonsikOcrV4Json.canonicalize(purchase)
            persisted -> YeonsikOcrV4Json.encodePersisted(purchase, canonicalIds)
            else -> YeonsikOcrV4Json.encodeDraft(purchase, canonicalIds)
        }).jsonObject
        val fields = base.toMutableMap()
        fields["schema_version"] = JsonPrimitive(YEONSIK_OCR_V5_SCHEMA)
        fields["mode"] = JsonPrimitive(IngestionMode.RESTAURANT_PURCHASE.wireValue)
        fields["source"] = buildJsonObject {
            put("producer", JsonPrimitive(envelope.source.producer))
            put("user_text", envelope.source.userText?.let(::JsonPrimitive) ?: JsonNull)
            put("source_files", JsonArray(envelope.source.sourceFiles.map { file -> buildJsonObject {
                put("id", JsonPrimitive(file.id)); put("type", JsonPrimitive(file.type.wireValue)); put("label", file.label?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
        fields["nutrition"] = JsonArray(envelope.nutrition.map { YeonsikOcrV2Json.nutritionJson(it, canonicalIds) })
        fields["consumption"] = JsonArray(envelope.consumption.map {
            YeonsikOcrV2Json.consumptionJson(if (persisted) it else it.copy(status = ConsumptionVerificationStatus.UNVERIFIED))
        })
        fields["links"] = JsonArray(envelope.purchaseNutritionLinks.map { link -> buildJsonObject {
            put("purchase_record_client_key", JsonPrimitive(link.purchaseRecordClientKey))
            put("purchase_line_key", JsonPrimitive(link.purchaseLineKey))
            put("nutrition_client_key", JsonPrimitive(link.nutritionClientKey))
        } })
        return json.encodeToString(JsonElement.serializer(), JsonObject(fields))
    }

    private fun JsonObject.string(key: String): String {
        val value = getValue(key)
        require(value is JsonPrimitive && value.isString) { "$key must be a string" }
        return value.content
    }
}
