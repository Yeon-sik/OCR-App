package com.pricetrace.receiptscanner.nutrition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object NutritionCorrectionPrompt {
    const val VERSION = "gemini-nutrition-label-review.v1"

    private val json = Json { prettyPrint = false }

    fun build(request: NutritionCorrectionRequest): String {
        val payload = buildJsonObject {
            put("documentId", request.documentId)
            put("targets", buildJsonArray {
                request.targets.forEach { target ->
                    add(
                        buildJsonObject {
                            put("fieldPath", target.fieldPath)
                            putNullable("currentValue", target.currentValue)
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
            You are a conservative Korean packaged-food nutrition label reviewer.
            Review only the supplied OCR lines and cropped label evidence images.
            Do not use a food database, web knowledge, or estimated serving values.
            Never claim that the nutrition draft is verified; the human reviewer decides that.

            Hard rules:
            1. You may propose only an existing target fieldPath. Never add a new field.
            2. fieldPath must be exactly one of: product_name, brand, category, basis_amount, basis_unit,
               calories_kcal, protein_grams, carbs_grams, fat_grams, sodium_mg, saturated_fat_grams,
               sugars_grams, fiber_grams, added_sugars_grams, trans_fat_grams, cholesterol_mg.
            3. oldValue must exactly equal the supplied currentValue; use an empty string when currentValue is null.
            4. Every fieldCheck and candidate must cite only sourceLineIds supplied for that target.
            5. Never invent a number. Numeric proposedValue must contain only a decimal number in the target's canonical unit.
               Canonical units are kcal, g, or mg as named by the fieldPath.
            6. If the label has multiple serving bases or conflicting values, mark the field needs_review or
               insufficient_evidence and do not choose one automatically.
            7. Do not treat % daily value, barcode digits, calories from another serving basis, manufacturer phone,
               or ingredient percentages as the target nutrient amount.
            8. Return at most 16 candidates and only one candidate per fieldPath.
            9. Keep reason short and Korean. Do not repeat sensitive manufacturer contact details.

            For every supplied target, return exactly one fieldChecks entry with fieldPath, verdict, sourceLineIds, reason.
            fieldChecks.verdict must be one of:
            - matches_evidence: current value is supported by the cited label evidence.
            - needs_review: evidence is readable but current value is not safely confirmed.
            - wrong_field_type: the current value belongs to another label field.
            - insufficient_evidence: the label evidence is missing, blurred, or ambiguous.

            evidenceVerdict must be one of:
            - plausible: every target matches evidence and candidates is empty.
            - needs_review: one or more targets need a safe correction or human review.
            - insufficient_evidence: the supplied evidence cannot support a safe decision.
            A plausible response must contain no candidates.

            Input JSON:
            ${json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)}
        """.trimIndent()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }
}
