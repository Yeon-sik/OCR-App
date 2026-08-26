package com.pricetrace.receiptscanner.storage

import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptSessionRestoreTest {
    @Test
    fun externalReceiptWithPersistedCanonicalDraftCanRestoreWithoutPages() {
        val session = session(
            inputOrigin = InputOrigin.EXTERNAL_JSON,
            workflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
            receiptStorageKey = "doc/draft/receipt.json",
        )

        assertTrue(session.hasPersistedCanonicalDraft)
        assertTrue(session.canRestore(emptyList()))
    }

    @Test
    fun externalNutritionWithPersistedCanonicalDraftCanRestoreWithoutPages() {
        val session = session(
            inputOrigin = InputOrigin.EXTERNAL_JSON,
            workflowType = OcrWorkflowType.FITNESS_NUTRITION,
            workflowDraftStorageKey = "doc/draft/nutrition.json",
        )

        assertTrue(session.hasPersistedCanonicalDraft)
        assertTrue(session.canRestore(emptyList()))
    }

    @Test
    fun externalSessionWithoutCanonicalDraftCannotRestoreWithoutPages() {
        val session = session(inputOrigin = InputOrigin.EXTERNAL_JSON)

        assertFalse(session.hasPersistedCanonicalDraft)
        assertFalse(session.canRestore(emptyList()))
    }

    @Test
    fun androidOcrSessionWithNoPagesIsNotNormalizedToRestorable() {
        val session = session(
            inputOrigin = InputOrigin.ANDROID_OCR,
            receiptStorageKey = "doc/draft/receipt.json",
        )

        assertFalse(session.canRestore(emptyList()))
    }

    private fun session(
        inputOrigin: InputOrigin,
        workflowType: OcrWorkflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
        receiptStorageKey: String? = null,
        workflowDraftStorageKey: String? = null,
    ) = ReceiptSession(
        documentId = "doc",
        createdAt = "2026-08-26T00:00:00Z",
        updatedAt = "2026-08-26T00:00:00Z",
        ocrStatus = "unprocessed",
        reviewStatus = "draft",
        jsonRevision = null,
        exportStatus = "not_exported",
        uploadStatus = "local_only",
        lastError = null,
        retryCount = 0,
        merchantName = null,
        issuedOn = null,
        grandTotalAmountMinor = null,
        receiptStorageKey = receiptStorageKey,
        manifestStorageKey = null,
        reviewedAt = null,
        workflowType = workflowType,
        inputOrigin = inputOrigin,
        workflowDraftStorageKey = workflowDraftStorageKey,
    )
}
