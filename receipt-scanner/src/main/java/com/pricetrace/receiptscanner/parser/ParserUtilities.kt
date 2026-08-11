package com.pricetrace.receiptscanner.parser

import java.math.BigDecimal
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal const val GROUPED_INTEGER_PATTERN = """(?:\d{1,3}(?:\s*,\s*\d{3})+|\d+)"""

object AmountParser {
    private val amountToken = Regex(
        pattern = """(?<![\d.])([+-]?\s*\(?\s*$GROUPED_INTEGER_PATTERN\s*\)?)(?:\s*원)?(?![\d.])""",
    )

    fun normalizeMinor(text: String): Long? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        if (normalized.isBlank() || normalized.contains('.')) return null
        val parenthesized = normalized.startsWith('(') && normalized.endsWith(')')
        val compact = normalized
            .removePrefix("₩")
            .removeSuffix("원")
            .trim()
            .removeSurrounding("(", ")")
            .replace(",", "")
            .replace(" ", "")
        if (!compact.matches(Regex("[+-]?\\d+"))) return null
        return compact.toLongOrNull()?.let { value ->
            if (parenthesized && value > 0) -value else value
        }
    }

    fun extractLastMinor(text: String): Long? = amountToken.findAll(text)
        .mapNotNull { match -> normalizeMinor(match.groupValues[1]) }
        .lastOrNull()

    fun extractFirstMinor(text: String): Long? = amountToken.findAll(text)
        .mapNotNull { match -> normalizeMinor(match.groupValues[1]) }
        .firstOrNull()

    fun extractAllMinor(text: String): List<Long> = amountToken.findAll(text)
        .mapNotNull { match -> normalizeMinor(match.groupValues[1]) }
        .toList()
}

data class ParsedDateTime(
    val issuedOn: String?,
    val localTime: String?,
    val offsetDateTime: String?,
)

object ReceiptDateTimeParser {
    private val fullYearDate = Regex("""(?<!\d)((?:19|20)\d{2})[./-](\d{1,2})[./-](\d{1,2})(?!\d)""")
    private val shortYearDate = Regex("""(?<!\d)(\d{2})[./-](\d{1,2})[./-](\d{1,2})(?!\d)""")
    private val koreanDate = Regex("""(?<!\d)((?:19|20)\d{2})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일""")
    private val clockTime = Regex(
        """(?<![\d.])([01]?\d|2[0-3])[:.]([0-5]\d)(?::([0-5]\d))?(?![\d.])""",
    )
    private val meridiemTime = Regex(
        """(?:^|\s)(오전|오후|AM|PM)\s*(\d{1,2})(?:\s*(?::|\.|시)\s*([0-5]?\d))?\s*(?:분)?""",
        RegexOption.IGNORE_CASE,
    )
    private val koreanTime = Regex(
        """(?<!\d)([01]?\d|2[0-3])\s*시(?:\s*([0-5]?\d)\s*분?)?(?!\d)""",
    )
    private val labeledSeparatedTime = Regex(
        """(?:일시|시간|시각)\s*[:：-]?\s*([01]?\d|2[0-3])\s+([0-5]\d)(?!\d)""",
    )
    private val labeledCompactTime = Regex(
        """(?:일시|시간|시각)\s*[:：-]?\s*([01]\d|2[0-3])([0-5]\d)(?!\d|\s*[년월일])""",
    )
    private val offsetDateTime = Regex(
        """((?:19|20)\d{2}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:?\d{2}))""",
    )

    fun parse(text: String): ParsedDateTime {
        val normalizedText = text.replace(Regex("""(?<=\d)\s*([./:-])\s*(?=\d)"""), "$1")
        val explicitOffset = offsetDateTime.find(normalizedText)?.groupValues?.get(1)?.let(::normalizeOffsetDateTime)
        val date = parseDate(normalizedText)
        val parsedTime = parseTime(normalizedText)
        return ParsedDateTime(
            issuedOn = explicitOffset?.substringBefore('T') ?: date?.toString(),
            localTime = parsedTime?.format(DateTimeFormatter.ISO_LOCAL_TIME),
            offsetDateTime = explicitOffset,
        )
    }

    private fun parseDate(text: String): LocalDate? {
        koreanDate.find(text)?.let { match ->
            return safeDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
        fullYearDate.find(text)?.let { match ->
            return safeDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
        shortYearDate.find(text)?.let { match ->
            return safeDate(
                2000 + match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
        return null
    }

    private fun parseTime(text: String): LocalTime? {
        meridiemTime.find(text)?.let { match ->
            val meridiem = match.groupValues[1].uppercase()
            val hour12 = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].ifBlank { "0" }.toIntOrNull() ?: return@let
            if (hour12 !in 1..12) return@let
            val hour24 = when {
                meridiem == "오전" || meridiem == "AM" -> if (hour12 == 12) 0 else hour12
                else -> if (hour12 == 12) 12 else hour12 + 12
            }
            return safeTime(hour24, minute, 0)
        }
        clockTime.find(text)?.let { match ->
            return safeTime(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].ifBlank { "0" }.toInt(),
            )
        }
        koreanTime.find(text)?.let { match ->
            return safeTime(
                match.groupValues[1].toInt(),
                match.groupValues[2].ifBlank { "0" }.toInt(),
                0,
            )
        }
        labeledSeparatedTime.find(text)?.let { match ->
            return safeTime(match.groupValues[1].toInt(), match.groupValues[2].toInt(), 0)
        }
        labeledCompactTime.find(text)?.let { match ->
            return safeTime(match.groupValues[1].toInt(), match.groupValues[2].toInt(), 0)
        }
        return null
    }

    private fun safeTime(hour: Int, minute: Int, second: Int): LocalTime? = runCatching {
        LocalTime.of(hour, minute, second)
    }.getOrNull()

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun normalizeOffsetDateTime(value: String): String? = runCatching {
        val normalized = value.replace(' ', 'T').let { text ->
            if (text.lastIndexOf('+') > text.indexOf('T') || text.lastIndexOf('-') > text.indexOf('T')) {
                val signIndex = maxOf(text.lastIndexOf('+'), text.lastIndexOf('-'))
                val offset = text.substring(signIndex)
                if (offset.length == 5 && ':' !in offset) {
                    text.substring(0, signIndex) + offset.substring(0, 3) + ":" + offset.substring(3)
                } else text
            } else text
        }
        OffsetDateTime.parse(normalized).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }.getOrNull()
}

internal fun multiplyExactMinor(quantity: String, unitPrice: Long): Long? = runCatching {
    BigDecimal(quantity).multiply(BigDecimal.valueOf(unitPrice)).longValueExact()
}.getOrNull()
