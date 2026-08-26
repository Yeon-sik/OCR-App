package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionPrompt
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionRequest
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceVerdict
import com.pricetrace.receiptscanner.correction.ReceiptFieldVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

internal object GeminiInteractionsProtocol {
    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private const val MAX_INLINE_IMAGE_BYTES = 10 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun createRequestBody(
        request: ReceiptCorrectionRequest,
        modelName: String,
    ): String {
        var attachedBytes = 0
        val input = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", ReceiptCorrectionPrompt.build(request))
                },
            )
            request.evidenceImages.forEach { evidence ->
                if (evidence.bytes.isEmpty() || attachedBytes + evidence.bytes.size > MAX_INLINE_IMAGE_BYTES) {
                    return@forEach
                }
                attachedBytes += evidence.bytes.size
                add(
                    buildJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            "Cropped evidence ${evidence.id} supports sourceLineIds=" +
                                evidence.sourceLineIds.joinToString(prefix = "[", postfix = "]"),
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("data", Base64.getEncoder().encodeToString(evidence.bytes))
                        put("mime_type", evidence.mimeType)
                    },
                )
            }
        }

        return buildJsonObject {
            put("model", modelName)
            put("input", input)
            put(
                "response_format",
                buildJsonObject {
                    put("type", "text")
                    put("mime_type", "application/json")
                    put("schema", correctionResponseSchema())
                },
            )
            put("store", false)
            put("background", false)
            put("stream", false)
            put(
                "generation_config",
                buildJsonObject {
                    put("max_output_tokens", 4_096)
                    put("thinking_level", "minimal")
                },
            )
        }.toString()
    }

    fun extractOutputText(responseBody: String): String? = runCatching {
        val root = json.parseToJsonElement(responseBody).jsonObject
        if (root["status"]?.jsonPrimitive?.contentOrNull != "completed") return@runCatching null
        root["steps"]
            ?.jsonArray
            ?.asSequence()
            ?.map(JsonElement::jsonObject)
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "model_output" }
            ?.flatMap { step -> step["content"]?.jsonArray?.asSequence().orEmpty() }
            ?.map(JsonElement::jsonObject)
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(separator = "\n")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun correctionResponseSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "evidenceVerdict",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(ReceiptEvidenceVerdict.entries.map { JsonPrimitive(it.wireValue) }))
                    },
                )
                put(
                    "merchantVerdict",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(ReceiptEvidenceVerdict.entries.map { JsonPrimitive(it.wireValue) }))
                    },
                )
                put(
                    "fieldChecks",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("fieldPath", stringSchema())
                                        put(
                                            "verdict",
                                            buildJsonObject {
                                                put("type", "string")
                                                put("enum", JsonArray(ReceiptFieldVerdict.entries.map { JsonPrimitive(it.wireValue) }))
                                            },
                                        )
                                        put(
                                            "sourceLineIds",
                                            buildJsonObject {
                                                put("type", "array")
                                                put("items", stringSchema())
                                            },
                                        )
                                        put("reason", stringSchema())
                                    },
                                )
                                put(
                                    "required",
                                    JsonArray(
                                        listOf("fieldPath", "verdict", "sourceLineIds", "reason").map(::JsonPrimitive),
                                    ),
                                )
                            },
                        )
                    },
                )
                put(
                    "candidates",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("fieldPath", stringSchema())
                                        put("oldValue", stringSchema())
                                        put("proposedValue", stringSchema())
                                        put(
                                            "sourceLineIds",
                                            buildJsonObject {
                                                put("type", "array")
                                                put("items", stringSchema())
                                            },
                                        )
                                        put("confidencePercent", buildJsonObject { put("type", "integer") })
                                        put("reason", stringSchema())
                                    },
                                )
                                put(
                                    "required",
                                    JsonArray(
                                        listOf(
                                            "fieldPath",
                                            "oldValue",
                                            "proposedValue",
                                            "sourceLineIds",
                                            "confidencePercent",
                                            "reason",
                                        ).map(::JsonPrimitive),
                                    ),
                                )
                            },
                        )
                    },
                )
            },
        )
        put(
            "required",
            JsonArray(listOf("evidenceVerdict", "merchantVerdict", "fieldChecks", "candidates").map(::JsonPrimitive)),
        )
    }

    private fun stringSchema(): JsonObject = buildJsonObject { put("type", "string") }
}
