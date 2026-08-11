package com.pricetrace.receiptscanner.parser

import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.ocr.OcrElement
import com.pricetrace.receiptscanner.ocr.OcrLine
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

internal data class Evidence(
    val line: OcrLine,
    val text: String,
    val boundingBox: BoundingBox?,
    val confidence: Float?,
) {
    val centerX: Int? get() = boundingBox?.let { (it.left + it.right) / 2 }
}

internal data class SpatialRow(
    val lines: List<OcrLine>,
) {
    val orderedLines: List<OcrLine> = lines.sortedWith(
        compareBy<OcrLine>({ it.boundingBox?.left ?: Int.MAX_VALUE }, OcrLine::recognitionOrder),
    )
    val pageIndex: Int = lines.first().pageIndex
    val minRecognitionOrder: Int = lines.minOf(OcrLine::recognitionOrder)
    val boundingBox: BoundingBox? = unionBoundingBoxes(lines.mapNotNull(OcrLine::boundingBox))
    val centerY: Int = boundingBox?.let { (it.top + it.bottom) / 2 } ?: minRecognitionOrder
    val text: String = normalizeOcrText(orderedLines.joinToString(" ", transform = OcrLine::text))
    val evidence: List<Evidence> = orderedLines.map { line ->
        Evidence(line, line.text, line.boundingBox, line.confidence)
    }
    val elementEvidence: List<Evidence> = orderedLines.flatMap { line ->
        line.elements.sortedBy(OcrElement::recognitionOrder).map { element ->
            Evidence(line, element.text, element.boundingBox, element.confidence ?: line.confidence)
        }
    }
    val cells: List<Evidence> = orderedLines.flatMap { line ->
        if (line.elements.isEmpty()) {
            listOf(Evidence(line, line.text, line.boundingBox, line.confidence))
        } else {
            line.elements.sortedWith(
                compareBy<OcrElement>({ it.boundingBox?.left ?: Int.MAX_VALUE }, OcrElement::recognitionOrder),
            ).map { element ->
                Evidence(line, element.text, element.boundingBox, element.confidence ?: line.confidence)
            }
        }
    }.sortedWith(compareBy({ it.boundingBox?.left ?: Int.MAX_VALUE }, { it.line.recognitionOrder }))

    fun canMerge(line: OcrLine): Boolean {
        val rowBox = boundingBox ?: return false
        val lineBox = line.boundingBox ?: return false
        val overlap = min(rowBox.bottom, lineBox.bottom) - max(rowBox.top, lineBox.top)
        val minimumHeight = min(rowBox.height, lineBox.height).coerceAtLeast(1)
        val centerDistance = kotlin.math.abs(
            (rowBox.top + rowBox.bottom) / 2 - (lineBox.top + lineBox.bottom) / 2,
        )
        val strictVerticalMatch = overlap > 0 &&
            overlap * 3 >= minimumHeight * 2 &&
            centerDistance * 3 <= minimumHeight
        if (strictVerticalMatch) return true

        // ML Kit may give separate column lines slightly different baselines. Relax the
        // vertical tolerance only when their x ranges are clearly separate; adjacent product
        // rows usually span the same x range and therefore remain protected by the strict rule.
        val horizontalOverlap = min(rowBox.right, lineBox.right) - max(rowBox.left, lineBox.left)
        val minimumWidth = min(rowBox.width, lineBox.width).coerceAtLeast(1)
        val separateColumns = horizontalOverlap <= 0 || horizontalOverlap * 3 <= minimumWidth
        return separateColumns && overlap > 0 &&
            overlap * 2 >= minimumHeight &&
            centerDistance * 2 <= minimumHeight
    }

    fun evidenceFor(value: String): List<Evidence> {
        val normalizedValue = normalizeOcrText(value)
        elementEvidence.firstOrNull { normalizeOcrText(it.text) == normalizedValue }?.let { return listOf(it) }
        evidence.firstOrNull { normalizeOcrText(it.text).contains(normalizedValue) }?.let { return listOf(it) }
        return evidence
    }
}

internal fun buildSpatialRows(lines: List<OcrLine>): List<SpatialRow> {
    val rows = mutableListOf<SpatialRow>()
    lines.sortedWith(
        compareBy<OcrLine>(
            OcrLine::pageIndex,
            { it.boundingBox?.top ?: Int.MAX_VALUE },
            { it.boundingBox?.left ?: Int.MAX_VALUE },
            OcrLine::recognitionOrder,
        ),
    ).forEach { line ->
        val rowIndex = rows.indexOfLast { row ->
            row.pageIndex == line.pageIndex && row.canMerge(line)
        }
        if (rowIndex >= 0) {
            rows[rowIndex] = SpatialRow(rows[rowIndex].lines + line)
        } else {
            rows += SpatialRow(listOf(line))
        }
    }
    return rows.sortedWith(
        compareBy<SpatialRow>(
            SpatialRow::pageIndex,
            { it.boundingBox?.top ?: Int.MAX_VALUE },
            SpatialRow::minRecognitionOrder,
        ),
    )
}

internal fun rowsShareVerticalBand(first: SpatialRow, second: SpatialRow): Boolean {
    val firstBox = first.boundingBox ?: return false
    val secondBox = second.boundingBox ?: return false
    val overlap = min(firstBox.bottom, secondBox.bottom) - max(firstBox.top, secondBox.top)
    val minimumHeight = min(firstBox.height, secondBox.height).coerceAtLeast(1)
    return overlap > 0 && overlap * 2 >= minimumHeight
}

internal fun normalizeOcrText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("\\s+"), " ")
    .trim()

private fun unionBoundingBoxes(boxes: List<BoundingBox>): BoundingBox? = if (boxes.isEmpty()) null else BoundingBox(
    left = boxes.minOf(BoundingBox::left),
    top = boxes.minOf(BoundingBox::top),
    right = boxes.maxOf(BoundingBox::right),
    bottom = boxes.maxOf(BoundingBox::bottom),
)
