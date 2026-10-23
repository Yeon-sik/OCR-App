package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

/** Public discovery and private user-confirmed resolution; no canonical master is stored here. */
class PriceTraceBrandClient(private val transport: PriceObservationHttpTransport) {
    suspend fun search(config: PriceTraceSupabaseConfig, query: String, limit: Int = 50, offset: Int = 0): List<PriceTraceBrandMatch> {
        require(config.isConnectionConfigured) { "PriceTrace 연결 설정 필요" }
        val response = call(config, "get_public_brand_catalog_v1", buildJsonObject {
            put("p_query", query); put("p_limit", limit.coerceIn(1,200)); put("p_offset", offset.coerceIn(0,1000000))
        }, authenticated = false)
        require(response.statusCode in 200..299) { "브랜드 검색 실패 (${response.statusCode})" }
        return parseSearch(response.body)
    }
    suspend fun call(config: PriceTraceSupabaseConfig, rpc: String, body: JsonObject, authenticated: Boolean = true): PriceObservationHttpResponse =
        transport.execute(PriceObservationHttpRequest("POST", config.url.trimEnd('/') + "/rest/v1/rpc/$rpc", buildMap {
            put("apikey", config.publishableKey); put("Accept", "application/json"); put("Content-Type", "application/json; charset=utf-8")
            if (authenticated) { require(config.accessToken.isNotBlank()); put("Authorization", "Bearer ${config.accessToken}") }
        }, body.toString()))

    companion object {
        fun parseSearch(body: String): List<PriceTraceBrandMatch> {
            val keys = setOf("brand_id","canonical_name","parent_brand_id","parent_canonical_name","matched_name","match_kind","logo_url","official_site_url")
            val rows = Json.parseToJsonElement(body).jsonArray.map { element ->
                val r = element.jsonObject
                require(r.keys == keys) { "invalid_brand_catalog_shape" }
                fun text(key: String): String? { val v = r.getValue(key); require(v is JsonNull || v is JsonPrimitive && v.isString); return v.jsonPrimitive.contentOrNull }
                val id=requireNotNull(text("brand_id")); val name=requireNotNull(text("canonical_name")); val parent=text("parent_brand_id")
                val kind=requireNotNull(text("match_kind")); val matched=requireNotNull(text("matched_name"))
                require(validBrandUuid(id) && (parent==null || validBrandUuid(parent)) && name.isNotBlank() && matched.isNotBlank() && kind in setOf("canonical","alias"))
                val logo=text("logo_url"); val site=text("official_site_url")
                listOfNotNull(logo,site).forEach { require(it.startsWith("https://",true) || it.startsWith("http://",true)) }
                PriceTraceBrandMatch(id,name,parent,text("parent_canonical_name"),matched,kind,logo,site)
            }
            require(rows.map { it.brandId }.distinct().size == rows.size)
            return rows
        }
        fun parseResolutions(body: String, authorityKey: String, candidate: ProductCandidate): List<JsonObject> {
            val rows=Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            val fields=mutableSetOf<String>(); val fingerprints=mutableSetOf<String>()
            rows.forEach { r ->
                val expected=setOf("product_client_key","request_fingerprint","source_field","observed_value","brand_id","canonical_name","brand_active","parent_brand_id","parent_canonical_name","created_at","updated_at","pair_relationship","pair_relationship_valid","requires_review")
                require(r.keys==expected) { "invalid_brand_resolution_shape" }
                fun text(k:String):String? {
                    val value=r.getValue(k)
                    require(value is JsonNull || value is JsonPrimitive && value.isString) { "invalid_brand_resolution_value" }
                    return value.jsonPrimitive.contentOrNull
                }
                require(text("product_client_key")==authorityKey) { "brand_resolution_client_key_mismatch" }
                val field=ProductBrandFactField.fromWire(requireNotNull(text("source_field")))
                require(fields.add(field.wire) && text("observed_value")==field.observed(candidate)?.trim()) { "brand_resolution_fact_mismatch" }
                require(validBrandUuid(text("brand_id")) && (text("parent_brand_id")==null || validBrandUuid(text("parent_brand_id"))))
                require(text("request_fingerprint")?.matches(Regex("[0-9a-f]{64}"))==true)
                require(r["brand_active"]?.jsonPrimitive?.booleanOrNull != null && r["brand_active"]?.jsonPrimitive?.isString==false)
                fun bool(key: String): Boolean {
                    val value = r.getValue(key) as? JsonPrimitive
                    require(value != null && !value.isString && value.booleanOrNull != null)
                    return value.boolean
                }
                val relation = requireNotNull(text("pair_relationship"))
                ProductBrandPairStatus(relation, bool("pair_relationship_valid"), bool("requires_review"), bool("brand_active"))
                listOf("canonical_name","parent_canonical_name").forEach { text(it) }
                OffsetDateTime.parse(text("created_at")); OffsetDateTime.parse(text("updated_at"))
                fingerprints.add(requireNotNull(text("request_fingerprint")))
            }
            val relations = rows.map { it.getValue("pair_relationship").jsonPrimitive.content }.distinct()
            require(relations.size <= 1) { "inconsistent_brand_pair_relationship" }
            if (rows.isNotEmpty()) {
                val relation = relations.single()
                require((rows.size == 1) == (relation == "single")) { "invalid_brand_pair_cardinality" }
                if (rows.size == 2) require((rows[0]["brand_id"] == rows[1]["brand_id"]) == (relation == "same_brand"))
            }
            require(fingerprints.size<=1) { "brand_resolution_revision_mismatch" }
            return rows
        }
    }
}
