package com.pricetrace.receiptscanner.correction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ReceiptCorrectionPrompt {
    const val VERSION = "gemini-receipt-correction.v1"

    private val json = Json { prettyPrint = false }

    fun build(request: ReceiptCorrectionRequest): String {
        val payload = buildJsonObject {
            put("documentId", request.documentId)
            put("targets", buildJsonArray {
                request.targets.forEach { target ->
                    add(
                        buildJsonObject {
                            put("lineItemId", target.lineItemId)
                            putNullable("description", target.description)
                            putNullable("quantity", target.quantity)
                            putNullable("unitPriceAmountMinor", target.unitPriceAmountMinor)
                            putNullable("netAmountMinor", target.netAmountMinor)
                            put("sourceLineIds", JsonArray(target.sourceLineIds.map(::JsonPrimitive)))
                        },
                    )
                }
            })
            put("evidenceLines", buildJsonArray {
                request.evidenceLines.forEach { line ->
                    add(
                        buildJsonObject {
                            put("id", line.id)
                            put("pageIndex", line.pageIndex)
                            put("text", line.text)
                        },
                    )
                }
            })
        }
        return """
            You are a conservative Korean receipt OCR correction suggester.
            Return suggestions only; never claim that a value is verified.

            Hard rules:
            1. You may change only an existing target field. Never add or remove a line item.
            2. fieldPath must be exactly one of:
               line_items[<lineItemId>].description
               line_items[<lineItemId>].quantity
               line_items[<lineItemId>].unit_price_amount_minor
               line_items[<lineItemId>].net_amount_minor
            3. oldValue must exactly match the current target value; use an empty string for null.
            4. Every suggestion must cite one or more sourceLineIds belonging to that same target.
            5. Do not invent text that is not supported by the OCR text or the attached cropped evidence image.
            6. If quantity, unit price, and line amount are all known, quantity multiplied by unit price must equal line amount.
            7. If evidence is ambiguous, omit the suggestion. An empty candidates array is valid.
            8. Do not output addresses, phone numbers, business numbers, card data, transaction IDs, or explanations containing them.
            9. Return at most 12 candidates and only one candidate per fieldPath.

            reason must be a short Korean explanation without personal information.
            confidencePercent must be an integer from 0 to 100.

            Input JSON:
            ${json.encodeToString(JsonObject.serializer(), payload)}
        """.trimIndent()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }
}
