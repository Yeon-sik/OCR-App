package com.pricetrace.receiptscanner.parser

import com.pricetrace.receiptscanner.ocr.OcrLine

/**
 * Coarse document regions derived only from OCR text and geometry.
 *
 * The regions are deliberately conservative: a missing boundary leaves a field for manual
 * review instead of allowing header/footer prose to participate in product parsing.
 */
internal data class ReceiptSections(
    val allRows: List<SpatialRow>,
    val headerRows: List<SpatialRow>,
    val itemRows: List<SpatialRow>,
    val summaryRows: List<SpatialRow>,
    val paymentRows: List<SpatialRow>,
) {
    val headerLines: List<OcrLine> get() = headerRows.flatMap(SpatialRow::lines).distinctBy(OcrLine::id)
    val itemLines: List<OcrLine> get() = itemRows.flatMap(SpatialRow::lines).distinctBy(OcrLine::id)
    val summaryLines: List<OcrLine> get() = summaryRows.flatMap(SpatialRow::lines).distinctBy(OcrLine::id)
    val paymentLines: List<OcrLine> get() = paymentRows.flatMap(SpatialRow::lines).distinctBy(OcrLine::id)
}

internal object ReceiptSectionDetector {
    fun detect(lines: List<OcrLine>): ReceiptSections {
        val allRows = buildSpatialRows(lines)
        val headerRows = mutableListOf<SpatialRow>()
        val itemRows = mutableListOf<SpatialRow>()
        val summaryRows = mutableListOf<SpatialRow>()
        val paymentRows = mutableListOf<SpatialRow>()

        allRows.groupBy(SpatialRow::pageIndex).values.forEach { pageRows ->
            val tableHeaderIndex = pageRows.indexOfFirst(::isItemTableHeader).takeIf { it >= 0 }
            val inferredItemIndex = pageRows.indices.firstOrNull { index ->
                looksLikeItemRow(pageRows[index]) || looksLikeWrappedItemStart(pageRows, index)
            }
            val itemStartIndex = tableHeaderIndex ?: inferredItemIndex?.let { inferredIndex ->
                val possibleHeaderIndex = inferredIndex - 1
                if (possibleHeaderIndex >= 0 && looksLikePossibleItemHeader(pageRows[possibleHeaderIndex])) {
                    possibleHeaderIndex
                } else {
                    inferredIndex
                }
            }
            val paymentStartIndex = pageRows.indices.firstOrNull { index ->
                val afterItemsStart = itemStartIndex == null || index > itemStartIndex
                afterItemsStart && !isItemTableHeader(pageRows[index]) &&
                    PAYMENT_BOUNDARY.containsMatchIn(pageRows[index].text) &&
                    !PAYMENT_NOTICE_CONTEXT.containsMatchIn(pageRows[index].text)
            }
            val summaryStartIndex = pageRows.indices.firstOrNull { index ->
                val afterItemsStart = itemStartIndex == null || index > itemStartIndex
                afterItemsStart && isSummaryStart(pageRows[index])
            } ?: paymentStartIndex
            val itemEndIndex = paymentStartIndex ?: pageRows.size
            val headerEndIndex = when {
                itemStartIndex != null -> itemStartIndex
                summaryStartIndex != null -> summaryStartIndex
                paymentStartIndex != null -> paymentStartIndex
                else -> pageRows.size
            }

            headerRows += pageRows.take(headerEndIndex)
            if (itemStartIndex != null && itemStartIndex < itemEndIndex) {
                itemRows += pageRows.subList(itemStartIndex, itemEndIndex)
            }

            val summaryFallbackStart = (pageRows.size - SUMMARY_FALLBACK_ROW_COUNT).coerceAtLeast(0)
            val paymentFallbackStart = (pageRows.size - PAYMENT_FALLBACK_ROW_COUNT).coerceAtLeast(0)
            summaryRows += pageRows.drop(summaryStartIndex ?: paymentStartIndex ?: summaryFallbackStart)
            paymentRows += pageRows.drop(paymentStartIndex ?: summaryStartIndex ?: paymentFallbackStart)
        }

        return ReceiptSections(
            allRows = allRows,
            headerRows = headerRows,
            itemRows = itemRows,
            summaryRows = summaryRows,
            paymentRows = paymentRows,
        )
    }

    private fun isSummaryStart(row: SpatialRow): Boolean {
        val text = normalizeOcrText(row.text)
        return !isItemTableHeader(row) && SUMMARY_BOUNDARY.containsMatchIn(text)
    }

    private fun isItemTableHeader(row: SpatialRow): Boolean {
        val text = normalizeOcrText(row.text)
        val hasName = ITEM_NAME_HEADER.containsMatchIn(text)
        val hasAmount = ITEM_AMOUNT_HEADER.containsMatchIn(text)
        val hasNumericColumn = ITEM_QUANTITY_HEADER.containsMatchIn(text) ||
            ITEM_UNIT_PRICE_HEADER.containsMatchIn(text)
        return hasName && hasAmount && hasNumericColumn
    }

    private fun looksLikeItemRow(row: SpatialRow): Boolean {
        val text = normalizeOcrText(row.text)
        if (text.isBlank() || !text.any(Char::isLetter)) return false
        if (NON_ITEM_CONTEXT.containsMatchIn(text) || PAYMENT_BOUNDARY.containsMatchIn(text)) return false
        if (NON_ITEM_NARRATIVE_CONTEXT.containsMatchIn(text)) return false
        if (ITEM_INLINE_MULTIPLICATION.containsMatchIn(text) || ITEM_NUMERIC_TAIL.containsMatchIn(text)) {
            return true
        }

        val numericCells = row.cells.count { cell ->
            val normalized = normalizeOcrText(cell.text)
            normalized.matches(NUMERIC_CELL)
        }
        return row.cells.size >= 3 && numericCells >= 2
    }

    private fun looksLikePossibleItemHeader(row: SpatialRow): Boolean {
        val text = normalizeOcrText(row.text)
        return row.cells.size >= 3 &&
            text.none(Char::isDigit) &&
            !NON_ITEM_CONTEXT.containsMatchIn(text) &&
            !PAYMENT_BOUNDARY.containsMatchIn(text)
    }

    private fun looksLikeWrappedItemStart(rows: List<SpatialRow>, index: Int): Boolean {
        val descriptionRow = rows[index]
        val amountRow = rows.getOrNull(index + 1) ?: return false
        if (descriptionRow.pageIndex != amountRow.pageIndex) return false
        val description = normalizeOcrText(descriptionRow.text)
        if (description.isBlank() || !description.any(Char::isLetter)) return false
        if (NON_ITEM_CONTEXT.containsMatchIn(description) || PAYMENT_BOUNDARY.containsMatchIn(description)) {
            return false
        }
        if (NON_ITEM_NARRATIVE_CONTEXT.containsMatchIn(description)) return false
        if (!AMOUNT_ONLY.matches(normalizeOcrText(amountRow.text))) return false

        val firstBox = descriptionRow.boundingBox ?: return true
        val secondBox = amountRow.boundingBox ?: return true
        val gap = secondBox.top - firstBox.bottom
        val maximumGap = maxOf(firstBox.height, secondBox.height).coerceAtLeast(1) * 2
        return gap in 0..maximumGap && secondBox.left >= firstBox.left + firstBox.width / 3
    }

    private const val PAYMENT_FALLBACK_ROW_COUNT = 10
    private const val SUMMARY_FALLBACK_ROW_COUNT = 14

    private val ITEM_NAME_HEADER = Regex("""(상품\s*명|품\s*명|품목\s*명|품목|제품\s*명|내역)""")
    private val ITEM_UNIT_PRICE_HEADER = Regex("""(단\s*가|판매\s*단가|판매\s*가|매가)""")
    private val ITEM_QUANTITY_HEADER = Regex("""(수\s*량|판매\s*수량|구매\s*수량|개수)""")
    private val ITEM_AMOUNT_HEADER = Regex("""(금\s*액|판매\s*금액|행\s*금액|합계\s*금액|합계|총액)""")
    private val PAYMENT_BOUNDARY = Regex(
        """(총\s*합\s*계|(?<!소)합\s*계|최\s*종\s*결\s*제|총\s*결\s*제|실\s*결\s*제|결\s*제\s*금\s*액|받\s*을\s*금\s*액|청\s*구\s*금\s*액|카\s*드\s*(?:결\s*제|승\s*인)|신\s*용\s*카\s*드\s*승\s*인|현\s*금\s*결\s*제)""",
    )
    private val PAYMENT_NOTICE_CONTEXT = Regex(
        """(결\s*제\s*가능|사용\s*가능|이용\s*가능|가능\s*(?:매장|점포|합니다)|결\s*제\s*수단\s*안내|카\s*드\s*안내)""",
    )
    private val SUMMARY_BOUNDARY = Regex(
        """^\s*(?:[*#-]\s*)?(?:소\s*계|공\s*급\s*가\s*액|과\s*세\s*(?:물\s*품)?\s*가\s*액|면\s*세\s*(?:물\s*품)?\s*가\s*액|총\s*할\s*인|할\s*인\s*합\s*계|부\s*가\s*(?:가\s*치\s*)?세|세\s*액|VAT|수\s*수\s*료|봉\s*사\s*료|배\s*달\s*비|배\s*송\s*비)(?=\s*[:：]?\s*(?:[₩￦(+\-]?\s*\d|$))""",
        RegexOption.IGNORE_CASE,
    )
    private val NON_ITEM_CONTEXT = Regex(
        """(사업자|대표자?|주소|소재지|전화|TEL|거래\s*(?:일시|일자|번호)|구매\s*(?:일시|일자|시각)|영업\s*시간|회원\s*번호|고객\s*번호|승인\s*번호|카드\s*번호|안내|공지|교환|환불)""",
        RegexOption.IGNORE_CASE,
    )
    private val NON_ITEM_NARRATIVE_CONTEXT = Regex(
        """(감사(?:합니다)?|고객(?:님|센터)?|방문|문의|이용|행사|이벤트|쇼핑|결제|카드|현금|승인|거래|주문|배송|보관|유의|주의|적립|포인트|가능합니다|바랍니다|드립니다|하세요|시작)""",
        RegexOption.IGNORE_CASE,
    )
    private val ITEM_INLINE_MULTIPLICATION = Regex(
        """\d+(?:\.\d+)?\s*[xX×*]\s*$GROUPED_INTEGER_PATTERN""",
    )
    private val ITEM_NUMERIC_TAIL = Regex(
        """\S.*\s+\d+(?:\.\d+)?(?:\s*(?:개|EA|PCS?))?\s+[₩￦]?[+-]?\s*$GROUPED_INTEGER_PATTERN\s*원?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMERIC_CELL = Regex(
        """^[₩￦]?[+-]?\s*\(?\s*$GROUPED_INTEGER_PATTERN\s*\)?\s*(?:원|개|EA|PCS?)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT_ONLY = Regex(
        """^[₩￦]?\s*[+-]?\s*\(?\s*$GROUPED_INTEGER_PATTERN\s*\)?\s*원?$""",
    )
}
