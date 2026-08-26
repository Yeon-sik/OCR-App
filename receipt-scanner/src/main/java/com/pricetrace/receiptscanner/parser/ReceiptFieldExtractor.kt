package com.pricetrace.receiptscanner.parser

import com.pricetrace.receiptscanner.domain.FieldProvenance
import com.pricetrace.receiptscanner.domain.ParsedField
import com.pricetrace.receiptscanner.domain.ParsedTotals
import com.pricetrace.receiptscanner.ocr.OcrLine
import java.text.Normalizer
import kotlin.math.abs

internal data class ExtractedReceiptFields(
    val merchantName: ParsedField<String>,
    val branchName: ParsedField<String>,
    val businessRegistrationNumber: ParsedField<String>,
    val address: ParsedField<String>,
    val issuedOn: ParsedField<String>,
    val issuedTime: ParsedField<String>,
    val issuedAt: ParsedField<String>,
    val totals: ParsedTotals,
    val nonItemSourceLineIds: Set<String>,
)

/**
 * Extracts receipt-level fields before product-line parsing.
 *
 * ML Kit can return the label and value as separate lines when they belong to different text
 * blocks. Spatial rows and element-level evidence keep those layouts parseable without copying
 * unrelated text into a field value.
 */
internal class ReceiptFieldExtractor(
    private val rulePrefix: String,
) {
    fun extract(lines: List<OcrLine>): ExtractedReceiptFields = extract(ReceiptSectionDetector.detect(lines))

    fun extract(sections: ReceiptSections): ExtractedReceiptFields {
        val rows = sections.allRows
        val merchantRows = sections.headerRows.ifEmpty { rows.take(METADATA_FALLBACK_ROW_COUNT) }
        val bottomMerchantRows = findBottomMerchantRows(sections)
        val summaryRows = sections.summaryRows.ifEmpty { rows.takeLast(SUMMARY_FALLBACK_ROW_COUNT) }
        val paymentRows = sections.paymentRows.ifEmpty { rows }
        val transactionRows = (merchantRows + summaryRows)
            .distinctBy { row -> row.pageIndex to row.minRecognitionOrder }
        val branch = findBranch(merchantRows)
        val merchant = findMerchant(merchantRows, bottomMerchantRows, branch?.value)
        val businessNumber = findBusinessNumber(merchantRows)
        val address = findAddress(merchantRows)
        val sectionDateTime = findDateTime(transactionRows)
        val labeledDateTimeFallback = findDateTime(rows, requirePurchaseContext = true)
        val dateTime = DateTimeCandidates(
            date = sectionDateTime.date ?: labeledDateTimeFallback.date,
            time = sectionDateTime.time ?: labeledDateTimeFallback.time,
            offsetDateTime = sectionDateTime.offsetDateTime ?: labeledDateTimeFallback.offsetDateTime,
        )
        val subtotal = findAmount(
            rows = summaryRows,
            keywords = SUBTOTAL_KEYWORDS,
            excludedContext = TOTAL_EXCLUDED_CONTEXT,
            rule = "totals.subtotal",
            confidence = 0.82f,
            sequentialPairDistance = 1,
        )
        val discount = findAmount(
            rows = summaryRows,
            keywords = DISCOUNT_TOTAL_KEYWORDS,
            excludedContext = Regex("""(취소|환불)"""),
            rule = "totals.discount",
            confidence = 0.82f,
            sequentialPairDistance = 1,
        )?.map { amount -> if (amount > 0) -amount else amount }
        val tax = findAmount(
            rows = summaryRows,
            keywords = TAX_AMOUNT_KEYWORDS,
            excludedContext = TAX_EXCLUDED_CONTEXT,
            rule = "totals.tax.explicit_amount",
            confidence = 0.86f,
            sequentialPairDistance = 1,
        )
        val fee = findAmount(
            rows = summaryRows,
            keywords = FEE_KEYWORDS,
            excludedContext = Regex("""(무료|없음)"""),
            rule = "totals.fee",
            confidence = 0.8f,
            sequentialPairDistance = 1,
        )
        val grandTotal = findGrandTotal(paymentRows)

        val nonItemSources = buildSet {
            listOfNotNull(
                merchant,
                branch,
                businessNumber,
                address,
                dateTime.date,
                dateTime.time,
                dateTime.offsetDateTime,
                subtotal,
                grandTotal,
            ).flatMapTo(this) { candidate -> candidate.sources.map { it.line.id } }
        }

        return ExtractedReceiptFields(
            merchantName = merchant.toParsedField(),
            branchName = branch.toParsedField(),
            businessRegistrationNumber = businessNumber.toParsedField(),
            address = address.toParsedField(),
            issuedOn = dateTime.date.toParsedField(),
            issuedTime = dateTime.time.toParsedField(),
            issuedAt = dateTime.offsetDateTime.toParsedField(),
            totals = ParsedTotals(
                subtotalAmountMinor = subtotal.toParsedField(),
                discountAmountMinor = discount.toParsedField(),
                taxAmountMinor = tax.toParsedField(),
                feeAmountMinor = fee.toParsedField(),
                grandTotalAmountMinor = grandTotal.toParsedField(),
            ),
            nonItemSourceLineIds = nonItemSources,
        )
    }

    private fun findBottomMerchantRows(sections: ReceiptSections): List<SpatialRow> {
        val itemRowKeys = sections.itemRows
            .map { row -> row.pageIndex to row.minRecognitionOrder }
            .toSet()
        return sections.allRows
            .groupBy(SpatialRow::pageIndex)
            .values
            .flatMap { pageRows -> pageRows.takeLast(MERCHANT_FOOTER_SEARCH_ROW_COUNT) }
            .filterNot { row -> (row.pageIndex to row.minRecognitionOrder) in itemRowKeys }
    }

    private fun findMerchant(
        headerRows: List<SpatialRow>,
        bottomRows: List<SpatialRow>,
        branchName: String?,
    ): Candidate<String>? {
        return listOfNotNull(
            findMerchantInRows(
                rows = headerRows.take(MERCHANT_SEARCH_ROW_COUNT),
                branchName = branchName,
            ),
            findMerchantInRows(
                rows = bottomRows.takeLast(MERCHANT_FOOTER_SEARCH_ROW_COUNT),
                branchName = branchName,
                scoreBias = FOOTER_MERCHANT_SCORE_BONUS,
            ),
        ).maxWithOrNull(
            compareBy<Candidate<String>> { candidate -> candidate.score }
                .thenBy { candidate -> if (candidate.rule == "merchant.explicit_label") 1 else 0 },
        )
    }

    private fun findMerchantInRows(
        rows: List<SpatialRow>,
        branchName: String?,
        scoreBias: Int = 0,
    ): Candidate<String>? {
        val unlabeledRows = merchantNameBlock(rows)
        val candidates = buildList {
            rows.forEachIndexed { rowIndex, row ->
                val isNoticeRow = MERCHANT_NOTICE_CONTEXT.containsMatchIn(row.text) &&
                    !MERCHANT_LABEL.containsMatchIn(row.text)
                if (isNoticeRow) return@forEachIndexed

                MERCHANT_LABEL.find(row.text)?.let { label ->
                    val rawValue = row.text.substring(label.range.last + 1)
                    sanitizeMerchant(rawValue, branchName)?.let { value ->
                        add(
                            Candidate(
                                value = value,
                                sources = row.evidenceFor(value),
                                rule = "merchant.explicit_label",
                                confidence = 0.9f,
                                score = 300 - rowIndex + scoreBias,
                            ),
                        )
                    }
                }

                if (row !in unlabeledRows) return@forEachIndexed

                dominantMerchantElementRuns(row).forEach { sources ->
                    if (sources.size == 1 && !merchantElementCoversRow(sources.single(), row)) {
                        return@forEach
                    }
                    val rawValue = sources.joinToString(" ") { source -> normalize(source.text) }
                    sanitizeMerchant(rawValue, branchName)?.let { value ->
                        val valueSources = merchantSourcesForValue(value, sources)
                        add(
                            Candidate(
                                value = value,
                                sources = valueSources,
                                rule = "merchant.dominant_size_run",
                                confidence = 0.86f,
                                score = merchantScore(value, row, rowIndex, unlabeledRows, elementLevel = false) + 70 + scoreBias,
                            ),
                        )
                    }
                }

                row.elementEvidence.forEach { evidence ->
                    sanitizeMerchant(evidence.text, branchName)?.let { value ->
                        add(
                            Candidate(
                                value = value,
                                sources = listOf(evidence),
                                rule = "merchant.element_isolated",
                                confidence = 0.66f,
                                score = merchantScore(value, row, rowIndex, unlabeledRows, elementLevel = true) + scoreBias,
                            ),
                        )
                    }
                }

                sanitizeMerchant(row.text, branchName)?.let { value ->
                    add(
                        Candidate(
                            value = value,
                            sources = row.evidenceFor(value),
                            rule = "merchant.header_isolated",
                            confidence = 0.76f,
                            score = merchantScore(value, row, rowIndex, unlabeledRows, elementLevel = false) + scoreBias,
                        ),
                    )
                }
            }
        }
        return candidates.maxByOrNull(Candidate<String>::score)
    }

    private fun merchantSourcesForValue(value: String, sources: List<Evidence>): List<Evidence> {
        val compactValue = normalize(value).replace(" ", "")
        return sources.filter { source ->
            val compactSource = normalize(source.text).replace(" ", "")
            compactSource.isNotBlank() && compactValue.contains(compactSource)
        }.ifEmpty { sources }
    }

    private fun dominantMerchantElementRuns(row: SpatialRow): List<List<Evidence>> {
        val boxed = row.elementEvidence
            .filter { evidence -> evidence.boundingBox != null && evidence.text.any(Char::isLetter) }
            .sortedWith(compareBy({ it.boundingBox?.left ?: Int.MAX_VALUE }, { it.line.recognitionOrder }))
        if (boxed.size < 2) return emptyList()
        val maximumHeight = boxed.maxOf { evidence -> evidence.boundingBox?.height ?: 0 }.coerceAtLeast(1)
        val dominant = boxed.filter { evidence ->
            val height = evidence.boundingBox?.height ?: 0
            height * 100 >= maximumHeight * MIN_DOMINANT_MERCHANT_HEIGHT_PERCENT
        }
        if (dominant.size < 2) return emptyList()

        val runs = mutableListOf<MutableList<Evidence>>()
        dominant.forEach { evidence ->
            val current = runs.lastOrNull()
            val previous = current?.lastOrNull()
            if (previous == null || !merchantElementsAreAdjacent(previous, evidence, maximumHeight)) {
                runs += mutableListOf(evidence)
            } else {
                current += evidence
            }
        }
        return runs.filter { run -> run.size >= 2 || (dominant.size == 1 && run.isNotEmpty()) }
    }

    private fun merchantElementCoversRow(element: Evidence, row: SpatialRow): Boolean {
        val elementText = normalize(element.text).replace(Regex("\\s+"), "")
        val rowText = normalize(row.text).replace(Regex("\\s+"), "")
        return elementText.isNotBlank() && rowText.startsWith(elementText) &&
            elementText.length * 100 >= rowText.length * 82
    }

    private fun merchantElementsAreAdjacent(first: Evidence, second: Evidence, maximumHeight: Int): Boolean {
        val firstBox = first.boundingBox ?: return false
        val secondBox = second.boundingBox ?: return false
        val overlap = minOf(firstBox.bottom, secondBox.bottom) - maxOf(firstBox.top, secondBox.top)
        val minimumHeight = minOf(firstBox.height, secondBox.height).coerceAtLeast(1)
        if (overlap <= 0 || overlap * 2 < minimumHeight) return false
        val horizontalGap = secondBox.left - firstBox.right
        return horizontalGap in -minimumHeight..(maximumHeight * MAX_MERCHANT_ELEMENT_GAP_HEIGHTS)
    }

    private fun merchantNameBlock(rows: List<SpatialRow>): List<SpatialRow> {
        // OCR block order can place a small address/business row before the actual logo/name.
        // Do not stop the merchant search at the first metadata row; exclude metadata itself
        // while keeping nearby unlabeled brand rows eligible.
        return rows
            .take(MAX_UNLABELED_MERCHANT_ROWS)
            .filter { row ->
                !HEADER_METADATA_BOUNDARY.containsMatchIn(row.text) ||
                    MERCHANT_LABEL.containsMatchIn(row.text) ||
                    MERCHANT_NAME_SIGNAL.containsMatchIn(row.text)
            }
    }

    private fun merchantScore(
        value: String,
        row: SpatialRow,
        rowIndex: Int,
        nameBlock: List<SpatialRow>,
        elementLevel: Boolean,
    ): Int {
        var score = 180 - rowIndex * 18
        if (MERCHANT_NAME_SIGNAL.containsMatchIn(value)) score += 30
        if (elementLevel) score -= 55
        if (value.length in 2..20) score += 10
        val tokenCount = value.split(Regex("\\s+")).count(String::isNotBlank)
        if (!elementLevel && tokenCount in 2..5 && value.none(Char::isDigit)) score += 35

        val rowBox = row.boundingBox
        val boxes = nameBlock.mapNotNull(SpatialRow::boundingBox)
        if (rowBox != null && boxes.isNotEmpty()) {
            val maximumHeight = boxes.maxOf { it.height }.coerceAtLeast(1)
            val documentLeft = boxes.minOf { it.left }
            val documentRight = boxes.maxOf { it.right }
            val documentCenter = (documentLeft + documentRight) / 2
            val documentWidth = (documentRight - documentLeft).coerceAtLeast(1)
            val rowCenter = (rowBox.left + rowBox.right) / 2
            score += rowBox.height * 70 / maximumHeight
            if (abs(rowCenter - documentCenter) * 5 <= documentWidth) score += 35
        }
        if (!elementLevel && value.count(Char::isUpperCase) >= 3 && tokenCount >= 2) score += 20
        return score
    }

    private fun sanitizeMerchant(rawValue: String, branchName: String?): String? {
        var value = normalize(rawValue)
            .replace(MERCHANT_LABEL, "")
            .trim(' ', ':', '：', '-', '|', '·')
        MERCHANT_TRAILING_METADATA.find(value)?.let { metadata ->
            value = value.substring(0, metadata.range.first).trim()
        }
        MERCHANT_TRAILING_VALUE.find(value)?.let { metadata ->
            value = value.substring(0, metadata.range.first).trim()
        }
        MERCHANT_TRAILING_SLOGAN.find(value)?.let { slogan ->
            value = value.substring(0, slogan.range.first).trim()
        }
        if (branchName != null) {
            value = value.replace(
                Regex("""(?:^|\s)${Regex.escape(branchName)}(?=\s|$)"""),
                " ",
            ).trim()
        }
        value = value.split(Regex("""\s{2,}|[|｜]""")).first().trim()
        if (value.length !in 2..36) return null
        if (value == branchName || GENERIC_HEADER.matches(value)) return null
        if (NON_MERCHANT_TEXT.containsMatchIn(value)) return null
        if (MERCHANT_NOTICE_CONTEXT.containsMatchIn(value)) return null
        if (BUSINESS_NUMBER.containsMatchIn(value) || PHONE_NUMBER.containsMatchIn(value)) return null
        if (
            ADDRESS_START.find(value)?.range?.first == 0 &&
            (ADMINISTRATIVE_AREA.containsMatchIn(value) || value.any(Char::isDigit))
        ) return null
        val merchantWordCount = value.split(Regex("\\s+")).count(String::isNotBlank)
        if (
            MERCHANT_SENTENCE_CONTEXT.containsMatchIn(value) &&
            (!MERCHANT_NAME_SIGNAL.containsMatchIn(value) || merchantWordCount >= 3)
        ) return null
        if (ReceiptDateTimeParser.parse(value).let { it.issuedOn != null || it.localTime != null }) return null
        if (value.count(Char::isDigit) > value.length / 2) return null
        if (AmountParser.normalizeMinor(value) != null) return null
        if (value.none { it.isLetter() }) return null
        return value
    }

    private fun findBranch(rows: List<SpatialRow>): Candidate<String>? {
        rows.take(24).forEachIndexed { rowIndex, row ->
            BRANCH_LABEL.find(row.text)?.groupValues?.get(1)?.let(::cleanBranch)?.let { value ->
                return Candidate(
                    value = value,
                    sources = row.evidenceFor(value),
                    rule = "branch.explicit_label",
                    confidence = 0.9f,
                    score = 300 - rowIndex,
                )
            }
        }

        return buildList {
            rows.take(24).forEachIndexed { rowIndex, row ->
                if (BRANCH_EXCLUDED_CONTEXT.containsMatchIn(row.text)) return@forEachIndexed
                val searchableValues = buildList {
                    add(row.text to row.evidence)
                    row.elementEvidence.forEach { evidence -> add(evidence.text to listOf(evidence)) }
                    add(normalize(row.text).replace(Regex("\\s+"), "") to row.evidence)
                }
                searchableValues.forEach { (searchable, sources) ->
                    BRANCH_TOKEN.findAll(searchable).forEach { match ->
                        cleanBranch(match.groupValues[1])?.takeUnless(::isGenericBranchToken)?.let { value ->
                            add(
                                Candidate(
                                    value = value,
                                    sources = sources,
                                    rule = "branch.terminal_token",
                                    confidence = 0.76f,
                                    score = 120 - rowIndex,
                                ),
                            )
                        }
                    }
                }
            }
        }.maxByOrNull(Candidate<String>::score)
    }

    private fun cleanBranch(value: String): String? = normalize(value)
        .trim(' ', ':', '：', '-', '(', ')', '[', ']')
        .replace(Regex("\\s+"), "")
        .replace(Regex("""지\s*점$"""), "지점")
        .replace(Regex("""매\s*장$"""), "매장")
        .takeIf { it.length in 2..24 && it !in setOf("지점", "매장", "점포") }

    private fun isGenericBranchToken(value: String): Boolean = GENERIC_BRANCH_SUFFIXES.any(value::endsWith)

    private fun findBusinessNumber(rows: List<SpatialRow>): Candidate<String>? = rows.mapIndexedNotNull { index, row ->
        val explicitlyLabeled = BUSINESS_NUMBER_LABEL.containsMatchIn(row.text)
        val exactMatch = BUSINESS_NUMBER.find(row.text)
        val tolerantMatch = if (explicitlyLabeled && exactMatch == null) {
            BUSINESS_NUMBER_TOLERANT.find(row.text)
        } else {
            null
        }
        val match = exactMatch ?: tolerantMatch ?: return@mapIndexedNotNull null
        val correctedOcrDigits = exactMatch == null
        val digits = match.groupValues.drop(1).joinToString("") { group -> normalizeOcrDigits(group) }
        if (digits.length != 10 || digits.any { !it.isDigit() }) return@mapIndexedNotNull null
        if (!explicitlyLabeled && !isValidBusinessNumber(digits)) return@mapIndexedNotNull null
        Candidate(
            value = "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5)}",
            sources = row.evidenceFor(match.value),
            rule = when {
                correctedOcrDigits -> "merchant.business_number_labeled_ocr_corrected"
                explicitlyLabeled -> "merchant.business_number_labeled"
                else -> "merchant.business_number_checksum"
            },
            confidence = when {
                correctedOcrDigits -> 0.8f
                explicitlyLabeled -> 0.94f
                else -> 0.84f
            },
            score = (if (explicitlyLabeled) 300 else 180) - index - if (correctedOcrDigits) 20 else 0,
        )
    }.maxByOrNull(Candidate<String>::score)

    private fun isValidBusinessNumber(value: String): Boolean {
        if (value.length != 10 || value.any { !it.isDigit() }) return false
        val digits = value.map(Char::digitToInt)
        val weights = intArrayOf(1, 3, 7, 1, 3, 7, 1, 3, 5)
        var sum = (0 until 9).sumOf { index -> digits[index] * weights[index] }
        sum += (digits[8] * 5) / 10
        return (10 - sum % 10) % 10 == digits[9]
    }

    private fun normalizeOcrDigits(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    'O', 'o', 'Q', 'D' -> '0'
                    'I', 'i', 'L', 'l', '|' -> '1'
                    'S', 's' -> '5'
                    'B' -> '8'
                    else -> character
                },
            )
        }
    }

    private fun findAddress(rows: List<SpatialRow>): Candidate<String>? {
        val candidates = buildList {
            rows.forEachIndexed { index, row ->
                val label = ADDRESS_LABEL.find(row.text) ?: return@forEachIndexed
                val labeledValue = sanitizeAddress(row.text.substring(label.range.last + 1))
                if (labeledValue != null && looksLikeAddress(labeledValue, explicitlyLabeled = true)) {
                    add(
                        Candidate(
                            value = labeledValue,
                            sources = row.evidence,
                            rule = "merchant.address_labeled",
                            confidence = 0.86f,
                            score = 300 - index + addressStructureScore(labeledValue),
                        ),
                    )
                }

                var combined = labeledValue.orEmpty()
                val combinedSources = row.evidence.toMutableList()
                for (offset in 1..2) {
                    val nextRow = rows.getOrNull(index + offset) ?: break
                    if (nextRow.pageIndex != row.pageIndex || ADDRESS_CONTINUATION_STOP.containsMatchIn(nextRow.text)) break
                    val nextValue = sanitizeAddress(nextRow.text) ?: break
                    if (!looksLikeAddressContinuation(combined, nextValue)) break
                    combined = listOf(combined, nextValue).filter(String::isNotBlank).joinToString(" ")
                    combinedSources += nextRow.evidence
                    if (looksLikeAddress(combined, explicitlyLabeled = true)) {
                        add(
                            Candidate(
                                value = combined,
                                sources = combinedSources.toList(),
                                rule = "merchant.address_continuation",
                                confidence = 0.84f,
                                score = 320 - index + addressStructureScore(combined),
                            ),
                        )
                    }
                }
            }

            rows.forEachIndexed { index, row ->
                val value = sanitizeAddress(row.text) ?: return@forEachIndexed
                if (!looksLikeAddress(value, explicitlyLabeled = false)) return@forEachIndexed
                add(
                    Candidate(
                        value = value,
                        sources = row.evidence,
                        rule = "merchant.address_structure",
                        confidence = 0.72f,
                        score = 150 - index + addressStructureScore(value),
                    ),
                )
            }

            rows.forEachIndexed { index, row ->
                var combined = sanitizeAddress(row.text) ?: return@forEachIndexed
                if (!ADMINISTRATIVE_AREA.containsMatchIn(combined)) return@forEachIndexed
                val combinedSources = row.evidence.toMutableList()
                for (offset in 1..MAX_ADDRESS_CONTINUATION_ROWS) {
                    val nextRow = rows.getOrNull(index + offset) ?: break
                    if (nextRow.pageIndex != row.pageIndex || ADDRESS_CONTINUATION_STOP.containsMatchIn(nextRow.text)) break
                    val nextValue = sanitizeAddress(nextRow.text) ?: break
                    if (!looksLikeAddressContinuation(combined, nextValue)) break
                    combined = "$combined $nextValue".trim()
                    combinedSources += nextRow.evidence
                    if (looksLikeAddress(combined, explicitlyLabeled = false)) {
                        add(
                            Candidate(
                                value = combined,
                                sources = combinedSources.toList(),
                                rule = "merchant.address_structural_continuation",
                                confidence = 0.76f,
                                score = 190 - index + addressStructureScore(combined),
                            ),
                        )
                    }
                }
            }
        }
        return candidates.maxByOrNull(Candidate<String>::score)
    }

    private fun sanitizeAddress(rawValue: String): String? {
        var value = normalize(rawValue).trim(' ', ':', '：', '-', '[', ']')
        ADDRESS_START.find(value)?.let { start ->
            value = value.substring(start.range.first).trim()
        }
        ADDRESS_TRAILING_METADATA.find(value)?.let { metadata ->
            value = value.substring(0, metadata.range.first).trim()
        }
        return value.takeIf { it.length >= 5 }
    }

    private fun looksLikeAddress(value: String, explicitlyLabeled: Boolean): Boolean {
        if (BUSINESS_NUMBER.containsMatchIn(value) || PHONE_NUMBER.containsMatchIn(value)) return false
        if (explicitlyLabeled) return value.any { it in '가'..'힣' } && value.count(Char::isDigit) <= value.length / 2
        val hasAdministrativeArea = ADMINISTRATIVE_AREA.containsMatchIn(value)
        val hasStreetOrLot = ROAD_OR_LOT_ADDRESS.containsMatchIn(value)
        return hasAdministrativeArea && hasStreetOrLot
    }

    private fun addressStructureScore(value: String): Int =
        (if (ADMINISTRATIVE_AREA.containsMatchIn(value)) 20 else 0) +
            (if (ROAD_OR_LOT_ADDRESS.containsMatchIn(value)) 40 else 0) +
            (if (value.any(Char::isDigit)) 20 else 0) +
            value.length.coerceAtMost(30)

    private fun looksLikeAddressContinuation(current: String, next: String): Boolean {
        if (next.count(Char::isDigit) > next.length / 2) return false
        if (current.isBlank()) {
            return ADMINISTRATIVE_AREA.containsMatchIn(next) || ADDRESS_PARTIAL_STRUCTURE.containsMatchIn(next)
        }
        return looksLikeAddress(next, explicitlyLabeled = false) ||
            ADDRESS_CONTINUATION_SIGNAL.containsMatchIn(next)
    }

    private fun findDateTime(
        rows: List<SpatialRow>,
        requirePurchaseContext: Boolean = false,
    ): DateTimeCandidates {
        val parsedRows = rows.map { row -> row to ReceiptDateTimeParser.parse(row.text) }
        val date = parsedRows.mapIndexedNotNull { index, (row, parsed) ->
            val value = parsed.issuedOn ?: return@mapIndexedNotNull null
            if (NON_PURCHASE_DATE_CONTEXT.containsMatchIn(row.text)) return@mapIndexedNotNull null
            val labeled = PURCHASE_DATE_TIME_CONTEXT.containsMatchIn(row.text)
            if (requirePurchaseContext && !labeled) return@mapIndexedNotNull null
            Candidate(
                value = value,
                sources = row.evidence,
                rule = if (labeled) "date.purchase_labeled" else "date.numeric",
                confidence = if (labeled) 0.94f else 0.84f,
                score = (if (labeled) 300 else 160) - index,
            )
        }.maxByOrNull(Candidate<String>::score)

        val dateSourceIds = date?.sources?.map { it.line.id }.orEmpty().toSet()
        val dateOrder = date?.sources?.minOfOrNull { it.line.recognitionOrder }
        val datePage = date?.sources?.firstOrNull()?.line?.pageIndex
        val time = parsedRows.mapIndexedNotNull { index, (row, parsed) ->
            val value = parsed.localTime ?: return@mapIndexedNotNull null
            if (BUSINESS_HOURS_CONTEXT.containsMatchIn(row.text)) return@mapIndexedNotNull null
            val sameRowAsDate = row.lines.any { it.id in dateSourceIds }
            val labeled = PURCHASE_DATE_TIME_CONTEXT.containsMatchIn(row.text)
            if (requirePurchaseContext && !labeled && !sameRowAsDate) return@mapIndexedNotNull null
            val nearDate = dateOrder != null && datePage == row.pageIndex &&
                abs(row.minRecognitionOrder - dateOrder) <= 3
            val baseScore = when {
                sameRowAsDate -> 360
                labeled -> 320
                nearDate -> 240
                else -> 80
            }
            Candidate(
                value = value,
                sources = row.evidence,
                rule = when {
                    sameRowAsDate -> "time.same_row_as_date"
                    labeled -> "time.purchase_labeled"
                    nearDate -> "time.near_date"
                    else -> "time.numeric"
                },
                confidence = if (sameRowAsDate || labeled) 0.93f else 0.78f,
                score = baseScore - index,
            )
        }.maxByOrNull(Candidate<String>::score)

        val offsetDateTime = parsedRows.mapIndexedNotNull { index, (row, parsed) ->
            if (requirePurchaseContext && !PURCHASE_DATE_TIME_CONTEXT.containsMatchIn(row.text)) {
                return@mapIndexedNotNull null
            }
            parsed.offsetDateTime?.let { value ->
                Candidate(
                    value = value,
                    sources = row.evidence,
                    rule = "datetime.explicit_offset",
                    confidence = 0.98f,
                    score = 400 - index,
                )
            }
        }.maxByOrNull(Candidate<String>::score)

        return DateTimeCandidates(date, time, offsetDateTime)
    }

    private fun findGrandTotal(rows: List<SpatialRow>): Candidate<Long>? {
        val primary = findAmount(
            rows = rows,
            keywords = FINAL_TOTAL_PRIMARY,
            excludedContext = FINAL_TOTAL_EXCLUDED_CONTEXT,
            rule = "totals.grand_total_explicit",
            confidence = 0.96f,
            baseScore = 450,
            sequentialPairDistance = 2,
        )
        if (primary != null) return primary

        val paymentAmount = findAmount(
            rows = rows,
            keywords = FINAL_TOTAL_PAYMENT_AMOUNT,
            excludedContext = FINAL_TOTAL_EXCLUDED_CONTEXT,
            rule = "totals.grand_total_payment_amount",
            confidence = 0.9f,
            baseScore = 340,
            sequentialPairDistance = 2,
        )
        if (paymentAmount != null) return paymentAmount

        val generic = findAmount(
            rows = rows,
            keywords = FINAL_TOTAL_GENERIC,
            excludedContext = GENERIC_TOTAL_EXCLUDED_CONTEXT,
            rule = "totals.grand_total_generic",
            confidence = 0.84f,
            baseScore = 260,
            sequentialPairDistance = 2,
        )
        if (generic != null) return generic

        return findAmount(
            rows = rows,
            keywords = PAYMENT_TOTAL_FALLBACK,
            excludedContext = PAYMENT_TOTAL_EXCLUDED_CONTEXT,
            rule = "totals.grand_total_payment_fallback",
            confidence = 0.78f,
            baseScore = 180,
            sequentialPairDistance = 2,
        )
    }

    private fun findAmount(
        rows: List<SpatialRow>,
        keywords: Regex,
        excludedContext: Regex,
        rule: String,
        confidence: Float,
        baseScore: Int = 200,
        sequentialPairDistance: Int = 0,
    ): Candidate<Long>? = rows.mapIndexedNotNull { index, row ->
        if (!keywords.containsMatchIn(row.text) || excludedContext.containsMatchIn(row.text)) {
            return@mapIndexedNotNull null
        }

        findFragmentedAmount(index, row, rows, keywords, sequentialPairDistance)?.let { fragmented ->
            return@mapIndexedNotNull Candidate(
                value = fragmented.value,
                sources = fragmented.sources,
                rule = "$rule.fragmented_rows",
                confidence = confidence - 0.06f,
                score = baseScore + index + 5,
            )
        }

        val directAmount = extractAmountNearestKeyword(row.text, keywords)
        if (directAmount != null) {
            return@mapIndexedNotNull Candidate(
                value = directAmount,
                sources = row.evidence,
                rule = rule,
                confidence = confidence,
                score = baseScore + index,
            )
        }

        val amountRow = findPairedAmountRow(row, rows, sequentialPairDistance) ?: return@mapIndexedNotNull null
        val amount = AmountParser.extractLastMinor(amountRow.text) ?: return@mapIndexedNotNull null
        Candidate(
            value = amount,
            sources = (row.evidence + amountRow.evidence).distinctBy { it.line.id to it.boundingBox },
            rule = "$rule.paired_row",
            confidence = confidence - 0.08f,
            score = baseScore + index - 10,
        )
    }.maxByOrNull(Candidate<Long>::score)

    private fun findFragmentedAmount(
        keywordIndex: Int,
        keywordRow: SpatialRow,
        rows: List<SpatialRow>,
        keywords: Regex,
        sequentialPairDistance: Int,
    ): FragmentedAmount? {
        val keyword = keywords.find(keywordRow.text) ?: return null
        val fragments = mutableListOf<AmountFragment>()
        val suffix = normalizeAmountFragment(keywordRow.text.substring(keyword.range.last + 1))
        if (suffix.isNotBlank() && AMOUNT_FRAGMENT.matches(suffix)) {
            fragments += AmountFragment(suffix, keywordRow.evidence)
        }

        val maximumDistance = maxOf(4, sequentialPairDistance + 2)
        rows.drop(keywordIndex + 1)
            .take(maximumDistance)
            .takeWhile { candidate -> candidate.pageIndex == keywordRow.pageIndex }
            .forEach { candidate ->
                val fragment = normalizeAmountFragment(candidate.text)
                if (!AMOUNT_FRAGMENT.matches(fragment)) return@forEach
                if (!isLikelySequentialAmountLayout(keywordRow, candidate)) return@forEach
                fragments += AmountFragment(fragment, candidate.evidence)
            }

        if (fragments.size < 2) return null
        return (2..fragments.size).mapNotNull { fragmentCount ->
            val selected = fragments.take(fragmentCount)
            val combined = selected.joinToString(separator = "", transform = AmountFragment::text)
            if (',' !in combined || !AMOUNT_ONLY.matches(combined)) return@mapNotNull null
            val value = AmountParser.extractLastMinor(combined) ?: return@mapNotNull null
            FragmentedAmount(
                value = value,
                sources = (keywordRow.evidence + selected.flatMap(AmountFragment::sources))
                    .distinctBy { it.line.id to it.boundingBox },
                fragmentCount = fragmentCount,
            )
        }.maxByOrNull(FragmentedAmount::fragmentCount)
    }

    private fun normalizeAmountFragment(value: String): String = normalize(value)
        .trim(' ', ':', '：', '=', '|')

    private fun extractAmountNearestKeyword(text: String, keywords: Regex): Long? {
        val keyword = keywords.find(text) ?: return null
        val afterKeyword = text.substring(keyword.range.last + 1)
        AmountParser.extractAllMinor(afterKeyword).firstOrNull()?.let { return it }
        return AmountParser.extractLastMinor(text.substring(0, keyword.range.first))
    }

    private fun findPairedAmountRow(
        keywordRow: SpatialRow,
        rows: List<SpatialRow>,
        sequentialPairDistance: Int,
    ): SpatialRow? = rows
        .asSequence()
        .filter { candidate -> candidate !== keywordRow && candidate.pageIndex == keywordRow.pageIndex }
        .filter { candidate -> AMOUNT_ONLY.matches(normalize(candidate.text)) }
        .filter { candidate ->
            rowsShareVerticalBand(keywordRow, candidate) || (
                sequentialPairDistance > 0 &&
                    candidate.minRecognitionOrder > keywordRow.minRecognitionOrder &&
                    candidate.minRecognitionOrder - keywordRow.minRecognitionOrder <= sequentialPairDistance &&
                    isLikelySequentialAmountLayout(keywordRow, candidate)
                )
        }
        .filterNot { candidate -> isMetadataNumberRow(candidate, keywordRow, rows) }
        .minByOrNull { candidate ->
            val sameBand = rowsShareVerticalBand(keywordRow, candidate)
            (if (sameBand) 0 else 10_000) +
                abs(candidate.centerY - keywordRow.centerY) * 10 +
                abs(candidate.minRecognitionOrder - keywordRow.minRecognitionOrder)
        }

    private fun isLikelySequentialAmountLayout(labelRow: SpatialRow, amountRow: SpatialRow): Boolean {
        val labelBox = labelRow.boundingBox ?: return true
        val amountBox = amountRow.boundingBox ?: return true
        val verticalGap = amountBox.top - labelBox.bottom
        val maximumGap = maxOf(labelBox.height, amountBox.height).coerceAtLeast(1) * 4
        if (verticalGap !in 0..maximumGap) return false
        val labelCenterX = (labelBox.left + labelBox.right) / 2
        val amountCenterX = (amountBox.left + amountBox.right) / 2
        return amountCenterX >= labelCenterX
    }

    private fun isMetadataNumberRow(
        amountRow: SpatialRow,
        keywordRow: SpatialRow,
        rows: List<SpatialRow>,
    ): Boolean {
        val nearestContext = rows.asSequence()
            .filter { candidate ->
                candidate !== amountRow &&
                    candidate.pageIndex == amountRow.pageIndex &&
                    !AMOUNT_ONLY.matches(normalize(candidate.text)) &&
                    abs(candidate.minRecognitionOrder - amountRow.minRecognitionOrder) <= 1
            }
            .minByOrNull { candidate ->
                abs(candidate.minRecognitionOrder - amountRow.minRecognitionOrder)
            }
        return nearestContext !== keywordRow && nearestContext?.text?.let(METADATA_NUMBER_CONTEXT::containsMatchIn) == true
    }

    private fun <T> Candidate<T>?.toParsedField(): ParsedField<T> = if (this == null) {
        ParsedField(null)
    } else {
        ParsedField(
            value = value,
            provenance = sources.distinctBy { it.line.id to it.boundingBox }.map { evidence ->
                FieldProvenance(
                    sourcePageId = evidence.line.pageId,
                    ocrLineId = evidence.line.id,
                    boundingBox = evidence.boundingBox,
                    rawText = evidence.text,
                    parserRuleId = "$rulePrefix.$rule",
                    confidence = minOf(evidence.confidence ?: confidence, confidence),
                )
            },
        )
    }

    private fun <T, R> Candidate<T>.map(transform: (T) -> R): Candidate<R> = Candidate(
        value = transform(value),
        sources = sources,
        rule = rule,
        confidence = confidence,
        score = score,
    )

    private data class Candidate<T>(
        val value: T,
        val sources: List<Evidence>,
        val rule: String,
        val confidence: Float,
        val score: Int,
    )

    private data class DateTimeCandidates(
        val date: Candidate<String>?,
        val time: Candidate<String>?,
        val offsetDateTime: Candidate<String>?,
    )

    private data class AmountFragment(
        val text: String,
        val sources: List<Evidence>,
    )

    private data class FragmentedAmount(
        val value: Long,
        val sources: List<Evidence>,
        val fragmentCount: Int,
    )

    private companion object {
        private const val METADATA_FALLBACK_ROW_COUNT = 16
        private const val SUMMARY_FALLBACK_ROW_COUNT = 14
        private const val MIN_DOMINANT_MERCHANT_HEIGHT_PERCENT = 68
        private const val MAX_MERCHANT_ELEMENT_GAP_HEIGHTS = 2
        private const val MERCHANT_SEARCH_ROW_COUNT = 16
        private const val MERCHANT_FOOTER_SEARCH_ROW_COUNT = 12
        private const val FOOTER_MERCHANT_SCORE_BONUS = 8
        private const val MAX_ADDRESS_CONTINUATION_ROWS = 2
        private val MERCHANT_LABEL = Regex(
            """(?:상\s*호\s*명?|가\s*맹\s*점\s*명|판\s*매\s*처\s*명?|점\s*포\s*명)\s*[:：-]?\s*""",
        )
        private val MERCHANT_TRAILING_METADATA = Regex(
            """\s+(?:고객\s*센터|대표자?|사업자(?:\s*등록)?(?:\s*번호)?|주소|소재지|전화|TEL|영업\s*시간|OPEN|CLOSE|포인트|적립|회원\s*번호|영수증|매출\s*전표).*""",
            RegexOption.IGNORE_CASE,
        )
        private val MERCHANT_TRAILING_VALUE = Regex(
            """\s+(?:(?:서울특별시|부산광역시|대구광역시|인천광역시|광주광역시|대전광역시|울산광역시|세종특별자치시|[가-힣]{1,10}(?:도|시))\s+[가-힣]{1,10}(?:시|군|구)|\d{3}\s*[-‐‑–—.:]?\s*\d{2}\s*[-‐‑–—.:]?\s*\d{5}|0\d{1,2}[- ]?\d{3,4}[- ]?\d{4}|(?:19|20)\d{2}[./-]\d{1,2}[./-]\d{1,2}).*""",
        )
        private val MERCHANT_TRAILING_SLOGAN = Regex(
            """\s+SINCE\s+(?:19|20)\d{2}\b.*$""",
            RegexOption.IGNORE_CASE,
        )
        private val MERCHANT_NAME_SIGNAL = Regex(
            """(마트|마켓|슈퍼|스토어|상회|상점|몰|백화점|편의점|카페|커피|약국|식당|SHOP|STORE|MARKET|MART|CAFE)""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_MERCHANT_TEXT = Regex(
            """(영수증|매출\s*전표|사업자|대표자?|주소|소재지|전화|TEL|거래|승인|주문|품명|단가|수량|금액|결제|합계|고객\s*센터|영업\s*시간|WELCOME|감사합니다|포인트|적립)""",
            RegexOption.IGNORE_CASE,
        )
        private val MERCHANT_NOTICE_CONTEXT = Regex(
            """(안내|공지|교환|환불|이벤트|행사|문의|보관|이용|구매|고객님|방문|가능합니다|바랍니다|드립니다|하세요|됩니다)""",
            RegexOption.IGNORE_CASE,
        )
        private val MERCHANT_SENTENCE_CONTEXT = Regex(
            """(쇼핑|시작|감사(?:합니다)?|즐거운|방문|문의|가능합니다|바랍니다|드립니다|하세요|이용해|확인해)""",
            RegexOption.IGNORE_CASE,
        )
        private val HEADER_METADATA_BOUNDARY = Regex(
            """(사업자|대표자?|주소|소재지|전화|TEL|고객\s*센터|거래\s*(?:일시|일자|번호)|구매\s*(?:일시|일자|시각)|영업\s*시간)""",
            RegexOption.IGNORE_CASE,
        )
        private const val MAX_UNLABELED_MERCHANT_ROWS = 10
        private val GENERIC_HEADER = Regex("""(?:RECEIPT|영수증|매출전표|카드전표)""", RegexOption.IGNORE_CASE)

        private val BRANCH_LABEL = Regex(
            """(?:지\s*점\s*명?|점\s*포\s*명|매\s*장\s*명)\s*[:：-]?\s*([가-힣A-Za-z0-9][가-힣A-Za-z0-9 _-]{0,24}?(?:지\s*점|점|매\s*장))""",
        )
        private val BRANCH_TOKEN = Regex(
            """(?<![가-힣A-Za-z0-9])([가-힣A-Za-z0-9][가-힣A-Za-z0-9_-]{0,19}(?:지점|점|매장))(?![가-힣A-Za-z0-9])""",
        )
        private val BRANCH_EXCLUDED_CONTEXT = Regex(
            """(결\s*제|승\s*인|카\s*드|현\s*금|안내|공지|문의|가능|이용|영업\s*시간|고객\s*센터)""",
            RegexOption.IGNORE_CASE,
        )
        private val GENERIC_BRANCH_SUFFIXES = listOf("백화점", "편의점", "할인점", "전문점", "판매점", "가맹점")

        private val BUSINESS_NUMBER_LABEL = Regex(
            """사\s*업\s*자\s*(?:등\s*록)?\s*(?:번\s*호|No\.?)""",
            RegexOption.IGNORE_CASE,
        )
        private val BUSINESS_NUMBER = Regex(
            """(?<!\d)(\d{3})\s*[-‐‑–—.:]?\s*(\d{2})\s*[-‐‑–—.:]?\s*(\d{5})(?!\d)""",
        )
        private val BUSINESS_NUMBER_TOLERANT = Regex(
            """(?<![A-Za-z0-9])([0-9OoQDIiLlSsBb]{3})\s*[-‐‑–—.:]?\s*([0-9OoQDIiLlSsBb]{2})\s*[-‐‑–—.:]?\s*([0-9OoQDIiLlSsBb]{5})(?![A-Za-z0-9])""",
        )
        private val PHONE_NUMBER = Regex("""(?<!\d)(?:0\d{1,2})[- ]?\d{3,4}[- ]?\d{4}(?!\d)""")

        private val ADDRESS_LABEL = Regex(
            """(?:(?:사\s*업\s*장|사\s*업\s*소)\s*)?(?:주\s*소|소\s*재\s*지)\s*[:：-]?\s*""",
        )
        private val ADDRESS_TRAILING_METADATA = Regex(
            """\s+(?:대표자?|사업자(?:\s*등록)?(?:\s*번호)?|전화|TEL|고객\s*센터).*""",
            RegexOption.IGNORE_CASE,
        )
        private val ADDRESS_CONTINUATION_STOP = Regex(
            """(거\s*래|구\s*매|결\s*제|승\s*인|발\s*행|일\s*시|일\s*자|영\s*업\s*시간|사업자|대표자?|상\s*호|판매처|전화|TEL|품\s*명|단\s*가|수\s*량|금\s*액|합\s*계)""",
            RegexOption.IGNORE_CASE,
        )
        private val ADMINISTRATIVE_AREA = Regex(
            """(?:특별시|광역시|특별자치시|특별자치도|[가-힣]{1,10}도|[가-힣]{1,10}시|[가-힣]{1,10}군|[가-힣]{1,10}구)""",
        )
        private val ADDRESS_START = Regex(
            """(?:(?:서울|부산|대구|인천|광주|대전|울산|세종)(?:특별시|광역시|특별자치시)?|[가-힣]{1,10}(?:특별시|광역시|특별자치시|도|시|군|구))""",
        )
        private val ROAD_OR_LOT_ADDRESS = Regex(
            """(?:[가-힣A-Za-z0-9·.-]+(?:대로|로|길)\s*\d{1,5}(?:-\d{1,5})?|[가-힣]+(?:동|읍|면|리)\s*\d{1,5}(?:-\d{1,5})?)""",
        )
        private val ADDRESS_PARTIAL_STRUCTURE = Regex(
            """(?:[가-힣A-Za-z0-9·.-]+(?:대로|로|길)|[가-힣]+(?:동|읍|면|리))""",
        )
        private val ADDRESS_CONTINUATION_SIGNAL = Regex(
            """(?:\d{1,5}(?:-\d{1,5})?|[A-Za-z가-힣0-9-]+(?:동|층|호|관)|빌딩|타워|센터|상가|아파트)""",
        )

        private val PURCHASE_DATE_TIME_CONTEXT = Regex(
            """(?:거\s*래|구\s*매|결\s*제|승\s*인|발\s*행|판\s*매)?\s*(?:일\s*시|일\s*자|날\s*짜|시\s*간|시\s*각)""",
        )
        private val BUSINESS_HOURS_CONTEXT = Regex(
            """(영업\s*시간|운영\s*시간|오픈|마감|OPEN|CLOSE)""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_PURCHASE_DATE_CONTEXT = Regex("""(유통\s*기한|소비\s*기한|제조\s*일|만료\s*일)""")

        private val FINAL_TOTAL_PRIMARY = Regex(
            """(최\s*종\s*결\s*제\s*금\s*액|총\s*결\s*제\s*금\s*액|실\s*결\s*제\s*금\s*액|받\s*을\s*금\s*액|청\s*구\s*금\s*액)""",
        )
        private val FINAL_TOTAL_PAYMENT_AMOUNT = Regex("""(결\s*제\s*금\s*액|승\s*인\s*금\s*액)""")
        private val FINAL_TOTAL_GENERIC = Regex("""(총\s*합\s*계|합\s*계|총\s*액)""")
        private val PAYMENT_TOTAL_FALLBACK = Regex(
            """(카\s*드\s*(?:결\s*제|승\s*인)|(?:결\s*제|승\s*인)\s*(?:내\s*역)?|현\s*금\s*결\s*제|간\s*편\s*결\s*제)""",
        )
        private val FINAL_TOTAL_EXCLUDED_CONTEXT = Regex("""(취소|환불|거스름돈|잔돈|예정|한도)""")
        private val GENERIC_TOTAL_EXCLUDED_CONTEXT = Regex(
            """(소\s*계|공급\s*가액|과세|면세|부가\s*(?:가치\s*)?세|세액|할인|수수료|배송비|배달비|취소|환불|거스름돈|잔돈)""",
        )
        private val PAYMENT_TOTAL_EXCLUDED_CONTEXT = Regex(
            """(취소|환불|거스름돈|잔돈|과세|면세|공급\s*가액|부가\s*(?:가치\s*)?세|세액|수수료|(?:카드|승인|거래|회원)\s*번호)""",
        )
        private val SUBTOTAL_KEYWORDS = Regex("""(소\s*계|공\s*급\s*가\s*액)""")
        private val DISCOUNT_TOTAL_KEYWORDS = Regex("""(총\s*할\s*인|할\s*인\s*합\s*계)""")
        private val TAX_AMOUNT_KEYWORDS = Regex(
            """(부\s*가\s*(?:가\s*치\s*)?세|VAT|세\s*액|세\s*금)""",
            RegexOption.IGNORE_CASE,
        )
        private val TAX_EXCLUDED_CONTEXT = Regex(
            """(과세\s*(?:물품)?\s*가액|면세\s*(?:물품)?\s*가액|세금\s*계산서|부가세\s*별도|없음|미표기|해당\s*없음)""",
        )
        private val FEE_KEYWORDS = Regex("""(수수료|봉사료|배달비|배송비)""")
        private val TOTAL_EXCLUDED_CONTEXT = Regex("""(취소|환불)""")
        private val AMOUNT_ONLY = Regex(
            """^[₩￦]?\s*[+-]?\s*\(?\s*$GROUPED_INTEGER_PATTERN\s*\)?\s*원?$""",
        )
        private val AMOUNT_FRAGMENT = Regex("""^[₩￦0-9,+\-()원\s]+$""")
        private val METADATA_NUMBER_CONTEXT = Regex(
            """((?:카드|승인|거래|회원|고객|주문)\s*(?:번\s*호|No\.?|#)|포인트|적립)""",
            RegexOption.IGNORE_CASE,
        )

        private fun normalize(value: String): String = Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()

    }
}
