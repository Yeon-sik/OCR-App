package com.pricetrace.receiptscanner.storage

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptSessionMetadataInstrumentedTest {
    @Test
    fun externalMetadataIsPersistedRestoredAndLookedUpWithoutOverwriting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ReceiptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = RoomReceiptSessionRepository(database.receiptSessionDao(), ReceiptFileStore(context))
            repository.createSession(
                documentId = "external-receipt",
                workflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
                createdAt = "2026-08-26T00:00:00Z",
                inputMetadata = SessionInputMetadata(
                    inputOrigin = InputOrigin.EXTERNAL_JSON,
                    upstreamDocumentId = "upstream-receipt",
                    importFingerprint = "fingerprint-a",
                ),
            )
            val created = requireNotNull(repository.getSession("external-receipt"))
            repository.updateSession(
                created.copy(receiptStorageKey = "external-receipt/draft/receipt.json"),
            )

            val restored = requireNotNull(repository.getSession("external-receipt"))
            assertEquals(InputOrigin.EXTERNAL_JSON, restored.inputOrigin)
            assertEquals("upstream-receipt", restored.upstreamDocumentId)
            assertEquals("fingerprint-a", restored.importFingerprint)
            assertTrue(restored.canRestore(repository.getPages("external-receipt")))
            assertEquals(1, repository.findSessionsByImportFingerprint("fingerprint-a").size)

            repository.createSession(
                documentId = "external-receipt-v2",
                workflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
                inputMetadata = SessionInputMetadata(
                    inputOrigin = InputOrigin.EXTERNAL_JSON,
                    upstreamDocumentId = "upstream-receipt",
                    importFingerprint = "fingerprint-b",
                ),
            )
            assertEquals(
                listOf("external-receipt-v2", "external-receipt"),
                repository.findSessionsByUpstreamDocumentId("upstream-receipt").map { it.documentId },
            )
            assertEquals(
                listOf("external-receipt"),
                repository.findSessionsByImportFingerprint("fingerprint-a").map { it.documentId },
            )
            assertFalse(
                repository.findSessionsByImportFingerprint("fingerprint-b")
                    .single()
                    .hasPersistedCanonicalDraft,
            )

            repository.createSession(
                documentId = "external-nutrition",
                workflowType = OcrWorkflowType.FITNESS_NUTRITION,
                inputMetadata = SessionInputMetadata(
                    inputOrigin = InputOrigin.EXTERNAL_JSON,
                    upstreamDocumentId = "upstream-nutrition",
                    importFingerprint = "nutrition-fingerprint",
                ),
            )
            val nutrition = requireNotNull(repository.getSession("external-nutrition"))
            repository.updateSession(
                nutrition.copy(workflowDraftStorageKey = "external-nutrition/draft/nutrition.json"),
            )
            val restoredNutrition = requireNotNull(repository.getSession("external-nutrition"))
            assertTrue(restoredNutrition.canRestore(repository.getPages("external-nutrition")))
            assertEquals("upstream-nutrition", restoredNutrition.upstreamDocumentId)
        } finally {
            database.close()
        }
    }
}
