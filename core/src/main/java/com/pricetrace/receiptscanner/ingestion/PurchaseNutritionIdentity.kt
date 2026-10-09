package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.*

/** Only record-key + line-key correlation may carry a server identity into V5 publication. */
object PurchaseNutritionIdentity {
    /** The server must account for every submitted line, including non-observation outcomes. */
    fun validateLineResults(record: PurchaseRecord, source: JsonObject): List<JsonObject> {
        val rows = (source["lineResults"] as? JsonArray)?.map { it.jsonObject }
            ?: error("pricetrace_purchase_line_results_missing")
        val keys = rows.map { (it["lineKey"] as? JsonPrimitive)?.takeIf { key -> key.isString }?.contentOrNull }
        val submitted = record.lineItems.mapIndexed { index, line -> line.lineKey ?: "line-${index + 1}" }
        require(submitted.distinct().size == submitted.size &&
            keys.all { !it.isNullOrBlank() } && keys.distinct().size == keys.size &&
            keys.toSet() == submitted.toSet()
        ) { "pricetrace_purchase_line_results_invalid" }
        return rows
    }

    fun hasAuthorityMetadata(source: JsonObject): Boolean =
        source["lineAuthorityVersion"] == JsonPrimitive("purchase-line-authority.v1") &&
            (source["lineResults"] as? JsonArray)?.all { row ->
                val line = row as? JsonObject ?: return@all false
                setOf("kind", "sourceAcceptanceStatus", "observationStatus", "authorityStatus",
                    "merchantResolutionStatus", "menuResolutionStatus").all { key ->
                    (line[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.isNotBlank() == true
                } && line.containsKey("authoritativeIds") &&
                    (line["authorityStatus"] != JsonPrimitive("exact") || line["authoritativeIds"] is JsonObject)
            } == true

    fun needsAuthorityRecovery(envelope: YeonsikOcrEnvelope, metadataJson: String?): Boolean {
        if (envelope.schemaVersion != YEONSIK_OCR_V5_SCHEMA || metadataJson == null) return false
        return runCatching {
            val sources = Json.parseToJsonElement(metadataJson).jsonObject["sources"]!!.jsonArray
            sources.any { !hasAuthorityMetadata(it.jsonObject) }
        }.getOrDefault(true)
    }

    /** Accepted source-only outcomes permit private import, without completing public authority. */
    fun sourceAccepted(envelope: YeonsikOcrEnvelope, metadataJson: String?): Boolean = runCatching {
        if (envelope.schemaVersion != YEONSIK_OCR_V5_SCHEMA || metadataJson == null) return false
        val records = envelope.purchaseRecords.filter { it.priceTraceSubmissionEligible }
        val sources = Json.parseToJsonElement(metadataJson).jsonObject["sources"]!!.jsonArray.map { it.jsonObject }
        val keys = sources.map { it["purchaseRecordClientKey"]?.jsonPrimitive?.contentOrNull }
        records.isNotEmpty() && keys.distinct().size == keys.size && keys.toSet() == records.map { it.clientKey }.toSet() &&
            sources.all { source ->
                validateLineResults(records.single { it.clientKey == source["purchaseRecordClientKey"]!!.jsonPrimitive.content }, source)
                source["sourceSaved"] == JsonPrimitive(true) && source["sourceAcceptanceStatus"] == JsonPrimitive("accepted") &&
                    hasAuthorityMetadata(source)
            }
    }.getOrDefault(false)

    fun exact(envelope: YeonsikOcrEnvelope, nutritionClientKey: String, metadataJson: String?): PriceTraceRestaurantMenuIdentity? = runCatching {
        val link = envelope.purchaseNutritionLinks.singleOrNull { it.nutritionClientKey == nutritionClientKey } ?: return null
        val record = envelope.purchaseRecords.singleOrNull { it.clientKey == link.purchaseRecordClientKey } ?: return null
        val root = Json.parseToJsonElement(metadataJson ?: return null).jsonObject
        val source = root["sources"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
            it["purchaseRecordClientKey"]?.jsonPrimitive?.contentOrNull == link.purchaseRecordClientKey
        } ?: return null
        val line = validateLineResults(record, source).singleOrNull {
            it["lineKey"]?.jsonPrimitive?.contentOrNull == link.purchaseLineKey
        } ?: return null
        if (line["sourceAcceptanceStatus"] != JsonPrimitive("accepted") ||
            line["observationCreated"] != JsonPrimitive(true) ||
            line["observationStatus"] != JsonPrimitive("created") ||
            line["authorityStatus"] != JsonPrimitive("exact") ||
            line["merchantResolutionStatus"] != JsonPrimitive("exact") ||
            line["menuResolutionStatus"] != JsonPrimitive("exact") ||
            line["observationType"] != JsonPrimitive("restaurant_menu_manual_observation")
        ) return null
        val observationId = (line["observationId"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (!java.util.UUID.fromString(observationId).toString().equals(observationId, ignoreCase = true)) return null
        val identity = PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(line) ?: return null
        if (listOf(identity.restaurantId, identity.restaurantLocationId, identity.restaurantMenuId, identity.catalogProductId).any {
                !java.util.UUID.fromString(it).toString().equals(it, ignoreCase = true)
            }) return null
        identity
    }.getOrNull()

    fun sellerName(envelope: YeonsikOcrEnvelope, nutritionClientKey: String): String? {
        val link = envelope.purchaseNutritionLinks.singleOrNull { it.nutritionClientKey == nutritionClientKey } ?: return null
        val record = envelope.purchaseRecords.singleOrNull { it.clientKey == link.purchaseRecordClientKey } ?: return null
        return record.lineItems.singleOrNull { it.lineKey == link.purchaseLineKey }?.sellerOverride ?: record.seller
    }
}
