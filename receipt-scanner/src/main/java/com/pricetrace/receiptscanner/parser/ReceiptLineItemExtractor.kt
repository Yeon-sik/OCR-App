package com.pricetrace.receiptscanner.parser

import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.domain.FieldProvenance
import com.pricetrace.receiptscanner.domain.ParsedField
import com.pricetrace.receiptscanner.domain.ParsedLineItem
import com.pricetrace.receiptscanner.domain.QuantityUnit
import com.pricetrace.receiptscanner.domain.ReceiptIdentifier
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ocr.OcrLine
import java.math.BigDecimal
import kotlin.math.abs

internal class ReceiptLineItemExtractor(
    private val rulePrefix: String,
) {
    fun extract(
        documentId: String,
        lines: List<OcrLine>,
        excludedLineIds: Set<String>,
    ): List<ParsedLineItem> {
        val availableLines = lines.filterNot { it.id in excludedLineIds }
        val rows = buildSpatialRows(availableLines)
        val table = extractTables(documentId, rows)
        val fallbackRows = rows.filter { row -> row.lines.none { it.id in table.consumedLineIds } }
        val wrappedRows = extractWrappedFallbackRows(documentId, fallbackRows)
        val remainingRows = fallbackRows.filter { row ->
            row.lines.none { it.id in wrappedRows.consumedLineIds }
        }
        return (table.items + wrappedRows.items + remainingRows.mapNotNull { row -> parseFallbackRow(documentId, row) })
            .distinctBy(ParsedLineItem::id)
    }

    private fun extractWrappedFallbackRows(
        documentId: String,
        rows: List<SpatialRow>,
    ): TableExtraction {
        val items = mutableListOf<ParsedLineItem>()
        val consumedLineIds = mutableSetOf<String>()
        rows.zipWithNext().forEach { (descriptionRow, amountRow) ->
            if (descriptionRow.lines.any { it.id in consumedLineIds }) return@forEach
            val item = parseWrappedFallbackRows(documentId, descriptionRow, amountRow) ?: return@forEach
            items += item
            consumedLineIds += descriptionRow.lines.map(OcrLine::id)
            consumedLineIds += amountRow.lines.map(OcrLine::id)
        }
        return TableExtraction(items, consumedLineIds)
    }

    private fun parseWrappedFallbackRows(
        documentId: String,
        descriptionRow: SpatialRow,
        amountRow: SpatialRow,
    ): ParsedLineItem? {
        if (descriptionRow.pageIndex != amountRow.pageIndex) return null
        if (amountRow.minRecognitionOrder - descriptionRow.minRecognitionOrder !in 1..2) return null
        if (!isLikelyWrappedAmountLayout(descriptionRow, amountRow)) return null
        val descriptionText = normalizeOcrText(descriptionRow.text)
        if (
            descriptionText.isBlank() ||
            isTableHeaderText(descriptionText) ||
            NON_ITEM_SUMMARY_KEYWORDS.containsMatchIn(descriptionText) ||
            PAYMENT_KEYWORDS.containsMatchIn(descriptionText) ||
            PRODUCT_METADATA_KEYWORDS.containsMatchIn(descriptionText) ||
            (isNonProductDescription(descriptionText) && classifyLine(descriptionText) == null) ||
            classifyLine(descriptionText) != null
        ) {
            return null
        }

        val cells = expandCompoundCells(descriptionRow.cells)
        if (cells.size < 2) return null
        val quantitySource = cells.last()
        val quantity = quantitySource.quantityCandidate() ?: return null
        if (quantity.toBigDecimalOrNull()?.let { it <= MAX_BARE_QUANTITY } != true) return null
        if (quantitySource.looksMoneyFormatted()) return null
        var descriptionSources = cells.dropLast(1)
        if (descriptionSources.none { it.text.any(Char::isLetter) }) return null

        var sku: String? = null
        var skuSources = emptyList<Evidence>()
        val firstCellText = normalizeOcrText(descriptionSources.first().text)
        if (looksLikeInferredSku(firstCellText) && descriptionSources.drop(1).any { it.text.any(Char::isLetter) }) {
            sku = firstCellText.replace(Regex("\\s+"), "")
            skuSources = listOf(descriptionSources.first())
            descriptionSources = descriptionSources.drop(1)
        }
        val description = sanitizeItemDescription(
            descriptionSources.joinToString(" ") { normalizeOcrText(it.text) },
        )
        if (description.isBlank() || looksLikeNumericOnly(description)) return null
        if (isGenericNoteLikeDescription(description)) return null

        val amountSources = expandCompoundCells(amountRow.cells)
        val lineAmount = amountSources.amount()?.takeIf { it > 0 } ?: return null
        val quantitySources = listOf(quantitySource)
        val allSources = (skuSources + descriptionSources + quantitySources + amountSources)
            .distinctBy { it.line.id to it.boundingBox }
        val sourceReferences = allSources.map { it.line.id }.distinct()
        val pageId = allSources.firstOrNull()?.line?.pageId ?: return null
        return ParsedLineItem(
            id = StableIds.lineId(
                documentId,
                pageId,
                sourceReferences.joinToString("|"),
                "$description|$lineAmount",
            ),
            type = ReceiptLineType.PRODUCT,
            description = field(description, descriptionSources, "wrapped.description", 0.76f),
            sourceLineReferences = sourceReferences,
            identifiers = sku?.let { listOf(ReceiptIdentifier("merchant_sku", it)) }.orEmpty(),
            quantity = field(
                ReceiptQuantity(quantity, QuantityUnit.EACH),
                quantitySources,
                "wrapped.quantity",
                0.78f,
            ),
            unitPriceAmountMinor = ParsedField(null),
            grossAmountMinor = field(lineAmount, amountSources, "wrapped.gross_amount", 0.78f),
            netAmountMinor = field(lineAmount, amountSources, "wrapped.net_amount", 0.78f),
            confidence = ConfidenceLevel.MEDIUM,
        )
    }

    private fun isLikelyWrappedAmountLayout(first: SpatialRow, second: SpatialRow): Boolean {
        val firstBox = first.boundingBox ?: return true
        val secondBox = second.boundingBox ?: return true
        val verticalGap = secondBox.top - firstBox.bottom
        val maximumGap = maxOf(firstBox.height, secondBox.height).coerceAtLeast(1) * 2
        if (verticalGap !in 0..maximumGap) return false
        return secondBox.left >= firstBox.left + firstBox.width / 2
    }

    private fun extractTables(documentId: String, rows: List<SpatialRow>): TableExtraction {
        val items = mutableListOf<ParsedLineItem>()
        val consumedLineIds = mutableSetOf<String>()
        var activeHeader: TableHeader? = null
        var pendingName: PendingName? = null

        rows.forEach { row ->
            detectHeader(row)?.let { header ->
                pendingName?.toIncompleteItem(documentId)?.let(items::add)
                activeHeader = header
                pendingName = null
                consumedLineIds += row.lines.map(OcrLine::id)
                return@forEach
            }

            val header = activeHeader ?: return@forEach
            if (row.pageIndex != header.pageIndex) {
                pendingName?.toIncompleteItem(documentId)?.let(items::add)
                activeHeader = null
                pendingName = null
                return@forEach
            }
            if (TABLE_END_KEYWORDS.containsMatchIn(row.text)) {
                pendingName?.toIncompleteItem(documentId)?.let(items::add)
                activeHeader = null
                pendingName = null
                return@forEach
            }

            val cells = assignCells(row, header)
            val numbers = resolveTableNumbers(row, header)
            val descriptionEvidence = tableDescriptionSources(row, header, cells)
            val description = descriptionEvidence.joinToString(" ") { normalizeOcrText(it.text) }
                .trim()
                .takeIf(String::isNotBlank)
            val amount = numbers?.lineAmount

            if (description != null && amount == null && !looksLikeNumericOnly(description)) {
                val skuEvidence = cells[ColumnType.SKU].orEmpty()
                val explicitSku = skuEvidence.joinToString("") { normalizeOcrText(it.text) }
                    .trim()
                    .takeIf(::looksLikeSku)
                val canCarryAsWrappedName = !isGenericNoteLikeDescription(description) &&
                    !isNonProductDescription(description) &&
                    !PRODUCT_METADATA_KEYWORDS.containsMatchIn(description) &&
                    !PAYMENT_KEYWORDS.containsMatchIn(description) &&
                    classifyLine(description) == null
                pendingName = if (!canCarryAsWrappedName) {
                    null
                } else {
                    val existing = pendingName
                    if (existing != null && !existing.canAppend(row, explicitSku)) {
                        existing.toIncompleteItem(documentId)?.let(items::add)
                    }
                    val appendTo = existing?.takeIf { it.canAppend(row, explicitSku) }
                    val directQuantitySource = cells[ColumnType.QUANTITY].orEmpty()
                        .firstOrNull { source -> source.quantityCandidate() != null }
                    val directUnitPriceSource = cells[ColumnType.UNIT_PRICE].orEmpty()
                        .firstOrNull { source -> source.strictAmount()?.let { it > 0 } == true }
                    PendingName(
                        value = mergeWrappedText(appendTo?.value, description),
                        sources = (appendTo?.sources.orEmpty() + descriptionEvidence)
                            .distinctBy { it.line.id to it.boundingBox },
                        sku = explicitSku ?: appendTo?.sku,
                        skuSources = (appendTo?.skuSources.orEmpty() + skuEvidence)
                            .distinctBy { it.line.id to it.boundingBox },
                        lastRow = row,
                        fragmentCount = (appendTo?.fragmentCount ?: 0) + 1,
                        hasUnresolvedNumericEvidence = appendTo?.hasUnresolvedNumericEvidence == true ||
                            hasNumericEvidenceOutsideName(cells),
                        quantity = directQuantitySource?.quantityCandidate() ?: appendTo?.quantity,
                        quantitySources = listOfNotNull(directQuantitySource).ifEmpty {
                            appendTo?.quantitySources.orEmpty()
                        },
                        unitPrice = directUnitPriceSource?.strictAmount() ?: appendTo?.unitPrice,
                        unitPriceSources = listOfNotNull(directUnitPriceSource).ifEmpty {
                            appendTo?.unitPriceSources.orEmpty()
                        },
                    )
                }
                consumedLineIds += row.lines.map(OcrLine::id)
                return@forEach
            }

            if (
                pendingName?.hasUnresolvedNumericEvidence == true &&
                description != null &&
                amount != null
            ) {
                pendingName.toIncompleteItem(documentId)?.let(items::add)
                pendingName = null
            }

            val item = parseTableRow(
                documentId = documentId,
                row = row,
                header = header,
                cells = cells,
                numbers = numbers,
                pendingName = pendingName,
            ) ?: parseFallbackRow(documentId, row)

            consumedLineIds += row.lines.map(OcrLine::id)
            if (item != null) {
                items += item
                pendingName = null
            } else if (description == null && amount == null) {
                pendingName = null
            }
        }

        pendingName?.toIncompleteItem(documentId)?.let(items::add)

        return TableExtraction(items, consumedLineIds)
    }

    private fun PendingName.canAppend(row: SpatialRow, explicitSku: String?): Boolean {
        if (fragmentCount >= MAX_WRAPPED_NAME_FRAGMENTS) return false
        if (explicitSku != null && explicitSku != sku) return false
        if (row.pageIndex != lastRow.pageIndex) return false
        if (row.minRecognitionOrder <= lastRow.minRecognitionOrder) return false

        val previousBox = lastRow.boundingBox ?: return true
        val currentBox = row.boundingBox ?: return true
        val verticalGap = currentBox.top - previousBox.bottom
        val maximumGap = maxOf(previousBox.height, currentBox.height).coerceAtLeast(1) * 2
        val leftTolerance = maxOf(previousBox.width, currentBox.width) / 5
        if (sku == null && explicitSku == null && currentBox.left <= previousBox.left + leftTolerance) {
            return false
        }
        return verticalGap in 0..maximumGap && currentBox.left >= previousBox.left - leftTolerance
    }

    private fun PendingName.toIncompleteItem(documentId: String): ParsedLineItem? {
        val normalizedDescription = sanitizeItemDescription(value).takeIf(String::isNotBlank) ?: return null
        if (
            looksLikeNumericOnly(normalizedDescription) ||
            isGenericNoteLikeDescription(normalizedDescription) ||
            isNonProductDescription(normalizedDescription)
        ) {
            return null
        }
        val allSources = (skuSources + sources + quantitySources + unitPriceSources)
            .distinctBy { it.line.id to it.boundingBox }
        val sourceReferences = allSources.map { it.line.id }.distinct()
        val pageId = allSources.firstOrNull()?.line?.pageId ?: return null
        return ParsedLineItem(
            id = StableIds.lineId(
                documentId,
                pageId,
                sourceReferences.joinToString("|"),
                "$normalizedDescription|incomplete",
            ),
            type = ReceiptLineType.PRODUCT,
            description = field(normalizedDescription, sources, "table.incomplete_description", 0.72f),
            sourceLineReferences = sourceReferences,
            identifiers = sku?.let { listOf(ReceiptIdentifier("merchant_sku", it)) }.orEmpty(),
            quantity = field(
                quantity?.let { ReceiptQuantity(it, QuantityUnit.EACH) },
                quantitySources,
                "table.incomplete_quantity",
                0.72f,
            ),
            unitPriceAmountMinor = field(
                unitPrice,
                unitPriceSources,
                "table.incomplete_unit_price",
                0.72f,
            ),
            confidence = ConfidenceLevel.LOW,
        )
    }

    private fun detectHeader(row: SpatialRow): TableHeader? {
        val detected = linkedMapOf<ColumnType, Int>()
        row.cells.forEach { cell ->
            val type = headerType(normalizeHeader(cell.text)) ?: return@forEach
            cell.centerX?.let { center -> detected.putIfAbsent(type, center) }
        }

        if (detected.size < 3) {
            val rowBox = row.boundingBox
            if (rowBox != null && row.text.isNotBlank()) {
                HEADER_PATTERNS.forEach { (type, regex) ->
                    regex.find(row.text)?.let { match ->
                        val characterCenter = (match.range.first + match.range.last + 1) / 2.0
                        val x = rowBox.left + (rowBox.width * characterCenter / row.text.length).toInt()
                        detected.putIfAbsent(type, x)
                    }
                }
            }
        }

        val hasRequiredFrame = ColumnType.NAME in detected && ColumnType.AMOUNT in detected &&
            (ColumnType.QUANTITY in detected || ColumnType.UNIT_PRICE in detected)
        if (!hasRequiredFrame) return null
        return TableHeader(row.pageIndex, detected.toMap())
    }

    private fun assignCells(
        row: SpatialRow,
        header: TableHeader,
    ): Map<ColumnType, List<Evidence>> = expandCompoundCells(row.cells)
        .mapNotNull { evidence ->
            val column = closestColumn(evidence, header) ?: return@mapNotNull null
            column to evidence
        }
        .groupBy(keySelector = Pair<ColumnType, Evidence>::first, valueTransform = Pair<ColumnType, Evidence>::second)

    private fun hasNumericEvidenceOutsideName(cells: Map<ColumnType, List<Evidence>>): Boolean = listOf(
        ColumnType.UNIT_PRICE,
        ColumnType.QUANTITY,
        ColumnType.AMOUNT,
    ).flatMap { column -> cells[column].orEmpty() }.any { evidence ->
        val normalized = normalizeOcrText(evidence.text)
        normalized.any(Char::isDigit) || QUANTITY_CELL.matches(normalizeQuantityText(normalized))
    }

    private fun closestColumn(evidence: Evidence, header: TableHeader): ColumnType? {
        val centerX = evidence.centerX ?: return null
        return header.centers.minByOrNull { (_, columnCenter) -> abs(centerX - columnCenter) }?.key
    }

    private fun closestColumn(cell: NumericCell, header: TableHeader): ColumnType? {
        val centerX = cell.centerX ?: return null
        return header.centers.minByOrNull { (_, columnCenter) -> abs(centerX - columnCenter) }?.key
    }

    private fun tableDescriptionSources(
        row: SpatialRow,
        header: TableHeader,
        cells: Map<ColumnType, List<Evidence>>,
    ): List<Evidence> {
        val expanded = expandCompoundCells(row.cells)
        val nameCenter = header.centers[ColumnType.NAME]
        val firstNumericCenter = listOfNotNull(
            header.centers[ColumnType.UNIT_PRICE],
            header.centers[ColumnType.QUANTITY],
            header.centers[ColumnType.AMOUNT],
        ).minOrNull()
        val nameBoundary = if (nameCenter != null && firstNumericCenter != null) {
            (nameCenter + firstNumericCenter) / 2
        } else {
            firstNumericCenter
        }

        val recovered = expanded.filter { evidence ->
            if (evidence.isStandaloneTableNumber() || evidence.text.none(Char::isLetter)) return@filter false
            if (PRODUCT_METADATA_KEYWORDS.containsMatchIn(evidence.text)) return@filter false
            if (evidence in cells[ColumnType.SKU].orEmpty()) return@filter false
            val box = evidence.boundingBox ?: return@filter evidence in cells[ColumnType.NAME].orEmpty()
            nameBoundary == null || box.left < nameBoundary
        }
        return (cells[ColumnType.NAME].orEmpty() + recovered)
            .filter { evidence -> closestColumn(evidence, header) == ColumnType.NAME }
            .mapNotNull(::sanitizeDescriptionEvidence)
            .distinctBy { it.line.id to it.boundingBox to normalizeOcrText(it.text) }
            .sortedBy { it.boundingBox?.left ?: Int.MAX_VALUE }
    }

    private fun resolveTableNumbers(row: SpatialRow, header: TableHeader): TableNumbers? {
        val quantityCenter = header.centers[ColumnType.QUANTITY]
        val unitPriceCenter = header.centers[ColumnType.UNIT_PRICE]
        val expandedSources = mergeGroupedAmountFragments(expandCompoundCells(row.cells))
        val numericSources = expandedSources.filter { cell ->
            cell.isStandaloneTableNumber() ||
                (closestColumn(cell, header) == ColumnType.QUANTITY && cell.ocrConfusedQuantityCandidate() != null)
        }
        val amountCenter = header.centers[ColumnType.AMOUNT] ?: return null
        val amountSource = numericSources
            .filter { source -> closestColumn(source, header) == ColumnType.AMOUNT }
            .mapNotNull { source -> source.strictAmount()?.let { amount -> Triple(source, amount, numericDistance(source, amountCenter)) } }
            .minWithOrNull(compareBy<Triple<NumericCell, Long, Int>>({ it.third }, { -(it.first.centerX ?: Int.MIN_VALUE) }))
            ?: return null
        val remaining = numericSources.filter { source ->
            source !== amountSource.first && closestColumn(source, header) in setOf(
                ColumnType.QUANTITY,
                ColumnType.UNIT_PRICE,
                ColumnType.AMOUNT,
            )
        }
        val canInferMissingQuantityColumn = quantityCenter == null && unitPriceCenter != null &&
            remaining.count { source -> source.quantityCandidate() != null || source.strictAmount() != null } >= 2

        val quantityOptions = if (quantityCenter == null && !canInferMissingQuantityColumn) {
            listOf(null)
        } else {
            listOf(null) + remaining.mapNotNull { source ->
                source.quantityCandidate()?.let { quantity ->
                    QuantityOption(quantity, source, QuantityResolution.PRINTED)
                } ?: source.ocrConfusedQuantityCandidate()?.let { quantity ->
                    QuantityOption(quantity, source, QuantityResolution.OCR_CORRECTED)
                }
            }
        }
        val unitPriceOptions = if (unitPriceCenter == null) {
            listOf(null)
        } else {
            listOf(null) + remaining.mapNotNull { source ->
                source.strictAmount()?.takeIf { it > 0 }?.let { unitPrice -> UnitPriceOption(unitPrice, source) }
            }
        }

        val bestAssignment = quantityOptions.flatMap { quantity ->
            unitPriceOptions.mapNotNull { unitPrice ->
                if (quantity?.source === unitPrice?.source) return@mapNotNull null
                val score = tableNumberScore(
                    quantity = quantity,
                    unitPrice = unitPrice,
                    lineAmount = amountSource.second,
                    quantityCenter = quantityCenter,
                    unitPriceCenter = unitPriceCenter,
                )
                NumberAssignment(quantity, unitPrice, score)
            }
        }.maxByOrNull(NumberAssignment::score)

        val assignedUnitPrice = bestAssignment?.unitPrice
        val assignedQuantity = bestAssignment?.quantity
        val quantityColumnSources = expandedSources.filter { source ->
            closestColumn(source, header) == ColumnType.QUANTITY
        }
        val hasReadableQuantityCell = quantityColumnSources.any { source ->
            source.quantityCandidate() != null || source.ocrConfusedQuantityCandidate() != null
        }
        val derivedQuantity = if (
            assignedQuantity == null &&
            !hasReadableQuantityCell &&
            quantityCenter != null &&
            unitPriceCenter != null
        ) {
            assignedUnitPrice?.value?.let { unitPrice ->
                inferExactIntegerQuantity(unitPrice, amountSource.second)
            }
        } else {
            null
        }
        val quantity = assignedQuantity?.value ?: derivedQuantity
        val quantityResolution = assignedQuantity?.resolution ?: derivedQuantity?.let {
            QuantityResolution.AMOUNT_RATIO
        }
        val quantitySources = assignedQuantity?.source?.sources.orEmpty().ifEmpty {
            if (derivedQuantity == null) {
                emptyList()
            } else {
                (
                    quantityColumnSources.flatMap(NumericCell::sources) +
                        assignedUnitPrice?.source?.sources.orEmpty() +
                        amountSource.first.sources
                    ).distinctBy { source -> source.line.id to source.boundingBox }
            }
        }

        return TableNumbers(
            quantity = quantity,
            quantitySources = quantitySources,
            quantityResolution = quantityResolution,
            unitPrice = assignedUnitPrice?.value,
            unitPriceSources = assignedUnitPrice?.source?.sources.orEmpty(),
            lineAmount = amountSource.second,
            amountSources = amountSource.first.sources,
            isConserved = quantity != null && assignedUnitPrice != null &&
                multiplyExactMinor(quantity, assignedUnitPrice.value) == amountSource.second,
        )
    }

    private fun tableNumberScore(
        quantity: QuantityOption?,
        unitPrice: UnitPriceOption?,
        lineAmount: Long,
        quantityCenter: Int?,
        unitPriceCenter: Int?,
    ): Int {
        var score = 0
        if (quantity != null) {
            score += if (quantityCenter != null) {
                250 - numericDistance(quantity.source, quantityCenter)
            } else {
                40
            }
            val quantityValue = quantity.value.toBigDecimalOrNull()
            if (quantityValue != null && quantityValue <= MAX_BARE_QUANTITY) score += 100
            if (quantity.source.hasQuantityUnit()) score += 100
            if (quantity.source.looksMoneyFormatted()) score -= 100
            if (quantity.resolution == QuantityResolution.OCR_CORRECTED) score -= 500
        }
        if (unitPrice != null && unitPriceCenter != null) {
            score += 250 - numericDistance(unitPrice.source, unitPriceCenter)
            if (unitPrice.source.looksMoneyFormatted()) score += 30
        }
        if (quantity != null && unitPrice != null) {
            val calculated = runCatching {
                BigDecimal(quantity.value).multiply(BigDecimal.valueOf(unitPrice.value))
            }.getOrNull()
            val difference = calculated?.subtract(BigDecimal.valueOf(lineAmount))?.abs()
            score += if (difference != null && difference <= MAX_ROUNDING_DELTA_MINOR) 600 else -350
        }
        return score
    }

    /**
     * Recover a missing/unreadable printed quantity only when explicit unit-price, quantity, and
     * amount columns exist and the two monetary cells produce one exact, small integer. The
     * monetary cells remain the provenance; fractional or rounded weights deliberately stay null.
     */
    private fun inferExactIntegerQuantity(unitPrice: Long, lineAmount: Long): String? {
        if (unitPrice <= 0L || lineAmount <= 0L || lineAmount % unitPrice != 0L) return null
        val quantity = lineAmount / unitPrice
        return quantity.takeIf { value ->
            value >= 1L && BigDecimal.valueOf(value) <= MAX_BARE_QUANTITY
        }?.toString()
    }

    private fun numericDistance(source: NumericCell, targetX: Int): Int = source.centerX
        ?.let { center -> abs(center - targetX) }
        ?: 1_000

    private fun parseTableRow(
        documentId: String,
        row: SpatialRow,
        header: TableHeader,
        cells: Map<ColumnType, List<Evidence>>,
        numbers: TableNumbers?,
        pendingName: PendingName?,
    ): ParsedLineItem? {
        val directDescriptionSources = tableDescriptionSources(row, header, cells)
        val directDescription = directDescriptionSources.joinToString(" ") { normalizeOcrText(it.text) }
            .trim()
            .takeIf(String::isNotBlank)
        val descriptionSources = (pendingName?.sources.orEmpty() + directDescriptionSources)
            .distinctBy { it.line.id to it.boundingBox }
        var description = sanitizeItemDescription(mergeWrappedText(pendingName?.value, directDescription))
        val directSkuSources = cells[ColumnType.SKU].orEmpty()
        val directSku = directSkuSources.joinToString("") { normalizeOcrText(it.text) }
            .trim()
            .takeIf(::looksLikeSku)
        val skuSources = if (directSku != null) directSkuSources else pendingName?.skuSources.orEmpty()
        var sku = directSku ?: pendingName?.sku
        if (sku == null && ColumnType.SKU in header.centers) {
            SKU_PREFIX.find(description)?.groupValues?.get(1)?.takeIf(::looksLikeInferredSku)?.let { prefix ->
                sku = prefix
                description = description.removePrefix(prefix).trimStart(' ', ':', '-')
            }
        }

        val quantitySources = numbers?.quantitySources.orEmpty().ifEmpty { pendingName?.quantitySources.orEmpty() }
        val unitPriceSources = numbers?.unitPriceSources.orEmpty().ifEmpty { pendingName?.unitPriceSources.orEmpty() }
        val amountSources = numbers?.amountSources.orEmpty()
        val quantity = numbers?.quantity ?: pendingName?.quantity
        val quantityResolution = numbers?.quantityResolution
            ?: pendingName?.quantity?.let { QuantityResolution.PRINTED }
        // Printed evidence only: a missing unit-price column stays null instead of amount / quantity inference.
        val unitPrice = numbers?.unitPrice ?: pendingName?.unitPrice
        val lineAmount = numbers?.lineAmount ?: return null
        val normalizedDescription = sanitizeItemDescription(description).takeIf(String::isNotBlank) ?: return null
        if (looksLikeNumericOnly(normalizedDescription)) return null
        if (NON_ITEM_SUMMARY_KEYWORDS.containsMatchIn(normalizedDescription)) return null
        val combinedText = listOf(normalizedDescription, row.text).joinToString(" ")
        val type = classifyLine(combinedText) ?: ReceiptLineType.PRODUCT
        if (type == ReceiptLineType.PRODUCT && isNonProductDescription(normalizedDescription)) return null
        val signedAmount = signedAmount(type, lineAmount)
        val allSources = (
            skuSources + descriptionSources + unitPriceSources + quantitySources + amountSources
            ).distinctBy { it.line.id to it.boundingBox }
        val sourceReferences = allSources.map { it.line.id }.distinct()
        if (sourceReferences.isEmpty()) return null
        val pageId = allSources.first().line.pageId
        val stableSource = sourceReferences.joinToString("|")

        val isConserved = quantity != null && unitPrice != null &&
            multiplyExactMinor(quantity, unitPrice) == lineAmount
        val confidence = when {
            quantityResolution == QuantityResolution.AMOUNT_RATIO -> ConfidenceLevel.MEDIUM
            quantityResolution == QuantityResolution.OCR_CORRECTED -> ConfidenceLevel.MEDIUM
            isConserved -> ConfidenceLevel.HIGH
            quantity != null && ColumnType.UNIT_PRICE !in header.centers -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
        return ParsedLineItem(
            id = StableIds.lineId(documentId, pageId, stableSource, "$normalizedDescription|$signedAmount"),
            type = type,
            description = field(
                normalizedDescription,
                descriptionSources,
                "table.description",
                if (descriptionSources.isNotEmpty()) 0.92f else 0.7f,
            ),
            sourceLineReferences = sourceReferences,
            identifiers = sku?.let { listOf(ReceiptIdentifier("merchant_sku", it)) }.orEmpty(),
            quantity = field(
                quantity?.let { ReceiptQuantity(it, QuantityUnit.EACH) },
                quantitySources,
                when (quantityResolution) {
                    QuantityResolution.OCR_CORRECTED -> "table.quantity_ocr_corrected"
                    QuantityResolution.AMOUNT_RATIO -> "table.quantity_from_amount_ratio"
                    else -> "table.quantity"
                },
                when (quantityResolution) {
                    QuantityResolution.OCR_CORRECTED -> 0.78f
                    QuantityResolution.AMOUNT_RATIO -> 0.7f
                    else -> 0.92f
                },
            ),
            unitPriceAmountMinor = field(unitPrice, unitPriceSources, "table.unit_price", 0.92f),
            grossAmountMinor = field(
                signedAmount.takeIf { type == ReceiptLineType.PRODUCT || type == ReceiptLineType.SERVICE },
                amountSources,
                "table.gross_amount",
                0.92f,
            ),
            discountAmountMinor = field(
                signedAmount.takeIf { type == ReceiptLineType.DISCOUNT },
                amountSources,
                "table.discount_amount",
                0.9f,
            ),
            taxAmountMinor = field(
                signedAmount.takeIf { type == ReceiptLineType.TAX },
                amountSources,
                "table.tax_amount",
                0.9f,
            ),
            netAmountMinor = field(signedAmount, amountSources, "table.net_amount", 0.92f),
            confidence = confidence,
        )
    }

    private fun parseFallbackRow(documentId: String, row: SpatialRow): ParsedLineItem? {
        val text = normalizeOcrText(row.text)
        if (text.isBlank() || isTableHeaderText(text) || NON_ITEM_SUMMARY_KEYWORDS.containsMatchIn(text)) return null
        if (PAYMENT_KEYWORDS.containsMatchIn(text)) return null
        if (PRODUCT_METADATA_KEYWORDS.containsMatchIn(text)) return null
        val classifiedType = classifyLine(text)
        if (classifiedType == null && isNonProductDescription(text)) return null
        if (classifiedType == null) {
            parseSpatialProductRow(documentId, row)?.let { return it }
        }
        val lineAmount = AmountParser.extractLastMinor(text) ?: return null
        val type = classifiedType ?: ReceiptLineType.PRODUCT
        val multiplication = QUANTITY_PRICE.find(text)
        val strictRawProduct = if (type == ReceiptLineType.PRODUCT) {
            parseStrictRawProduct(row, text)
        } else {
            null
        }
        val explicitQuantity = multiplication?.groupValues?.get(1)?.takeIf(String::isNotBlank)
            ?: EXPLICIT_QUANTITY.find(text)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
            ?: strictRawProduct?.quantity
        val explicitUnitPrice = multiplication?.groupValues?.get(2)?.let(AmountParser::normalizeMinor)
        if (
            type == ReceiptLineType.PRODUCT &&
            multiplication == null &&
            explicitQuantity == null &&
            abs(lineAmount) <= MAX_BARE_QUANTITY.toLong() &&
            !trailingAmountHasMoneySignal(text)
        ) {
            return null
        }
        var sku = SKU_PREFIX.find(text)?.groupValues?.get(1)?.takeIf(::looksLikeInferredSku)
        var description = strictRawProduct?.description ?: (sku?.let { text.removePrefix(it).trimStart() } ?: text)
        if (strictRawProduct == null) {
            description = description
                .replace(QUANTITY_PRICE, " ")
                .replace(EXPLICIT_QUANTITY, " ")
                .replace(TRAILING_AMOUNT, " ")
                .trim(' ', ':', '-')
        }
        description = sanitizeItemDescription(description)
        if (description.isBlank() || looksLikeNumericOnly(description)) return null
        if (isGenericNoteLikeDescription(description)) return null
        if (type == ReceiptLineType.PRODUCT && isNonProductDescription(description)) return null
        if (type != ReceiptLineType.PRODUCT) sku = null

        val signedAmount = signedAmount(type, lineAmount)
        val sources = row.evidence
        val sourceReferences = sources.map { it.line.id }.distinct()
        val pageId = sources.firstOrNull()?.line?.pageId ?: return null
        val provenanceRule = "fallback.${type.wireValue}"
        return ParsedLineItem(
            id = StableIds.lineId(documentId, pageId, sourceReferences.joinToString("|"), row.text),
            type = type,
            description = field(description, sources, "$provenanceRule.description", 0.62f),
            sourceLineReferences = sourceReferences,
            identifiers = sku?.let { listOf(ReceiptIdentifier("merchant_sku", it)) }.orEmpty(),
            quantity = field(
                explicitQuantity?.let { ReceiptQuantity(it, QuantityUnit.EACH) },
                sources.takeIf { explicitQuantity != null }.orEmpty(),
                "$provenanceRule.quantity",
                0.7f,
            ),
            unitPriceAmountMinor = field(
                explicitUnitPrice,
                sources.takeIf { explicitUnitPrice != null }.orEmpty(),
                "$provenanceRule.unit_price",
                0.7f,
            ),
            grossAmountMinor = field(
                signedAmount.takeIf { type == ReceiptLineType.PRODUCT || type == ReceiptLineType.SERVICE },
                sources,
                "$provenanceRule.gross_amount",
                0.68f,
            ),
            discountAmountMinor = field(
                signedAmount.takeIf { type == ReceiptLineType.DISCOUNT },
                sources.takeIf { type == ReceiptLineType.DISCOUNT }.orEmpty(),
                "$provenanceRule.discount_amount",
                0.75f,
            ),
            taxAmountMinor = field(
                signedAmount.takeIf { type == ReceiptLineType.TAX },
                sources.takeIf { type == ReceiptLineType.TAX }.orEmpty(),
                "$provenanceRule.tax_amount",
                0.8f,
            ),
            netAmountMinor = field(signedAmount, sources, "$provenanceRule.net_amount", 0.68f),
            confidence = when {
                explicitQuantity != null && explicitUnitPrice != null -> ConfidenceLevel.MEDIUM
                type != ReceiptLineType.PRODUCT -> ConfidenceLevel.MEDIUM
                else -> ConfidenceLevel.LOW
            },
        )
    }

    private fun parseSpatialProductRow(documentId: String, row: SpatialRow): ParsedLineItem? {
        val cells = expandCompoundCells(row.cells)
        if (cells.size < 3) return null
        val columns = inferNumericColumns(cells) ?: return null
        val rawDescriptionSources = cells.take(columns.descriptionEndIndex)
        if (rawDescriptionSources.isEmpty()) return null

        var descriptionSources = rawDescriptionSources.mapNotNull(::sanitizeDescriptionEvidence)
        var description = sanitizeItemDescription(
            descriptionSources.joinToString(" ") { normalizeOcrText(it.text) },
        )
        var sku: String? = null
        var skuSources = emptyList<Evidence>()
        val firstCellText = normalizeOcrText(rawDescriptionSources.first().text)
        if (looksLikeInferredSku(firstCellText) && rawDescriptionSources.drop(1).any { it.text.any(Char::isLetter) }) {
            sku = firstCellText.replace(Regex("\\s+"), "")
            skuSources = listOf(rawDescriptionSources.first())
            descriptionSources = rawDescriptionSources.drop(1).mapNotNull(::sanitizeDescriptionEvidence)
            description = sanitizeItemDescription(
                descriptionSources.joinToString(" ") { normalizeOcrText(it.text) },
            )
        }
        if (description.isBlank() || looksLikeNumericOnly(description)) return null
        if (isNonProductDescription(description)) return null

        val quantitySources = listOf(columns.quantitySource)
        val unitPriceSources = listOfNotNull(columns.unitPriceSource)
        val amountSources = listOf(columns.amountSource)
        val allSources = (skuSources + descriptionSources + unitPriceSources + quantitySources + amountSources)
            .distinctBy { it.line.id to it.boundingBox }
        val sourceReferences = allSources.map { it.line.id }.distinct()
        val pageId = allSources.firstOrNull()?.line?.pageId ?: return null
        return ParsedLineItem(
            id = StableIds.lineId(
                documentId,
                pageId,
                sourceReferences.joinToString("|"),
                "$description|${columns.lineAmount}",
            ),
            type = ReceiptLineType.PRODUCT,
            description = field(description, descriptionSources, "spatial.description", 0.82f),
            sourceLineReferences = sourceReferences,
            identifiers = sku?.let { listOf(ReceiptIdentifier("merchant_sku", it)) }.orEmpty(),
            quantity = field(
                ReceiptQuantity(columns.quantity, QuantityUnit.EACH),
                quantitySources,
                "spatial.quantity",
                0.84f,
            ),
            unitPriceAmountMinor = field(
                columns.unitPrice,
                unitPriceSources,
                "spatial.unit_price",
                0.84f,
            ),
            grossAmountMinor = field(columns.lineAmount, amountSources, "spatial.gross_amount", 0.84f),
            netAmountMinor = field(columns.lineAmount, amountSources, "spatial.net_amount", 0.84f),
            confidence = if (columns.unitPrice != null) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
        )
    }

    private fun parseStrictRawProduct(row: SpatialRow, text: String): StrictRawProduct? {
        if (row.cells.size != 1) return null
        val match = STRICT_RAW_PRODUCT.find(text) ?: return null
        val description = sanitizeItemDescription(match.groupValues[1].trim(' ', ':', '-'))
        val quantity = match.groupValues[2]
        if (description.isBlank() || looksLikeNumericOnly(description)) return null
        if (isGenericNoteLikeDescription(description)) return null
        if (PRODUCT_METADATA_KEYWORDS.containsMatchIn(description)) return null
        if (PAYMENT_KEYWORDS.containsMatchIn(description)) return null
        if (NON_ITEM_SUMMARY_KEYWORDS.containsMatchIn(description)) return null
        if (quantity.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO && it <= MAX_BARE_QUANTITY } != true) return null
        return StrictRawProduct(description, quantity)
    }

    private fun inferNumericColumns(cells: List<Evidence>): NumericColumns? {
        val amountIndex = cells.lastIndex
        val amountSource = cells[amountIndex]
        val lineAmount = amountSource.strictAmount() ?: return null
        if (amountIndex < 2) return null

        val adjacentIndex = amountIndex - 1
        val adjacent = cells[adjacentIndex]
        if (amountIndex >= 3) {
            val earlierIndex = amountIndex - 2
            val earlier = cells[earlierIndex]
            val options = listOfNotNull(
                numericPair(
                    quantitySource = adjacent,
                    quantityIndex = adjacentIndex,
                    unitPriceSource = earlier,
                    unitPriceIndex = earlierIndex,
                    lineAmount = lineAmount,
                    amountSource = amountSource,
                ),
                numericPair(
                    quantitySource = earlier,
                    quantityIndex = earlierIndex,
                    unitPriceSource = adjacent,
                    unitPriceIndex = adjacentIndex,
                    lineAmount = lineAmount,
                    amountSource = amountSource,
                ),
            )
            options.maxByOrNull(NumericColumns::score)?.let { return it }
            if (earlier.strictAmount() != null && adjacent.strictAmount() != null) return null
        }

        val quantity = adjacent.quantityCandidate() ?: return null
        val quantityNumber = quantity.toBigDecimalOrNull() ?: return null
        val descriptionSources = cells.take(adjacentIndex)
        val hasDescription = descriptionSources.any { evidence -> evidence.text.any(Char::isLetter) }
        val isConvincingBareQuantity = quantityNumber <= MAX_BARE_QUANTITY && !adjacent.looksMoneyFormatted()
        if (!hasDescription || (!adjacent.hasQuantityUnit() && !isConvincingBareQuantity)) return null
        return NumericColumns(
            quantity = quantity,
            quantitySource = adjacent,
            unitPrice = null,
            unitPriceSource = null,
            lineAmount = lineAmount,
            amountSource = amountSource,
            descriptionEndIndex = adjacentIndex,
            score = if (adjacent.hasQuantityUnit()) 180 else 120,
        )
    }

    private fun numericPair(
        quantitySource: Evidence,
        quantityIndex: Int,
        unitPriceSource: Evidence,
        unitPriceIndex: Int,
        lineAmount: Long,
        amountSource: Evidence,
    ): NumericColumns? {
        val quantity = quantitySource.quantityCandidate() ?: return null
        val unitPrice = unitPriceSource.strictAmount() ?: return null
        val calculated = runCatching {
            BigDecimal(quantity).multiply(BigDecimal.valueOf(unitPrice))
        }.getOrNull() ?: return null
        val difference = calculated.subtract(BigDecimal.valueOf(lineAmount)).abs()
        if (difference > MAX_ROUNDING_DELTA_MINOR) return null
        val exactConservation = multiplyExactMinor(quantity, unitPrice) == lineAmount
        val quantityNumber = quantity.toBigDecimalOrNull() ?: return null
        val quantityScore = when {
            quantitySource.hasQuantityUnit() -> 200
            quantityNumber <= BigDecimal("100") -> 150
            quantityNumber <= BigDecimal("999") -> 100
            else -> 20
        }
        val score = quantityScore +
            (if (unitPriceSource.looksMoneyFormatted()) 30 else 0) +
            (if (unitPriceIndex < quantityIndex) 10 else 0) -
            (if (exactConservation) 0 else 25)
        return NumericColumns(
            quantity = quantity,
            quantitySource = quantitySource,
            unitPrice = unitPrice,
            unitPriceSource = unitPriceSource,
            lineAmount = lineAmount,
            amountSource = amountSource,
            descriptionEndIndex = minOf(quantityIndex, unitPriceIndex),
            score = score,
        )
    }

    private fun classifyLine(text: String): ReceiptLineType? = when {
        DISCOUNT_KEYWORDS.containsMatchIn(text) -> ReceiptLineType.DISCOUNT
        TAX_KEYWORDS.containsMatchIn(text) -> ReceiptLineType.TAX
        TAXABLE_BASE_KEYWORDS.containsMatchIn(text) -> ReceiptLineType.OTHER
        FEE_KEYWORDS.containsMatchIn(text) -> ReceiptLineType.FEE
        REFUND_KEYWORDS.containsMatchIn(text) -> ReceiptLineType.REFUND
        TIP_KEYWORDS.containsMatchIn(text) -> ReceiptLineType.TIP
        ROUNDING_KEYWORDS.containsMatchIn(text) -> ReceiptLineType.ROUNDING
        else -> null
    }

    private fun signedAmount(type: ReceiptLineType, amount: Long): Long = when (type) {
        ReceiptLineType.DISCOUNT, ReceiptLineType.REFUND -> if (amount > 0) -amount else amount
        else -> amount
    }

    private fun List<Evidence>.amount(): Long? = joinToString(" ") { it.text }
        .let(AmountParser::extractLastMinor)

    private fun List<Evidence>.quantity(): String? {
        val text = normalizeQuantityText(joinToString(" ") { it.text })
        val match = QUANTITY_CELL.find(text) ?: return null
        return match.groupValues[1].trimStart('+').takeIf { value ->
            value.toBigDecimalOrNull()?.signum() == 1
        }
    }

    private fun expandCompoundCells(cells: List<Evidence>): List<Evidence> = cells
        .flatMap(::splitCompoundNumericEvidence)
        .sortedWith(compareBy({ it.boundingBox?.left ?: Int.MAX_VALUE }, { it.line.recognitionOrder }))

    private fun splitCompoundNumericEvidence(evidence: Evidence): List<Evidence> {
        val normalized = normalizeOcrText(evidence.text)
        val box = evidence.boundingBox ?: return listOf(evidence)
        val matches = TABLE_NUMBER_TOKEN.findAll(normalized).toList()
        if (matches.size < 2) return listOf(evidence)
        val remainder = TABLE_NUMBER_TOKEN.replace(normalized, "")
            .replace(TABLE_NUMBER_SEPARATOR, "")
        if (remainder.isNotBlank()) return listOf(evidence)

        return matches.mapNotNull { match ->
            val token = match.value.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val tokenLeft = box.left + box.width * match.range.first / normalized.length.coerceAtLeast(1)
            val tokenRight = (
                box.left + box.width * (match.range.last + 1) / normalized.length.coerceAtLeast(1)
                ).coerceAtLeast(tokenLeft + 1).coerceAtMost(box.right)
            evidence.copy(
                text = token,
                boundingBox = BoundingBox(tokenLeft, box.top, tokenRight, box.bottom),
            )
        }.ifEmpty { listOf(evidence) }
    }

    /**
     * ML Kit can emit a printed grouped integer as adjacent OCR elements such as
     * `26,` + `700` or `26` + `,` + `700`. Keep the original evidence objects for
     * provenance, but treat those elements as one numeric cell while resolving
     * table columns. A comma must cross an element boundary, so an ordinary
     * quantity followed by a complete amount (`2` + `3,000`) is never joined.
     */
    private fun mergeGroupedAmountFragments(cells: List<Evidence>): List<NumericCell> {
        val input = cells.map { evidence ->
            NumericCell(
                text = normalizeOcrText(evidence.text),
                sources = listOf(evidence),
                boundingBox = evidence.boundingBox,
            )
        }
        val merged = mutableListOf<NumericCell>()
        var index = 0
        while (index < input.size) {
            val maximumGroupSize = minOf(3, input.size - index)
            val grouped = (maximumGroupSize downTo 2)
                .asSequence()
                .map { size -> input.subList(index, index + size) }
                .firstOrNull(::isGroupedAmountFragment)
            if (grouped == null) {
                merged += input[index]
                index += 1
            } else {
                merged += NumericCell(
                    text = grouped.joinToString("") { it.text.replace(" ", "") },
                    sources = grouped.flatMap(NumericCell::sources),
                    boundingBox = unionCellBoxes(grouped.mapNotNull(NumericCell::boundingBox)),
                )
                index += grouped.size
            }
        }
        return merged
    }

    private fun isGroupedAmountFragment(group: List<NumericCell>): Boolean {
        val boundaryCommas = group.zipWithNext().map { (first, second) ->
            first.text.trimEnd().endsWith(',') || second.text.trimStart().startsWith(',')
        }
        if (boundaryCommas.isEmpty() || boundaryCommas.any { hasComma -> !hasComma }) return false
        if (!group.zipWithNext().all { (first, second) -> cellsAreAdjacent(first, second) }) return false
        val compact = group.joinToString("") { it.text.replace(" ", "") }
        return GROUPED_AMOUNT_CELL.matches(compact)
    }

    private fun cellsAreAdjacent(first: NumericCell, second: NumericCell): Boolean {
        val firstBox = first.boundingBox
        val secondBox = second.boundingBox
        if (firstBox == null || secondBox == null) {
            return first.sources.any { firstSource ->
                second.sources.any { secondSource -> firstSource.line.id == secondSource.line.id }
            }
        }
        val overlap = minOf(firstBox.bottom, secondBox.bottom) - maxOf(firstBox.top, secondBox.top)
        val minimumHeight = minOf(firstBox.height, secondBox.height).coerceAtLeast(1)
        if (overlap <= 0 || overlap * 2 < minimumHeight) return false
        val horizontalGap = secondBox.left - firstBox.right
        val maximumGap = maxOf(firstBox.height, secondBox.height).coerceAtLeast(1) * 2
        return horizontalGap in -minimumHeight..maximumGap
    }

    private fun unionCellBoxes(boxes: List<BoundingBox>): BoundingBox? = if (boxes.isEmpty()) {
        null
    } else {
        BoundingBox(
            left = boxes.minOf(BoundingBox::left),
            top = boxes.minOf(BoundingBox::top),
            right = boxes.maxOf(BoundingBox::right),
            bottom = boxes.maxOf(BoundingBox::bottom),
        )
    }

    private fun Evidence.isStandaloneTableNumber(): Boolean = TABLE_NUMBER_CELL.matches(normalizeOcrText(text)) ||
        QUANTITY_CELL.matches(normalizeQuantityText(text))

    private fun <T> field(
        value: T?,
        sources: List<Evidence>,
        rule: String,
        fallbackConfidence: Float,
    ): ParsedField<T> = if (value == null) {
        ParsedField(null)
    } else {
        ParsedField(
            value = value,
            provenance = sources.distinctBy { it.line.id to it.boundingBox }.map { source ->
                FieldProvenance(
                    sourcePageId = source.line.pageId,
                    ocrLineId = source.line.id,
                    boundingBox = source.boundingBox,
                    rawText = source.text,
                    parserRuleId = "$rulePrefix.$rule",
                    confidence = minOf(source.confidence ?: fallbackConfidence, fallbackConfidence),
                )
            },
        )
    }

    private fun headerType(value: String): ColumnType? {
        when {
            SKU_HEADER.matches(value) -> return ColumnType.SKU
            NAME_HEADER.matches(value) -> return ColumnType.NAME
            UNIT_PRICE_HEADER.matches(value) -> return ColumnType.UNIT_PRICE
            QUANTITY_HEADER.matches(value) -> return ColumnType.QUANTITY
            AMOUNT_HEADER.matches(value) -> return ColumnType.AMOUNT
        }
        if (value.length !in 2..8 || value.any(Char::isDigit)) return null
        val ranked = FUZZY_HEADER_ALIASES.flatMap { (type, aliases) ->
            aliases.map { alias -> type to editDistance(value.lowercase(), alias.lowercase()) }
        }
        val minimum = ranked.minOfOrNull { it.second } ?: return null
        if (minimum > 1) return null
        return ranked.filter { it.second == minimum }.map { it.first }.distinct().singleOrNull()
    }

    private fun normalizeHeader(value: String): String = normalizeOcrText(value)
        .replace(Regex("""[\s:：|/·]"""), "")

    private fun isTableHeaderText(value: String): Boolean {
        val matches = value.split(Regex("\\s+")).mapNotNull { headerType(normalizeHeader(it)) }.toSet()
        return ColumnType.NAME in matches && ColumnType.AMOUNT in matches
    }

    private fun looksLikeSku(value: String): Boolean {
        val compact = value.replace(Regex("\\s+"), "")
        return compact.length in 4..24 && compact.any(Char::isDigit) &&
            compact.matches(Regex("""[A-Za-z0-9-]+""")) && ',' !in compact
    }

    private fun looksLikeInferredSku(value: String): Boolean =
        looksLikeSku(value) && !MEASUREMENT_TOKEN.matches(value.replace(Regex("\\s+"), ""))

    private fun looksLikeNumericOnly(value: String): Boolean = value.none(Char::isLetter)

    private fun isGenericNoteLikeDescription(value: String): Boolean =
        NOTE_LIKE_DESCRIPTION.containsMatchIn(normalizeOcrText(value))

    /**
     * Reject prose that happens to contain a number. Receipt footers and notices often end in
     * dates, phone fragments, or amounts; the loose fallback parser must not promote those lines
     * to products. Product names remain allowed when they contain ordinary spaces or measurements.
     */
    private fun isNonProductDescription(value: String): Boolean {
        val normalized = normalizeOcrText(value)
        if (normalized.isBlank()) return true
        if (NON_PRODUCT_DESCRIPTION_CONTEXT.containsMatchIn(normalized)) return true
        val wordCount = normalized.split(Regex("\\s+")).count(String::isNotBlank)
        return wordCount >= 4 && NARRATIVE_DESCRIPTION_CONTEXT.containsMatchIn(normalized)
    }

    private fun Evidence.strictAmount(): Long? {
        val normalized = normalizeOcrText(text)
        if (!PRINTED_AMOUNT_CELL.matches(normalized)) return null
        return AmountParser.extractLastMinor(normalized)
    }

    private fun Evidence.quantityCandidate(): String? {
        val normalized = normalizeQuantityText(text)
        val match = QUANTITY_CELL.find(normalized) ?: return null
        return match.groupValues[1].trimStart('+').takeIf { value ->
            value.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO && it <= MAX_QUANTITY } == true
        }
    }

    private fun Evidence.hasQuantityUnit(): Boolean = QUANTITY_WITH_UNIT.matches(normalizeQuantityText(text))

    private fun Evidence.looksMoneyFormatted(): Boolean = MONEY_FORMAT_SIGNAL.containsMatchIn(normalizeOcrText(text))

    private fun NumericCell.isStandaloneTableNumber(): Boolean = TABLE_NUMBER_CELL.matches(text) ||
        QUANTITY_CELL.matches(normalizeQuantityText(text))

    private fun NumericCell.strictAmount(): Long? {
        if (!PRINTED_AMOUNT_CELL.matches(text)) return null
        return AmountParser.extractLastMinor(text)
    }

    private fun NumericCell.quantityCandidate(): String? {
        val match = QUANTITY_CELL.find(normalizeQuantityText(text)) ?: return null
        return match.groupValues[1].trimStart('+').takeIf { value ->
            value.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO && it <= MAX_QUANTITY } == true
        }
    }

    /**
     * Interpret glyphs that are commonly confused with digits, but only as a candidate. Callers
     * must additionally prove it with the explicit quantity column and monetary conservation.
     */
    private fun NumericCell.ocrConfusedQuantityCandidate(): String? {
        val normalized = normalizeOcrText(text)
            .trim(' ', '*', '＊', '※')
            .trim()
        val match = OCR_CONFUSED_QUANTITY.matchEntire(normalized) ?: return null
        val rawDigits = match.groupValues[1]
        if (rawDigits.all(Char::isDigit)) return null
        val corrected = buildString(rawDigits.length) {
            rawDigits.forEach { character ->
                append(
                    when (character) {
                        'I', 'i', 'l', 'L', '|', '!', 'ㅣ' -> '1'
                        'Z', 'z' -> '2'
                        'S', 's' -> '5'
                        'O', 'o', 'ㅇ' -> '0'
                        else -> character
                    },
                )
            }
        }
        return corrected.takeIf { value ->
            value.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO && it <= MAX_QUANTITY } == true
        }
    }

    private fun NumericCell.hasQuantityUnit(): Boolean = QUANTITY_WITH_UNIT.matches(normalizeQuantityText(text))

    private fun NumericCell.looksMoneyFormatted(): Boolean = MONEY_FORMAT_SIGNAL.containsMatchIn(text)

    private fun trailingAmountHasMoneySignal(value: String): Boolean = TRAILING_AMOUNT.find(value)
        ?.value
        ?.let(MONEY_FORMAT_SIGNAL::containsMatchIn)
        ?: false

    private fun mergeWrappedText(first: String?, second: String?): String {
        val firstValue = first?.trim().orEmpty()
        val secondValue = second?.trim().orEmpty()
        return when {
            firstValue.isEmpty() -> secondValue
            secondValue.isEmpty() -> firstValue
            firstValue == secondValue -> firstValue
            secondValue.startsWith(firstValue) -> secondValue
            firstValue.endsWith(secondValue) -> firstValue
            else -> "$firstValue $secondValue"
        }
    }

    private fun sanitizeItemDescription(value: String): String = normalizeOcrText(value)
        .replace(LEADING_ITEM_MARKERS, "")
        .replace(TRAILING_ITEM_MARKERS, "")
        .replace(OCR_KG_MEASUREMENT) { match -> "${match.groupValues[1]}kg" }
        .let(::correctKnownItemNameOcr)
        .trim()

    /**
     * A one-edit correction is applied only when one full Hangul token has one unambiguous known
     * receipt spelling. This intentionally avoids broad syllable replacement such as 플 -> 골.
     */
    private fun correctKnownItemNameOcr(value: String): String = HANGUL_ITEM_TOKEN.replace(value) { match ->
        val candidates = KNOWN_ITEM_NAME_HINTS.filter { candidate ->
            candidate.length == match.value.length && editDistance(match.value, candidate) == 1
        }
        candidates.singleOrNull() ?: match.value
    }

    /** Keep the name cell textual even when OCR appends a numeric column to the same element. */
    private fun sanitizeDescriptionEvidence(evidence: Evidence): Evidence? {
        if (evidence.isStandaloneTableNumber() || evidence.text.none(Char::isLetter)) return null
        var value = normalizeOcrText(evidence.text)
        repeat(3) {
            value = value.replace(TRAILING_NAME_NUMERIC_TOKEN, "").trim()
        }
        if (value.isBlank() || value.none(Char::isLetter)) return null
        return evidence.copy(text = value)
    }

    private fun normalizeQuantityText(value: String): String {
        val normalized = normalizeOcrText(value)
            .trim(' ', '*', '＊', '※')
            .trim()
        return OCR_ONE_QUANTITY.matchEntire(normalized)?.let { match ->
            "1${match.groupValues[1]}"
        } ?: normalized
    }

    private fun editDistance(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        first.forEachIndexed { firstIndex, firstCharacter ->
            val current = IntArray(second.length + 1)
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondCharacter ->
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (firstCharacter == secondCharacter) 0 else 1,
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private data class TableExtraction(
        val items: List<ParsedLineItem>,
        val consumedLineIds: Set<String>,
    )

    private data class TableHeader(
        val pageIndex: Int,
        val centers: Map<ColumnType, Int>,
    )

    private data class PendingName(
        val value: String,
        val sources: List<Evidence>,
        val sku: String?,
        val skuSources: List<Evidence>,
        val lastRow: SpatialRow,
        val fragmentCount: Int,
        val hasUnresolvedNumericEvidence: Boolean,
        val quantity: String?,
        val quantitySources: List<Evidence>,
        val unitPrice: Long?,
        val unitPriceSources: List<Evidence>,
    )

    private data class StrictRawProduct(
        val description: String,
        val quantity: String,
    )

    private data class NumericColumns(
        val quantity: String,
        val quantitySource: Evidence,
        val unitPrice: Long?,
        val unitPriceSource: Evidence?,
        val lineAmount: Long,
        val amountSource: Evidence,
        val descriptionEndIndex: Int,
        val score: Int,
    )

    private data class QuantityOption(
        val value: String,
        val source: NumericCell,
        val resolution: QuantityResolution,
    )

    private data class UnitPriceOption(
        val value: Long,
        val source: NumericCell,
    )

    private data class NumericCell(
        val text: String,
        val sources: List<Evidence>,
        val boundingBox: BoundingBox?,
    ) {
        val centerX: Int? get() = boundingBox?.let { (it.left + it.right) / 2 }
    }

    private data class NumberAssignment(
        val quantity: QuantityOption?,
        val unitPrice: UnitPriceOption?,
        val score: Int,
    )

    private data class TableNumbers(
        val quantity: String?,
        val quantitySources: List<Evidence>,
        val quantityResolution: QuantityResolution?,
        val unitPrice: Long?,
        val unitPriceSources: List<Evidence>,
        val lineAmount: Long,
        val amountSources: List<Evidence>,
        val isConserved: Boolean,
    )

    private enum class QuantityResolution {
        PRINTED,
        OCR_CORRECTED,
        AMOUNT_RATIO,
    }

    private enum class ColumnType {
        SKU,
        NAME,
        UNIT_PRICE,
        QUANTITY,
        AMOUNT,
    }

    private companion object {
        private val SKU_HEADER = Regex("""(?:상품|품목)?코드|품번|바코드|SKU""", RegexOption.IGNORE_CASE)
        private val NAME_HEADER = Regex("""상품명|품명|품목명|품목|제품명|내역""")
        private val UNIT_PRICE_HEADER = Regex("""단가|판매단가|판매가|매가""")
        private val QUANTITY_HEADER = Regex("""수량|판매수량|구매수량|개수|수""")
        private val AMOUNT_HEADER = Regex("""금액|판매금액|행금액|합계금액|합계|총액""")
        private val FUZZY_HEADER_ALIASES = mapOf(
            ColumnType.SKU to listOf("상품코드", "품목코드", "코드", "품번", "바코드", "sku"),
            ColumnType.NAME to listOf("상품명", "품명", "품목명", "품목", "제품명", "내역"),
            ColumnType.UNIT_PRICE to listOf("단가", "판매단가", "판매가", "매가"),
            ColumnType.QUANTITY to listOf("수량", "판매수량", "구매수량", "개수"),
            ColumnType.AMOUNT to listOf("금액", "판매금액", "행금액", "합계금액", "합계", "총액"),
        )
        private val HEADER_PATTERNS = listOf(
            ColumnType.SKU to Regex("""(?:상품\s*|품목\s*)?코드|품번|바코드|SKU""", RegexOption.IGNORE_CASE),
            ColumnType.NAME to Regex("""상품\s*명|품\s*명|품목\s*명|품목|제품\s*명|내역"""),
            ColumnType.UNIT_PRICE to Regex("""단\s*가|판매\s*단가|판매\s*가|매가"""),
            ColumnType.QUANTITY to Regex("""수\s*량|판매\s*수량|구매\s*수량|개수"""),
            ColumnType.AMOUNT to Regex("""금\s*액|판매\s*금액|행\s*금액|합계\s*금액|합계|총액"""),
        )
        private val SKU_PREFIX = Regex("""^\s*([A-Z0-9-]{4,24})\s+""", RegexOption.IGNORE_CASE)
        private val MEASUREMENT_TOKEN = Regex("""\d+(?:\.\d+)?(?:ML|L|G|KG|KS|K5)""", RegexOption.IGNORE_CASE)
        private val QUANTITY_CELL = Regex("""^\s*\+?(\d+(?:\.\d+)?)\s*(?:개|EA|PCS?)?\s*$""", RegexOption.IGNORE_CASE)
        private val QUANTITY_WITH_UNIT = Regex(
            """^\s*\+?\d+(?:\.\d+)?\s*(?:개|EA|PCS?)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val OCR_ONE_QUANTITY = Regex(
            """^[IilL|!ㅣ]\s*((?:개|EA|PCS?)?)$""",
            RegexOption.IGNORE_CASE,
        )
        private val OCR_CONFUSED_QUANTITY = Regex(
            """^([0-9IilL|!ㅣZzSsOoㅇ]+)\s*(?:개|EA|PCS?)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val PRINTED_AMOUNT_CELL = Regex(
            """^[₩￦]?\s*[+-]?\s*\(?\s*$GROUPED_INTEGER_PATTERN\s*\)?\s*원?$""",
        )
        private val TABLE_NUMBER_TOKEN = Regex(
            """[₩￦]?[+-]?\(?(?:\d+\.\d+|$GROUPED_INTEGER_PATTERN)\)?\s*(?:원|개|EA|PCS?)?""",
            RegexOption.IGNORE_CASE,
        )
        private val TABLE_NUMBER_CELL = Regex(
            """^[₩￦]?\s*[+-]?\s*\(?\s*(?:\d+\.\d+|$GROUPED_INTEGER_PATTERN)\s*\)?\s*(?:원|개|EA|PCS?)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val GROUPED_AMOUNT_CELL = Regex(
            """^[₩￦]?[+-]?\(?\d{1,3}(?:,\d{3})+\)?원?$""",
        )
        private val TABLE_NUMBER_SEPARATOR = Regex("""[\s|/:·]+""")
        private val MONEY_FORMAT_SIGNAL = Regex("""[,₩￦]|원$""")
        private val MAX_QUANTITY = BigDecimal("9999")
        private val MAX_BARE_QUANTITY = BigDecimal("100")
        private const val MAX_WRAPPED_NAME_FRAGMENTS = 2
        private val MAX_ROUNDING_DELTA_MINOR = BigDecimal.ONE
        private val STRICT_RAW_PRODUCT = Regex(
            """^\s*(.+?)\s+(\d+(?:\.\d+)?)\s+([+-]?$GROUPED_INTEGER_PATTERN)\s*원?\s*$""",
        )
        private val QUANTITY_PRICE = Regex(
            """(\d+(?:\.\d+)?)\s*[xX×*]\s*($GROUPED_INTEGER_PATTERN)(?:\s*=\s*($GROUPED_INTEGER_PATTERN))?""",
        )
        private val EXPLICIT_QUANTITY = Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*(?:개|EA|PCS?)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
        private val NOTE_LIKE_DESCRIPTION = Regex(
            """^(?:MEMO|메모|비고|안내|참고)(?:\b|[:\s-].*)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_PRODUCT_DESCRIPTION_CONTEXT = Regex(
            """(안내|공지|참고|비고|감사(?:합니다)?|고객(?:님|센터)?|방문|교환|환불|문의|이용|행사|이벤트|쇼핑|결제|카드|현금|승인|거래|주문|배송|보관|유의|주의|적립|포인트|회원|영업\s*시간|전화|주소|사업자|가능합니다|바랍니다|드립니다|하세요)""",
            RegexOption.IGNORE_CASE,
        )
        private val NARRATIVE_DESCRIPTION_CONTEXT = Regex(
            """(시작|추천|즐거운|편리한|최저가|신선한|원하시는|도와드|확인해|이용해)""",
            RegexOption.IGNORE_CASE,
        )
        private val LEADING_ITEM_MARKERS = Regex("""^[*＊※]+\s*""")
        private val TRAILING_ITEM_MARKERS = Regex("""\s*[*＊※]+$""")
        private val OCR_KG_MEASUREMENT = Regex(
            """(\d+(?:\.\d+)?)\s*k(?:s|5)(?=$|[^A-Za-z0-9])""",
            RegexOption.IGNORE_CASE,
        )
        private val HANGUL_ITEM_TOKEN = Regex("""[가-힣]{3,}""")
        private val KNOWN_ITEM_NAME_HINTS = setOf("제스프리골드")
        private val TRAILING_AMOUNT = Regex(
            """[+-]?\(?\s*$GROUPED_INTEGER_PATTERN\s*\)?\s*원?\s*$""",
        )
        private val TRAILING_NAME_NUMERIC_TOKEN = Regex(
            """\s+[₩￦]?\s*[+-]?\(?\s*$GROUPED_INTEGER_PATTERN\s*\)?\s*(?:원|개|EA|PCS?)?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val TABLE_END_KEYWORDS = Regex(
            """^\s*(?:[*#-]\s*)?(?:총\s*합계|합\s*계|최종\s*결제(?:\s*금액)?|결제\s*금액|받을\s*금액|공급\s*가액|과세\s*(?:물품)?\s*가액|면세\s*(?:물품)?\s*가액|부가\s*(?:가치\s*)?세|세액|카드\s*(?:결제|승인)|현금\s*결제)(?=\s*[:：]?\s*(?:[₩￦(+\-]?\s*\d|$))""",
        )
        private val NON_ITEM_SUMMARY_KEYWORDS = Regex(
            """(총\s*합계|합\s*계|최종\s*결제|결제\s*금액|받을\s*금액|공급\s*가액|과세\s*(?:물품)?\s*가액|면세\s*(?:물품)?\s*가액)""",
        )
        private val PAYMENT_KEYWORDS = Regex(
            """(카\s*드|현\s*금|간\s*편\s*결\s*제|결\s*제\s*수\s*단|결\s*제\s*금\s*액|승\s*인\s*금\s*액)""",
        )
        private val PRODUCT_METADATA_KEYWORDS = Regex(
            """((?:사용|적립|보유|누적|잔여)\s*포인트|포인트\s*(?:사용|적립|잔액|합계|누적|보유)|회원\s*(?:번호|No)|고객\s*번호|사업자|대표자?|주소|소재지|전화|TEL|카드\s*번호|승인\s*번호|거래\s*번호|주문\s*번호|영업\s*시간)""",
            RegexOption.IGNORE_CASE,
        )
        private val DISCOUNT_KEYWORDS = Regex("""(할인|쿠폰|프로모션|에누리)""")
        private val TAX_KEYWORDS = Regex(
            """(부가\s*(?:가치\s*)?세|세액|세금|VAT)""",
            RegexOption.IGNORE_CASE,
        )
        private val TAXABLE_BASE_KEYWORDS = Regex("""((?:과세|면세)\s*(?:물품)?\s*가액)""")
        private val FEE_KEYWORDS = Regex("""(수수료|봉사료|배달비|배송비)""")
        private val REFUND_KEYWORDS = Regex("""(반품|환불|취소)""")
        private val TIP_KEYWORDS = Regex("""(팁|TIP)""", RegexOption.IGNORE_CASE)
        private val ROUNDING_KEYWORDS = Regex("""(절사|반올림|조정)""")
    }
}
