package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.StableIds
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

/**
 * Strict purchase-evidence codec for yeonsik-ocr.v4.
 *
 * This codec is deliberately separate from v1-v3. It accepts only purchase mode and
 * does not let producer review fields grant verification authority.
 */
object YeonsikOcrV4Json {
    private val json = Json {
        prettyPrint = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun decode(
        value: String,
        localDocumentId: String,
        preservePersistedVerification: Boolean = false,
    ): YeonsikOcrEnvelope {
        require(localDocumentId.isNotBlank())
        val root = json.parseToJsonElement(value).jsonObject
        requireKeys(root, TOP_LEVEL_KEYS, "v4 envelope")
        require(root.string("schema_version") == YEONSIK_OCR_V4_SCHEMA) {
            "yeonsik-ocr.v4 schema_version is required"
        }
        require(root.string("mode") == IngestionMode.PURCHASE.wireValue) {
            "yeonsik-ocr.v4 requires purchase mode"
        }

        val source = decodeSource(root.objectValue("source"))
        require(root["merchant_candidate"] == JsonNull)
        require(root["receipt"] == JsonNull)
        val productCandidates = root.arrayValue("product_candidates").map {
            decodeProductCandidate(it, source)
        }
        require(root.arrayValue("price_observations").isEmpty())
        require(root.arrayValue("nutrition").isEmpty())
        require(root.arrayValue("consumption").isEmpty())
        require(root.arrayValue("links").isEmpty())

        val purchaseRecords = root.arrayValue("purchase_records").map {
            decodePurchaseRecord(it.jsonObject)
        }
        require(purchaseRecords.isNotEmpty()) {
            "yeonsik-ocr.v4 requires at least one purchase_record"
        }
        validatePurchaseEvidence(source, productCandidates, purchaseRecords)

        val targets = root.arrayValue("projection_targets").map {
            IngestionProjection.fromWireValue(it.jsonPrimitive.content)
        }.toSet()
        val hints = decodeHints(root.objectValue("classification_hints"))
        val review = decodeReview(
            root.objectValue("review"),
            productCandidates,
            purchaseRecords,
            preservePersistedVerification,
        )

        return YeonsikOcrEnvelope(
            mode = IngestionMode.PURCHASE,
            source = source,
            merchantCandidate = null,
            receipt = null,
            nutrition = emptyList(),
            consumption = emptyList(),
            classificationHints = hints,
            links = emptyList(),
            targets = targets,
            review = review,
            productCandidates = productCandidates,
            schemaVersion = YEONSIK_OCR_V4_SCHEMA,
            priceObservations = emptyList(),
            purchaseRecords = purchaseRecords,
        )
    }

    fun encode(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String =
        encodeDraft(envelope, canonicalIds)

    fun encodeDraft(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String {
        validate(envelope)
        return encodeJson(
            toJson(envelope, canonicalIds, includeAuthorityFields = false, forFingerprint = false),
        )
    }

    fun encodePersisted(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String {
        validate(envelope)
        return encodeJson(
            toJson(envelope, canonicalIds, includeAuthorityFields = true, forFingerprint = false),
        )
    }

    fun canonicalize(envelope: YeonsikOcrEnvelope): String {
        validate(envelope)
        return encodeJson(
            toJson(envelope, canonicalIds = true, includeAuthorityFields = false, forFingerprint = true),
        )
    }

    fun validate(envelope: YeonsikOcrEnvelope) {
        require(envelope.schemaVersion == YEONSIK_OCR_V4_SCHEMA) {
            "yeonsik-ocr.v4 validation requires a v4 envelope"
        }
        require(envelope.mode == IngestionMode.PURCHASE)
        require(envelope.merchantCandidate == null && envelope.receipt == null)
        require(envelope.productCandidates.map { it.clientKey }.distinct().size == envelope.productCandidates.size) {
            "v4 product candidate client_key values must be unique"
        }
        require(envelope.priceObservations.isEmpty())
        require(envelope.nutrition.isEmpty() && envelope.consumption.isEmpty())
        require(envelope.links.isEmpty())
        require(envelope.purchaseRecords.isNotEmpty())
        validatePurchaseEvidence(envelope.source, envelope.productCandidates, envelope.purchaseRecords)
    }

    /** Shared by the PriceTrace and CashOS V4 adapters. */
    fun encodePurchaseRecord(value: PurchaseRecord): JsonObject = purchaseRecordJson(value)

    private fun encodeJson(value: JsonElement): String =
        json.encodeToString(JsonElement.serializer(), value)

    private fun toJson(
        envelope: YeonsikOcrEnvelope,
        canonicalIds: Boolean,
        includeAuthorityFields: Boolean,
        forFingerprint: Boolean,
    ): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive(YEONSIK_OCR_V4_SCHEMA))
        put("mode", JsonPrimitive(IngestionMode.PURCHASE.wireValue))
        put("source", sourceJson(envelope.source))
        put("merchant_candidate", JsonNull)
        put("receipt", JsonNull)
        put("product_candidates", JsonArray(envelope.productCandidates.map {
            productCandidateJson(it, envelope.source.userText)
        }))
        put("price_observations", JsonArray(emptyList()))
        put("purchase_records", JsonArray(envelope.purchaseRecords.map(::purchaseRecordJson)))
        put("nutrition", JsonArray(emptyList()))
        put("consumption", JsonArray(emptyList()))
        put("classification_hints", classificationHintsJson(envelope.classificationHints))
        put("links", JsonArray(emptyList()))
        put(
            "projection_targets",
            JsonArray(
                envelope.targets.sortedBy(IngestionProjection::wireValue).map {
                    JsonPrimitive(it.wireValue)
                },
            ),
        )
        put(
            "review",
            reviewJson(
                envelope,
                includeAuthorityFields = includeAuthorityFields,
                forFingerprint = forFingerprint,
            ),
        )
    }

    private fun purchaseRecordJson(value: PurchaseRecord): JsonObject = buildJsonObject {
        put("client_key", JsonPrimitive(value.clientKey))
        put("platform", JsonPrimitive(value.platform))
        put("platform_code", value.platformCode?.let(::JsonPrimitive) ?: JsonNull)
        put("seller", value.seller?.let(::JsonPrimitive) ?: JsonNull)
        put("seller_branch_name", value.sellerBranchName?.let(::JsonPrimitive) ?: JsonNull)
        put("seller_source_namespace", value.sellerSourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
        put("seller_source_code", value.sellerSourceCode?.let(::JsonPrimitive) ?: JsonNull)
        put("seller_business_kind", value.sellerBusinessKind?.let(::JsonPrimitive) ?: JsonNull)
        put("source_version", value.sourceVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("order_reference", value.orderReference?.let(::JsonPrimitive) ?: JsonNull)
        put("purchase_kind", JsonPrimitive(value.purchaseKind.wireValue))
        put("ordered_on", value.orderedOn?.let(::JsonPrimitive) ?: JsonNull)
        put("ordered_at", value.orderedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("paid_on", value.paidOn?.let(::JsonPrimitive) ?: JsonNull)
        put("paid_at", value.paidAt?.let(::JsonPrimitive) ?: JsonNull)
        put("status", JsonPrimitive(value.status.wireValue))
        put("currency", JsonPrimitive(value.currency))
        put(
            "totals",
            buildJsonObject {
                put("subtotal_amount_krw", value.totals.subtotalAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("discount_amount_krw", value.totals.discountAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("shipping_amount_krw", value.totals.shippingAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("tax_amount_krw", value.totals.taxAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("grand_total_amount_krw", value.totals.grandTotalAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("paid_amount_krw", value.totals.paidAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
        put(
            "payment",
            value.payment?.let { payment ->
                buildJsonObject {
                    put("method", payment.method?.let(::JsonPrimitive) ?: JsonNull)
                    put("provider", payment.provider?.let(::JsonPrimitive) ?: JsonNull)
                    put("status", payment.status?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull,
        )
        put("line_items", JsonArray(value.lineItems.map { line ->
            buildJsonObject {
                put("line_key", line.lineKey?.let(::JsonPrimitive) ?: JsonNull)
                put("product_client_key", line.productClientKey?.let(::JsonPrimitive) ?: JsonNull)
                put("description", line.description?.let(::JsonPrimitive) ?: JsonNull)
                put("seller_override", line.sellerOverride?.let(::JsonPrimitive) ?: JsonNull)
                put("option_text", line.optionText?.let(::JsonPrimitive) ?: JsonNull)
                put("price_status", JsonPrimitive(line.priceStatus))
                put("merchant_sku", line.merchantSku?.let(::JsonPrimitive) ?: JsonNull)
                put("quantity", line.quantity?.let(::JsonPrimitive) ?: JsonNull)
                put("unit_price_amount_krw", line.unitPriceAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("gross_amount_krw", line.grossAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("discount_amount_krw", line.discountAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
                put("net_amount_krw", line.netAmountKrw?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("evidence", JsonArray(value.evidence.map { evidence ->
            buildJsonObject {
                put("source_type", JsonPrimitive(evidence.sourceType))
                put("source_attachment_ids", JsonArray(evidence.sourceAttachmentIds.map(::JsonPrimitive)))
                put("source_ref", evidence.sourceRef?.let(::JsonPrimitive) ?: JsonNull)
                put("field", JsonPrimitive(evidence.field))
                put("observed_value", evidence.observedValue?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("confidence", JsonPrimitive(value.confidence))
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
        put("barcodes", JsonArray(value.barcodes.map { barcode ->
            buildJsonObject {
                put("scheme", JsonPrimitive(barcode.scheme))
                put("value", JsonPrimitive(barcode.value))
            }
        }))
        put("source_attachment_ids", JsonArray(value.effectiveSourceAttachmentIds.map(::JsonPrimitive)))
        put("evidence", JsonArray(value.evidence.map { evidence ->
            productEvidenceJson(evidence, value.effectiveSourceAttachmentIds, sourceUserText)
        }))
        put("confidence", JsonPrimitive(value.confidence))
    }

    private fun productEvidenceJson(
        value: ProductCandidateEvidence,
        candidateSourceAttachmentIds: List<String>,
        sourceUserText: String?,
    ): JsonObject {
        val sourceRef = productEvidenceSourceRef(
            value = value,
            candidateSourceAttachmentIds = candidateSourceAttachmentIds,
            sourceUserText = sourceUserText,
        )
            ?: error("product candidate evidence source_ref is required")
        return buildJsonObject {
            put("source_type", JsonPrimitive(value.sourceType))
            put("source_ref", JsonPrimitive(sourceRef))
            put("field", JsonPrimitive(value.field))
            put("observed_value", value.observedValue?.let(::JsonPrimitive) ?: JsonNull)
            put("content_hash", value.contentHash?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun decodeProductCandidate(
        element: JsonElement,
        source: IngestionSource,
    ): ProductCandidate {
        val root = element.jsonObject
        requireKeysAllowingOptional(
            root,
            PRODUCT_CANDIDATE_KEYS - setOf("merchant_sku", "evidence"),
            setOf("merchant_sku", "evidence"),
            "product_candidate",
        )
        val sourceAttachmentIds = root.arrayValue("source_attachment_ids").strings()
        val barcodes = root.arrayValue("barcodes").map { barcodeElement ->
            val barcode = barcodeElement.jsonObject
            requireKeys(barcode, BARCODE_KEYS, "product_candidate.barcode")
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
            decodeProductEvidence(it, sourceAttachmentIds, source.userText)
        } ?: if (sourceAttachmentIds.isNotEmpty()) {
            val sourceType = source.sourceFiles
                .firstOrNull { it.id == sourceAttachmentIds.first() }
                ?.type?.wireValue
                ?: error("product candidate references an unknown source file")
            productFacts(
                sourceAttachmentIds = sourceAttachmentIds,
                sourceType = sourceType,
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
                sourceRef = userStatementSourceRef(source.userText)
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
            clientKey = root.string("client_key"),
            productName = productName,
            brand = brand,
            subBrand = subBrand,
            manufacturer = manufacturer,
            specification = specification,
            merchantSku = merchantSku,
            contentAmount = root.nullableNumber("content_amount"),
            contentUnit = root.nullableString("content_unit"),
            packageCount = root.nullableNumber("package_count")?.toPositiveInt("package_count"),
            variant = variant,
            barcodes = barcodes,
            evidence = evidence,
            sourceAttachmentIds = sourceAttachmentIds,
            confidence = root.number("confidence"),
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
            "product_candidate.evidence",
        )
        val sourceType = root.string("source_type")
        val sourceRef = root.nullableString("source_ref")
            ?: when (sourceType) {
                "user_statement" -> userStatementSourceRef(sourceUserText)
                "order_history" -> sourceAttachmentIds.firstOrNull()
                else -> null
            }
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
        if (barcodes.isNotEmpty()) {
            add("barcodes" to barcodes.joinToString(",") { "${it.scheme}:${it.value}" })
        }
    }.map { (field, observedValue) ->
        ProductCandidateEvidence(
            sourceAttachmentIds = sourceAttachmentIds,
            sourceType = sourceType,
            sourceRef = sourceRef,
            field = field,
            observedValue = observedValue,
        )
    }

    private fun productEvidenceSourceRef(
        value: ProductCandidateEvidence,
        candidateSourceAttachmentIds: List<String>,
        sourceUserText: String?,
    ): String? {
        val explicit = value.sourceRef?.takeIf(String::isNotBlank)
            ?: value.source?.takeIf(String::isNotBlank)
        if (explicit != null) return explicit
        return when (value.sourceType) {
            "user_statement" -> userStatementSourceRef(sourceUserText)
            "order_history" -> value.sourceAttachmentIds.firstOrNull()
                ?: candidateSourceAttachmentIds.firstOrNull()
            else -> value.sourceAttachmentIds.firstOrNull()
                ?: candidateSourceAttachmentIds.firstOrNull()
        }
    }

    private fun userStatementSourceRef(sourceUserText: String?): String? =
        sourceUserText?.trim()?.takeIf(String::isNotBlank)?.let {
            "user-statement:sha256:" + StableIds.sha256(it)
        }

    private fun decodePurchaseRecord(root: JsonObject): PurchaseRecord {
        requireKeysAllowingOptional(
            root,
            PURCHASE_RECORD_REQUIRED_KEYS,
            PURCHASE_RECORD_OPTIONAL_KEYS,
            "purchase_record",
        )
        val totalsRoot = root.objectValue("totals")
        requireKeys(totalsRoot, TOTAL_KEYS, "purchase_record.totals")
        val payment = root["payment"]?.takeUnless { it == JsonNull }?.let {
            val paymentRoot = it.jsonObject
            requireKeys(paymentRoot, PAYMENT_KEYS, "purchase_record.payment")
            PurchaseRecordPayment(
                method = paymentRoot.nullableString("method"),
                provider = paymentRoot.nullableString("provider"),
                status = paymentRoot.nullableString("status"),
            )
        }
        val lineItems = root.arrayValue("line_items").map { element ->
            val line = element.jsonObject
            requireKeysAllowingOptional(
                line,
                LINE_REQUIRED_KEYS,
                LINE_OPTIONAL_KEYS,
                "purchase_record.line_items",
            )
            PurchaseRecordLine(
                lineKey = line.nullableString("line_key"),
                productClientKey = line.nullableString("product_client_key"),
                description = line.nullableString("description"),
                sellerOverride = line.nullableString("seller_override"),
                optionText = line.nullableString("option_text"),
                priceStatus = when {
                    "price_status" !in line -> "itemized"
                    line["price_status"] == JsonNull -> "unknown"
                    else -> line.string("price_status")
                },
                merchantSku = line.nullableString("merchant_sku"),
                quantity = line.nullableNumber("quantity"),
                unitPriceAmountKrw = line.nullableLong("unit_price_amount_krw"),
                grossAmountKrw = line.nullableLong("gross_amount_krw"),
                discountAmountKrw = line.nullableLong("discount_amount_krw"),
                netAmountKrw = line.nullableLong("net_amount_krw"),
            )
        }
        val evidence = root.arrayValue("evidence").map { element ->
            val item = element.jsonObject
            requireKeys(item, EVIDENCE_KEYS, "purchase_record.evidence")
            PurchaseRecordEvidence(
                sourceType = item.string("source_type"),
                sourceAttachmentIds = item.arrayValue("source_attachment_ids").strings(),
                sourceRef = item.nullableString("source_ref"),
                field = item.string("field"),
                observedValue = item.nullableString("observed_value"),
            )
        }
        return PurchaseRecord(
            clientKey = root.string("client_key"),
            platform = root.string("platform"),
            purchaseKind = PurchaseKind.fromWireValue(root.string("purchase_kind")),
            platformCode = root.nullableString("platform_code"),
            seller = root.nullableString("seller"),
            sellerBranchName = root.nullableString("seller_branch_name"),
            sellerSourceNamespace = root.nullableString("seller_source_namespace"),
            sellerSourceCode = root.nullableString("seller_source_code"),
            sellerBusinessKind = root.nullableString("seller_business_kind"),
            sourceVersion = root.nullableString("source_version"),
            orderReference = root.nullableString("order_reference"),
            orderedOn = root.nullableString("ordered_on"),
            orderedAt = root.nullableString("ordered_at"),
            paidOn = root.nullableString("paid_on"),
            paidAt = root.nullableString("paid_at"),
            status = PurchaseRecordStatus.fromWireValue(root.string("status")),
            currency = root.string("currency"),
            totals = PurchaseRecordTotals(
                subtotalAmountKrw = totalsRoot.nullableLong("subtotal_amount_krw"),
                discountAmountKrw = totalsRoot.nullableLong("discount_amount_krw"),
                shippingAmountKrw = totalsRoot.nullableLong("shipping_amount_krw"),
                taxAmountKrw = totalsRoot.nullableLong("tax_amount_krw"),
                grandTotalAmountKrw = totalsRoot.nullableLong("grand_total_amount_krw"),
                paidAmountKrw = totalsRoot.nullableLong("paid_amount_krw"),
            ),
            payment = payment,
            lineItems = lineItems,
            evidence = evidence,
            confidence = root.number("confidence"),
        )
    }

    private fun decodeSource(root: JsonObject): IngestionSource {
        requireKeys(root, SOURCE_KEYS, "source")
        val producer = root.string("producer")
        require(producer == "chatgpt" || producer == "ocr_app") {
            "unsupported envelope producer"
        }
        val files = root.arrayValue("source_files").map { element ->
            val item = element.jsonObject
            requireKeys(item, SOURCE_FILE_KEYS, "source_file")
            SourceAttachment(
                id = item.string("id"),
                type = SourceAttachmentType.fromWireValue(item.string("type")),
                label = item.nullableString("label"),
            )
        }
        require(files.map { it.id }.distinct().size == files.size) {
            "source attachment IDs must be unique"
        }
        require(files.all { it.type == SourceAttachmentType.ORDER_HISTORY ||
            it.type == SourceAttachmentType.PAYMENT_HISTORY }) {
            "v4 source files must be order_history or payment_history"
        }
        return IngestionSource(
            producer = producer,
            sourceFiles = files,
            userText = root.nullableString("user_text"),
        )
    }

    private fun validatePurchaseEvidence(
        source: IngestionSource,
        productCandidates: List<ProductCandidate>,
        records: List<PurchaseRecord>,
    ) {
        validateProductCandidateEvidence(source, productCandidates)
        val filesById = source.sourceFiles.associateBy { it.id }
        records.forEach { record ->
            record.evidence.forEach { evidence ->
                if (evidence.sourceType == "user_statement") {
                    require(!source.userText.isNullOrBlank()) {
                        "user_statement evidence requires source.user_text"
                    }
                    require(evidence.sourceAttachmentIds.isEmpty()) {
                        "user_statement evidence cannot reference history attachments"
                    }
                } else {
                    require(evidence.sourceType == "order_history" || evidence.sourceType == "payment_history")
                    require(evidence.sourceAttachmentIds.isNotEmpty())
                    evidence.sourceAttachmentIds.forEach { id ->
                        val file = filesById[id] ?: error("purchase evidence references unknown source file")
                        require(file.type.wireValue == evidence.sourceType) {
                            "purchase evidence source type does not match source file"
                        }
                    }
                }
            }
            require(record.conflictFields().isEmpty() || record.conflictFields().all(String::isNotBlank))
        }
    }

    private fun validateProductCandidateEvidence(
        source: IngestionSource,
        candidates: List<ProductCandidate>,
    ) {
        val filesById = source.sourceFiles.associateBy { it.id }
        candidates.forEach { candidate ->
            val sourceAttachmentIds = candidate.effectiveSourceAttachmentIds
            sourceAttachmentIds.forEach { id ->
                require(id in filesById) {
                    "product candidate references unknown source file"
                }
            }
            candidate.evidence.forEach { evidence ->
                when (evidence.sourceType) {
                    "user_statement" -> {
                        require(!source.userText.isNullOrBlank()) {
                            "product candidate user_statement evidence requires source.user_text"
                        }
                        // source_attachment_ids belongs to the candidate as a whole in the
                        // V4 wire shape. It may coexist with a distinct user_statement fact.
                    }
                    "order_history" -> {
                        require(sourceAttachmentIds.isNotEmpty()) {
                            "product candidate order_history evidence requires source attachments"
                        }
                        require(sourceAttachmentIds.all {
                            filesById.getValue(it).type == SourceAttachmentType.ORDER_HISTORY
                        }) {
                            "product candidate evidence source type does not match source file"
                        }
                    }
                    else -> error("v4 product candidate evidence must be order_history or user_statement")
                }
            }
        }
    }

    private fun decodeHints(root: JsonObject): Map<String, String?> {
        require(root.keys subtract setOf("cashos") == emptySet<String>())
        val cashos = root.objectValue("cashos")
        require(cashos.keys subtract CASHOS_HINT_KEYS == emptySet<String>())
        return cashos.mapKeys { "cashos." + it.key }
            .mapValues { (_, value) -> value.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull }
    }

    private fun decodeReview(
        root: JsonObject,
        productCandidates: List<ProductCandidate>,
        records: List<PurchaseRecord>,
        preservePersistedVerification: Boolean,
    ): IngestionReview {
        require(root.keys == REVIEW_KEYS || root.keys == REVIEW_KEYS_WITHOUT_AUTHORITY_FIELDS)
        val declaredStatus = IngestionReviewStatus.entries.firstOrNull {
            it.wireValue == root.string("status")
        } ?: error("Unsupported v4 review status")
        val declaredBasis = root["verification_basis"]?.takeUnless { it == JsonNull }?.let {
            require(it is JsonPrimitive && it.isString)
            VerificationBasis.fromWireValue(it.content)
        }
        val conflicts = conflictIssues(productCandidates, records)
        val status = when {
            conflicts.isNotEmpty() -> IngestionReviewStatus.CONFLICT
            preservePersistedVerification -> declaredStatus
            else -> IngestionReviewStatus.NEEDS_REVIEW
        }
        return IngestionReview(
            status = status,
            blockingIssues = if (conflicts.isNotEmpty()) conflicts
            else if (preservePersistedVerification) root.arrayValue("blocking_issues").strings()
            else emptyList(),
            warnings = root.arrayValue("warnings").strings(),
            verificationBasis = if (preservePersistedVerification) {
                declaredBasis ?: VerificationBasis.SOURCE_EVIDENCE
            } else VerificationBasis.SOURCE_EVIDENCE,
        )
    }

    private fun reviewJson(
        envelope: YeonsikOcrEnvelope,
        includeAuthorityFields: Boolean,
        forFingerprint: Boolean,
    ): JsonObject {
        val conflicts = conflictIssues(envelope.productCandidates, envelope.purchaseRecords)
        val status = if (conflicts.isNotEmpty()) IngestionReviewStatus.CONFLICT.wireValue
        else if (includeAuthorityFields) envelope.review.status.wireValue
        else IngestionReviewStatus.NEEDS_REVIEW.wireValue
        return buildJsonObject {
            put("status", JsonPrimitive(status))
            put(
                "blocking_issues",
                JsonArray(
                    if (forFingerprint) emptyList()
                    else (if (conflicts.isNotEmpty()) conflicts else envelope.review.blockingIssues)
                        .map(::JsonPrimitive),
                ),
            )
            put(
                "warnings",
                JsonArray(
                    if (forFingerprint) emptyList() else envelope.review.warnings.map(::JsonPrimitive),
                ),
            )
            if (includeAuthorityFields) {
                put("verification_basis", JsonPrimitive(envelope.review.verificationBasis.wireValue))
                put("user_verified", JsonPrimitive(envelope.review.status == IngestionReviewStatus.READY))
            }
        }
    }

    private fun conflictIssues(
        productCandidates: List<ProductCandidate>,
        records: List<PurchaseRecord>,
    ): List<String> =
        (productCandidates.flatMap { candidate ->
            candidate.conflictFields().sorted().map { field ->
                "product_candidate_conflict:" + candidate.clientKey + ":" + field
            }
        } + records.flatMap { record ->
            record.conflictFields().sorted().map { field ->
                "purchase_evidence_conflict:" + record.clientKey + ":" + field
            }
        }).distinct().sorted()

    private fun sourceJson(source: IngestionSource): JsonObject = buildJsonObject {
        put("producer", JsonPrimitive(source.producer))
        put("source_files", JsonArray(source.sourceFiles.map { file ->
            buildJsonObject {
                put("id", JsonPrimitive(file.id))
                put("type", JsonPrimitive(file.type.wireValue))
                put("label", file.label?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
        put("user_text", source.userText?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun classificationHintsJson(hints: Map<String, String?>): JsonObject = buildJsonObject {
        put("cashos", JsonObject(
            hints.filterKeys {
                it.startsWith("cashos.") && it.removePrefix("cashos.") in CASHOS_HINT_KEYS
            }.mapKeys { it.key.removePrefix("cashos.") }
                .mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull },
        ))
    }

    private fun requireKeys(root: JsonObject, expected: Set<String>, label: String) {
        require(root.keys == expected) {
            label + " keys mismatch: unexpected=" + (root.keys - expected) +
                ", missing=" + (expected - root.keys)
        }
    }

    private fun requireKeysAllowingOptional(
        root: JsonObject,
        required: Set<String>,
        optional: Set<String>,
        label: String,
    ) {
        val allowed = required + optional
        require(root.keys - allowed == emptySet<String>() && required - root.keys == emptySet<String>()) {
            label + " keys mismatch: unexpected=" + (root.keys - allowed) +
                ", missing=" + (required - root.keys)
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error(key + " must be a non-empty string")

    private fun JsonObject.nullableString(key: String): String? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let {
            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: error(key + " must be a string or null")
        }

    private fun JsonObject.number(key: String): Double =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?.takeIf(Double::isFinite) ?: error(key + " must be a finite number")

    private fun JsonObject.nullableNumber(key: String): Double? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let {
            (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.doubleOrNull
                ?.takeIf(Double::isFinite) ?: error(key + " must be a finite number or null")
        }

    private fun JsonObject.nullableLong(key: String): Long? = this[key]
        .takeUnless { it == null || it == JsonNull }
        ?.let {
            val primitive = (it as? JsonPrimitive)?.takeIf { item -> !item.isString }
                ?: error(key + " must be an integer or null")
            primitive.content.toLongOrNull() ?: error(key + " must be an integer or null")
        }

    private fun Double.toPositiveInt(key: String): Int {
        require(isFinite() && this % 1.0 == 0.0 && this > 0 && this <= Int.MAX_VALUE.toDouble()) {
            "$key must be a positive integer"
        }
        return toInt()
    }

    private fun JsonObject.objectValue(key: String): JsonObject =
        this[key]?.jsonObject ?: error(key + " must be an object")

    private fun JsonObject.arrayValue(key: String): JsonArray =
        this[key]?.jsonArray ?: error(key + " must be an array")

    private fun JsonArray.strings(): List<String> = map {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("array must contain non-empty strings")
    }

    private val TOP_LEVEL_KEYS = setOf(
        "schema_version", "mode", "source", "merchant_candidate", "receipt",
        "product_candidates", "price_observations", "purchase_records", "nutrition",
        "consumption", "classification_hints", "links", "projection_targets", "review",
    )
    private val SOURCE_KEYS = setOf("producer", "source_files", "user_text")
    private val SOURCE_FILE_KEYS = setOf("id", "type", "label")
    private val PRODUCT_CANDIDATE_KEYS = setOf(
        "client_key", "product_name", "merchant_sku", "brand_name", "sub_brand_name",
        "manufacturer_name", "variant_name", "specification_text", "content_amount",
        "content_unit", "package_count", "barcodes", "source_attachment_ids", "evidence",
        "confidence",
    )
    private val PRODUCT_EVIDENCE_KEYS = setOf(
        "source_type", "source_ref", "field", "observed_value", "content_hash",
    )
    private val BARCODE_KEYS = setOf("scheme", "value")
    private val PURCHASE_RECORD_REQUIRED_KEYS = setOf(
        "client_key", "platform", "seller", "ordered_on", "ordered_at", "paid_on", "paid_at",
        "purchase_kind", "status", "currency", "totals", "payment", "line_items", "evidence",
        "confidence",
    )
    private val PURCHASE_RECORD_OPTIONAL_KEYS = setOf(
        "platform_code", "seller_branch_name", "seller_source_namespace", "seller_source_code",
        "seller_business_kind", "source_version", "order_reference",
    )
    private val TOTAL_KEYS = setOf(
        "subtotal_amount_krw", "discount_amount_krw", "shipping_amount_krw",
        "tax_amount_krw", "grand_total_amount_krw", "paid_amount_krw",
    )
    private val PAYMENT_KEYS = setOf("method", "provider", "status")
    private val LINE_REQUIRED_KEYS = setOf(
        "product_client_key", "description", "seller_override", "quantity",
        "unit_price_amount_krw", "gross_amount_krw", "discount_amount_krw", "net_amount_krw",
    )
    private val LINE_OPTIONAL_KEYS = setOf(
        "line_key", "option_text", "price_status", "merchant_sku",
    )
    private val EVIDENCE_KEYS = setOf(
        "source_type", "source_attachment_ids", "source_ref", "field", "observed_value",
    )
    private val REVIEW_KEYS = setOf(
        "status", "blocking_issues", "warnings", "verification_basis", "user_verified",
    )
    private val REVIEW_KEYS_WITHOUT_AUTHORITY_FIELDS = setOf("status", "blocking_issues", "warnings")
    private val CASHOS_HINT_KEYS = setOf("category_hint", "institution_hint", "payment_method_hint")
}
