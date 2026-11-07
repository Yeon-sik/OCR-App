package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.*
import java.util.UUID

enum class SavedIdentityAction { LOAD, SEARCH, CONNECT }
data class SavedIdentityCommand(
    val action: SavedIdentityAction = SavedIdentityAction.LOAD,
    val entity: String? = null, val sourceId: String? = null,
    val query: String = "", val targetId: String? = null,
)
data class SavedIdentityChoice(val id: String, val name: String, val branch: String? = null,
    val context: String? = null, val similarity: Double = 0.0, val matchKind: String = "contains") {
    val label get() = listOfNotNull(name, branch, context).joinToString(" · ")
    val matchLabel get() = when (matchKind) {
        "exact" -> "같은 이름"
        "alias" -> "등록된 다른 표기"
        "similar" -> "이름 유사도 ${kotlin.math.round(similarity * 100).toInt()}%"
        else -> "이름 포함"
    }
}
data class SavedIdentitySource(val entity: String, val sourceId: String, val name: String,
    val branch: String?, val context: String?, val selected: SavedIdentityChoice?) {
    val key get() = "$entity:$sourceId"
    val entityLabel get() = when (entity) { "brand" -> "브랜드"; "store" -> "가게"; else -> "식당" }
    val label get() = listOfNotNull(entityLabel, name, branch, context).joinToString(" · ")
}
data class SavedIdentityState(val ownerId: String, val sources: List<SavedIdentitySource>, val truncated: Boolean,
    val searchEntity: String? = null, val searchSourceId: String? = null, val query: String? = null,
    val choices: List<SavedIdentityChoice> = emptyList())
data class SavedIdentityRequest(val command: SavedIdentityCommand, val recordIds: List<String>)
interface SavedIdentitySubmitter {
    suspend fun reviewSavedIdentity(request: SavedIdentityRequest): ProjectionSubmission
}

object SavedIdentityConnections {
    const val MAX_RECORD_IDS = 500
    val projections = setOf(IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
    fun recordIds(session: IngestionSession): List<String> {
        val result = linkedSetOf<String>()
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (key == "identityConnections") return@forEach
                    if (key in setOf("receiptId", "candidateId", "purchaseSourceId", "storeId"))
                        (child as? JsonPrimitive)?.contentOrNull?.takeIf(::isUuid)?.let(result::add)
                    else visit(child)
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        session.projections.filter { it.projection in projections }.forEach {
            runCatching { Json.parseToJsonElement(it.metadataJson ?: "{}") }.getOrNull()?.let(::visit)
        }
        return result.toList()
    }

    fun validateRecordIds(recordIds: List<String>) {
        require(recordIds.isNotEmpty() && recordIds.size <= MAX_RECORD_IDS) {
            "연결 대상은 1개부터 ${MAX_RECORD_IDS}개까지 불러올 수 있습니다. 수집 기록을 나누어 확인하세요."
        }
        require(recordIds.all(::isUuid)) { "저장된 기록 ID를 확인할 수 없습니다. 기록을 다시 불러오세요." }
    }
    fun state(session: IngestionSession?): SavedIdentityState? = session?.projections
        ?.filter { it.projection in projections }?.firstNotNullOfOrNull { projection ->
            runCatching { Json.parseToJsonElement(projection.metadataJson ?: "{}").jsonObject["identityConnections"]?.jsonObject?.let(::decode) }.getOrNull()
        }
    fun decode(root: JsonObject): SavedIdentityState {
        val inventory = root.getValue("inventory").jsonObject
        require(inventory["schemaVersion"]?.jsonPrimitive?.content == "identity-connections.v1")
        val owner = inventory.getValue("ownerId").jsonPrimitive.content.also { require(isUuid(it)) }
        fun choice(row: JsonObject, scored: Boolean = false): SavedIdentityChoice {
            val id = row.getValue("id").jsonPrimitive.content.also { require(isUuid(it)) }
            val name = row.getValue("name").jsonPrimitive.content.also { require(it.isNotBlank()) }
            val score = if (scored) row.getValue("similarity").jsonPrimitive.double.also { require(it.isFinite() && it in 0.0..1.0) } else 0.0
            val kind = if (scored) row.getValue("matchKind").jsonPrimitive.content.also { require(it in setOf("exact","alias","similar","contains")) } else "contains"
            return SavedIdentityChoice(id,name,row["branchName"]?.jsonPrimitive?.contentOrNull,row["context"]?.jsonPrimitive?.contentOrNull,score,kind)
        }
        val sources = inventory.getValue("sources").jsonArray.map { raw ->
            val row = raw.jsonObject
            val entity = row.getValue("entity").jsonPrimitive.content.also { require(it in setOf("brand","store","restaurant")) }
            SavedIdentitySource(entity,row.getValue("sourceId").jsonPrimitive.content.also { require(isUuid(it)) },
                row.getValue("name").jsonPrimitive.content.also { require(it.isNotBlank()) },
                row["branchName"]?.jsonPrimitive?.contentOrNull,row["context"]?.jsonPrimitive?.contentOrNull,
                row["selected"]?.takeUnless { it == JsonNull }?.jsonObject?.let { choice(it) })
        }.also { require(it.size <= 200 && it.map { row -> row.key }.distinct().size == it.size) }
        val search = root["search"]?.takeUnless { it == JsonNull }?.jsonObject
        if (search != null) {
            require(search["schemaVersion"]?.jsonPrimitive?.content == "identity-search.v1")
            require(sources.any { it.entity == search["entity"]?.jsonPrimitive?.content && it.sourceId == search["sourceId"]?.jsonPrimitive?.content })
        }
        val choices = search?.get("choices")?.jsonArray?.map { choice(it.jsonObject,true) }.orEmpty()
        require(choices.size <= 20 && choices.map { it.id }.distinct().size == choices.size)
        return SavedIdentityState(owner,sources,inventory.getValue("truncated").jsonPrimitive.boolean,
            search?.get("entity")?.jsonPrimitive?.content,search?.get("sourceId")?.jsonPrimitive?.content,
            search?.get("query")?.jsonPrimitive?.content,choices)
    }
    private fun isUuid(value: String) = runCatching { UUID.fromString(value).toString().equals(value,true) }.getOrDefault(false)
}
