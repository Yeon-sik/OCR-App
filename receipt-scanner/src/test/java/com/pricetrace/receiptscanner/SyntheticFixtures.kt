package com.pricetrace.receiptscanner

import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.QuantityUnit
import com.pricetrace.receiptscanner.domain.ReceiptDocument
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptMerchant
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.ReceiptSource
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.ReceiptV2Totals
import com.pricetrace.receiptscanner.domain.RetailChannel
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.ocr.OcrBlock
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import com.pricetrace.receiptscanner.ocr.OcrLine
import com.pricetrace.receiptscanner.ocr.OcrPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SyntheticFixtures {
    fun ocrDocument(): OcrDocument {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/synthetic-ocr-lines.json"))
        val root = resource.bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
        val documentId = root.getValue("document_id").jsonPrimitive.content
        val pageId = root.getValue("page_id").jsonPrimitive.content
        val lines = root.getValue("lines").jsonArray.mapIndexed { index, value ->
            OcrLine(
                id = "ocr_line_$index",
                pageId = pageId,
                pageIndex = 0,
                text = value.jsonPrimitive.content,
                boundingBox = BoundingBox(10, 20 + index * 30, 980, 45 + index * 30),
                elements = emptyList(),
                confidence = if (index == 4) 0.72f else 0.93f,
                recognitionOrder = index,
            )
        }
        val block = OcrBlock(
            id = "ocr_block_0",
            pageId = pageId,
            pageIndex = 0,
            text = lines.joinToString("\n") { it.text },
            boundingBox = BoundingBox(0, 0, 1000, 400),
            lines = lines,
            recognitionOrder = 0,
        )
        val page = OcrPage(pageId, 0, block.text, listOf(block))
        return OcrDocument(
            documentId = documentId,
            rawText = page.rawText,
            pages = listOf(page),
            engine = OcrEngineInfo("synthetic", "1"),
        )
    }

    fun verifiedCandidate(
        grandTotal: Long = 1_000,
        netAmount: Long = 1_000,
        sourceReferences: List<String> = listOf("ocr_line_1"),
    ): ReceiptV2 = ReceiptV2(
        document = ReceiptDocument(
            id = "receipt_fixture_valid",
            status = ReceiptStatus.DRAFT,
            issuedOn = "2026-07-31",
            issuedAt = null,
            currency = "KRW",
            source = ReceiptSource(
                originalDocumentId = "TX-001",
                sourceImages = listOf("page_fixture_001"),
                transcriptionStatus = TranscriptionStatus.PARSED,
                rawText = "합성 OCR 텍스트",
            ),
        ),
        merchant = ReceiptMerchant(
            name = "가상마트",
            branchName = "서울점",
            businessKind = BusinessKind.RETAIL,
            retailChannel = RetailChannel.REGULAR,
        ),
        lineItems = listOf(
            ReceiptV2LineItem(
                id = "line_fixture_001",
                type = ReceiptLineType.PRODUCT,
                description = "합성 상품",
                sourceLineReferences = sourceReferences,
                identifiers = emptyList(),
                quantity = ReceiptQuantity("1", QuantityUnit.EACH),
                unitPriceAmountMinor = 1_000,
                grossAmountMinor = 1_000,
                discountAmountMinor = null,
                taxAmountMinor = null,
                netAmountMinor = netAmount,
                confidence = ConfidenceLevel.MEDIUM,
                taxRatePercent = null,
            ),
        ),
        totals = ReceiptV2Totals(
            subtotalAmountMinor = 1_000,
            discountAmountMinor = null,
            taxAmountMinor = null,
            feeAmountMinor = null,
            grandTotalAmountMinor = grandTotal,
        ),
        payments = emptyList(),
    )
}
