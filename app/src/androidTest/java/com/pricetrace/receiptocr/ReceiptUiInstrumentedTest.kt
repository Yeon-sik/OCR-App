package com.pricetrace.receiptocr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionCandidate
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionPrompt
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionProvider
import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.QuantityUnit
import com.pricetrace.receiptscanner.domain.ReceiptDocument
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptMerchant
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.ReceiptSource
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.ReceiptV2Totals
import com.pricetrace.receiptscanner.domain.ReceiptValidator
import com.pricetrace.receiptscanner.domain.ReconciliationDiagnostics
import com.pricetrace.receiptscanner.domain.ReconciliationSuggestion
import com.pricetrace.receiptscanner.domain.RetailChannel
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
import com.pricetrace.receiptscanner.storage.RoomReceiptSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReceiptUiInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scannerCancellationAndFailureRemainVisibleStates() {
        var state by mutableStateOf(ReceiptAppUiState(message = "스캔을 취소했습니다."))
        composeRule.setContent { ReceiptOcrTheme { ReceiptOcrContent(uiState = state) } }

        composeRule.onNodeWithText("스캔을 취소했습니다.").assertIsDisplayed()
        composeRule.runOnIdle {
            state = ReceiptAppUiState(message = "스캔 결과를 가져오지 못했습니다. 다시 시도하세요.")
        }
        composeRule.onNodeWithText("스캔 결과를 가져오지 못했습니다. 다시 시도하세요.").assertIsDisplayed()
    }

    @Test
    fun ocrProgressAndLowConfidenceRowAreExplicit() {
        var state by mutableStateOf(ReceiptAppUiState(screen = AppScreen.OCR_PROGRESS, isProcessingOcr = true))
        composeRule.setContent { ReceiptOcrTheme { ReceiptOcrContent(uiState = state) } }
        composeRule.onNodeWithTag("ocr_progress").assertIsDisplayed()

        composeRule.runOnIdle {
            state = ReceiptAppUiState(screen = AppScreen.ITEM_REVIEW, receipt = receipt(verified = false))
        }
        composeRule.onNodeWithTag("low_confidence_line-1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun reviewEditAndVerifiedExportActionsReachCallbacks() {
        var merchantEdit: String? = null
        var localTimeEdit: String? = null
        var shareClicks = 0
        var state by mutableStateOf(ReceiptAppUiState(screen = AppScreen.FIELD_REVIEW, receipt = receipt(false)))
        composeRule.setContent {
            ReceiptOcrTheme {
                ReceiptOcrContent(
                    uiState = state,
                    onMerchantNameChanged = { merchantEdit = it },
                    onIssuedLocalTimeChanged = { localTimeEdit = it },
                    onShare = { shareClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("merchant_name_field").performTextReplacement("수정상점")
        composeRule.onNodeWithTag("issued_local_time_field").performTextReplacement("18:07")
        composeRule.runOnIdle {
            assertEquals("수정상점", merchantEdit)
            assertEquals("18:07", localTimeEdit)
        }

        composeRule.runOnIdle {
            state = ReceiptAppUiState(
                screen = AppScreen.JSON_PREVIEW,
                receipt = receipt(true),
                jsonPreview = ReceiptV2Json.encodePretty(receipt(true)),
            )
        }
        composeRule.onNodeWithTag("share_json_button").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, shareClicks) }
    }

    @Test
    fun rowAdditionAndDeletionReachCallbacksAndDeletionIsConfirmedFirst() {
        var addedAfter: String? = "unset"
        var removed: String? = null
        val state = ReceiptAppUiState(screen = AppScreen.ITEM_REVIEW, receipt = receipt(verified = false))
        composeRule.setContent {
            ReceiptOcrTheme {
                ReceiptOcrContent(
                    uiState = state,
                    onAddLineItem = { addedAfter = it },
                    onRemoveLineItem = { removed = it },
                )
            }
        }

        composeRule.onNodeWithTag("item_review").performScrollToNode(hasTestTag("add_line_item_button"))
        composeRule.onNodeWithTag("add_line_item_button").performClick()
        composeRule.runOnIdle { assertEquals(null, addedAfter) }

        composeRule.onNodeWithTag("item_review").performScrollToNode(hasTestTag("remove_line_line-1"))
        composeRule.onNodeWithTag("remove_line_line-1").performClick()
        composeRule.runOnIdle { assertEquals(null, removed) }
        composeRule.onNodeWithTag("confirm_remove_line").performClick()
        composeRule.runOnIdle { assertEquals("line-1", removed) }
    }

    @Test
    fun reconciliationGapOffersACandidateThatOnlyAppliesWhenTheReviewerAccepts() {
        var applied: ReconciliationSuggestion? = null
        val mismatched = receipt(verified = false).let { base ->
            base.copy(totals = base.totals.copy(grandTotalAmountMinor = 8_000))
        }
        val state = ReceiptAppUiState(
            screen = AppScreen.RECONCILIATION,
            receipt = mismatched,
            validation = ReceiptValidator.validateForUserVerification(mismatched),
            diagnosis = ReconciliationDiagnostics.analyze(mismatched),
        )
        composeRule.setContent {
            ReceiptOcrTheme {
                ReceiptOcrContent(uiState = state, onApplySuggestion = { applied = it })
            }
        }

        composeRule.onNodeWithTag("reconciliation").performScrollToNode(hasTestTag("apply_LINE_AMOUNT_MISREAD"))
        composeRule.onNodeWithTag("hypothesis_LINE_AMOUNT_MISREAD", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, applied) }

        composeRule.onNodeWithTag("apply_LINE_AMOUNT_MISREAD").performClick()
        composeRule.runOnIdle {
            assertEquals(ReconciliationSuggestion.SetLineNetAmount("line-1", 8_000), applied)
        }
    }

    @Test
    fun undoIsOfferedOnlyAfterAnEditExists() {
        var undoClicks = 0
        var state by mutableStateOf(
            ReceiptAppUiState(screen = AppScreen.ITEM_REVIEW, receipt = receipt(verified = false)),
        )
        composeRule.setContent {
            ReceiptOcrTheme {
                ReceiptOcrContent(uiState = state, onUndo = { undoClicks += 1 })
            }
        }

        composeRule.onNodeWithTag("undo_button").assertIsNotEnabled()
        composeRule.runOnIdle { state = state.copy(canUndo = true) }
        composeRule.onNodeWithTag("undo_button").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, undoClicks) }
    }

    @Test
    fun geminiSuggestionsRequireAnExplicitRequestAndIndividualApply() {
        var requestClicks = 0
        var appliedCandidateId: String? = null
        var savedApiKey: String? = null
        var clearKeyClicks = 0
        val candidate = ReceiptCorrectionCandidate(
            id = "candidate-1",
            fieldPath = "line_items[line-1].description",
            oldValue = "테스트 상품",
            proposedValue = "테스트상품",
            sourceLineIds = listOf("ocr-line-1"),
            confidencePercent = 88,
            reason = "상품명 공백 교정",
            providerId = "gemini-api-direct",
            model = "gemini-3.5-flash-lite",
            promptVersion = ReceiptCorrectionPrompt.VERSION,
        )
        val state = ReceiptAppUiState(
            screen = AppScreen.AI_CORRECTION,
            receipt = receipt(false),
            correctionProvider = ReceiptCorrectionProvider(
                id = "gemini-api-direct",
                displayName = "Gemini API 직접 연결",
                model = "gemini-3.5-flash-lite",
                isAvailable = true,
            ),
            aiCorrectionCandidates = listOf(candidate),
        )
        composeRule.setContent {
            ReceiptOcrTheme {
                ReceiptOcrContent(
                    uiState = state,
                    onSaveGeminiApiKey = { savedApiKey = it },
                    onClearGeminiApiKey = { clearKeyClicks += 1 },
                    onRequestAiCorrections = { requestClicks += 1 },
                    onApplyAiCorrection = { appliedCandidateId = it },
                )
            }
        }

        composeRule.onNodeWithTag("gemini_api_key_input").performTextReplacement("test-api-key-with-more-than-twenty-chars")
        composeRule.onNodeWithTag("save_gemini_api_key_button").performClick()
        composeRule.onNodeWithTag("clear_gemini_api_key_button").performClick()
        composeRule.onNodeWithTag("request_ai_corrections_button").assertIsEnabled()
        composeRule.runOnIdle {
            assertEquals(0, requestClicks)
            assertEquals(null, appliedCandidateId)
            assertEquals("test-api-key-with-more-than-twenty-chars", savedApiKey)
            assertEquals(1, clearKeyClicks)
        }
        composeRule.onNodeWithTag("request_ai_corrections_button").performClick()
        composeRule.onNodeWithTag("ai_correction_review")
            .performScrollToNode(hasTestTag("apply_ai_candidate_candidate-1"))
        composeRule.onNodeWithTag("apply_ai_candidate_candidate-1").performClick()
        composeRule.runOnIdle {
            assertEquals(1, requestClicks)
            assertEquals("candidate-1", appliedCandidateId)
        }
    }

    @Test
    fun roomSessionCanBeRecoveredThroughANewRepositoryReference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileStore = ReceiptFileStore(context)
        val firstRepository = RoomReceiptSessionRepository.create(context, fileStore)
        val documentId = "instrumented-${UUID.randomUUID()}"
        val jsonKey = "$documentId/draft/receipt.json"
        val pageId = "page-$documentId"
        val candidate = receipt(false).copy(
            document = receipt(false).document.copy(
                id = documentId,
                source = receipt(false).document.source.copy(sourceImages = listOf(pageId)),
            ),
        )
        firstRepository.createSession(documentId)
        firstRepository.addPages(
            documentId,
            listOf(
                ReceiptPage(
                    id = pageId,
                    documentId = documentId,
                    storageKey = "$documentId/pages/synthetic.jpg",
                    sha256 = "synthetic-sha-256",
                    mimeType = "image/jpeg",
                    width = 100,
                    height = 200,
                    pageIndex = 0,
                    createdAt = "2026-08-03T10:00:00+09:00",
                ),
            ),
        )
        fileStore.writeText(jsonKey, ReceiptV2Json.encodeCanonical(candidate))
        val created = requireNotNull(firstRepository.getSession(documentId))
        firstRepository.updateSession(created.copy(merchantName = "합성상점", receiptStorageKey = jsonKey))

        val secondRepository = RoomReceiptSessionRepository.create(context, ReceiptFileStore(context))
        val restored = requireNotNull(secondRepository.getSession(documentId))
        assertEquals("합성상점", restored.merchantName)
        assertEquals(pageId, secondRepository.getPages(documentId).single().id)
        assertEquals(candidate, ReceiptV2Json.decode(fileStore.readBytes(requireNotNull(restored.receiptStorageKey)).toString(Charsets.UTF_8)))
        val duplicateDocumentId = "instrumented-${UUID.randomUUID()}"
        secondRepository.createSession(duplicateDocumentId)
        val duplicateCandidates = secondRepository.addPages(
            duplicateDocumentId,
            listOf(
                ReceiptPage(
                    id = "page-$duplicateDocumentId",
                    documentId = duplicateDocumentId,
                    storageKey = "$duplicateDocumentId/pages/synthetic.jpg",
                    sha256 = "synthetic-sha-256",
                    mimeType = "image/jpeg",
                    width = 100,
                    height = 200,
                    pageIndex = 0,
                    createdAt = "2026-08-03T10:01:00+09:00",
                ),
            ),
        )
        assertTrue(pageId in duplicateCandidates)
        assertTrue(secondRepository.deleteSession(duplicateDocumentId).isComplete)
        assertTrue(secondRepository.deleteSession(documentId).isComplete)
        val orphanDocumentId = "instrumented-orphan-${UUID.randomUUID()}"
        fileStore.writeText("$orphanDocumentId/draft/receipt.json", "{}")
        assertTrue(secondRepository.deleteSession(orphanDocumentId).isComplete)
        assertFalse(fileStore.resolveStorageKey(orphanDocumentId).exists())
    }

    @Test
    fun fileProviderGrantsReadableContentUriForJsonOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "receipt-scanner/instrumented-share-${UUID.randomUUID()}")
        val json = File(directory, "receipt.json")
        directory.mkdirs()
        json.writeText("{\"schema_version\":\"receipt.v2\"}")
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", json)
            assertEquals("content", uri.scheme)
            val readBack = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            assertEquals(json.readText(), readBack)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun receipt(verified: Boolean): ReceiptV2 {
        val transcriptionStatus = if (verified) TranscriptionStatus.USER_VERIFIED else TranscriptionStatus.PARSED
        return ReceiptV2(
            document = ReceiptDocument(
                id = "doc-ui-test",
                status = if (verified) ReceiptStatus.FINAL else ReceiptStatus.DRAFT,
                issuedOn = "2026-08-03",
                issuedAt = null,
                currency = "KRW",
                source = ReceiptSource(
                    originalDocumentId = null,
                    sourceImages = emptyList(),
                    transcriptionStatus = transcriptionStatus,
                    notes = listOf("purchase_local_time=14:35:00"),
                    rawText = "합성 OCR",
                ),
            ),
            merchant = ReceiptMerchant(
                name = "합성상점",
                branchName = null,
                businessKind = BusinessKind.RETAIL,
                retailChannel = RetailChannel.REGULAR,
            ),
            lineItems = listOf(
                ReceiptV2LineItem(
                    id = "line-1",
                    type = ReceiptLineType.PRODUCT,
                    description = "합성 상품",
                    sourceLineReferences = listOf("ocr-line-1"),
                    identifiers = emptyList(),
                    quantity = ReceiptQuantity("1", QuantityUnit.EACH),
                    unitPriceAmountMinor = 1_000,
                    grossAmountMinor = 1_000,
                    discountAmountMinor = null,
                    taxAmountMinor = null,
                    netAmountMinor = 1_000,
                    confidence = if (verified) ConfidenceLevel.USER_VERIFIED else ConfidenceLevel.LOW,
                    taxRatePercent = null,
                ),
            ),
            totals = ReceiptV2Totals(
                subtotalAmountMinor = 1_000,
                discountAmountMinor = null,
                taxAmountMinor = null,
                feeAmountMinor = null,
                grandTotalAmountMinor = 1_000,
            ),
            payments = emptyList(),
        )
    }
}
