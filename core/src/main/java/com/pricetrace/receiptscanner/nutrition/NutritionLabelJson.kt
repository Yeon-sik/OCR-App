package com.pricetrace.receiptscanner.nutrition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

object NutritionLabelJson {
    private val json = Json { prettyPrint = true }

    fun encode(draft: NutritionLabelDraft): String = json.encodeToString(
        JsonElement.serializer(),
        draft.toJson(includeEvidence = true),
    )

    fun decode(value: String): NutritionLabelDraft {
        val root = json.parseToJsonElement(value) as? JsonObject ?: error("Nutrition draft must be an object")
        require(root.string("schema_version") == FITNESS_NUTRITION_DRAFT_SCHEMA) {
            "Unsupported nutrition draft schema"
        }
        val nutrientsObject = root.objectValue("nutrients")
        val nutrients = NutritionField.entries.mapNotNull { field ->
            val element = nutrientsObject[field.wireKey]
            val amount = when (element) {
                null, JsonNull -> null
                else -> (element as? JsonPrimitive)?.doubleOrNull
                    ?: error("${field.wireKey} must be a number or null")
            }
            amount?.let { field to it }
        }.toMap()
        val evidence = root.objectValue("evidence").mapValues { (_, element) ->
            (element as? JsonArray ?: error("Evidence must be an array")).map { item ->
                val entry = item as? JsonObject ?: error("Evidence entry must be an object")
                NutritionFieldEvidence(
                    ocrLineId = entry.string("ocr_line_id"),
                    pageId = entry.string("page_id"),
                    rawText = entry.string("raw_text"),
                    confidence = when (val confidence = entry["confidence"]) {
                        null, JsonNull -> null
                        else -> (confidence as? JsonPrimitive)?.doubleOrNull?.toFloat()
                            ?: error("confidence must be a number or null")
                    },
                )
            }
        }
        return NutritionLabelDraft(
            documentId = root.string("document_id"),
            parserVersion = root.string("parser_version"),
            productName = root.string("name"),
            brand = root.nullableString("brand"),
            category = root.string("category"),
            basisAmount = root.nullableNumber("basis_amount"),
            basisUnit = root.string("basis_unit"),
            nutrients = nutrients,
            evidence = evidence,
            parseWarnings = (root["parse_warnings"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.contentOrNull ?: error("Warning must be text") }
                .orEmpty(),
            status = NutritionDraftStatus.fromWireValue(root.string("status")),
            confirmedAt = root.nullableString("confirmed_at"),
        )
    }

    /** Public server payload: deliberately excludes OCR text, line evidence, and source images. */
    fun encodeServerRow(
        draft: NutritionLabelDraft,
        ownerId: String,
        updatedAt: String,
        revision: Int? = null,
        includeIdentity: Boolean = true,
    ): String = json.encodeToString(
        JsonElement.serializer(),
        draft.toServerRow(ownerId, updatedAt, revision, includeIdentity),
    )

    fun serverRow(
        draft: NutritionLabelDraft,
        ownerId: String,
        updatedAt: String,
        revision: Int? = null,
        includeIdentity: Boolean = true,
    ): JsonObject = draft.toServerRow(ownerId, updatedAt, revision, includeIdentity)

    private fun NutritionLabelDraft.toJson(includeEvidence: Boolean): JsonObject = JsonObject(
        linkedMapOf(
            "schema_version" to JsonPrimitive(FITNESS_NUTRITION_DRAFT_SCHEMA),
            "document_id" to JsonPrimitive(documentId),
            "parser_version" to JsonPrimitive(parserVersion),
            "status" to JsonPrimitive(status.wireValue),
            "confirmed_at" to confirmedAt.jsonString(),
            "name" to JsonPrimitive(productName),
            "brand" to brand.jsonString(),
            "kind" to JsonPrimitive(NutritionContract.KIND_EXTERNAL_MENU),
            "category" to JsonPrimitive(category),
            "basis_amount" to basisAmount.jsonNumber(),
            "basis_unit" to JsonPrimitive(NutritionUnit.normalize(basisUnit)),
            "prep_state" to JsonPrimitive(NutritionContract.PREP_UNSPECIFIED),
            "cooking_method" to JsonPrimitive(NutritionContract.COOKING_UNSPECIFIED),
            "nutrients" to JsonObject(
                NutritionField.entries.associate { field -> field.wireKey to value(field).jsonNumber() },
            ),
            "source_type" to JsonPrimitive(NutritionContract.SOURCE_TYPE),
            "source_reference" to JsonPrimitive(sourceReference),
            "source_version" to JsonPrimitive(parserVersion),
            "data_version" to JsonPrimitive(FITNESS_NUTRITION_DATA_VERSION),
            "visibility" to JsonPrimitive(NutritionContract.VISIBILITY_PRIVATE),
            "parse_warnings" to JsonArray(parseWarnings.map(::JsonPrimitive)),
            "evidence" to if (includeEvidence) JsonObject(
                evidence.mapValues { (_, values) ->
                    JsonArray(
                        values.map { item ->
                            JsonObject(
                                linkedMapOf(
                                    "ocr_line_id" to JsonPrimitive(item.ocrLineId),
                                    "page_id" to JsonPrimitive(item.pageId),
                                    "raw_text" to JsonPrimitive(item.rawText),
                                    "confidence" to item.confidence?.toDouble().jsonNumber(),
                                ),
                            )
                        },
                    )
                },
            ) else JsonObject(emptyMap()),
        ),
    )

    private fun NutritionLabelDraft.toServerRow(
        ownerId: String,
        updatedAt: String,
        revision: Int?,
        includeIdentity: Boolean,
    ): JsonObject {
        require(status == NutritionDraftStatus.USER_VERIFIED) { "Only user-verified nutrition can be uploaded" }
        require(NutritionLabelValidator.validate(this).isReadyForUpload) { "Nutrition draft is incomplete" }
        require(ownerId.isNotBlank()) { "Nutrition owner is required" }
        require(updatedAt.isNotBlank()) { "Nutrition update timestamp is required" }
        val values = linkedMapOf<String, JsonElement>()
        if (includeIdentity) {
            values["id"] = JsonPrimitive(foodId)
            values["owner_id"] = JsonPrimitive(ownerId)
        }
        values["name"] = JsonPrimitive(productName.trim())
        values["brand"] = brand?.trim()?.takeIf(String::isNotEmpty).jsonString()
        values["kind"] = JsonPrimitive(NutritionContract.KIND_EXTERNAL_MENU)
        values["category"] = JsonPrimitive(category)
        values["basis_amount"] = requireNotNull(basisAmount).jsonNumber()
        values["basis_unit"] = JsonPrimitive(NutritionUnit.normalize(basisUnit))
        values["prep_state"] = JsonPrimitive(NutritionContract.PREP_UNSPECIFIED)
        values["cooking_method"] = JsonPrimitive(NutritionContract.COOKING_UNSPECIFIED)
        NutritionField.entries.forEach { field -> values[field.wireKey] = value(field).jsonNumber() }
        values["source_type"] = JsonPrimitive(NutritionContract.SOURCE_TYPE)
        values["source_reference"] = JsonPrimitive(sourceReference)
        values["source_version"] = JsonPrimitive(parserVersion)
        values["data_version"] = JsonPrimitive(FITNESS_NUTRITION_DATA_VERSION)
        values["visibility"] = JsonPrimitive(NutritionContract.VISIBILITY_PRIVATE)
        values["updated_at"] = JsonPrimitive(updatedAt)
        values["deleted_at"] = JsonNull
        revision?.let { values["revision"] = JsonPrimitive(it) }
        return JsonObject(values)
    }

    private fun String?.jsonString(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Double?.jsonNumber(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull
        ?: error("$key must be a string")

    private fun JsonObject.nullableString(key: String): String? = when (val element = get(key)) {
        null, JsonNull -> null
        else -> (element as? JsonPrimitive)?.contentOrNull ?: error("$key must be a string or null")
    }

    private fun JsonObject.nullableNumber(key: String): Double? = when (val element = get(key)) {
        null, JsonNull -> null
        else -> (element as? JsonPrimitive)?.doubleOrNull ?: error("$key must be a number or null")
    }

    private fun JsonObject.objectValue(key: String): JsonObject = get(key) as? JsonObject
        ?: error("$key must be an object")
}
