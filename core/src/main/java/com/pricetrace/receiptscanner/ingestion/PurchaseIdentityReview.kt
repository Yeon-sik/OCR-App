package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.*

/** Shared runtime review protocol. None of these selectors is a canonical fact. */
enum class PurchaseIdentityEntity(val wireValue: String, val label: String) {
    RESTAURANT("restaurant", "식당"), MENU("menu", "메뉴"), PLATFORM("platform", "플랫폼")
}
enum class PurchaseIdentityAction(val wireValue: String) {
    SEARCH("search"), SELECT("select"), CREATE("create"), DEFER("defer")
}
data class PurchaseIdentityCommand(
    val purchaseRecordClientKey: String, val lineKey: String,
    val entity: PurchaseIdentityEntity, val action: PurchaseIdentityAction,
    val query: String = "", val selectedId: String? = null,
    val selectedKind: String = "owner_confirmed",
)
data class PurchaseIdentityChoice(val id: String, val name: String, val branchName: String? = null, val identityKind: String = "owner_confirmed",
    val sourceNamespace: String? = null,val sourceCode: String? = null) {
    val label: String get() = listOfNotNull(name, branchName,sourceNamespace?.let { "$it / $sourceCode" },
        if(identityKind == "global_verified") "공개 검증 · 내 기록에 연결" else "내 확인").joinToString(" · ")
}
data class PurchaseIdentityReviewState(
    val purchaseRecordClientKey: String, val lineKey: String, val entity: PurchaseIdentityEntity,
    val query: String, val status: String, val choices: List<PurchaseIdentityChoice>,
    val selected: PurchaseIdentityChoice? = null,
)
data class PurchaseIdentityReviewRequest(
    val command: PurchaseIdentityCommand, val purchaseSourceId: String,
    val envelope: YeonsikOcrEnvelope,
)
interface PurchaseIdentityReviewSubmitter {
    suspend fun reviewPurchaseIdentity(request: PurchaseIdentityReviewRequest): ProjectionSubmission
}

data class PurchaseIdentityReviewCard(
    val command: PurchaseIdentityCommand, val observed: String, val state: PurchaseIdentityReviewState?,
    val linkedLabel: String?, val authorityStatus: String?,
    val sourceSaved: Boolean = true,
) {
    val canCreate: Boolean get() = state != null && state.choices.isEmpty() && state.selected == null &&
        state.status == "needs_selection" && linkedLabel == null
    val statusLabel: String get() = linkedLabel?.let { "연결: $it" } ?: when (state?.status) {
        "deferred" -> "보류 중"
        "restaurant_required" -> "식당을 먼저 연결하세요"
        else -> "연결할 항목을 검색하세요"
    }
}

object PurchaseIdentityReview {
    fun cards(envelope: YeonsikOcrEnvelope, metadata: String?): List<PurchaseIdentityReviewCard> {
        if (envelope.schemaVersion != YEONSIK_OCR_V5_SCHEMA) return emptyList()
        val sources = runCatching { Json.parseToJsonElement(metadata ?: "{}").jsonObject["sources"]?.jsonArray?.map { it.jsonObject } }.getOrNull().orEmpty()
        return envelope.purchaseRecords.flatMap { record -> record.lineItems.flatMapIndexed { index, line ->
            val lineKey = line.lineKey ?: return@flatMapIndexed emptyList()
            val source = sources.singleOrNull { it["purchaseRecordClientKey"] == JsonPrimitive(record.clientKey) }
            val row = (source?.get("lineResults") as? JsonArray)?.map { it.jsonObject }?.singleOrNull { it["lineKey"] == JsonPrimitive(lineKey) }
            val entities = if (index == 0) PurchaseIdentityEntity.entries else listOf(PurchaseIdentityEntity.RESTAURANT,PurchaseIdentityEntity.MENU)
            entities.map { entity ->
                val observed = when (entity) { PurchaseIdentityEntity.RESTAURANT -> line.sellerOverride ?: record.seller.orEmpty(); PurchaseIdentityEntity.MENU -> line.description.orEmpty(); PurchaseIdentityEntity.PLATFORM -> record.platform }
                val command = PurchaseIdentityCommand(record.clientKey,lineKey,entity,PurchaseIdentityAction.SEARCH,observed.take(200))
                val state = state(metadata,command) ?: if (entity == PurchaseIdentityEntity.RESTAURANT) {
                    PurchaseNutritionIdentity.ownerRestaurantChoices(metadata).singleOrNull { it.purchaseRecordClientKey == record.clientKey && it.lineKey == lineKey }
                        ?.let { group -> PurchaseIdentityReviewState(record.clientKey,lineKey,entity,observed,"needs_selection",
                            group.choices.map { PurchaseIdentityChoice(it.restaurantId,it.merchantName,it.branchName,sourceNamespace = it.sourceNamespace,sourceCode = it.sourceCode) }) }
                } else null
                val authority = if (entity == PurchaseIdentityEntity.PLATFORM) (source?.get("platformIdentity") as? JsonObject)?.get("authorityStatus")?.jsonPrimitive?.contentOrNull
                    else row?.get("authorityStatus")?.jsonPrimitive?.contentOrNull
                val linked = state?.selected?.label ?: if (entity == PurchaseIdentityEntity.PLATFORM)
                    (source?.get("platformIdentity") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                    else if (row?.get("observationCreated") == JsonPrimitive(true)) when(authority) {
                        "owner_confirmed" -> "내 확인 ${entity.label}"
                        "exact" -> "검증된 ${entity.label}"
                        else -> null
                    } else if (entity == PurchaseIdentityEntity.RESTAURANT && row?.get("ownerRestaurantId") is JsonPrimitive) "내 확인 식당" else null
                PurchaseIdentityReviewCard(command,observed,state,linked,authority,source?.get("sourceSaved") == JsonPrimitive(true) && source["sourceAcceptanceStatus"] == JsonPrimitive("accepted"))
            }
        } }
    }

    fun states(metadata: String?): List<PurchaseIdentityReviewState> = runCatching {
        (Json.parseToJsonElement(metadata ?: return emptyList()).jsonObject["identityReviews"] as? JsonArray).orEmpty().map { raw ->
            val row = raw.jsonObject
            fun choice(value: JsonElement): PurchaseIdentityChoice {
                val item = value.jsonObject
                val id = item.getValue("id").jsonPrimitive.content
                require(java.util.UUID.fromString(id).toString().equals(id, true))
                val kind = item["identityKind"]?.jsonPrimitive?.contentOrNull ?: "owner_confirmed"
                require(kind in setOf("owner_confirmed","global_verified"))
                val namespace = item["sourceNamespace"]?.jsonPrimitive?.contentOrNull
                val code = item["sourceCode"]?.jsonPrimitive?.contentOrNull
                require((namespace == null) == (code == null))
                return PurchaseIdentityChoice(id, item.getValue("name").jsonPrimitive.content,
                    item["branchName"]?.jsonPrimitive?.contentOrNull ?: item["servingLabel"]?.jsonPrimitive?.contentOrNull,kind,namespace,code)
            }
            val choices = row.getValue("choices").jsonArray.map(::choice)
            require(choices.map { it.id }.distinct().size == choices.size)
            PurchaseIdentityReviewState(row.getValue("purchaseRecordClientKey").jsonPrimitive.content,
                row.getValue("lineKey").jsonPrimitive.content,
                PurchaseIdentityEntity.entries.single { it.wireValue == row.getValue("entity").jsonPrimitive.content },
                row.getValue("query").jsonPrimitive.content, row.getValue("status").jsonPrimitive.content,
                choices, row["selected"]?.takeIf { it != JsonNull }?.let(::choice))
        }
    }.getOrDefault(emptyList())

    fun state(metadata: String?, command: PurchaseIdentityCommand): PurchaseIdentityReviewState? = states(metadata).singleOrNull {
        it.purchaseRecordClientKey == command.purchaseRecordClientKey && it.lineKey == command.lineKey && it.entity == command.entity
    }

    fun merge(metadata: String?, command: PurchaseIdentityCommand, response: JsonObject): String {
        val root = metadata?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        require(response["entity"] == JsonPrimitive(command.entity.wireValue))
        val row = JsonObject(response + mapOf("purchaseRecordClientKey" to JsonPrimitive(command.purchaseRecordClientKey),
            "lineKey" to JsonPrimitive(command.lineKey)))
        val old = (root["identityReviews"] as? JsonArray).orEmpty().filterNot {
            val value = it.jsonObject
            value["purchaseRecordClientKey"] == row["purchaseRecordClientKey"] && value["lineKey"] == row["lineKey"] && value["entity"] == row["entity"]
        }
        val result = JsonObject(root + ("identityReviews" to JsonArray(old + row))).toString()
        require(states(result).any { it.purchaseRecordClientKey == command.purchaseRecordClientKey && it.lineKey == command.lineKey && it.entity == command.entity })
        return result
    }
}
