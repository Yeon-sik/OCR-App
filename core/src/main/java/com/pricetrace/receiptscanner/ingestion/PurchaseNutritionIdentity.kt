package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.*

/** Only record-key + line-key correlation may carry a server identity into V5 publication. */
object PurchaseNutritionIdentity {
    fun exact(envelope: YeonsikOcrEnvelope, nutritionClientKey: String, metadataJson: String?): PriceTraceRestaurantMenuIdentity? = runCatching {
        val link = envelope.purchaseNutritionLinks.singleOrNull { it.nutritionClientKey == nutritionClientKey } ?: return null
        val root = Json.parseToJsonElement(metadataJson ?: return null).jsonObject
        val source = root["sources"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
            it["purchaseRecordClientKey"]?.jsonPrimitive?.contentOrNull == link.purchaseRecordClientKey
        } ?: return null
        val line = source["lineResults"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
            it["lineKey"]?.jsonPrimitive?.contentOrNull == link.purchaseLineKey
        } ?: return null
        // The existing V4 server does not yet expose this authority metadata. Absence fails closed.
        PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(line)
    }.getOrNull()

    fun sellerName(envelope: YeonsikOcrEnvelope, nutritionClientKey: String): String? {
        val link = envelope.purchaseNutritionLinks.singleOrNull { it.nutritionClientKey == nutritionClientKey } ?: return null
        val record = envelope.purchaseRecords.singleOrNull { it.clientKey == link.purchaseRecordClientKey } ?: return null
        return record.lineItems.singleOrNull { it.lineKey == link.purchaseLineKey }?.sellerOverride ?: record.seller
    }
}
