package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.*

/** Only record-key + line-key correlation may carry a server identity into V5 publication. */
object PurchaseNutritionIdentity {
    data class OwnerConfirmedIdentity(
        val purchaseSourceId: String, val purchaseRecordClientKey: String, val lineKey: String,
        val restaurantId: String, val restaurantLocationId: String, val restaurantMenuId: String,
    )
    data class PendingResolution(val purchaseRecordClientKey: String, val purchaseSourceId: String, val lineKey: String)
    data class OwnerRestaurantChoice(val restaurantId: String, val restaurantLocationId: String,
        val merchantName: String, val branchName: String?, val sourceNamespace: String? = null, val sourceCode: String? = null) {
        val displayLabel: String get() = listOfNotNull(merchantName, branchName ?: "지점 미기록",
            sourceNamespace?.let { "$it / $sourceCode" }).joinToString(" · ")
    }
    data class OwnerRestaurantChoices(val purchaseRecordClientKey: String, val lineKey: String,
        val choices: List<OwnerRestaurantChoice>)
    /** Explicit UI selection; runtime only, never canonical input. */
    data class OwnerRestaurantSelection(val purchaseRecordClientKey: String, val lineKey: String, val restaurantId: String)

    fun ownerRestaurantChoices(metadataJson: String?): List<OwnerRestaurantChoices> = runCatching {
        val sources = Json.parseToJsonElement(metadataJson ?: return emptyList()).jsonObject["sources"] as? JsonArray
            ?: return emptyList()
        sources.flatMap { element ->
            val source = element.jsonObject
            if (source["sourceAcceptanceStatus"] != JsonPrimitive("accepted")) return@flatMap emptyList()
            val recordKey = source["purchaseRecordClientKey"]!!.jsonPrimitive.content
            (source["lineResults"] as? JsonArray).orEmpty().mapNotNull { value ->
                val row = value.jsonObject
                if (row["observationCreated"] == JsonPrimitive(true) || row["kind"] != JsonPrimitive("restaurant_purchase")) return@mapNotNull null
                val choices = (row["ownerRestaurantChoices"] as? JsonArray).orEmpty().map { raw ->
                    val choice = raw.jsonObject
                    val id = choice["restaurantId"]!!.jsonPrimitive.content
                    val locationId = choice["restaurantLocationId"]!!.jsonPrimitive.content
                    require(java.util.UUID.fromString(id).toString().equals(id, true))
                    require(java.util.UUID.fromString(locationId).toString().equals(locationId, true))
                    OwnerRestaurantChoice(id, locationId, choice["merchantName"]!!.jsonPrimitive.content,
                        choice["branchName"]?.jsonPrimitive?.contentOrNull, choice["sourceNamespace"]?.jsonPrimitive?.contentOrNull,
                        choice["sourceCode"]?.jsonPrimitive?.contentOrNull)
                }
                if (choices.isEmpty()) null else {
                    require(choices.map { it.restaurantId }.distinct().size == choices.size)
                    OwnerRestaurantChoices(recordKey, row["lineKey"]!!.jsonPrimitive.content, choices)
                }
            }
        }
    }.getOrDefault(emptyList())

    /** Selectors are accepted server metadata, never user-supplied canonical UUIDs. */
    fun pendingResolutions(metadataJson: String?): List<PendingResolution> = runCatching {
        val sources = Json.parseToJsonElement(metadataJson ?: return emptyList()).jsonObject["sources"] as? JsonArray
            ?: return emptyList()
        sources.flatMap { element ->
            val source = element.jsonObject
            val key = source["purchaseRecordClientKey"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            val id = source["purchaseSourceId"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            require(java.util.UUID.fromString(id).toString().equals(id, true))
            if (source["sourceAcceptanceStatus"] != JsonPrimitive("accepted")) return@flatMap emptyList()
            (source["lineResults"] as? JsonArray).orEmpty().mapNotNull { row ->
                val line = row.jsonObject
                val reason = line["reasonCode"]?.jsonPrimitive?.contentOrNull
                if (line["kind"] != JsonPrimitive("restaurant_purchase") ||
                    line["observationCreated"] == JsonPrimitive(true) ||
                    reason == null || !(reason.startsWith("restaurant_") || reason.startsWith("menu_") || reason == "owner_restaurant_selection_required")) return@mapNotNull null
                val lineKey = line["lineKey"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                PendingResolution(key, id, lineKey)
            }
        }
    }.getOrDefault(emptyList())

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

    fun sellerName(envelope: YeonsikOcrEnvelope, nutritionClientKey: String): String? =
        linkedPurchaseLine(envelope, nutritionClientKey)?.let { (record, line) ->
            line.sellerOverride ?: record.seller
        }

    /** Private reference only. Never accepted by exact() or the public publication RPC. */
    fun ownerConfirmed(envelope: YeonsikOcrEnvelope, nutritionClientKey: String, metadataJson: String?): OwnerConfirmedIdentity? = runCatching {
        if (envelope.schemaVersion != YEONSIK_OCR_V5_SCHEMA) return null
        val link = envelope.purchaseNutritionLinks.singleOrNull { it.nutritionClientKey == nutritionClientKey } ?: return null
        val record = envelope.purchaseRecords.singleOrNull { it.clientKey == link.purchaseRecordClientKey } ?: return null
        val source = Json.parseToJsonElement(metadataJson ?: return null).jsonObject["sources"]!!.jsonArray
            .map { it.jsonObject }.singleOrNull { it["purchaseRecordClientKey"]?.jsonPrimitive?.content == record.clientKey } ?: return null
        val line = validateLineResults(record, source).single { it["lineKey"]?.jsonPrimitive?.content == link.purchaseLineKey }
        if (source["sourceAcceptanceStatus"] != JsonPrimitive("accepted") ||
            line["sourceAcceptanceStatus"] != JsonPrimitive("accepted") ||
            line["authorityStatus"] != JsonPrimitive("owner_confirmed") ||
            line["merchantResolutionStatus"] != JsonPrimitive("owner_confirmed") ||
            line["menuResolutionStatus"] != JsonPrimitive("owner_confirmed") ||
            line["observationStatus"] != JsonPrimitive("created") || line["observationCreated"] != JsonPrimitive(true) ||
            line["observationType"] != JsonPrimitive("purchase_owner_price_observation") || line["authoritativeIds"] != JsonNull) return null
        val ids = line["ownerConfirmedIds"]!!.jsonObject
        fun uuid(value: JsonElement?): String = requireNotNull(value?.jsonPrimitive?.contentOrNull).also {
            require(java.util.UUID.fromString(it).toString().equals(it, true))
        }
        uuid(line["observationId"])
        OwnerConfirmedIdentity(uuid(source["purchaseSourceId"]), record.clientKey, link.purchaseLineKey,
            uuid(ids["restaurantId"]), uuid(ids["restaurantLocationId"]), uuid(ids["restaurantMenuId"]))
    }.getOrNull()

    /** An explicit line seller has no branch fact and cannot inherit the record's branch. */
    fun sellerBranchName(envelope: YeonsikOcrEnvelope, nutritionClientKey: String): String? =
        linkedPurchaseLine(envelope, nutritionClientKey)?.let { (record, line) ->
            record.sellerBranchName.takeIf { line.sellerOverride == null }
        }

    private fun linkedPurchaseLine(
        envelope: YeonsikOcrEnvelope,
        nutritionClientKey: String,
    ): Pair<PurchaseRecord, PurchaseRecordLine>? {
        val link = envelope.purchaseNutritionLinks.singleOrNull { it.nutritionClientKey == nutritionClientKey } ?: return null
        val record = envelope.purchaseRecords.singleOrNull { it.clientKey == link.purchaseRecordClientKey } ?: return null
        val line = record.lineItems.singleOrNull { it.lineKey == link.purchaseLineKey } ?: return null
        return record to line
    }
}
