package com.pricetrace.receiptscanner.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.input.InputOrigin
import com.pricetrace.receiptscanner.nutrition.NutritionLabelJson
import com.pricetrace.receiptscanner.verification.VerifiedDraftGate
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExternalJsonDraftPersistenceInstrumentedTest {
    @Test
    fun receiptDraftSurvivesRestartAndImageAppendDoesNotOverwriteIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileStore = ReceiptFileStore(context)
        val database = Room.inMemoryDatabaseBuilder(context, ReceiptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val documentId = "external-receipt-${UUID.randomUUID()}"
        try {
            val imported = receiptImport(documentId)
            val draft = (imported.draft as CanonicalDraft.Receipt).value
            val draftKey = "$documentId/draft/receipt.json"
            val repository = RoomReceiptSessionRepository(database.receiptSessionDao(), fileStore)
            repository.createSession(
                documentId = documentId,
                workflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
                inputMetadata = SessionInputMetadata(
                    inputOrigin = InputOrigin.EXTERNAL_JSON,
                    upstreamDocumentId = imported.upstreamDocumentId,
                    importFingerprint = imported.importFingerprint,
                ),
            )
            fileStore.writeText(draftKey, ReceiptV2Json.encodeCanonical(draft))
            repository.updateSession(requireNotNull(repository.getSession(documentId)).copy(
                ocrStatus = "parsed",
                receiptStorageKey = draftKey,
            ))

            val restarted = RoomReceiptSessionRepository(database.receiptSessionDao(), ReceiptFileStore(context))
            val restoredSession = requireNotNull(restarted.getSession(documentId))
            assertTrue(restoredSession.canRestore(emptyList()))
            assertEquals(InputOrigin.EXTERNAL_JSON, restoredSession.inputOrigin)
            assertEquals(imported.upstreamDocumentId, restoredSession.upstreamDocumentId)
            assertEquals(imported.importFingerprint, restoredSession.importFingerprint)
            val restoredBeforeAppend = ReceiptV2Json.decode(
                fileStore.readBytes(requireNotNull(restoredSession.receiptStorageKey)).toString(Charsets.UTF_8),
            )
            assertEquals(draft, restoredBeforeAppend)
            assertFalse(
                VerifiedDraftGate.evaluate(InputOrigin.EXTERNAL_JSON, 0, false).isAllowed,
            )

            val page = evidencePage(documentId, "receipt-evidence")
            fileStore.writeText(page.storageKey, "local image evidence")
            restarted.addPages(documentId, listOf(page))
            val restoredAfterAppend = ReceiptV2Json.decode(
                fileStore.readBytes(requireNotNull(restarted.getSession(documentId)?.receiptStorageKey))
                    .toString(Charsets.UTF_8),
            )
            assertEquals(draft, restoredAfterAppend)
            assertTrue(
                VerifiedDraftGate.evaluate(
                    InputOrigin.EXTERNAL_JSON,
                    restarted.getPages(documentId).size,
                    fileStore.readBytes(page.storageKey).isNotEmpty(),
                ).isAllowed,
            )
        } finally {
            database.close()
            fileStore.deleteDocumentFiles(documentId)
        }
    }

    @Test
    fun nutritionDraftSurvivesRestartAndImageAppendDoesNotOverwriteIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileStore = ReceiptFileStore(context)
        val database = Room.inMemoryDatabaseBuilder(context, ReceiptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val documentId = "external-nutrition-${UUID.randomUUID()}"
        try {
            val imported = nutritionImport(documentId)
            val draft = (imported.draft as CanonicalDraft.Nutrition).value
            val draftKey = "$documentId/draft/fitness-nutrition.json"
            val repository = RoomReceiptSessionRepository(database.receiptSessionDao(), fileStore)
            repository.createSession(
                documentId = documentId,
                workflowType = OcrWorkflowType.FITNESS_NUTRITION,
                inputMetadata = SessionInputMetadata(
                    inputOrigin = InputOrigin.EXTERNAL_JSON,
                    upstreamDocumentId = imported.upstreamDocumentId,
                    importFingerprint = imported.importFingerprint,
                ),
            )
            fileStore.writeText(draftKey, NutritionLabelJson.encode(draft))
            repository.updateSession(requireNotNull(repository.getSession(documentId)).copy(
                ocrStatus = "parsed",
                reviewStatus = draft.status.wireValue,
                workflowDraftStorageKey = draftKey,
            ))

            val restarted = RoomReceiptSessionRepository(database.receiptSessionDao(), ReceiptFileStore(context))
            val restoredSession = requireNotNull(restarted.getSession(documentId))
            assertTrue(restoredSession.canRestore(emptyList()))
            assertEquals(InputOrigin.EXTERNAL_JSON, restoredSession.inputOrigin)
            assertEquals(
                draft,
                NutritionLabelJson.decode(
                    fileStore.readBytes(requireNotNull(restoredSession.workflowDraftStorageKey))
                        .toString(Charsets.UTF_8),
                ),
            )
            assertFalse(VerifiedDraftGate.evaluate(InputOrigin.EXTERNAL_JSON, 0, false).isAllowed)

            val page = evidencePage(documentId, "nutrition-evidence")
            fileStore.writeText(page.storageKey, "local image evidence")
            restarted.addPages(documentId, listOf(page))
            assertEquals(
                draft,
                NutritionLabelJson.decode(
                    fileStore.readBytes(requireNotNull(restarted.getSession(documentId)?.workflowDraftStorageKey))
                        .toString(Charsets.UTF_8),
                ),
            )
            assertTrue(
                VerifiedDraftGate.evaluate(
                    InputOrigin.EXTERNAL_JSON,
                    restarted.getPages(documentId).size,
                    fileStore.readBytes(page.storageKey).isNotEmpty(),
                ).isAllowed,
            )
        } finally {
            database.close()
            fileStore.deleteDocumentFiles(documentId)
        }
    }

    @Test
    fun failedDraftWriteCanBeCleanedWithoutAffectingAnExistingSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileStore = ReceiptFileStore(context)
        val database = Room.inMemoryDatabaseBuilder(context, ReceiptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = RoomReceiptSessionRepository(database.receiptSessionDao(), fileStore)
        val existingId = "existing-${UUID.randomUUID()}"
        val failedId = "failed-${UUID.randomUUID()}"
        try {
            repository.createSession(existingId)
            val existing = requireNotNull(repository.getSession(existingId))
            repository.updateSession(existing.copy(merchantName = "보존 상점"))

            repository.createSession(
                failedId,
                inputMetadata = SessionInputMetadata(inputOrigin = InputOrigin.EXTERNAL_JSON),
            )
            try {
                fileStore.writeText("/absolute-test", "must not be persisted")
                error("expected invalid storage key")
            } catch (_: IllegalArgumentException) {
                // The file write failed after session creation, as in an import persistence failure.
            }
            assertTrue(repository.deleteSession(failedId).isComplete)
            assertNull(repository.getSession(failedId))
            assertEquals("보존 상점", requireNotNull(repository.getSession(existingId)).merchantName)
        } finally {
            repository.deleteSession(failedId)
            repository.deleteSession(existingId)
            database.close()
        }
    }

    @Test
    fun sameFingerprintIsLookupOnlyAndDifferentFingerprintWithSameUpstreamIsSeparate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileStore = ReceiptFileStore(context)
        val database = Room.inMemoryDatabaseBuilder(context, ReceiptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = RoomReceiptSessionRepository(database.receiptSessionDao(), fileStore)
        val firstId = "duplicate-first-${UUID.randomUUID()}"
        val secondId = "duplicate-second-${UUID.randomUUID()}"
        try {
            repository.createSession(
                firstId,
                inputMetadata = SessionInputMetadata(
                    inputOrigin = InputOrigin.EXTERNAL_JSON,
                    upstreamDocumentId = "same-upstream",
                    importFingerprint = "same-fingerprint",
                ),
            )
            repository.createSession(
                secondId,
                inputMetadata = SessionInputMetadata(
                    inputOrigin = InputOrigin.EXTERNAL_JSON,
                    upstreamDocumentId = "same-upstream",
                    importFingerprint = "changed-fingerprint",
                ),
            )
            assertEquals(listOf(firstId), repository.findSessionsByImportFingerprint("same-fingerprint").map { it.documentId })
            assertEquals(
                setOf(firstId, secondId),
                repository.findSessionsByUpstreamDocumentId("same-upstream").map { it.documentId }.toSet(),
            )
            assertNull(requireNotNull(repository.getSession(firstId)).receiptStorageKey)
            assertNull(requireNotNull(repository.getSession(secondId)).receiptStorageKey)
        } finally {
            repository.deleteSession(firstId)
            repository.deleteSession(secondId)
            database.close()
        }
    }

    private fun receiptImport(localDocumentId: String) = when (
        val outcome = ExternalJsonImporter().import(receiptJson(), localDocumentId, OcrWorkflowType.PRICE_TRACE_RECEIPT)
    ) {
        is ExternalJsonImportOutcome.Success -> outcome.result
        is ExternalJsonImportOutcome.Failure -> error(outcome.error)
    }

    private fun nutritionImport(localDocumentId: String) = when (
        val outcome = ExternalJsonImporter().import(
            nutritionJson(),
            localDocumentId,
            OcrWorkflowType.FITNESS_NUTRITION,
        )
    ) {
        is ExternalJsonImportOutcome.Success -> outcome.result
        is ExternalJsonImportOutcome.Failure -> error(outcome.error)
    }

    private fun evidencePage(documentId: String, suffix: String) = ReceiptPage(
        id = "page-$suffix",
        documentId = documentId,
        storageKey = "$documentId/pages/$suffix.bin",
        sha256 = suffix,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        pageIndex = 0,
        createdAt = "2026-08-27T00:00:00Z",
    )

    private fun receiptJson() = """
        {
          "schema_version":"receipt.v2",
          "document":{"id":"upstream-receipt","type":"receipt","status":"final","issued_on":"2026-08-27","issued_at":null,"currency":"KRW","source":{"capture_method":"ocr","original_document_id":null,"source_images":[],"transcription_status":"user_verified","notes":[],"raw_text":null}},
          "merchant":{"name":"External Mart","branch_name":null,"business_kind":"retail","retail_channel":"regular","catalog_namespace":null,"merchant_id":null,"business_registration_number":null,"address":null,"phone":null},
          "line_items":[{"id":"line-1","type":"product","description":"Milk","source_line_references":[],"identifiers":[],"quantity":{"value":1,"unit":"each"},"unit_price_amount_minor":1000,"gross_amount_minor":1000,"discount_amount_minor":0,"tax_amount_minor":0,"net_amount_minor":1000,"confidence":"user_verified","tax_rate_percent":null}],
          "totals":{"subtotal_amount_minor":1000,"discount_amount_minor":0,"tax_amount_minor":0,"fee_amount_minor":0,"grand_total_amount_minor":1000},"payments":[]
        }
    """.trimIndent()

    private fun nutritionJson() = """
        {
          "schema_version":"fitness-nutrition-draft.v1","document_id":"upstream-nutrition","parser_version":"test-parser","status":"user_verified","confirmed_at":"2026-01-01T00:00:00Z","name":"External Cereal","brand":"External Brand","kind":"external_menu","category":"cereal","basis_amount":100.0,"basis_unit":"g","prep_state":"unspecified","cooking_method":"unspecified","nutrients":{"calories_kcal":380.0,"protein_grams":10.0,"carbs_grams":70.0,"fat_grams":5.0,"sodium_mg":100.0,"saturated_fat_grams":1.0,"sugars_grams":12.0},"source_type":"product_label_ocr","source_reference":"ocr-document:upstream-nutrition","source_version":"v1","data_version":2,"visibility":"private","parse_warnings":[],"evidence":{}
        }
    """.trimIndent()
}
