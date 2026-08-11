package com.pricetrace.receiptscanner.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParserUtilitiesTest {
    @Test
    fun `amount normalization preserves signed integer minor units`() {
        assertEquals(1234567L, AmountParser.normalizeMinor("₩1,234,567원"))
        assertEquals(26760L, AmountParser.normalizeMinor("26, 760"))
        assertEquals(26700L, AmountParser.normalizeMinor("26, 700"))
        assertEquals(-500L, AmountParser.normalizeMinor("(500)"))
        assertEquals(-700L, AmountParser.normalizeMinor("- 700 원"))
        assertNull(AmountParser.normalizeMinor("1,234.50"))
        assertNull(AmountParser.normalizeMinor("금액 미확인"))
    }

    @Test
    fun `last amount ignores earlier quantity and unit price`() {
        assertEquals(3000L, AmountParser.extractLastMinor("딸기우유 2 x 1,500 = 3,000원"))
        assertEquals(26760L, AmountParser.extractLastMinor("최종 결제금액 26, 760"))
    }

    @Test
    fun `date and time parsing validates calendar values without inventing an offset`() {
        val parsed = ReceiptDateTimeParser.parse("구매 2026.07.31 14:35")
        assertEquals("2026-07-31", parsed.issuedOn)
        assertEquals("14:35:00", parsed.localTime)
        assertNull(parsed.offsetDateTime)
        assertNull(ReceiptDateTimeParser.parse("2026.02.30").issuedOn)
    }

    @Test
    fun `explicit offset is retained in ISO format`() {
        val parsed = ReceiptDateTimeParser.parse("2026-07-31T14:35:00+09:00")
        assertEquals("2026-07-31T14:35:00+09:00", parsed.offsetDateTime)
    }

    @Test
    fun `korean date and labeled compact time remain discoverable`() {
        val parsed = ReceiptDateTimeParser.parse("거래일시 2026년 8월 3일 시각 1807")

        assertEquals("2026-08-03", parsed.issuedOn)
        assertEquals("18:07:00", parsed.localTime)
    }
}
