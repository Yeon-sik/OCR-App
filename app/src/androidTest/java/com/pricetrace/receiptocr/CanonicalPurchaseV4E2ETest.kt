package com.pricetrace.receiptocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.ingestion.PurchaseRecord
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordEvidence
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordPayment
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordStatus
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordTotals
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V4_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV4Json
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side smoke test for the same Android validator path used by the canonical JSON screen. */
@RunWith(AndroidJUnit4::class)
class CanonicalPurchaseV4E2ETest {
    @Test
    fun paymentOnlyPurchaseConfirmsAndSubmitsCashOsOnly() = runBlocking {
        val submitted = mutableListOf<ProjectionRequest>()
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.CASHOS_TRANSACTION to RecordingSubmitter(submitted),
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to RecordingSubmitter(submitted),
            ),
            now = { "2026-09-11T12:00:00Z" },
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            newLocalDocumentId = { "android-e2e-v4-document" },
            newIngestionId = { "android-e2e-v4-ingestion" },
        )

        var state = validator.importJson(
            YeonsikOcrV4Json.encode(paymentOnlyEnvelope()),
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals("yeonsik-ocr.v4", state.envelope?.schemaVersion)
        assertEquals(setOf(IngestionProjection.CASHOS_TRANSACTION), state.plan?.eligible)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, state.envelope?.review?.status)

        state = validator.confirm(state)
        assertEquals(IngestionReviewStatus.READY, state.envelope?.review?.status)
        assertEquals(VerificationBasis.MANUAL_CANONICAL_REVIEW, state.envelope?.review?.verificationBasis)

        state = validator.submit(state)
        assertEquals(listOf(IngestionProjection.CASHOS_TRANSACTION), submitted.map { it.projection })
        assertTrue(state.notice?.contains("cashos_transaction=uploaded") == true)
        assertTrue(state.session?.projections?.single {
            it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION
        }?.status?.wireValue == "disabled")
    }

    private class RecordingSubmitter(
        private val submitted: MutableList<ProjectionRequest>,
    ) : IngestionProjectionSubmitter {
        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
            submitted += request
            return ProjectionSubmission.Success("android-e2e-${request.projection.wireValue}")
        }
    }

    private fun paymentOnlyEnvelope(): YeonsikOcrEnvelope = YeonsikOcrEnvelope(
        mode = IngestionMode.PURCHASE,
        source = IngestionSource(
            producer = "chatgpt",
            sourceFiles = emptyList(),
            userText = "쿠팡 결제내역에서 확인한 결제입니다.",
        ),
        classificationHints = mapOf(
            "cashos.category_hint" to "shopping",
            "cashos.payment_method_hint" to "card",
        ),
        targets = setOf(IngestionProjection.CASHOS_TRANSACTION),
        schemaVersion = YEONSIK_OCR_V4_SCHEMA,
        purchaseRecords = listOf(
            PurchaseRecord(
                clientKey = "purchase-android-e2e",
                platform = "쿠팡",
                paidOn = "2026-09-11",
                status = PurchaseRecordStatus.PAID,
                totals = PurchaseRecordTotals(
                    grandTotalAmountKrw = 1100,
                    paidAmountKrw = 1100,
                ),
                payment = PurchaseRecordPayment(method = "card", status = "paid"),
                evidence = listOf(
                    PurchaseRecordEvidence(
                        sourceType = "user_statement",
                        field = "platform",
                        observedValue = "쿠팡",
                    ),
                    PurchaseRecordEvidence(
                        sourceType = "user_statement",
                        field = "paid_on",
                        observedValue = "2026-09-11",
                    ),
                ),
                confidence = 0.98,
            ),
        ),
    )
}
