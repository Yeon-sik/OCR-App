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
    const val VERSION = "gemini-receipt-preflight.v3"

    private val json = Json { prettyPrint = false }

    fun build(request: ReceiptCorrectionRequest): String {
        val payload = buildJsonObject {
            put("documentId", request.documentId)
            put("targets", buildJsonArray {
                request.targets.forEach { target ->
                    add(
                        buildJsonObject {
                            put("lineItemId", target.lineItemId)
                            put("targetPath", target.fieldPath.ifBlank { "line_items[${target.lineItemId}]" })
                            putNullable("currentValue", target.currentValue ?: target.description)
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
            You are a conservative Korean receipt OCR preflight reviewer.
            Review every supplied target, including merchant identity fields and product-row fields.
            Use only the supplied OCR lines and cropped evidence images. Never claim that the whole receipt is verified.
            merchantVerdict means only that the supplied receipt evidence is consistent with a merchant identity;
            it does not prove legal registration, online existence, current operation, or a real-world registry match.

            Hard rules:
            1. You may change only an existing target field. Never add or remove a line item.
            2. Candidate fieldPath must be exactly one of:
               merchant.name
               merchant.branch_name
               merchant.business_registration_number
               merchant.address
               merchant.phone
               line_items[<lineItemId>].description
               line_items[<lineItemId>].quantity
               line_items[<lineItemId>].unit_price_amount_minor
               line_items[<lineItemId>].net_amount_minor
            3. oldValue must exactly match the target currentValue; use an empty string for null.
            4. Every fieldCheck and candidate must cite sourceLineIds supplied for that target.
            5. Do not invent a value that is not supported by the OCR text or attached crop.
            6. Merchant type checks are strict:
               - merchant.address must be address-like; reject a store name, person name, phone number, or business number.
               - merchant.phone must be a phone number, not an address, name, or business number.
               - merchant.business_registration_number must be a business number, not a phone number or address.
               - merchant.name and merchant.branch_name must not be an address, phone number, business number, or person-label value.
            7. If quantity, unit price, and line amount are all known, quantity multiplied by unit price must equal line amount.
            8. If evidence is ambiguous or a field has no supporting line, mark it insufficient_evidence and omit the correction.
            9. Return at most 12 candidates and only one candidate per fieldPath.
            10. Do not repeat full sensitive values in reason. Keep reason short and Korean.

            For every supplied merchant target, return exactly one fieldChecks entry with:
            fieldPath, verdict, sourceLineIds, reason.
            fieldChecks.verdict must be exactly one of:
            - matches_evidence: current value has the correct type and is supported by the cited lines.
            - needs_review: evidence is readable but current value is not safely confirmed.
            - wrong_field_type: the current value belongs to another field type.
            - insufficient_evidence: evidence is missing, blurred, or ambiguous.
            merchantVerdict must be plausible only when all supplied merchant fields are matches_evidence;
            otherwise use needs_review or insufficient_evidence.

            evidenceVerdict must be exactly one of:
            - plausible: all supplied targets are supported and candidates is empty.
            - needs_review: at least one target conflicts with readable evidence; include only safe candidates.
            - insufficient_evidence: the supplied evidence is not sufficient for a safe decision.

            reason must be a short Korean explanation.
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
