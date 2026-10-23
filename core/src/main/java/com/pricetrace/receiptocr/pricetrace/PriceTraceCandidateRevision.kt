package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.serialization.json.*

data class PriceTraceCandidateRevision(val logicalClientKey: String, val authorityClientKey: String, val sourceRevisionFingerprint: String)

/** One source builder drives both the revision hash and the final PT request. */
object PriceTraceCandidatePayload {
    fun revision(candidate: ProductCandidate): PriceTraceCandidateRevision {
        val material = buildJsonObject { put("logical_client_key", candidate.clientKey); put("source", sorted(source(candidate))) }
        val hash = StableIds.sha256(material.toString())
        return PriceTraceCandidateRevision(candidate.clientKey, "ocrpc-r1-$hash", hash)
    }
    fun wire(candidate: ProductCandidate, authorityKey: String = revision(candidate).authorityClientKey): JsonObject =
        JsonObject(source(candidate) + ("client_key" to JsonPrimitive(authorityKey)))
    private fun sorted(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(value.map(::sorted))
        else -> value
    }
    /** Accepted keys are recovered by explicit logical key, never response array order. */
    fun acceptedKeys(metadata: String?, candidates: List<ProductCandidate>? = null): Map<String, String> {
        val root = metadata?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: return emptyMap()
        val rows = listOf("responses", "products").flatMap { (root[it] as? JsonArray).orEmpty() }
        val keys = linkedMapOf<String,String>()
        rows.forEach { element ->
            val row = element as? JsonObject ?: return@forEach
            val logical = (row["clientKey"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val sourceFingerprint=(row["sourceRevisionFingerprint"] as? JsonPrimitive)?.contentOrNull
            if(candidates!=null && sourceFingerprint!=null && candidates.singleOrNull { it.clientKey==logical }?.let { revision(it).sourceRevisionFingerprint }!=sourceFingerprint) return@forEach
            val authority = (row["priceTraceAuthorityClientKey"] as? JsonPrimitive)?.contentOrNull
                ?: (row["productClientKey"] as? JsonPrimitive)?.contentOrNull ?: logical
            require(validAuthorityKey(authority)) { "invalid_candidate_authority_key" }
            require(keys[logical] == null || keys[logical] == authority) { "conflicting_candidate_authority_key" }
            keys[logical] = authority
        }
        return keys
    }

    /** Aggregate workflow resets must retain only independently accepted, exact-source candidates. */
    fun preserveCurrentCandidateAuthorityMetadata(metadata: String?, candidates: List<ProductCandidate>): String? {
        val root = metadata?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val current = candidates.associate { it.clientKey to revision(it).sourceRevisionFingerprint }
        fun text(row: JsonObject, key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val arrays = listOf("responses", "products").associateWith { field ->
            JsonArray((root[field] as? JsonArray).orEmpty().filter { element ->
                val row = element as? JsonObject ?: return@filter false
                val logical = text(row, "clientKey") ?: return@filter false
                val authority = when {
                    "priceTraceAuthorityClientKey" in row -> text(row, "priceTraceAuthorityClientKey") ?: return@filter false
                    "productClientKey" in row -> text(row, "productClientKey") ?: return@filter false
                    else -> logical
                }
                val acceptedIdentity = listOf("candidateId", "catalogProductId", "catalog_product_id").any { !text(row, it).isNullOrBlank() }
                acceptedIdentity && validAuthorityKey(authority) && current[logical] != null &&
                    text(row, "sourceRevisionFingerprint") == current[logical]
            })
        }
        if (arrays.values.all { it.isEmpty() }) return null
        val preserved = buildJsonObject {
            put("schemaVersion", "product-candidate.v1"); put("contract", "PRICETRACE_PRODUCT_CANDIDATE")
            arrays.forEach { (field, rows) -> put(field, rows) }
        }
        acceptedKeys(preserved.toString(), candidates) // Reject conflicting duplicate representations.
        return preserved.toString()
    }

    private fun validAuthorityKey(key: String) = key.isNotBlank() && key.length <= 200 &&
        key == key.trim() && key.none(Char::isISOControl) &&
        !key.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

    /** Add a baseline only when Core has proved the saved canonical source is unchanged. */
    fun annotateCurrentLegacySource(metadata: String, candidates: List<ProductCandidate>): String {
        val root = runCatching { Json.parseToJsonElement(metadata).jsonObject }.getOrNull() ?: return metadata
        val byKey = candidates.associateBy(ProductCandidate::clientKey)
        var changed = false
        val updated = root.toMutableMap()
        for (field in listOf("responses", "products")) {
            val rows = root[field] as? JsonArray ?: continue
            updated[field] = JsonArray(rows.map { element ->
                val row = element as? JsonObject ?: return@map element
                val candidate = byKey[(row["clientKey"] as? JsonPrimitive)?.contentOrNull] ?: return@map row
                if ("sourceRevisionFingerprint" in row) return@map row
                changed = true
                JsonObject(row + ("sourceRevisionFingerprint" to JsonPrimitive(revision(candidate).sourceRevisionFingerprint)))
            })
        }
        return if (changed) JsonObject(updated).toString() else metadata
    }

    /** Legacy display/artifact hashes may sort arrays or collide on delimiter-containing facts. */
    fun changedSourceKeys(metadata: String?, candidates: List<ProductCandidate>): Set<String> {
        val root = metadata?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return emptySet()
        val fingerprints = candidates.associate { it.clientKey to revision(it).sourceRevisionFingerprint }
        return listOf("responses", "products").flatMap { (root[it] as? JsonArray).orEmpty() }
            .mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val key = (row["clientKey"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val stored = (row["sourceRevisionFingerprint"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                key.takeIf { fingerprints[key] != stored }
            }.toSet()
    }
    fun source(candidate: ProductCandidate): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive("PRICETRACE_PRODUCT_CANDIDATE"))
        put("contract_version", JsonPrimitive("product-candidate.v1"))
        put("source_app", JsonPrimitive("pricetrace_ocr_app"))
        put("sub_brand", candidate.subBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("source_version", candidate.sourceVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("candidate_type", JsonPrimitive(candidate.candidateType))
        put("product_name", JsonPrimitive(candidate.productName))
        put("brand", candidate.brand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", candidate.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("specification", candidate.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("content_amount", candidate.contentAmount?.let(::JsonPrimitive) ?: JsonNull)
        put("content_unit", candidate.contentUnit?.let(::JsonPrimitive) ?: JsonNull)
        put("package_count", candidate.packageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("variant", candidate.variant?.let(::JsonPrimitive) ?: JsonNull)
        put("identifiers", JsonArray(candidateIdentifiers(candidate)))
        put("evidence", JsonArray(candidate.evidence.mapNotNull { evidence ->
            val field = priceTraceProductCandidateEvidenceField(evidence)
            if (field == null) {
                null
            } else {
                val sourceRef = evidence.sourceRef?.takeIf(String::isNotBlank)
                    ?: evidence.source?.takeIf(String::isNotBlank)
                    ?: evidence.sourceAttachmentIds.firstOrNull()
                    ?: error("product candidate evidence source_ref is required")
                buildJsonObject {
                    put("source_type", JsonPrimitive(evidence.sourceType))
                    put("source_ref", JsonPrimitive(sourceRef))
                    put("field", JsonPrimitive(field))
                    put("observed_value", evidence.observedValue?.let(::JsonPrimitive) ?: JsonNull)
                    put("content_hash", evidence.contentHash?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }))
        put("provenance", buildJsonObject {
            candidate.evidence.firstOrNull()?.sourceAttachmentIds?.firstOrNull()
                ?.let { put("capture_id", JsonPrimitive(it)) }
            put("extraction_method", JsonPrimitive("mixed"))
            put("extractor", JsonPrimitive("ocr-app"))
            candidate.sourceVersion?.let { put("extractor_version", JsonPrimitive(it)) }
            candidate.sourceVersion?.let { put("source_revision", JsonPrimitive(it)) }
        })
    }

    /** Normalize only the V4 order-history aliases at the PriceTrace boundary. */
    private fun priceTraceProductCandidateEvidenceField(
        evidence: ProductCandidateEvidence,
    ): String? = if (evidence.sourceType != "order_history") {
        evidence.field
    } else {
        when (evidence.field) {
            "brand_name" -> "brand"
            "manufacturer_name" -> "manufacturer"
            "variant_name" -> "variant"
            "specification_text" -> "specification"
            "sub_brand_name" -> "sub_brand"
            "barcodes" -> null
            else -> evidence.field
        }
    }

    private fun candidateIdentifiers(candidate: ProductCandidate): List<JsonObject> {
        return candidate.barcodes.map { barcode -> buildJsonObject {
            put("scheme", JsonPrimitive(priceTraceIdentifierScheme(barcode)))
            put("value", JsonPrimitive(barcode.value))
        }}
    }

    /** Convert the Project's barcode observation type to PriceTrace's existing identifier scheme. */
    fun priceTraceIdentifierScheme(barcode: ProductCandidateBarcode): String =
        when (barcode.type.lowercase().replace("-", "").replace("_", "")) {
            "ean", "ean8", "ean13" -> "ean"
            "upc", "upca", "upce" -> "upc"
            "gtin", "gtin8", "gtin12", "gtin13", "gtin14", "barcode" -> "gtin"
            else -> when (barcode.value.filterNot { it == ' ' || it == '-' }.length) {
                8, 13 -> "ean"
                12 -> "upc"
                14 -> "gtin"
                else -> error("unsupported product candidate barcode type: ${barcode.type}")
            }
        }

}
