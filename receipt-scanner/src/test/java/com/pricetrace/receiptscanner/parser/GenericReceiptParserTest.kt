package com.pricetrace.receiptscanner.parser

import com.pricetrace.receiptscanner.SyntheticFixtures
import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptValidator
import com.pricetrace.receiptscanner.domain.ValidationCode
import com.pricetrace.receiptscanner.domain.purchaseLocalTime
import com.pricetrace.receiptscanner.domain.toReceiptV2
import com.pricetrace.receiptscanner.ocr.OcrBlock
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrElement
import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import com.pricetrace.receiptscanner.ocr.OcrLine
import com.pricetrace.receiptscanner.ocr.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericReceiptParserTest {
    private val parser = GenericReceiptParser()

    @Test
    fun `synthetic OCR fixture produces provenance aware structured draft`() {
        val parsed = parser.parse(SyntheticFixtures.ocrDocument())

        assertEquals("가상마트", parsed.merchantName.value)
        assertEquals("서울점", parsed.branchName.value)
        assertEquals("2026-07-31", parsed.issuedOn.value)
        assertEquals("14:35:00", parsed.issuedTime.value)
        assertNull(parsed.issuedAt.value)
        assertEquals("14:35:00", parsed.toReceiptV2().document.source.purchaseLocalTime())
        assertNull(parsed.toReceiptV2().document.issuedAt)
        assertEquals("KRW", parsed.currency.value)
        assertEquals(2727L, parsed.totals.grandTotalAmountMinor.value)
        assertEquals(227L, parsed.totals.taxAmountMinor.value)
        assertTrue(parsed.lineItems.any { it.type == ReceiptLineType.PRODUCT })
        assertTrue(parsed.lineItems.any { it.type == ReceiptLineType.DISCOUNT })
        assertTrue(parsed.lineItems.any { it.type == ReceiptLineType.TAX })

        val product = parsed.lineItems.first { it.type == ReceiptLineType.PRODUCT }
        assertEquals("250428", product.identifiers.single().value)
        assertEquals("2", product.quantity.value?.value)
        assertEquals(1500L, product.unitPriceAmountMinor.value)
        assertEquals(3000L, product.netAmountMinor.value)
        assertNotNull(product.description.provenance.single().boundingBox)
    }

    @Test
    fun `stable line IDs repeat for the same source and no standard product is inferred`() {
        val first = parser.parse(SyntheticFixtures.ocrDocument())
        val second = parser.parse(SyntheticFixtures.ocrDocument())
        assertEquals(first.lineItems.map { it.id }, second.lineItems.map { it.id })
        assertTrue(first.lineItems.flatMap { it.identifiers }.all { it.scheme == "merchant_sku" })
    }

    @Test
    fun `parser leaves absent evidence null`() {
        val original = SyntheticFixtures.ocrDocument()
        val withoutCurrency = original.copy(
            rawText = "가상상점\n알 수 없는 값",
            pages = original.pages.map { page ->
                page.copy(
                    rawText = "가상상점\n알 수 없는 값",
                    blocks = page.blocks.map { block ->
                        block.copy(lines = block.lines.take(1), text = "가상상점")
                    },
                )
            },
        )
        val parsed = parser.parse(withoutCurrency)
        assertNull(parsed.currency.value)
        assertNull(parsed.totals.grandTotalAmountMinor.value)
        assertTrue(parsed.lineItems.isEmpty())
    }

    @Test
    fun `merchant metadata is excluded and small merchant fields are recovered`() {
        val parsed = parser.parse(
            documentOf(
                line(
                    index = 0,
                    text = "행복마트 고객센터 1588-0000",
                    elements = listOf("행복마트", "고객센터", "1588-0000"),
                ),
                line(1, "강남역점"),
                line(2, "사업자등록번호 123 45 67890"),
                line(3, "주소 서울특별시 강남구 테헤란로 123"),
                line(4, "거래일자 2026-08-03"),
                line(5, "구매시각 오후 2시 35분"),
                line(6, "카드 결제금액 12,300원 카드번호 4321"),
            ),
        )

        assertEquals("행복마트", parsed.merchantName.value)
        assertEquals("강남역점", parsed.branchName.value)
        assertEquals("123-45-67890", parsed.businessRegistrationNumber.value)
        assertEquals("서울특별시 강남구 테헤란로 123", parsed.address.value)
        assertEquals("2026-08-03", parsed.issuedOn.value)
        assertEquals("14:35:00", parsed.issuedTime.value)
        assertEquals(12_300L, parsed.totals.grandTotalAmountMinor.value)
        assertNull(parsed.totals.taxAmountMinor.value)
        assertEquals(12_300L, parsed.payments.single().amountMinor)
        assertEquals("행복마트", parsed.merchantName.provenance.single().rawText)
        assertEquals(BoundingBox(10, 20, 323, 45), parsed.merchantName.provenance.single().boundingBox)
    }

    @Test
    fun `dominant size merchant tokens exclude smaller adjacent text`() {
        val brand = line(
            index = 0,
            text = "GS THE FRESH SINCE 1974",
            left = 100,
            right = 900,
            top = 20,
        ).copy(
            boundingBox = BoundingBox(100, 20, 900, 75),
            elements = listOf(
                OcrElement("brand_gs", "GS", BoundingBox(100, 20, 190, 70), 0.95f, 0),
                OcrElement("brand_the", "THE", BoundingBox(210, 20, 340, 70), 0.95f, 1),
                OcrElement("brand_fresh", "FRESH", BoundingBox(360, 20, 600, 70), 0.95f, 2),
                OcrElement("brand_small", "SINCE 1974", BoundingBox(630, 42, 850, 62), 0.9f, 3),
            ),
        )
        val parsed = parser.parse(
            documentOf(
                brand,
                line(1, "강남점", top = 85),
                line(2, "거래일시 2026-08-04 14:10", top = 115),
                line(3, "최종 결제금액 1,500원", top = 155),
            ),
        )

        assertEquals("GS THE FRESH", parsed.merchantName.value)
        assertEquals(3, parsed.merchantName.provenance.size)
    }

    @Test
    fun `small unlabeled address fragments combine inside the header region`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "강남점", top = 50),
                line(2, "서울특별시 강남구", top = 80),
                line(3, "테헤란로 123", top = 110),
                line(4, "거래일시 2026-08-04 14:20", top = 140),
                line(5, "최종 결제금액 1,500원", top = 180),
            ),
        )

        assertEquals("서울특별시 강남구 테헤란로 123", parsed.address.value)
        assertEquals(setOf("line_2", "line_3"), parsed.address.provenance.map { it.ocrLineId }.toSet())
    }

    @Test
    fun `same row split label and amount form the final payment total`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트"),
                line(1, "부산점"),
                line(2, "2026-08-03", left = 10, right = 300),
                line(3, "14:35", left = 10, right = 200),
                line(4, "과세물품가액 11,182원", left = 10, right = 500),
                line(5, "최종 결제금액", left = 10, right = 400, top = 200),
                line(6, "12,300원", left = 700, right = 940, top = 200),
            ),
        )

        assertEquals("14:35:00", parsed.issuedTime.value)
        assertEquals(12_300L, parsed.totals.grandTotalAmountMinor.value)
        assertNull(parsed.totals.taxAmountMinor.value)
    }

    @Test
    fun `card approval amount is a total fallback and taxable base is not tax`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트 부산점"),
                line(1, "거래일시 2026.08.03 18:07"),
                line(2, "면세물품가액 0원"),
                line(3, "과세물품가액 20,000원"),
                line(4, "신용카드 승인 20,000원"),
            ),
        )

        assertEquals(20_000L, parsed.totals.grandTotalAmountMinor.value)
        assertNull(parsed.totals.taxAmountMinor.value)
        assertTrue(parsed.lineItems.none { it.type == ReceiptLineType.TAX })
    }

    @Test
    fun `table headers map split OCR columns to exact product fields`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "거래일시 2026-08-03 14:35", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품코드" to (20..130),
                    "품명" to (150..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 8,
                    top = 160,
                    "250428" to (20..130),
                    "딸기우유" to (150..450),
                    "1,500" to (500..620),
                    "2" to (650..730),
                    "3,000" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 13,
                    top = 200,
                    "190370" to (20..130),
                    "초코바" to (150..450),
                    "800" to (500..620),
                    "1" to (650..730),
                    "800" to (800..950),
                ),
            )
            add(line(18, "합계 3,800원", top = 250))
        }

        val parsed = parser.parse(documentOf(*lines.toTypedArray()))

        assertEquals(2, parsed.lineItems.size)
        val milk = parsed.lineItems[0]
        assertEquals("딸기우유", milk.description.value)
        assertEquals("250428", milk.identifiers.single().value)
        assertEquals("2", milk.quantity.value?.value)
        assertEquals(1_500L, milk.unitPriceAmountMinor.value)
        assertEquals(3_000L, milk.netAmountMinor.value)
        assertEquals(5, milk.sourceLineReferences.size)
        val chocolate = parsed.lineItems[1]
        assertEquals("초코바", chocolate.description.value)
        assertEquals(800L, chocolate.unitPriceAmountMinor.value)
        assertEquals(800L, chocolate.netAmountMinor.value)
    }

    @Test
    fun `slightly drifted OCR column baselines still form one table row`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 55),
                line(2, "거래일시 2026-08-05 12:30", top = 90),
                line(3, "상품명", left = 100, right = 450, top = 130),
                line(4, "단가", left = 500, right = 620, top = 136),
                line(5, "수량", left = 650, right = 730, top = 132),
                line(6, "금액", left = 800, right = 950, top = 138),
                line(7, "딸기우유", left = 100, right = 450, top = 170),
                line(8, "1,500", left = 500, right = 620, top = 176),
                line(9, "2", left = 650, right = 730, top = 172),
                line(10, "3,000", left = 800, right = 950, top = 178),
                line(11, "총합계 3,000원", top = 230),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("딸기우유", item.description.value)
        assertEquals("2", item.quantity.value?.value)
        assertEquals(1_500L, item.unitPriceAmountMinor.value)
        assertEquals(3_000L, item.netAmountMinor.value)
    }

    @Test
    fun `header payment notice does not cut off the later item table`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "신용카드 결제 가능 매장", top = 50))
            add(line(2, "서울점", top = 80))
            add(line(3, "거래일시 2026-08-04 13:10", top = 110))
            addAll(
                rowCells(
                    startIndex = 4,
                    top = 150,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 8,
                    top = 190,
                    "딸기우유" to (100..450),
                    "1,500" to (500..620),
                    "2" to (650..730),
                    "3,000" to (800..950),
                ),
            )
            add(line(12, "총합계 3,000원", top = 240))
        }

        val parsed = parser.parse(documentOf(*lines.toTypedArray()))

        assertEquals("정확마트", parsed.merchantName.value)
        assertEquals("서울점", parsed.branchName.value)
        assertEquals("딸기우유", parsed.lineItems.single().description.value)
        assertEquals(3_000L, parsed.lineItems.single().netAmountMinor.value)
        assertEquals(3_000L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `header payment notice keeps following metadata when an item table is absent`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "신용카드 결제 가능 매장", top = 50),
                line(2, "서울점", top = 80),
                line(3, "사업자등록번호 123-45-67890", top = 110),
                line(4, "주소 서울특별시 강남구 테헤란로 123", top = 140),
                line(5, "거래일시 2026-08-04 13:20", top = 170),
                line(6, "최종 결제금액 1,000원", top = 220),
            ),
        )

        assertEquals("서울점", parsed.branchName.value)
        assertEquals("123-45-67890", parsed.businessRegistrationNumber.value)
        assertEquals("서울특별시 강남구 테헤란로 123", parsed.address.value)
        assertEquals("2026-08-04", parsed.issuedOn.value)
        assertEquals("13:20:00", parsed.issuedTime.value)
        assertEquals(1_000L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `item and footer text cannot populate header or summary fields`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            addAll(
                rowCells(
                    startIndex = 2,
                    top = 90,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 6,
                    top = 130,
                    "세액공제 달력 2027-01-01" to (100..450),
                    "5,000" to (500..620),
                    "1" to (650..730),
                    "5,000" to (800..950),
                ),
            )
            add(line(10, "총합계 5,000원", top = 180))
            add(line(11, "반품 주소 서울특별시 강남구 테헤란로 123", top = 220))
        }

        val parsed = parser.parse(documentOf(*lines.toTypedArray()))

        assertEquals("세액공제 달력 2027-01-01", parsed.lineItems.single().description.value)
        assertEquals(5_000L, parsed.lineItems.single().netAmountMinor.value)
        assertNull(parsed.address.value)
        assertNull(parsed.issuedOn.value)
        assertNull(parsed.totals.taxAmountMinor.value)
        assertEquals(5_000L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `table without unit price preserves explicit quantity and line amount`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "부산점", top = 50))
            add(line(2, "거래일시 2026-08-03 18:07", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품명" to (100..520),
                    "수량" to (620..720),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 6,
                    top = 160,
                    "500ML 생수" to (100..520),
                    "2개" to (620..720),
                    "1,000원" to (800..950),
                ),
            )
            add(line(9, "최종 결제금액 1,000원", top = 220))
        }

        val parsed = parser.parse(documentOf(*lines.toTypedArray()))
        val item = parsed.lineItems.single()

        assertEquals("500ML 생수", item.description.value)
        assertTrue(item.identifiers.isEmpty())
        assertEquals("2", item.quantity.value?.value)
        assertNull(item.unitPriceAmountMinor.value)
        assertEquals(1_000L, item.netAmountMinor.value)
        val validation = ReceiptValidator.validateForUserVerification(parsed.toReceiptV2())
        assertTrue(validation.issues.none { it.code == ValidationCode.ITEM_UNIT_PRICE_MISSING })
        assertTrue(validation.canMarkUserVerified)
    }

    @Test
    fun `unresolved numeric first product stays separate from the next complete product`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "거래일시 2026-08-04 14:30", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "사과주스" to (100..450),
                    "1,000" to (500..620),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 9,
                    top = 200,
                    "바나나우유" to (100..450),
                    "2,000" to (500..620),
                    "1" to (650..730),
                    "2,000" to (800..950),
                ),
            )
            add(line(13, "총합계 2,000원", top = 250))
        }

        val items = parser.parse(documentOf(*lines.toTypedArray())).lineItems

        assertEquals(listOf("사과주스", "바나나우유"), items.map { it.description.value })
        assertNull(items[0].netAmountMinor.value)
        assertEquals(1_000L, items[0].unitPriceAmountMinor.value)
        assertEquals("1", items[1].quantity.value?.value)
        assertEquals(2_000L, items[1].unitPriceAmountMinor.value)
        assertEquals(2_000L, items[1].netAmountMinor.value)
    }

    @Test
    fun `item markers are trimmed and small one glyph recovers quantity without a quantity header`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "거래일시 2026-08-04 14:40", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 6,
                    top = 160,
                    "*딸기우유*" to (100..450),
                    "1,500" to (500..620),
                    "I*" to (650..730),
                    "1,500" to (800..950),
                ),
            )
            add(line(10, "총합계 1,500원", top = 210))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("딸기우유", item.description.value)
        assertEquals("1", item.quantity.value?.value)
        assertEquals(1_500L, item.unitPriceAmountMinor.value)
        assertEquals(1_500L, item.netAmountMinor.value)
    }

    @Test
    fun `known item typo and kg suffix are corrected while raw OCR evidence is retained`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "거래일시 2026-08-08 10:20", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "제스프리플드 1ks" to (100..450),
                    "12,000" to (500..620),
                    "S" to (650..730),
                    "60,000" to (800..950),
                ),
            )
            add(line(11, "총합계 60,000원", top = 220))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("제스프리골드 1kg", item.description.value)
        assertEquals("5", item.quantity.value?.value)
        assertEquals(12_000L, item.unitPriceAmountMinor.value)
        assertEquals(60_000L, item.netAmountMinor.value)
        assertEquals("제스프리플드 1ks", item.description.provenance.single().rawText)
        assertEquals("S", item.quantity.provenance.single().rawText)
        assertTrue(item.quantity.provenance.single().parserRuleId.endsWith("quantity_ocr_corrected"))
    }

    @Test
    fun `ambiguous quantity glyph stays null when monetary conservation rejects it`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "거래일시 2026-08-08 10:30", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "사과" to (100..450),
                    "1,000" to (500..620),
                    "S" to (650..730),
                    "4,000" to (800..950),
                ),
            )
            add(line(11, "총합계 4,000원", top = 220))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertNull(item.quantity.value)
        assertEquals(1_000L, item.unitPriceAmountMinor.value)
        assertEquals(4_000L, item.netAmountMinor.value)
    }

    @Test
    fun `missing quantity cell is recovered from exact unit price and amount ratio`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "거래일시 2026-08-08 10:40", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "바나나" to (100..450),
                    "1,000" to (500..620),
                    "5,000" to (800..950),
                ),
            )
            add(line(10, "총합계 5,000원", top = 220))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("5", item.quantity.value?.value)
        assertEquals(setOf("1,000", "5,000"), item.quantity.provenance.map { it.rawText }.toSet())
        assertTrue(item.quantity.provenance.all { it.parserRuleId.endsWith("quantity_from_amount_ratio") })
    }

    @Test
    fun `table aliases and column order define quantity and unit price`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "대전점", top = 50),
                line(2, "2026-08-03 19:20", top = 80),
                line(
                    index = 3,
                    text = "품목 개수 매가 합계",
                    elements = listOf("품목", "개수", "매가", "합계"),
                    left = 100,
                    right = 940,
                    top = 120,
                ),
                line(
                    index = 4,
                    text = "스파클링워터500ml 3개 900원 2,700원",
                    elements = listOf("스파클링워터500ml", "3개", "900원", "2,700원"),
                    left = 100,
                    right = 940,
                    top = 160,
                ),
                line(5, "총합계 2,700원", top = 220),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("스파클링워터500ml", item.description.value)
        assertEquals("3", item.quantity.value?.value)
        assertEquals(900L, item.unitPriceAmountMinor.value)
        assertEquals(2_700L, item.netAmountMinor.value)
    }

    @Test
    fun `wrapped product name and split numeric row stay one item`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "광주점", top = 50))
            add(line(2, "2026-08-03 20:15", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "품번" to (20..130),
                    "품명" to (150..520),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "880123" to (20..130),
                    "무가당" to (150..520),
                ),
            )
            addAll(rowCells(startIndex = 9, top = 190, "두유 190ML" to (150..520)))
            addAll(
                rowCells(
                    startIndex = 10,
                    top = 220,
                    "2" to (650..730),
                    "2,400" to (800..950),
                ),
            )
            add(line(12, "합계 2,400원", top = 270))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("무가당 두유 190ML", item.description.value)
        assertEquals("880123", item.identifiers.single().value)
        assertEquals("2", item.quantity.value?.value)
        assertNull(item.unitPriceAmountMinor.value)
        assertEquals(2_400L, item.netAmountMinor.value)
        assertEquals(5, item.sourceLineReferences.size)
    }

    @Test
    fun `spaced labels and numeric OCR confusions remain discoverable`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "상 호 명 : 행복 마트 고객 센터 1588-0000", top = 20),
                line(1, "지 점 명 : 강남 역 점", top = 50),
                line(2, "사업자 등록 번호 : 123 - 45 - 6789O", top = 80),
                line(3, "소 재 지 : 서울특별시 강남구 테헤란로", top = 110),
                line(4, "123 행복빌딩", top = 140),
                line(5, "거 래 일 시 : 2026 . 08 . 03 18 : 07", top = 170),
                line(6, "결 제 금 액 : 12,300 원", top = 230),
            ),
        )

        assertEquals("행복 마트", parsed.merchantName.value)
        assertEquals("강남역점", parsed.branchName.value)
        assertEquals("123-45-67890", parsed.businessRegistrationNumber.value)
        assertEquals("서울특별시 강남구 테헤란로 123 행복빌딩", parsed.address.value)
        assertEquals("2026-08-03", parsed.issuedOn.value)
        assertEquals("18:07:00", parsed.issuedTime.value)
        assertEquals(12_300L, parsed.totals.grandTotalAmountMinor.value)
        assertEquals(12_300L, parsed.payments.single().amountMinor)
    }

    @Test
    fun `near match table headers still anchor exact product columns`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "2026-08-03 21:10", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "품멍" to (100..430),
                    "단까" to (500..620),
                    "수랑" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "딸기우유" to (100..430),
                    "1,500" to (500..620),
                    "2" to (650..730),
                    "3,000" to (800..950),
                ),
            )
            add(line(11, "합계 3,000원", top = 220))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("딸기우유", item.description.value)
        assertEquals("2", item.quantity.value?.value)
        assertEquals(1_500L, item.unitPriceAmountMinor.value)
        assertEquals(3_000L, item.netAmountMinor.value)
    }

    @Test
    fun `headerless spatial numeric tail recovers printed unit price and quantity`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "2026-08-03 21:30", top = 80),
                line(
                    index = 3,
                    text = "250428 딸기우유 1,500 2 3,000",
                    elements = listOf("250428", "딸기우유", "1,500", "2", "3,000"),
                    left = 20,
                    right = 970,
                    top = 140,
                ),
                line(4, "총합계 3,000원", top = 200),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("딸기우유", item.description.value)
        assertEquals("250428", item.identifiers.single().value)
        assertEquals("2", item.quantity.value?.value)
        assertEquals(1_500L, item.unitPriceAmountMinor.value)
        assertEquals(3_000L, item.netAmountMinor.value)
    }

    @Test
    fun `headerless numeric tail keeps missing unit price null`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "2026-08-03 21:40", top = 80),
                line(
                    index = 3,
                    text = "500ML 생수 2 1,000",
                    elements = listOf("500ML", "생수", "2", "1,000"),
                    left = 80,
                    right = 950,
                    top = 140,
                ),
                line(4, "총합계 1,000원", top = 200),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("500ML 생수", item.description.value)
        assertEquals("2", item.quantity.value?.value)
        assertNull(item.unitPriceAmountMinor.value)
        assertEquals(1_000L, item.netAmountMinor.value)
    }

    @Test
    fun `loose numeric discovery does not turn receipt metadata into products`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "2026-08-03 21:50", top = 80),
                line(
                    index = 3,
                    text = "포인트 적립 100 100",
                    elements = listOf("포인트", "적립", "100", "100"),
                    left = 100,
                    right = 900,
                    top = 140,
                ),
                line(4, "회원번호 1234", top = 170),
                line(5, "총합계 1,000원", top = 220),
            ),
        )

        assertTrue(parsed.lineItems.isEmpty())
    }

    @Test
    fun `headerless bare trailing quantity is not accepted as a product amount`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "2026-08-03 22:00", top = 80),
                line(
                    index = 3,
                    text = "250428 딸기우유 2",
                    elements = listOf("250428", "딸기우유", "2"),
                    top = 140,
                ),
                line(4, "3,000", left = 800, right = 950, top = 175),
                line(5, "총합계 3,000원", top = 220),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("딸기우유", item.description.value)
        assertEquals("250428", item.identifiers.single().value)
        assertEquals("2", item.quantity.value?.value)
        assertNull(item.unitPriceAmountMinor.value)
        assertEquals(3_000L, item.netAmountMinor.value)
    }

    @Test
    fun `generic name only table note does not contaminate later direct product name`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "2026-08-03 22:03", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "품명" to (150..520),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(rowCells(startIndex = 6, top = 160, "MEMO" to (150..520)))
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 200,
                    "딸기우유" to (150..520),
                    "1" to (650..730),
                    "1,500" to (800..950),
                ),
            )
            add(line(10, "합계 1,500원", top = 250))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("딸기우유", item.description.value)
        assertTrue("line_6" !in item.sourceLineReferences)
    }

    @Test
    fun `wrapped table name without sku retains both name fragments`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "2026-08-03 22:03", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "품명" to (150..520),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(rowCells(startIndex = 6, top = 160, "유기농" to (150..520)))
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 200,
                    "딸기우유" to (150..520),
                    "1" to (650..730),
                    "1,500" to (800..950),
                ),
            )
            add(line(10, "합계 1,500원", top = 250))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("유기농 딸기우유", item.description.value)
        assertTrue("line_6" in item.sourceLineReferences)
    }

    @Test
    fun `wrapped fallback ignores memo like rows`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "2026-08-03 22:04", top = 80),
                line(
                    index = 3,
                    text = "MEMO 2",
                    elements = listOf("MEMO", "2"),
                    left = 100,
                    right = 900,
                    top = 140,
                ),
                line(4, "1,000", left = 800, right = 950, top = 175),
                line(5, "합계 1,000원", top = 220),
            ),
        )

        assertTrue(parsed.lineItems.isEmpty())
    }

    @Test
    fun `strict raw line description quantity amount keeps low amount product`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "2026-08-03 22:04", top = 80),
                line(3, "봉투 1 20", top = 140),
                line(4, "합계 20원", top = 200),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("봉투", item.description.value)
        assertEquals("1", item.quantity.value?.value)
        assertNull(item.unitPriceAmountMinor.value)
        assertEquals(20L, item.netAmountMinor.value)
    }

    @Test
    fun `wrapped table name retains pending and final name fragments`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "2026-08-03 22:05", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "품번" to (20..130),
                    "품명" to (150..520),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "880123" to (20..130),
                    "서울우유" to (150..520),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 9,
                    top = 190,
                    "딸기 250ML" to (150..520),
                    "1" to (650..730),
                    "1,500" to (800..950),
                ),
            )
            add(line(12, "합계 1,500원", top = 240))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("서울우유 딸기 250ML", item.description.value)
        assertEquals("880123", item.identifiers.single().value)
        assertTrue(item.sourceLineReferences.contains("line_8"))
        assertTrue(item.sourceLineReferences.contains("line_9"))
    }

    @Test
    fun `numeric only table name is not emitted as a product`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "2026-08-03 22:10", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "품명" to (150..520),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 6,
                    top = 160,
                    "250428" to (150..520),
                    "2" to (650..730),
                    "3,000" to (800..950),
                ),
            )
            add(line(9, "합계 3,000원", top = 220))
        }

        assertTrue(parser.parse(documentOf(*lines.toTypedArray())).lineItems.isEmpty())
    }

    @Test
    fun `headerless fractional quantity accepts normal one won rounding`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "2026-08-03 22:15", top = 80),
                line(
                    index = 3,
                    text = "바나나 0.546 12,800 6,989",
                    elements = listOf("바나나", "0.546", "12,800", "6,989"),
                    top = 140,
                ),
                line(4, "총합계 6,989원", top = 200),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("바나나", item.description.value)
        assertEquals("0.546", item.quantity.value?.value)
        assertEquals(12_800L, item.unitPriceAmountMinor.value)
        assertEquals(6_989L, item.netAmountMinor.value)
    }

    @Test
    fun `sequential payment total is not reused as an absent tax amount`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "거래일시 2026-08-03 22:20", top = 80),
                line(3, "부가세", top = 130),
                line(4, "결제금액", top = 165),
                line(5, "12300", left = 800, right = 950, top = 200),
            ),
        )

        assertNull(parsed.totals.taxAmountMinor.value)
        assertEquals(12_300L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `split total skips a preceding card number and pairs the following amount`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "거래일시 2026-08-03 22:25", top = 80),
                line(3, "카드번호", top = 120),
                line(4, "1234", left = 800, right = 950, top = 150),
                line(5, "결제금액", top = 180),
                line(6, "9000원", left = 800, right = 950, top = 210),
            ),
        )

        assertEquals(9_000L, parsed.totals.grandTotalAmountMinor.value)
        assertEquals(9_000L, parsed.payments.single().amountMinor)
        assertTrue("line_3" !in parsed.payments.single().sourceLineReferences)
        assertTrue("line_4" !in parsed.payments.single().sourceLineReferences)
    }

    @Test
    fun `merchant and address fields do not absorb adjacent text`() {
        val parsed = parser.parse(
            documentOf(
                line(
                    index = 0,
                    text = "판매처명 행복마트 서울특별시 강남구 테헤란로 123",
                    elements = listOf("판매처명", "행복마트", "서울특별시 강남구 테헤란로 123"),
                    top = 20,
                ),
                line(1, "강남점", top = 50),
                line(2, "주소 서울특별시 강남구 테헤란로 123", top = 80),
                line(3, "대표 홍길동", top = 110),
                line(4, "거래일시 2026-08-03 22:30", top = 140),
                line(5, "결제금액 1,000원", top = 180),
            ),
        )

        assertEquals("행복마트", parsed.merchantName.value)
        assertEquals("서울특별시 강남구 테헤란로 123", parsed.address.value)
    }

    @Test
    fun `address starts at the first regional token inside a dense header row`() {
        val parsed = parser.parse(
            documentOf(
                line(
                    index = 0,
                    text = "GS THE FRESH 강남점 사업자등록번호 123-45-67890 서울특별시 강남구 테헤란로 123",
                    elements = listOf(
                        "GS THE FRESH",
                        "강남점",
                        "사업자등록번호 123-45-67890",
                        "서울특별시 강남구 테헤란로 123",
                    ),
                    top = 20,
                ),
                line(1, "거래일시 2026-08-05 10:20", top = 60),
                line(2, "최종 결제금액 1,000원", top = 100),
            ),
        )

        assertEquals("서울특별시 강남구 테헤란로 123", parsed.address.value)
    }

    @Test
    fun `specific final total outranks an earlier approval amount`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 50),
                line(2, "거래일시 2026-08-03 22:35", top = 80),
                line(3, "승인금액 11,000원", top = 130),
                line(4, "최종 결제금액 10,000원", top = 160),
            ),
        )

        assertEquals(10_000L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `spaced merchant brand and grouped final amount infer krw`() {
        val parsed = parser.parse(
            documentOf(
                line(
                    index = 0,
                    text = "GS THE FRESH",
                    elements = listOf("GS", "THE", "FRESH"),
                    left = 120,
                    right = 720,
                    top = 20,
                ),
                line(1, "강남점", top = 55),
                line(2, "거래일시 2026-08-04 01:50", top = 90),
                line(3, "최종 결제금액 26, 760", top = 140),
            ),
        )

        assertEquals("GS THE FRESH", parsed.merchantName.value)
        assertEquals("KRW", parsed.currency.value)
        assertEquals(26_760L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `notice text below the item table cannot replace the header merchant`() {
        val parsed = parser.parse(
            documentOf(
                line(
                    index = 0,
                    text = "GS THE FRESH",
                    elements = listOf("GS", "THE", "FRESH"),
                    left = 120,
                    right = 720,
                    top = 20,
                ),
                line(1, "강남점", top = 55),
                line(2, "거래일시 2026-08-04 01:50", top = 90),
                line(
                    index = 3,
                    text = "행사 안내 GS마트 구매 상품은 교환 가능합니다",
                    elements = listOf("행사 안내", "GS마트", "구매 상품은 교환 가능합니다"),
                    left = 80,
                    right = 940,
                    top = 115,
                ),
                line(
                    index = 4,
                    text = "상품명 수량 금액",
                    elements = listOf("상품명", "수량", "금액"),
                    left = 100,
                    right = 940,
                    top = 145,
                ),
                line(
                    index = 5,
                    text = "생수 1개 1,000원",
                    elements = listOf("생수", "1개", "1,000원"),
                    left = 100,
                    right = 940,
                    top = 180,
                ),
                line(6, "최종 결제금액 1,000원", top = 220),
                line(7, "행사 안내 GS마트 구매 상품은 교환 가능합니다", top = 250),
            ),
        )

        assertEquals("GS THE FRESH", parsed.merchantName.value)
        assertEquals("생수", parsed.lineItems.single().description.value)
    }

    @Test
    fun `overlapping boxes from adjacent products remain separate rows`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 55),
                line(2, "2026-08-04 10:10", top = 90),
                line(3, "사과 1 1,000원", top = 150),
                line(4, "바나나 1 2,000원", top = 162),
                line(5, "총합계 3,000원", top = 220),
            ),
        )

        assertEquals(listOf("사과", "바나나"), parsed.lineItems.map { it.description.value })
        assertEquals(listOf(1_000L, 2_000L), parsed.lineItems.map { it.netAmountMinor.value })
    }

    @Test
    fun `same column product names do not accumulate into one pending table item`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 55))
            add(line(2, "2026-08-04 10:30", top = 90))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 130,
                    "상품명" to (150..520),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(rowCells(startIndex = 6, top = 165, "사과" to (150..520)))
            addAll(rowCells(startIndex = 7, top = 195, "바나나" to (150..520)))
            addAll(
                rowCells(
                    startIndex = 8,
                    top = 225,
                    "1" to (650..730),
                    "2,000" to (800..950),
                ),
            )
            add(line(10, "총합계 2,000원", top = 270))
        }

        val items = parser.parse(documentOf(*lines.toTypedArray())).lineItems

        assertEquals(listOf("사과", "바나나"), items.map { it.description.value })
        assertNull(items[0].netAmountMinor.value)
        assertEquals(2_000L, items[1].netAmountMinor.value)
    }

    @Test
    fun `vertically fragmented grouped payment amount is reconstructed before a short prefix`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "GS THE FRESH", top = 20),
                line(1, "강남점", top = 55),
                line(2, "거래일시 2026-08-04 11:20", top = 90),
                line(3, "최종 결제금액 26", left = 10, right = 810, top = 150),
                line(4, ",", left = 810, right = 830, top = 180),
                line(5, "760원", left = 830, right = 950, top = 210),
            ),
        )

        assertEquals(26_760L, parsed.totals.grandTotalAmountMinor.value)
        assertEquals(26_760L, parsed.payments.single().amountMinor)
        assertEquals("KRW", parsed.currency.value)
    }

    @Test
    fun `split grouped line amount keeps all digits in calculated item total`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 55))
            add(line(2, "2026-08-04 12:10", top = 90))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 130,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 170,
                    "딸기우유" to (100..450),
                    "13,350" to (500..620),
                    "2" to (650..730),
                    "26," to (735..800),
                    "700" to (805..950),
                ),
            )
            add(line(12, "총합계 26, 700원", top = 220))
        }

        val parsed = parser.parse(documentOf(*lines.toTypedArray()))
        val item = parsed.lineItems.single()

        assertEquals("딸기우유", item.description.value)
        assertEquals("2", item.quantity.value?.value)
        assertEquals(13_350L, item.unitPriceAmountMinor.value)
        assertEquals(26_700L, item.netAmountMinor.value)
        assertEquals(26_700L, parsed.lineItems.sumOf { it.netAmountMinor.value ?: 0L })
        assertEquals(setOf("line_10", "line_11"), item.netAmountMinor.provenance.map { it.ocrLineId }.toSet())
    }

    @Test
    fun `large centered brand outranks a smaller header phrase containing mart`() {
        val brand = line(
            index = 0,
            text = "GS THE FRESH",
            elements = listOf("GS", "THE", "FRESH"),
            left = 180,
            right = 820,
            top = 20,
        ).copy(boundingBox = BoundingBox(180, 20, 820, 75))
        val parsed = parser.parse(
            documentOf(
                brand,
                line(1, "스마트한 쇼핑의 시작", left = 260, right = 740, top = 85),
                line(2, "강남점", left = 380, right = 620, top = 120),
                line(3, "사업자등록번호 123-45-67890", top = 155),
                line(4, "최종 결제금액 1,000원", top = 210),
            ),
        )

        assertEquals("GS THE FRESH", parsed.merchantName.value)
    }

    @Test
    fun `compound numeric OCR element is split by table columns and conservation`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 55),
                line(2, "2026-08-04 12:10", top = 90),
                line(
                    index = 3,
                    text = "상품명 단가 수량 금액",
                    elements = listOf("상품명", "단가", "수량", "금액"),
                    left = 100,
                    right = 940,
                    top = 130,
                ),
                line(
                    index = 4,
                    text = "딸기우유 1,500 2 3,000",
                    elements = listOf("딸기우유", "1,500 2 3,000"),
                    left = 100,
                    right = 940,
                    top = 170,
                ),
                line(5, "총합계 3,000원", top = 220),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("딸기우유", item.description.value)
        assertEquals("2", item.quantity.value?.value)
        assertEquals(1_500L, item.unitPriceAmountMinor.value)
        assertEquals(3_000L, item.netAmountMinor.value)
    }

    @Test
    fun `name cell strips a trailing numeric token before assigning table columns`() {
        val lines = buildList {
            add(line(0, "정확마트", top = 20))
            add(line(1, "서울점", top = 50))
            add(line(2, "거래일시 2026-08-05 10:30", top = 80))
            addAll(
                rowCells(
                    startIndex = 3,
                    top = 120,
                    "상품명" to (100..450),
                    "단가" to (500..620),
                    "수량" to (650..730),
                    "금액" to (800..950),
                ),
            )
            addAll(
                rowCells(
                    startIndex = 7,
                    top = 160,
                    "딸기우유 2" to (100..450),
                    "1,500" to (500..620),
                    "2" to (650..730),
                    "3,000" to (800..950),
                ),
            )
            add(line(11, "총합계 3,000원", top = 220))
        }

        val item = parser.parse(documentOf(*lines.toTypedArray())).lineItems.single()

        assertEquals("딸기우유", item.description.value)
        assertEquals("2", item.quantity.value?.value)
        assertEquals(1_500L, item.unitPriceAmountMinor.value)
        assertEquals(3_000L, item.netAmountMinor.value)
    }

    @Test
    fun `compound quantity and amount keep absent unit price null`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "서울점", top = 55),
                line(2, "2026-08-04 12:20", top = 90),
                line(
                    index = 3,
                    text = "상품명 수량 금액",
                    elements = listOf("상품명", "수량", "금액"),
                    left = 100,
                    right = 940,
                    top = 130,
                ),
                line(
                    index = 4,
                    text = "500ML 생수 2 1,000",
                    elements = listOf("500ML 생수", "2 1,000"),
                    left = 100,
                    right = 940,
                    top = 170,
                ),
                line(5, "총합계 1,000원", top = 220),
            ),
        )

        val item = parsed.lineItems.single()
        assertEquals("500ML 생수", item.description.value)
        assertEquals("2", item.quantity.value?.value)
        assertNull(item.unitPriceAmountMinor.value)
        assertEquals(1_000L, item.netAmountMinor.value)
    }

    @Test
    fun `foreign currency marker prevents krw inference`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "GLOBAL MARKET", top = 20),
                line(1, "거래일시 2026-08-04 01:55", top = 60),
                line(2, "최종 결제금액 100 USD", top = 110),
            ),
        )

        assertNull(parsed.currency.value)
        assertEquals(100L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `instruction sentence with a trailing amount is not emitted as a product`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "GS THE FRESH", top = 20),
                line(1, "강남점", top = 55),
                line(2, "거래일시 2026-08-04 02:10", top = 90),
                line(3, "행사 안내 구매 상품은 교환 가능합니다 1,000원", top = 135),
                line(4, "최종 결제금액 1,000원", top = 190),
            ),
        )

        assertEquals("GS THE FRESH", parsed.merchantName.value)
        assertTrue(parsed.lineItems.isEmpty())
        assertEquals(1_000L, parsed.totals.grandTotalAmountMinor.value)
    }

    @Test
    fun `merchant name keeps the brand when the same row contains a since slogan`() {
        val parsed = parser.parse(
            documentOf(
                line(
                    index = 0,
                    text = "GS THE FRESH SINCE 1974",
                    elements = listOf("GS THE FRESH", "SINCE 1974"),
                    left = 180,
                    right = 820,
                    top = 20,
                ),
                line(1, "강남점", top = 60),
                line(2, "거래일시 2026-08-05 12:10", top = 100),
                line(3, "최종 결제금액 1,000원", top = 170),
            ),
        )

        assertEquals("GS THE FRESH", parsed.merchantName.value)
    }

    @Test
    fun `merchant search continues after metadata rows reordered ahead of the brand`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "영수증 안내", top = 20),
                line(1, "주소 서울특별시 강남구 테헤란로 123", top = 55),
                line(2, "GS THE FRESH", top = 90),
                line(3, "강남점", top = 125),
                line(4, "거래일시 2026-08-05 12:20", top = 160),
                line(5, "최종 결제금액 1,000원", top = 220),
            ),
        )

        assertEquals("GS THE FRESH", parsed.merchantName.value)
        assertEquals("서울특별시 강남구 테헤란로 123", parsed.address.value)
    }

    @Test
    fun `fragmented total context still infers krw without an explicit won marker`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "GS THE FRESH", top = 20),
                line(1, "강남점", top = 55),
                line(2, "거래일시 2026-08-04 02:15", top = 90),
                line(3, "최종 결제금액", left = 10, right = 500, top = 140),
                line(4, "26,", left = 700, right = 800, top = 175),
                line(5, "760", left = 805, right = 950, top = 210),
            ),
        )

        assertEquals(26_760L, parsed.totals.grandTotalAmountMinor.value)
        assertEquals("KRW", parsed.currency.value)
    }

    @Test
    fun `standalone won marker is retained as explicit currency evidence`() {
        val parsed = parser.parse(
            documentOf(
                line(0, "정확마트", top = 20),
                line(1, "거래일시 2026-08-04 02:20", top = 60),
                line(2, "최종 결제금액 1,000", top = 110),
                line(3, "원", top = 145),
            ),
        )

        assertEquals("KRW", parsed.currency.value)
        assertEquals(1_000L, parsed.totals.grandTotalAmountMinor.value)
    }

    private fun documentOf(vararg lines: OcrLine): OcrDocument {
        val block = OcrBlock(
            id = "block",
            pageId = "page",
            pageIndex = 0,
            text = lines.joinToString("\n", transform = OcrLine::text),
            boundingBox = BoundingBox(0, 0, 1_000, 1_000),
            lines = lines.toList(),
            recognitionOrder = 0,
        )
        val page = OcrPage("page", 0, block.text, listOf(block))
        return OcrDocument("document", page.rawText, listOf(page), OcrEngineInfo("test", "1"))
    }

    private fun line(
        index: Int,
        text: String,
        elements: List<String> = emptyList(),
        left: Int = 10,
        right: Int = 950,
        top: Int = 20 + index * 30,
    ): OcrLine {
        val elementWidth = if (elements.isEmpty()) 0 else (right - left) / elements.size
        return OcrLine(
            id = "line_$index",
            pageId = "page",
            pageIndex = 0,
            text = text,
            boundingBox = BoundingBox(left, top, right, top + 25),
            elements = elements.mapIndexed { elementIndex, value ->
                val elementLeft = left + elementIndex * elementWidth
                OcrElement(
                    id = "line_${index}_element_$elementIndex",
                    text = value,
                    boundingBox = BoundingBox(elementLeft, top, elementLeft + elementWidth, top + 25),
                    confidence = 0.9f,
                    recognitionOrder = elementIndex,
                )
            },
            confidence = 0.9f,
            recognitionOrder = index,
        )
    }

    private fun rowCells(
        startIndex: Int,
        top: Int,
        vararg cells: Pair<String, IntRange>,
    ): List<OcrLine> = cells.mapIndexed { offset, (text, horizontalRange) ->
        line(
            index = startIndex + offset,
            text = text,
            left = horizontalRange.first,
            right = horizontalRange.last,
            top = top,
        )
    }
}
