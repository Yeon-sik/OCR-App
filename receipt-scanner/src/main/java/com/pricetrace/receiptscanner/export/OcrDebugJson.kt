package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.domain.FieldProvenance
import com.pricetrace.receiptscanner.domain.ParsedReceipt
import com.pricetrace.receiptscanner.ocr.OcrBlock
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrElement
import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import com.pricetrace.receiptscanner.ocr.OcrLine
import com.pricetrace.receiptscanner.ocr.OcrPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

/** Private diagnostic format. It is deliberately separate from the receipt.v2 contract. */
object OcrDebugJson {
    private val json = Json { prettyPrint = true }

    fun encode(document: OcrDocument, parsedReceipt: ParsedReceipt? = null): String = json.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            linkedMapOf(
                "document_id" to JsonPrimitive(document.documentId),
                "engine" to JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(document.engine.name),
                        "version" to JsonPrimitive(document.engine.version),
                    ),
                ),
                "raw_text" to JsonPrimitive(document.rawText),
                "pages" to JsonArray(document.pages.map(::encodePage)),
                "parse_evidence" to (parsedReceipt?.toEvidenceJson() ?: JsonArray(emptyList())),
            ),
        ),
    )

    fun decode(value: String): OcrDocument {
        val root = json.parseToJsonElement(value).requiredObject("root")
        root.requireOnly("document_id", "engine", "raw_text", "pages", "parse_evidence")
        val engine = root.requiredObject("engine").also { it.requireOnly("name", "version") }
        root.requiredArray("parse_evidence")
        return OcrDocument(
            documentId = root.requiredString("document_id"),
            engine = OcrEngineInfo(
                name = engine.requiredString("name"),
                version = engine.requiredString("version"),
            ),
            rawText = root.requiredString("raw_text"),
            pages = root.requiredArray("pages").map { decodePage(it.requiredObject("page")) },
        )
    }

    private fun encodePage(page: OcrPage) = JsonObject(
        linkedMapOf(
            "page_id" to JsonPrimitive(page.pageId),
            "page_index" to JsonPrimitive(page.pageIndex),
            "raw_text" to JsonPrimitive(page.rawText),
            "blocks" to JsonArray(page.blocks.map(::encodeBlock)),
        ),
    )

    private fun encodeBlock(block: OcrBlock) = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(block.id),
            "text" to JsonPrimitive(block.text),
            "recognition_order" to JsonPrimitive(block.recognitionOrder),
            "bounding_box" to block.boundingBox.toJson(),
            "lines" to JsonArray(block.lines.map(::encodeLine)),
        ),
    )

    private fun encodeLine(line: OcrLine) = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(line.id),
            "text" to JsonPrimitive(line.text),
            "recognition_order" to JsonPrimitive(line.recognitionOrder),
            "confidence" to (line.confidence?.let(::JsonPrimitive) ?: JsonNull),
            "bounding_box" to line.boundingBox.toJson(),
            "elements" to JsonArray(line.elements.map(::encodeElement)),
        ),
    )

    private fun encodeElement(element: OcrElement) = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(element.id),
            "text" to JsonPrimitive(element.text),
            "recognition_order" to JsonPrimitive(element.recognitionOrder),
            "confidence" to (element.confidence?.let(::JsonPrimitive) ?: JsonNull),
            "bounding_box" to element.boundingBox.toJson(),
        ),
    )

    private fun ParsedReceipt.toEvidenceJson(): JsonArray {
        val entries = buildList<JsonElement> {
            addEvidence("merchant.name", merchantName.provenance)
            addEvidence("merchant.branch_name", branchName.provenance)
            addEvidence("merchant.business_registration_number", businessRegistrationNumber.provenance)
            addEvidence("merchant.address", address.provenance)
            addEvidence("merchant.phone", phone.provenance)
            addEvidence("document.issued_on", issuedOn.provenance)
            addEvidence("document.issued_time", issuedTime.provenance)
            addEvidence("document.issued_at", issuedAt.provenance)
            addEvidence("document.source.original_document_id", originalDocumentId.provenance)
            addEvidence("document.currency", currency.provenance)
            addEvidence("totals.subtotal_amount_minor", totals.subtotalAmountMinor.provenance)
            addEvidence("totals.discount_amount_minor", totals.discountAmountMinor.provenance)
            addEvidence("totals.tax_amount_minor", totals.taxAmountMinor.provenance)
            addEvidence("totals.fee_amount_minor", totals.feeAmountMinor.provenance)
            addEvidence("totals.grand_total_amount_minor", totals.grandTotalAmountMinor.provenance)
            lineItems.forEach { item ->
                val prefix = "line_items[${item.id}]"
                addEvidence("$prefix.description", item.description.provenance)
                addEvidence("$prefix.quantity", item.quantity.provenance)
                addEvidence("$prefix.unit_price_amount_minor", item.unitPriceAmountMinor.provenance)
                addEvidence("$prefix.gross_amount_minor", item.grossAmountMinor.provenance)
                addEvidence("$prefix.discount_amount_minor", item.discountAmountMinor.provenance)
                addEvidence("$prefix.tax_amount_minor", item.taxAmountMinor.provenance)
                addEvidence("$prefix.net_amount_minor", item.netAmountMinor.provenance)
                if (item.identifiers.isNotEmpty()) {
                    addEvidence("$prefix.identifiers", item.description.provenance)
                }
            }
        }
        return JsonArray(entries)
    }

    private fun MutableList<JsonElement>.addEvidence(path: String, provenance: List<FieldProvenance>) {
        if (provenance.isEmpty()) return
        add(
            JsonObject(
                linkedMapOf(
                    "field_path" to JsonPrimitive(path),
                    "user_modified" to JsonPrimitive(provenance.any(FieldProvenance::userModified)),
                    "sources" to JsonArray(provenance.map(::encodeProvenance)),
                ),
            ),
        )
    }

    private fun encodeProvenance(value: FieldProvenance) = JsonObject(
        linkedMapOf(
            "source_page_id" to JsonPrimitive(value.sourcePageId),
            "ocr_line_id" to JsonPrimitive(value.ocrLineId),
            "bounding_box" to value.boundingBox.toJson(),
            "raw_text" to JsonPrimitive(value.rawText),
            "parser_rule_id" to JsonPrimitive(value.parserRuleId),
            "confidence" to (value.confidence?.let(::JsonPrimitive) ?: JsonNull),
            "user_modified" to JsonPrimitive(value.userModified),
        ),
    )

    private fun decodePage(value: JsonObject): OcrPage {
        value.requireOnly("page_id", "page_index", "raw_text", "blocks")
        val pageId = value.requiredString("page_id")
        val pageIndex = value.requiredInt("page_index")
        return OcrPage(
            pageId = pageId,
            pageIndex = pageIndex,
            rawText = value.requiredString("raw_text"),
            blocks = value.requiredArray("blocks").mapIndexed { order, element ->
                decodeBlock(element.requiredObject("block"), pageId, pageIndex, order)
            },
        )
    }

    private fun decodeBlock(
        value: JsonObject,
        pageId: String,
        pageIndex: Int,
        fallbackOrder: Int,
    ): OcrBlock {
        value.requireOnly("id", "text", "recognition_order", "bounding_box", "lines")
        return OcrBlock(
            id = value.requiredString("id"),
            pageId = pageId,
            pageIndex = pageIndex,
            text = value.requiredString("text"),
            boundingBox = value.optionalBoundingBox("bounding_box"),
            lines = value.requiredArray("lines").mapIndexed { order, element ->
                decodeLine(element.requiredObject("line"), pageId, pageIndex, order)
            },
            recognitionOrder = value["recognition_order"].asPrimitive()?.intOrNull ?: fallbackOrder,
        )
    }

    private fun decodeLine(
        value: JsonObject,
        pageId: String,
        pageIndex: Int,
        fallbackOrder: Int,
    ): OcrLine {
        value.requireOnly("id", "text", "recognition_order", "confidence", "bounding_box", "elements")
        return OcrLine(
            id = value.requiredString("id"),
            pageId = pageId,
            pageIndex = pageIndex,
            text = value.requiredString("text"),
            boundingBox = value.optionalBoundingBox("bounding_box"),
            elements = value.requiredArray("elements").mapIndexed { order, element ->
                decodeElement(element.requiredObject("element"), order)
            },
            confidence = value.optionalFloat("confidence"),
            recognitionOrder = value["recognition_order"].asPrimitive()?.intOrNull ?: fallbackOrder,
        )
    }

    private fun decodeElement(value: JsonObject, fallbackOrder: Int): OcrElement {
        value.requireOnly("id", "text", "recognition_order", "confidence", "bounding_box")
        return OcrElement(
            id = value.requiredString("id"),
            text = value.requiredString("text"),
            boundingBox = value.optionalBoundingBox("bounding_box"),
            confidence = value.optionalFloat("confidence"),
            recognitionOrder = value["recognition_order"].asPrimitive()?.intOrNull ?: fallbackOrder,
        )
    }

    private fun BoundingBox?.toJson(): JsonElement = this?.let { box ->
        JsonObject(
            linkedMapOf(
                "left" to JsonPrimitive(box.left),
                "top" to JsonPrimitive(box.top),
                "right" to JsonPrimitive(box.right),
                "bottom" to JsonPrimitive(box.bottom),
            ),
        )
    } ?: JsonNull

    private fun JsonElement.requiredObject(label: String): JsonObject = this as? JsonObject
        ?: error("$label must be an object")

    private fun JsonObject.requiredObject(key: String): JsonObject = requireNotNull(get(key)) {
        "Missing key: $key"
    }.requiredObject(key)

    private fun JsonObject.requiredArray(key: String): JsonArray = get(key) as? JsonArray
        ?: error("$key must be an array")

    private fun JsonObject.requiredString(key: String): String = get(key).asPrimitive()?.contentOrNull
        ?: error("$key must be a string")

    private fun JsonObject.requiredInt(key: String): Int = get(key).asPrimitive()?.int
        ?: error("$key must be an integer")

    private fun JsonObject.optionalFloat(key: String): Float? = when (val element = get(key)) {
        null, JsonNull -> null
        else -> element.asPrimitive()?.floatOrNull ?: error("$key must be a number or null")
    }

    private fun JsonObject.optionalBoundingBox(key: String): BoundingBox? = when (val element = get(key)) {
        null, JsonNull -> null
        else -> element.requiredObject(key).let { box ->
            box.requireOnly("left", "top", "right", "bottom")
            BoundingBox(
                left = box.requiredInt("left"),
                top = box.requiredInt("top"),
                right = box.requiredInt("right"),
                bottom = box.requiredInt("bottom"),
            )
        }
    }

    private fun JsonElement?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonObject.requireOnly(vararg keys: String) {
        val unexpected = this.keys - keys.toSet()
        require(unexpected.isEmpty()) { "Unexpected OCR debug keys: ${unexpected.sorted()}" }
        require(keys.all(::containsKey)) { "Missing OCR debug keys: ${(keys.toSet() - this.keys).sorted()}" }
    }
}
