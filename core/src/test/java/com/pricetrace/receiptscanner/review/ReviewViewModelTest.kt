package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.ReceiptDocument
import com.pricetrace.receiptscanner.domain.ReceiptMerchant
import com.pricetrace.receiptscanner.domain.ReceiptSource
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2Totals
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewViewModelTest {
    @Test
    fun destinationTokensAreCentralizedAndStable() {
        assertEquals("#22C55E", DestinationColorTokens.PRICE_TRACE)
        assertEquals("#FACC15", DestinationColorTokens.CASH_OS)
        assertEquals("#7DD3FC", DestinationColorTokens.FITNESS)
        assertEquals(DestinationColorTokens.PRICE_TRACE, ReviewDestination.PRICE_TRACE.colorHex)
    }

    @Test
    fun canonicalRowsUsePlannerInsteadOfProjectionTargetHints() {
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.RESTAURANT,
            source = IngestionSource(producer = "test", sourceFiles = emptyList()),
            receipt = receipt(),
            // Deliberately empty: the planner, not this hint, supplies PT/Cash destinations.
            targets = emptySet(),
        )

        val model = ReviewViewModel.fromCanonical(envelope)
        val pt = model.destinations.single { it.destination == ReviewDestination.PRICE_TRACE }
        val cash = model.destinations.single { it.destination == ReviewDestination.CASH_OS }
        val fitness = model.destinations.single { it.destination == ReviewDestination.FITNESS }

        assertEquals(ReviewDestinationStatus.PLANNED, pt.status)
        assertEquals(ReviewDestinationStatus.PLANNED, cash.status)
        assertEquals(ReviewDestinationStatus.NOT_APPLICABLE, fitness.status)
        assertTrue(model.rows.any { it.item == "판매처명" })
    }

    @Test
    fun rowDestinationsReflectSelectedProjections() {
        val envelope = YeonsikOcrEnvelope(
            mode = IngestionMode.RESTAURANT,
            source = IngestionSource(producer = "test", sourceFiles = emptyList()),
            receipt = receipt(),
        )

        val merchantRow = ReviewViewModel.fromCanonical(
            envelope,
            selectedProjections = setOf(IngestionProjection.PRICETRACE_RECEIPT),
        ).rows.single { it.item == "판매처명" }

        assertEquals(
            listOf(ReviewDestination.PRICE_TRACE, ReviewDestination.CASH_OS),
            merchantRow.destinations.map { it.destination },
        )
        assertEquals(
            ReviewDestinationStatus.PLANNED,
            merchantRow.destinations.single { it.destination == ReviewDestination.PRICE_TRACE }.status,
        )
        assertEquals(
            ReviewDestinationStatus.UNSELECTED,
            merchantRow.destinations.single { it.destination == ReviewDestination.CASH_OS }.status,
        )
    }

    private fun receipt() = ReceiptV2(
        document = ReceiptDocument(
            id = null,
            localDocumentId = "review-test",
            issuedOn = "2026-09-13",
            issuedAt = null,
            currency = "KRW",
            source = ReceiptSource(
                originalDocumentId = null,
                sourceImages = emptyList(),
                transcriptionStatus = TranscriptionStatus.PARSED,
                rawText = null,
            ),
        ),
        merchant = ReceiptMerchant(name = "테스트 마트", branchName = null),
        lineItems = emptyList(),
        totals = ReceiptV2Totals(
            itemsGrossAmountMinor = 1000,
            discountAmountMinor = null,
            taxAmountMinor = null,
            feeAmountMinor = null,
            tipAmountMinor = null,
            roundingAmountMinor = null,
            grandTotalAmountMinor = 1000,
        ),
        payments = emptyList(),
    )
}
