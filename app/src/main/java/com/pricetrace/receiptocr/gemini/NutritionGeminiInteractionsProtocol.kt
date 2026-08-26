package com.pricetrace.receiptocr.gemini

import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionPrompt
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionRequest
import com.pricetrace.receiptscanner.nutrition.NutritionEvidenceVerdict
import com.pricetrace.receiptscanner.nutrition.NutritionFieldVerdict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

internal object NutritionGeminiInteractionsProtocol {
    private const val MAX_INLINE_IMAGE_BYTES = 10 * 1024 * 1024

    fun createRequestBody(
        request: NutritionCorrectionRequest,
        modelName: String,
    ): String {
        var attachedBytes = 0
        val input = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", NutritionCorrectionPrompt.build(request))
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
                            "Cropped nutrition evidence ${evidence.id} supports sourceLineIds=" +
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
                    put("schema", responseSchema())
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

    private fun responseSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "evidenceVerdict",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(NutritionEvidenceVerdict.entries.map { JsonPrimitive(it.wireValue) }))
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
                                                put("enum", JsonArray(NutritionFieldVerdict.entries.map { JsonPrimitive(it.wireValue) }))
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
                                    JsonArray(listOf("fieldPath", "verdict", "sourceLineIds", "reason").map(::JsonPrimitive)),
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
        put("required", JsonArray(listOf("evidenceVerdict", "fieldChecks", "candidates").map(::JsonPrimitive)))
    }

    private fun stringSchema(): JsonObject = buildJsonObject { put("type", "string") }
}
