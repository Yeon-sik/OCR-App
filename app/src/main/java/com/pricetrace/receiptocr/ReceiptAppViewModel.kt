package com.pricetrace.receiptocr

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.pricetrace.receiptocr.fitness.NutritionAuthOutcome
import com.pricetrace.receiptocr.fitness.NutritionGatewayFailure
import com.pricetrace.receiptocr.fitness.NutritionPublishOutcome
import com.pricetrace.receiptocr.gemini.ReceiptCorrectionEvidenceCropper
import com.pricetrace.receiptscanner.capture.CaptureOutcome
import com.pricetrace.receiptscanner.capture.ScannerLaunchPreparation
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionCandidate
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionFailureReason
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionOutcome
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionPolicy
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionProvider
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionRequestFactory
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.ReceiptReviewProgress
import com.pricetrace.receiptscanner.domain.ReceiptValidationResult
import com.pricetrace.receiptscanner.domain.ReceiptValidator
import com.pricetrace.receiptscanner.domain.ReconciliationDiagnosis
import com.pricetrace.receiptscanner.domain.ReconciliationDiagnostics
import com.pricetrace.receiptscanner.domain.ReconciliationSuggestion
import com.pricetrace.receiptscanner.domain.ReviewAccuracyCalculator
import com.pricetrace.receiptscanner.domain.ReviewAccuracySummary
import com.pricetrace.receiptscanner.domain.ReviewedReceiptSample
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.domain.parserVersion
import com.pricetrace.receiptscanner.domain.toReceiptV2
import com.pricetrace.receiptscanner.domain.withParserVersion
import com.pricetrace.receiptscanner.export.OcrDebugJson
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.export.ReviewAccuracyReportJson
import com.pricetrace.receiptscanner.ocr.MlKitDocumentOcrEngine
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrInputPage
import com.pricetrace.receiptscanner.ocr.OcrOutcome
import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.nutrition.NutritionLabelJson
import com.pricetrace.receiptscanner.nutrition.NutritionLabelValidator
import com.pricetrace.receiptscanner.nutrition.NutritionUnit
import com.pricetrace.receiptscanner.review.ReceiptReviewController
import com.pricetrace.receiptscanner.review.toFieldCorrection
import com.pricetrace.receiptscanner.storage.ReceiptSession
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.OffsetDateTime

enum class AppScreen {
    SESSION_LIST,
    IMAGE_CONFIRM,
    OCR_PROGRESS,
    FIELD_REVIEW,
    ITEM_REVIEW,
    AI_CORRECTION,
    RECONCILIATION,
    JSON_PREVIEW,
    NUTRITION_REVIEW,
    EVALUATION,
}

data class ReceiptAppUiState(
    val screen: AppScreen = AppScreen.SESSION_LIST,
    val selectedWorkflow: OcrWorkflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
    val isPreparingScanner: Boolean = false,
    val isImportingPages: Boolean = false,
    val isProcessingOcr: Boolean = false,
    val isRequestingAiCorrections: Boolean = false,
    val isExporting: Boolean = false,
    val isNutritionSigningIn: Boolean = false,
    val isNutritionPublishing: Boolean = false,
    val message: String? = null,
    val possibleDuplicatePageIds: List<String> = emptyList(),
    val currentDocumentId: String? = null,
    val receipt: ReceiptV2? = null,
    val ocrDocument: OcrDocument? = null,
    val validation: ReceiptValidationResult? = null,
    val progress: ReceiptReviewProgress? = null,
    val diagnosis: ReconciliationDiagnosis? = null,
    val correctionProvider: ReceiptCorrectionProvider? = null,
    val aiCorrectionCandidates: List<ReceiptCorrectionCandidate> = emptyList(),
    val rejectedAiCorrectionCount: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showOnlyAttentionItems: Boolean = false,
    /**
     * Rows the filter was opened with. Correcting a row clears its attention flag immediately, so a live
     * set would make the row disappear under the reviewer's cursor mid-edit.
     */
    val attentionFilterIds: List<String> = emptyList(),
    val reconciliationReason: String? = null,
    val includeRawTextInShare: Boolean = false,
    val jsonPreview: String? = null,
    val reviewedAt: String? = null,
    val accuracySummary: ReviewAccuracySummary? = null,
    val isBuildingAccuracyReport: Boolean = false,
    val nutritionDraft: NutritionLabelDraft? = null,
    val nutritionValidationErrors: List<String> = emptyList(),
    val nutritionSupabaseUrl: String = "",
    val isNutritionPublishableKeyConfigured: Boolean = false,
    val nutritionSignedInEmail: String? = null,
)

sealed interface ReceiptUiEvent {
    data class ShareJson(val storageKey: String) : ReceiptUiEvent
    data class ShareAccuracyReport(val storageKey: String) : ReceiptUiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptAppViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val container = (application as ReceiptOcrApplication).container
    private val repository = container.sessionRepository
    private val captureProvider = container.captureProvider
    private val fileStore = container.fileStore
    private val ocrEngine = container.createOcrEngine()
    private val parser = container.parser
    private val nutritionParser = container.nutritionParser
    private val exportService = container.exportService
    private val publisher = container.publisher
    private val geminiApiKeyStore = container.geminiApiKeyStore
    private val correctionSuggester = container.correctionSuggester
    private val nutritionSupabaseStore = container.nutritionSupabaseStore
    private val nutritionGateway = container.nutritionGateway

    private val mutableUiState = MutableStateFlow(ReceiptAppUiState())
    val uiState: StateFlow<ReceiptAppUiState> = mutableUiState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<ReceiptUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ReceiptUiEvent> = mutableEvents.asSharedFlow()

    val sessions: StateFlow<List<ReceiptSession>> = repository.observeSessions().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val selectedDocumentId = MutableStateFlow<String?>(null)
    val selectedPages: StateFlow<List<ReceiptPage>> = selectedDocumentId
        .flatMapLatest { documentId ->
            if (documentId == null) flowOf(emptyList()) else repository.observePages(documentId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val persistenceMutex = Mutex()
    private val persistedEditIds = mutableSetOf<String>()
    private var reviewController: ReceiptReviewController? = null
    private var nutritionPersistenceJob: Job? = null
    private var pendingDocumentId: String?
        get() = savedStateHandle[PENDING_DOCUMENT_ID]
        set(value) { savedStateHandle[PENDING_DOCUMENT_ID] = value }
    private var pendingSessionWasNew: Boolean
        get() = savedStateHandle[PENDING_SESSION_WAS_NEW] ?: false
        set(value) { savedStateHandle[PENDING_SESSION_WAS_NEW] = value }

    init {
        refreshNutritionConnectionState()
    }

    fun prepareScanner(
        activity: Activity,
        onReady: (IntentSender) -> Unit,
        appendToCurrent: Boolean = false,
    ) {
        val state = mutableUiState.value
        if (state.isPreparingScanner || state.isImportingPages || state.isProcessingOcr) return
        viewModelScope.launch {
            mutableUiState.value = state.copy(isPreparingScanner = true, message = null)
            val existingDocumentId = state.currentDocumentId.takeIf { appendToCurrent }
            val documentId = existingDocumentId ?: StableIds.newOcrDocumentId().also {
                repository.createSession(it, state.selectedWorkflow)
            }
            pendingDocumentId = documentId
            pendingSessionWasNew = existingDocumentId == null
            selectedDocumentId.value = documentId

            when (val preparation = captureProvider.prepareLaunch(activity)) {
                is ScannerLaunchPreparation.Ready -> {
                    mutableUiState.value = mutableUiState.value.copy(
                        isPreparingScanner = false,
                        currentDocumentId = documentId,
                    )
                    onReady(preparation.intentSender)
                }
                is ScannerLaunchPreparation.Failure -> {
                    cleanEmptyPendingSessionIfNeeded()
                    mutableUiState.value = if (existingDocumentId != null) {
                        state.copy(
                            screen = AppScreen.IMAGE_CONFIRM,
                            isPreparingScanner = false,
                            message = captureFailureMessage(preparation.reason.wireValue),
                        )
                    } else {
                        homeState(
                            workflow = state.selectedWorkflow,
                            message = captureFailureMessage(preparation.reason.wireValue),
                        )
                    }
                    clearPendingCapture()
                }
            }
        }
    }

    fun consumeScannerResult(resultCode: Int, data: Intent?) {
        val documentId = pendingDocumentId ?: return
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(
                isImportingPages = true,
                message = null,
            )
            when (val outcome = captureProvider.handleActivityResult(documentId, resultCode, data)) {
                is CaptureOutcome.Success -> {
                    val workflow = repository.getSession(documentId)?.workflowType
                        ?: mutableUiState.value.selectedWorkflow
                    selectedDocumentId.value = documentId
                    mutableUiState.value = homeState(
                        workflow = workflow,
                        screen = AppScreen.IMAGE_CONFIRM,
                        currentDocumentId = documentId,
                        message = "${outcome.pages.size}개 페이지를 앱 전용 저장소에 보존했습니다.",
                        possibleDuplicatePageIds = outcome.possibleDuplicatePageIds,
                    )
                }
                is CaptureOutcome.Failure -> {
                    cleanEmptyPendingSessionIfNeeded()
                    mutableUiState.value = if (pendingSessionWasNew) {
                        homeState(
                            workflow = mutableUiState.value.selectedWorkflow,
                            message = captureFailureMessage(outcome.reason.wireValue),
                        )
                    } else {
                        mutableUiState.value.copy(
                            screen = AppScreen.IMAGE_CONFIRM,
                            isImportingPages = false,
                            message = captureFailureMessage(outcome.reason.wireValue),
                        )
                    }
                }
            }
            clearPendingCapture()
        }
    }

    fun selectSession(documentId: String) {
        nutritionPersistenceJob?.cancel()
        viewModelScope.launch {
            selectedDocumentId.value = documentId
            val session = repository.getSession(documentId)
            val pages = repository.getPages(documentId)
            if (session == null || pages.isEmpty()) {
                mutableUiState.value = homeState(
                    workflow = session?.workflowType ?: mutableUiState.value.selectedWorkflow,
                    message = "세션 메타데이터 또는 원본 페이지가 없어 복원할 수 없습니다.",
                )
                return@launch
            }
            mutableUiState.value = homeState(
                workflow = session.workflowType,
                screen = AppScreen.IMAGE_CONFIRM,
                currentDocumentId = documentId,
                message = "저장된 검수 세션을 불러오는 중입니다.",
            )

            val restoredOcr = restoreOcrDocument(documentId)
            if (session.workflowType == OcrWorkflowType.FITNESS_NUTRITION) {
                reviewController = null
                persistedEditIds.clear()
                val draft = session.workflowDraftStorageKey?.let { storageKey ->
                    try {
                        NutritionLabelJson.decode(fileStore.readBytes(storageKey).toString(Charsets.UTF_8))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
                mutableUiState.value = if (draft == null) {
                    mutableUiState.value.copy(
                        screen = AppScreen.IMAGE_CONFIRM,
                        message = "저장된 상품 이미지를 복원했습니다. OCR을 시작할 수 있습니다.",
                    )
                } else {
                    nutritionState(
                        draft = draft,
                        ocrDocument = restoredOcr,
                        message = "Fitness 영양성분 검수 초안을 복원했습니다.",
                    )
                }
                return@launch
            }

            val receipt = session.receiptStorageKey?.let { storageKey ->
                try {
                    ReceiptV2Json.decode(fileStore.readBytes(storageKey).toString(Charsets.UTF_8))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (receipt == null) {
                reviewController = null
                persistedEditIds.clear()
                mutableUiState.value = mutableUiState.value.copy(
                    message = "저장된 원본 이미지를 복원했습니다. OCR을 시작할 수 있습니다.",
                )
                return@launch
            }

            val edits = repository.getEdits(documentId)
            persistedEditIds.clear()
            persistedEditIds += edits.map { it.id }
            reviewController = ReceiptReviewController(receipt, edits)
            val storedParserVersion = receipt.document.source.parserVersion()
            val restoreMessage = if (storedParserVersion == parser.version) {
                "검수 세션과 수정 이력을 복원했습니다."
            } else {
                "이 세션은 ${storedParserVersion ?: "기록되지 않은 이전 파서"} 결과입니다. " +
                    "기존 검수값은 보존했습니다. 새 규칙을 적용하려면 이미지 확인에서 OCR을 다시 시작하세요."
            }
            mutableUiState.value = stateFromReview(
                screen = AppScreen.FIELD_REVIEW,
                ocrDocument = restoredOcr,
                message = restoreMessage,
                reviewedAt = session.reviewedAt.takeIf {
                    receipt.document.source.transcriptionStatus == TranscriptionStatus.USER_VERIFIED
                },
            )
        }
    }

    fun deleteSession(documentId: String) {
        nutritionPersistenceJob?.cancel()
        viewModelScope.launch {
            val workflow = repository.getSession(documentId)?.workflowType
                ?: mutableUiState.value.selectedWorkflow
            val result = repository.deleteSession(documentId)
            if (selectedDocumentId.value == documentId) selectedDocumentId.value = null
            mutableUiState.value = homeState(
                workflow = workflow,
                message = if (result.isComplete) {
                    "세션 metadata와 앱 전용 파일을 삭제했습니다."
                } else {
                    "세션 삭제가 완전하지 않습니다: ${result.detail ?: "unknown"}"
                },
            )
        }
    }

    fun startOcr() {
        val documentId = mutableUiState.value.currentDocumentId ?: return
        if (mutableUiState.value.isProcessingOcr) return
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(
                screen = AppScreen.OCR_PROGRESS,
                isProcessingOcr = true,
                message = null,
            )
            val pages = repository.getPages(documentId)
            val inputs = try {
                pages.map { page -> OcrInputPage(page, fileStore.readBytes(page.storageKey)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordOcrFailure(documentId, "image_read_failed")
                return@launch
            }
            val outcome = try {
                ocrEngine.recognize(documentId, inputs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordOcrFailure(documentId, "ocr_engine_failed")
                return@launch
            }
            when (outcome) {
                is OcrOutcome.Success -> {
                    try {
                        val session = requireNotNull(repository.getSession(documentId))
                        if (session.workflowType == OcrWorkflowType.FITNESS_NUTRITION) {
                            processNutritionOcrSuccess(session, outcome.document)
                        } else {
                            processReceiptOcrSuccess(session, outcome.document)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        recordOcrFailure(documentId, "parse_or_persist_failed")
                    }
                }
                is OcrOutcome.Failure -> recordOcrFailure(documentId, outcome.reason.wireValue)
            }
        }
    }

    fun selectWorkflow(workflow: OcrWorkflowType) {
        val state = mutableUiState.value
        if (state.isPreparingScanner || state.isImportingPages || state.isProcessingOcr) return
        selectedDocumentId.value = null
        nutritionPersistenceJob?.cancel()
        reviewController = null
        persistedEditIds.clear()
        mutableUiState.value = homeState(workflow = workflow)
    }

    fun updateNutritionProductName(value: String) = updateNutritionDraft {
        copy(productName = value, status = NutritionDraftStatus.PARSED, confirmedAt = null)
    }

    fun updateNutritionBrand(value: String) = updateNutritionDraft {
        copy(brand = value.trim().takeIf(String::isNotEmpty), status = NutritionDraftStatus.PARSED, confirmedAt = null)
    }

    fun updateNutritionCategory(value: String) = updateNutritionDraft {
        copy(category = value.trim().lowercase(), status = NutritionDraftStatus.PARSED, confirmedAt = null)
    }

    fun updateNutritionBasisAmount(value: String) {
        val amount = value.trim().replace(',', '.').takeIf(String::isNotEmpty)?.toDoubleOrNull()
        if (value.isNotBlank() && (amount == null || !amount.isFinite())) {
            updateNutritionDraft {
                copy(basisAmount = null, status = NutritionDraftStatus.PARSED, confirmedAt = null)
            }
            mutableUiState.value = mutableUiState.value.copy(message = "기준량은 0 이상의 숫자로 입력하세요.")
            return
        }
        updateNutritionDraft {
            copy(basisAmount = amount, status = NutritionDraftStatus.PARSED, confirmedAt = null)
        }
    }

    fun updateNutritionBasisUnit(value: String) = updateNutritionDraft {
        copy(
            basisUnit = NutritionUnit.normalize(value),
            status = NutritionDraftStatus.PARSED,
            confirmedAt = null,
        )
    }

    fun updateNutritionValue(field: NutritionField, value: String) {
        val amount = value.trim().replace(',', '.').takeIf(String::isNotEmpty)?.toDoubleOrNull()
        if (value.isNotBlank() && (amount == null || !amount.isFinite())) {
            updateNutritionDraft { withNutrient(field, null) }
            mutableUiState.value = mutableUiState.value.copy(
                message = "${field.koreanLabel}은 0 이상의 숫자로 입력하거나 비워 두세요.",
            )
            return
        }
        updateNutritionDraft { withNutrient(field, amount) }
    }

    fun saveNutritionConnection(url: String, publishableKey: String) {
        val existing = nutritionSupabaseStore.read()
        val effectiveKey = publishableKey.trim().ifEmpty { existing.publishableKey }
        nutritionSupabaseStore.saveConnection(url, effectiveKey)
            .onSuccess {
                refreshNutritionConnectionState(
                    message = "Nutrition Supabase 연결 정보를 저장했습니다. 로그인 후에만 private 식품을 전송합니다.",
                )
            }
            .onFailure { error ->
                mutableUiState.value = mutableUiState.value.copy(
                    message = error.message ?: "Nutrition Supabase 연결 정보를 저장하지 못했습니다.",
                )
            }
    }

    fun signInNutrition(email: String, password: String) {
        val state = mutableUiState.value
        if (state.isNutritionSigningIn || state.isNutritionPublishing) return
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(isNutritionSigningIn = true, message = null)
            when (val outcome = nutritionGateway.signIn(email, password)) {
                is NutritionAuthOutcome.Success -> refreshNutritionConnectionState(
                    message = "${outcome.email} 계정으로 Nutrition DB에 로그인했습니다.",
                )
                is NutritionAuthOutcome.Failure -> mutableUiState.value = mutableUiState.value.copy(
                    isNutritionSigningIn = false,
                    message = nutritionFailureMessage(outcome.reason),
                )
            }
        }
    }

    fun confirmAndPublishNutrition() {
        val state = mutableUiState.value
        val currentDraft = state.nutritionDraft ?: return
        if (state.isNutritionPublishing || state.isNutritionSigningIn) return
        val validation = NutritionLabelValidator.validate(currentDraft)
        if (!validation.isReadyForUpload) {
            mutableUiState.value = state.copy(
                nutritionValidationErrors = validation.errors,
                message = "필수 상품·영양성분 값을 원본과 대조해 먼저 확정하세요.",
            )
            return
        }
        nutritionPersistenceJob?.cancel()
        viewModelScope.launch {
            val verifiedAt = currentDraft.confirmedAt ?: OffsetDateTime.now().toString()
            val verified = currentDraft.asUserVerified(verifiedAt)
            mutableUiState.value = nutritionState(
                draft = verified,
                ocrDocument = state.ocrDocument,
                message = null,
            ).copy(isNutritionPublishing = true)
            try {
                persistNutritionDraftNow(verified, uploadStatus = "pending")
                when (val outcome = nutritionGateway.publish(verified)) {
                    is NutritionPublishOutcome.Success -> {
                        updateNutritionSessionPublication(
                            verified,
                            uploadStatus = "uploaded",
                            lastError = null,
                        )
                        mutableUiState.value = nutritionState(
                            draft = verified,
                            ocrDocument = state.ocrDocument,
                            message = "Fitness Nutrition DB에 private 식품으로 저장했습니다. " +
                                "상품 연결·공개는 Fitness App의 별도 승인 흐름에서 처리합니다.",
                        )
                    }
                    is NutritionPublishOutcome.Failure -> {
                        updateNutritionSessionPublication(
                            verified,
                            uploadStatus = "failed",
                            lastError = "nutrition_${outcome.reason.name.lowercase()}",
                        )
                        val failureMessage = nutritionFailureMessage(outcome.reason) +
                            " 확정 초안은 로컬에 보존했으므로 다시 전송할 수 있습니다."
                        mutableUiState.value = nutritionState(
                            draft = verified,
                            ocrDocument = state.ocrDocument,
                            message = failureMessage,
                        )
                        if (outcome.reason == NutritionGatewayFailure.AUTHENTICATION) {
                            nutritionSupabaseStore.clearSession()
                            refreshNutritionConnectionState(failureMessage)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.value = nutritionState(
                    draft = verified,
                    ocrDocument = state.ocrDocument,
                    message = "확정 초안을 저장하거나 전송하지 못했습니다. 현재 화면의 값은 유지됩니다.",
                )
            }
        }
    }

    fun updateMerchantName(value: String) = applyEdit { updateMerchantName(value) }
    fun updateBranchName(value: String) = applyEdit { updateBranchName(value) }
    fun updateBusinessRegistrationNumber(value: String) = applyEdit { updateBusinessRegistrationNumber(value) }
    fun updateAddress(value: String) = applyEdit { updateAddress(value) }
    fun updatePhone(value: String) = applyEdit { updatePhone(value) }
    fun updateOriginalDocumentId(value: String) = applyEdit { updateOriginalDocumentId(value) }
    fun updateIssuedOn(value: String) = applyEdit { updateIssuedOn(value) }
    fun updateIssuedLocalTime(value: String) = applyEdit("시간은 HH:mm 또는 HH:mm:ss로 입력하세요.") {
        updateIssuedLocalTime(value)
    }
    fun updateIssuedAt(value: String) = applyEdit { updateIssuedAt(value) }
    fun updateCurrency(value: String) = applyEdit { updateCurrency(value) }
    fun updateGrandTotal(value: String) = applyEdit("금액은 쉼표를 포함한 정수로 입력하세요.") {
        updateGrandTotal(value)
    }
    fun updateSubtotal(value: String) = applyEdit("금액은 쉼표를 포함한 정수로 입력하세요.") {
        updateSubtotal(value)
    }
    fun updateDiscountTotal(value: String) = applyEdit("할인은 부호를 포함한 정수로 입력하세요.") {
        updateDiscountTotal(value)
    }
    fun updateTaxTotal(value: String) = applyEdit("세금은 정수로 입력하세요.") { updateTaxTotal(value) }
    fun updateFeeTotal(value: String) = applyEdit("수수료는 정수로 입력하세요.") { updateFeeTotal(value) }

    fun updatePaymentMethod(index: Int, value: String) = applyEdit { updatePaymentMethod(index, value) }
    fun updatePaymentAmount(index: Int, value: String) = applyEdit("결제 금액은 정수로 입력하세요.") {
        updatePaymentAmount(index, value)
    }

    fun updateLineDescription(lineId: String, value: String) = applyEdit {
        updateLineDescription(lineId, value)
    }

    fun updateLineType(lineId: String, value: ReceiptLineType) = applyEdit {
        updateLineType(lineId, value)
    }

    fun updateMerchantSku(lineId: String, value: String) = applyEdit {
        updateMerchantSku(lineId, value)
    }

    fun updateLineQuantity(lineId: String, value: String) = applyEdit("수량은 정확한 숫자로 입력하세요.") {
        updateLineQuantity(lineId, value)
    }

    fun updateLineUnitPrice(lineId: String, value: String) = applyEdit("단가는 정수 금액으로 입력하세요.") {
        updateLineUnitPrice(lineId, value)
    }

    fun updateLineNetAmount(lineId: String, value: String) = applyEdit("행 금액은 정수로 입력하세요.") {
        updateLineNetAmount(lineId, value)
    }

    fun updateReconciliationReason(value: String) {
        val controller = reviewController ?: return
        controller.setReconciliationReason(value)
        refreshAfterEdit(null)
    }

    fun showAiCorrection() {
        val state = mutableUiState.value
        if (state.receipt == null || state.ocrDocument == null) {
            mutableUiState.value = state.copy(message = "보존된 OCR 근거가 있어야 Gemini 제안을 요청할 수 있습니다.")
            return
        }
        mutableUiState.value = state.copy(
            screen = AppScreen.AI_CORRECTION,
            correctionProvider = correctionSuggester.provider,
            message = null,
        )
    }

    fun saveGeminiApiKey(rawApiKey: String) {
        val saved = geminiApiKeyStore.save(rawApiKey) && geminiApiKeyStore.isConfigured()
        mutableUiState.value = mutableUiState.value.copy(
            correctionProvider = correctionSuggester.provider,
            message = if (saved) {
                "Gemini API 키를 이 기기에 암호화 저장했습니다."
            } else {
                "API 키를 저장하지 못했습니다. 공백 없는 20~512자 키인지 확인하세요."
            },
        )
    }

    fun clearGeminiApiKey() {
        val cleared = geminiApiKeyStore.clear()
        mutableUiState.value = mutableUiState.value.copy(
            correctionProvider = correctionSuggester.provider,
            aiCorrectionCandidates = emptyList(),
            rejectedAiCorrectionCount = 0,
            message = if (cleared) {
                "이 기기에 저장된 Gemini API 키를 삭제했습니다."
            } else {
                "API 키 삭제를 완전히 확인하지 못했습니다. 앱을 다시 열어 구성 상태를 확인하세요."
            },
        )
    }

    fun requestAiCorrections() {
        val state = mutableUiState.value
        val receipt = state.receipt ?: return
        val ocrDocument = state.ocrDocument ?: return
        if (state.isRequestingAiCorrections) return
        if (!correctionSuggester.provider.isAvailable) {
            mutableUiState.value = state.copy(
                message = correctionSuggester.provider.unavailableReason
                    ?: "Gemini 교정 제안기가 구성되지 않았습니다.",
            )
            return
        }
        val requestedDocumentId = receipt.document.id
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(
                screen = AppScreen.AI_CORRECTION,
                isRequestingAiCorrections = true,
                aiCorrectionCandidates = emptyList(),
                rejectedAiCorrectionCount = 0,
                message = null,
            )
            val baseRequest = ReceiptCorrectionRequestFactory.create(receipt, ocrDocument)
            if (baseRequest.targets.isEmpty()) {
                mutableUiState.value = mutableUiState.value.copy(
                    isRequestingAiCorrections = false,
                    message = "Gemini에 보낼 수 있는 기존 OCR 상품 행이 없습니다.",
                )
                return@launch
            }
            val outcome = try {
                val pages = repository.getPages(requestedDocumentId)
                val request = try {
                    ReceiptCorrectionEvidenceCropper.attachEvidenceImages(
                        request = baseRequest,
                        pages = pages,
                        ocrDocument = ocrDocument,
                        fileStore = fileStore,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    baseRequest
                }
                correctionSuggester.suggest(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.PROVIDER)
            }
            val latestState = mutableUiState.value
            if (
                latestState.receipt?.document?.id != requestedDocumentId ||
                latestState.ocrDocument !== ocrDocument
            ) {
                return@launch
            }
            when (outcome) {
                is ReceiptCorrectionOutcome.Success -> {
                    val currentReceipt = reviewController?.state?.value?.receipt ?: receipt
                    val validated = ReceiptCorrectionPolicy.validateBatch(
                        currentReceipt,
                        ocrDocument,
                        outcome.batch.candidates,
                    )
                    mutableUiState.value = mutableUiState.value.copy(
                        isRequestingAiCorrections = false,
                        aiCorrectionCandidates = validated.accepted,
                        rejectedAiCorrectionCount = validated.rejected.size,
                        message = if (validated.accepted.isEmpty()) {
                            "근거·산술 검사를 통과한 Gemini 교정 제안이 없습니다."
                        } else {
                            "Gemini 제안 ${validated.accepted.size}건을 받았습니다. 원본과 대조한 뒤 개별 적용하세요."
                        },
                    )
                }
                is ReceiptCorrectionOutcome.Failure -> {
                    mutableUiState.value = mutableUiState.value.copy(
                        isRequestingAiCorrections = false,
                        message = aiCorrectionFailureMessage(outcome.reason),
                    )
                }
            }
        }
    }

    fun applyAiCorrection(candidateId: String) {
        val state = mutableUiState.value
        val candidate = state.aiCorrectionCandidates.firstOrNull { it.id == candidateId } ?: return
        val receipt = reviewController?.state?.value?.receipt ?: return
        val ocrDocument = state.ocrDocument ?: return
        val rejection = ReceiptCorrectionPolicy.rejectionReason(receipt, ocrDocument, candidate)
        if (rejection != null) {
            mutableUiState.value = state.copy(
                aiCorrectionCandidates = state.aiCorrectionCandidates.filterNot { it.id == candidateId },
                message = "초안이 바뀌어 이 Gemini 제안은 더 이상 적용할 수 없습니다.",
            )
            return
        }
        if (reviewController?.applyCorrectionSuggestion(candidate) != true) {
            mutableUiState.value = state.copy(message = "Gemini 제안을 적용하지 못했습니다.")
            return
        }
        mutableUiState.value = state.copy(
            aiCorrectionCandidates = state.aiCorrectionCandidates.filterNot { it.id == candidateId },
        )
        refreshAfterEdit("Gemini 제안을 초안에 적용했습니다. 원본 이미지를 직접 확인하세요.")
    }

    fun dismissAiCorrection(candidateId: String) {
        mutableUiState.value = mutableUiState.value.copy(
            aiCorrectionCandidates = mutableUiState.value.aiCorrectionCandidates.filterNot { it.id == candidateId },
        )
    }

    /** Adds a row the reviewer read on the paper receipt but OCR never produced. */
    fun addLineItem(afterLineItemId: String? = null) {
        val controller = reviewController ?: return
        controller.addLineItem(afterLineItemId)
        refreshAfterEdit("직접 입력 행을 추가했습니다. 원본을 보고 상품명·수량·금액을 채우세요.")
    }

    fun removeLineItem(lineItemId: String) {
        val controller = reviewController ?: return
        if (!controller.removeLineItem(lineItemId)) return
        refreshAfterEdit("행을 삭제했습니다. 되돌리기로 복구할 수 있습니다.")
    }

    fun undo() {
        val controller = reviewController ?: return
        if (!controller.undo()) return
        refreshAfterEdit("마지막 수정을 되돌렸습니다. 되돌린 내역도 수정 이력에 남습니다.")
    }

    fun redo() {
        val controller = reviewController ?: return
        if (!controller.redo()) return
        refreshAfterEdit("되돌린 수정을 다시 적용했습니다.")
    }

    fun setShowOnlyAttentionItems(value: Boolean) {
        val state = mutableUiState.value
        mutableUiState.value = state.copy(
            showOnlyAttentionItems = value,
            attentionFilterIds = if (value) state.progress?.attentionLineItemIds.orEmpty() else emptyList(),
        )
    }

    /**
     * Applies a reconciliation candidate as the reviewer's own edit. The suggestion is never applied on
     * its own, and the resulting value is recorded exactly like a manual correction.
     */
    fun applyReconciliationSuggestion(suggestion: ReconciliationSuggestion) {
        val controller = reviewController ?: return
        when (suggestion) {
            is ReconciliationSuggestion.SetLineNetAmount -> {
                if (!controller.updateLineNetAmount(suggestion.lineItemId, suggestion.amountMinor.toString())) return
                refreshAfterEdit("행 금액을 ${suggestion.amountMinor}으로 바꿨습니다. 원본과 다시 대조하세요.")
            }
            is ReconciliationSuggestion.SetGrandTotal -> {
                if (!controller.updateGrandTotal(suggestion.amountMinor.toString())) return
                refreshAfterEdit("최종 합계를 ${suggestion.amountMinor}으로 바꿨습니다. 원본과 다시 대조하세요.")
            }
            is ReconciliationSuggestion.RemoveLineItem -> removeLineItem(suggestion.lineItemId)
            is ReconciliationSuggestion.AddLineItem -> {
                controller.addLineItem(type = suggestion.type, netAmountMinor = suggestion.amountMinor)
                refreshAfterEdit(
                    "금액 ${suggestion.amountMinor}의 직접 입력 행을 추가했습니다. 원본에서 내용을 확인해 채우세요.",
                )
            }
        }
    }

    fun showImageConfirmation() = navigate(AppScreen.IMAGE_CONFIRM)
    fun showFieldReview() = navigate(AppScreen.FIELD_REVIEW)
    fun showItemReview() = navigate(AppScreen.ITEM_REVIEW)
    fun showAiCorrectionReview() = showAiCorrection()
    fun showReconciliation() = navigate(AppScreen.RECONCILIATION)
    fun showJsonPreview() = navigate(AppScreen.JSON_PREVIEW)
    fun showEvaluation() {
        mutableUiState.value = mutableUiState.value.copy(screen = AppScreen.EVALUATION, message = null)
        buildAccuracyReport()
    }

    /**
     * Builds the field-level error report from receipts the user already confirmed. No separate ground
     * truth is entered: every review correction is itself a label of what the recognizer got wrong.
     */
    fun buildAccuracyReport() {
        if (mutableUiState.value.isBuildingAccuracyReport) return
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(isBuildingAccuracyReport = true)
            val samples = try {
                collectReviewedSamples()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    isBuildingAccuracyReport = false,
                    message = "검수 이력을 읽지 못했습니다.",
                )
                return@launch
            }
            mutableUiState.value = mutableUiState.value.copy(
                isBuildingAccuracyReport = false,
                accuracySummary = samples.takeIf { it.isNotEmpty() }?.let(ReviewAccuracyCalculator::summarize),
                message = if (samples.isEmpty()) {
                    "확정한 영수증이 아직 없습니다. 검수를 마친 영수증이 쌓이면 필드별 오류율이 계산됩니다."
                } else {
                    null
                },
            )
        }
    }

    fun shareAccuracyReport() {
        val summary = mutableUiState.value.accuracySummary ?: return
        viewModelScope.launch {
            try {
                val key = "evaluation/review-accuracy.json"
                fileStore.writeText(
                    key,
                    ReviewAccuracyReportJson.encode(
                        summary = summary,
                        generatedAt = OffsetDateTime.now().toString(),
                        engineName = MlKitDocumentOcrEngine.ENGINE_NAME,
                        engineVersion = MlKitDocumentOcrEngine.ENGINE_VERSION,
                    ),
                )
                mutableEvents.emit(ReceiptUiEvent.ShareAccuracyReport(key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(message = "보고서를 저장하지 못했습니다.")
            }
        }
    }

    private suspend fun collectReviewedSamples(): List<ReviewedReceiptSample> = repository.observeSessions()
        .first()
        .filter { it.reviewedAt != null && it.receiptStorageKey != null }
        .mapNotNull { session ->
            val receipt = try {
                ReceiptV2Json.decode(
                    fileStore.readBytes(requireNotNull(session.receiptStorageKey)).toString(Charsets.UTF_8),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (receipt.document.source.transcriptionStatus != TranscriptionStatus.USER_VERIFIED) {
                return@mapNotNull null
            }
            ReviewedReceiptSample(
                documentId = session.documentId,
                parserVersion = receipt.document.source.parserVersion(),
                receipt = receipt,
                corrections = repository.getEdits(session.documentId).map { it.toFieldCorrection() },
                ocrCompletedAt = session.ocrCompletedAt,
                reviewedAt = session.reviewedAt,
            )
        }

    fun confirmUserVerified() {
        val controller = reviewController ?: return
        controller.markUserVerified()
            .onSuccess {
                val verifiedAt = OffsetDateTime.now().toString()
                mutableUiState.value = stateFromReview(
                    screen = AppScreen.JSON_PREVIEW,
                    ocrDocument = mutableUiState.value.ocrDocument,
                    message = "필수 검증을 통과했습니다. user_verified JSON을 내보낼 수 있습니다.",
                    reviewedAt = verifiedAt,
                )
                persistDraft()
            }
            .onFailure {
                mutableUiState.value = stateFromReview(
                    screen = AppScreen.RECONCILIATION,
                    ocrDocument = mutableUiState.value.ocrDocument,
                    message = "필수 검증 항목을 먼저 해결하세요.",
                    reviewedAt = mutableUiState.value.reviewedAt,
                )
            }
    }

    fun setIncludeRawTextInShare(include: Boolean) {
        val state = mutableUiState.value
        mutableUiState.value = state.copy(
            includeRawTextInShare = include,
            jsonPreview = state.receipt?.let { receipt ->
                ReceiptV2Json.encodePretty(receipt.forExternalShare(include))
            },
        )
    }

    fun saveVerifiedJson() = exportVerified(shareAfterExport = false)
    fun shareVerifiedJson() = exportVerified(shareAfterExport = true)

    fun goBack(): Boolean {
        val state = mutableUiState.value
        val destination = when (state.screen) {
            AppScreen.SESSION_LIST -> return false
            AppScreen.IMAGE_CONFIRM -> AppScreen.SESSION_LIST
            AppScreen.OCR_PROGRESS -> return true
            AppScreen.FIELD_REVIEW -> AppScreen.IMAGE_CONFIRM
            AppScreen.ITEM_REVIEW -> AppScreen.FIELD_REVIEW
            AppScreen.AI_CORRECTION -> AppScreen.ITEM_REVIEW
            AppScreen.RECONCILIATION -> AppScreen.ITEM_REVIEW
            AppScreen.JSON_PREVIEW -> AppScreen.RECONCILIATION
            AppScreen.NUTRITION_REVIEW -> AppScreen.IMAGE_CONFIRM
            AppScreen.EVALUATION -> AppScreen.SESSION_LIST
        }
        if (destination == AppScreen.SESSION_LIST) {
            selectedDocumentId.value = null
            mutableUiState.value = homeState(workflow = state.selectedWorkflow, message = state.message)
        } else {
            mutableUiState.value = state.copy(screen = destination, message = null)
        }
        return true
    }

    fun resolvePageFile(storageKey: String): File = fileStore.resolveStorageKey(storageKey)

    fun clearMessage() {
        mutableUiState.value = mutableUiState.value.copy(message = null)
    }

    override fun onCleared() {
        nutritionPersistenceJob?.cancel()
        ocrEngine.close()
    }

    private suspend fun processReceiptOcrSuccess(session: ReceiptSession, document: OcrDocument) {
        val parsedReceipt = parser.parse(document)
        val parsedDraft = parsedReceipt.toReceiptV2(includePrivateRawText = true)
        val receipt = parsedDraft.copy(
            document = parsedDraft.document.copy(
                source = parsedDraft.document.source.withParserVersion(parser.version),
            ),
        )
        fileStore.writeText(
            "${session.documentId}/ocr/ocr-debug.json",
            OcrDebugJson.encode(document, parsedReceipt),
        )
        val draftKey = "${session.documentId}/draft/receipt.json"
        fileStore.writeText(draftKey, ReceiptV2Json.encodeCanonical(receipt))
        val draftReadyAt = OffsetDateTime.now().toString()
        repository.updateSession(
            session.copy(
                updatedAt = draftReadyAt,
                ocrStatus = "parsed",
                reviewStatus = "draft",
                uploadStatus = "local_only",
                lastError = null,
                merchantName = receipt.merchant.name,
                displayTitle = receipt.merchant.name,
                issuedOn = receipt.document.issuedOn,
                grandTotalAmountMinor = receipt.totals.grandTotalAmountMinor,
                receiptStorageKey = draftKey,
                workflowDraftStorageKey = draftKey,
                reviewedAt = null,
                ocrCompletedAt = draftReadyAt,
            ),
        )
        reviewController = ReceiptReviewController(receipt)
        persistedEditIds.clear()
        mutableUiState.value = mutableUiState.value.copy(
            aiCorrectionCandidates = emptyList(),
            rejectedAiCorrectionCount = 0,
        )
        mutableUiState.value = stateFromReview(
            screen = AppScreen.FIELD_REVIEW,
            ocrDocument = document,
            message = "OCR 초안을 만들었습니다. 원본 이미지와 대조해 직접 검수하세요.",
        )
    }

    private suspend fun processNutritionOcrSuccess(session: ReceiptSession, document: OcrDocument) {
        val draft = nutritionParser.parse(document)
        val debugKey = "${session.documentId}/ocr/ocr-debug.json"
        val draftKey = "${session.documentId}/draft/fitness-nutrition.json"
        fileStore.writeText(debugKey, OcrDebugJson.encode(document))
        val encoded = NutritionLabelJson.encode(draft)
        fileStore.writeText(draftKey, encoded)
        val draftReadyAt = OffsetDateTime.now().toString()
        repository.updateSession(
            session.copy(
                updatedAt = draftReadyAt,
                ocrStatus = "parsed",
                reviewStatus = draft.status.wireValue,
                jsonRevision = StableIds.sha256(encoded),
                uploadStatus = "local_only",
                lastError = null,
                displayTitle = draft.productName.takeIf(String::isNotBlank),
                workflowDraftStorageKey = draftKey,
                reviewedAt = null,
                ocrCompletedAt = draftReadyAt,
            ),
        )
        reviewController = null
        persistedEditIds.clear()
        mutableUiState.value = nutritionState(
            draft = draft,
            ocrDocument = document,
            message = "영양성분 OCR 초안을 만들었습니다. 모르는 값은 0으로 채우지 말고 원본과 대조하세요.",
        )
    }

    private suspend fun restoreOcrDocument(documentId: String): OcrDocument? = try {
        val key = "$documentId/ocr/ocr-debug.json"
        OcrDebugJson.decode(fileStore.readBytes(key).toString(Charsets.UTF_8))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun updateNutritionDraft(operation: NutritionLabelDraft.() -> NutritionLabelDraft) {
        val current = mutableUiState.value
        val draft = current.nutritionDraft ?: return
        val updated = draft.operation()
        mutableUiState.value = nutritionState(
            draft = updated,
            ocrDocument = current.ocrDocument,
            message = null,
        )
        persistNutritionDraft(updated)
    }

    private fun persistNutritionDraft(draft: NutritionLabelDraft) {
        nutritionPersistenceJob?.cancel()
        nutritionPersistenceJob = viewModelScope.launch {
            try {
                persistNutritionDraftNow(draft, uploadStatus = "local_only")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    message = "영양성분 변경 내용을 저장하지 못했습니다. 현재 화면 값은 유지됩니다.",
                )
            }
        }
    }

    private suspend fun persistNutritionDraftNow(draft: NutritionLabelDraft, uploadStatus: String) {
        persistenceMutex.withLock {
            val key = "${draft.documentId}/draft/fitness-nutrition.json"
            val encoded = NutritionLabelJson.encode(draft)
            fileStore.writeText(key, encoded)
            val session = requireNotNull(repository.getSession(draft.documentId))
            repository.updateSession(
                session.copy(
                    updatedAt = OffsetDateTime.now().toString(),
                    reviewStatus = draft.status.wireValue,
                    jsonRevision = StableIds.sha256(encoded),
                    uploadStatus = uploadStatus,
                    lastError = null,
                    displayTitle = draft.productName.takeIf(String::isNotBlank),
                    workflowDraftStorageKey = key,
                    reviewedAt = draft.confirmedAt.takeIf {
                        draft.status == NutritionDraftStatus.USER_VERIFIED
                    },
                ),
            )
        }
    }

    private suspend fun updateNutritionSessionPublication(
        draft: NutritionLabelDraft,
        uploadStatus: String,
        lastError: String?,
    ) {
        val session = repository.getSession(draft.documentId) ?: return
        repository.updateSession(
            session.copy(
                updatedAt = OffsetDateTime.now().toString(),
                reviewStatus = draft.status.wireValue,
                uploadStatus = uploadStatus,
                lastError = lastError,
                retryCount = if (lastError == null) session.retryCount else session.retryCount + 1,
                reviewedAt = draft.confirmedAt,
            ),
        )
    }

    private fun nutritionState(
        draft: NutritionLabelDraft,
        ocrDocument: OcrDocument?,
        message: String?,
    ): ReceiptAppUiState {
        val current = mutableUiState.value
        return current.copy(
            screen = AppScreen.NUTRITION_REVIEW,
            selectedWorkflow = OcrWorkflowType.FITNESS_NUTRITION,
            isPreparingScanner = false,
            isImportingPages = false,
            isProcessingOcr = false,
            isNutritionSigningIn = false,
            isNutritionPublishing = false,
            currentDocumentId = draft.documentId,
            receipt = null,
            validation = null,
            progress = null,
            diagnosis = null,
            nutritionDraft = draft,
            nutritionValidationErrors = NutritionLabelValidator.validate(draft).errors,
            ocrDocument = ocrDocument,
            message = message,
        )
    }

    private fun homeState(
        workflow: OcrWorkflowType,
        screen: AppScreen = AppScreen.SESSION_LIST,
        currentDocumentId: String? = null,
        message: String? = null,
        possibleDuplicatePageIds: List<String> = emptyList(),
    ): ReceiptAppUiState {
        val config = nutritionSupabaseStore.read()
        return ReceiptAppUiState(
            screen = screen,
            selectedWorkflow = workflow,
            currentDocumentId = currentDocumentId,
            message = message,
            possibleDuplicatePageIds = possibleDuplicatePageIds,
            nutritionSupabaseUrl = config.url,
            isNutritionPublishableKeyConfigured = config.publishableKey.isNotBlank(),
            nutritionSignedInEmail = config.email.takeIf { config.isSignedIn },
        )
    }

    private fun refreshNutritionConnectionState(message: String? = mutableUiState.value.message) {
        val config = nutritionSupabaseStore.read()
        mutableUiState.value = mutableUiState.value.copy(
            isNutritionSigningIn = false,
            nutritionSupabaseUrl = config.url,
            isNutritionPublishableKeyConfigured = config.publishableKey.isNotBlank(),
            nutritionSignedInEmail = config.email.takeIf { config.isSignedIn },
            message = message,
        )
    }

    private fun navigate(screen: AppScreen) {
        val state = mutableUiState.value
        if (screen != AppScreen.IMAGE_CONFIRM && screen != AppScreen.SESSION_LIST && state.receipt == null) return
        mutableUiState.value = state.copy(
            screen = screen,
            message = null,
            jsonPreview = state.receipt?.let { ReceiptV2Json.encodePretty(it.forExternalShare(state.includeRawTextInShare)) },
        )
    }

    private fun applyEdit(
        invalidMessage: String = "값을 수정하지 못했습니다.",
        operation: ReceiptReviewController.() -> Boolean,
    ) {
        val controller = reviewController ?: return
        if (!controller.operation()) {
            mutableUiState.value = mutableUiState.value.copy(message = invalidMessage)
            return
        }
        refreshAfterEdit(null)
    }

    private fun refreshAfterEdit(message: String?) {
        mutableUiState.value = stateFromReview(
            screen = mutableUiState.value.screen,
            ocrDocument = mutableUiState.value.ocrDocument,
            message = message,
            reviewedAt = null,
        )
        persistDraft()
    }

    private fun stateFromReview(
        screen: AppScreen,
        ocrDocument: OcrDocument?,
        message: String?,
        reviewedAt: String? = null,
    ): ReceiptAppUiState {
        val review = requireNotNull(reviewController).state.value
        val current = mutableUiState.value
        val validation = ReceiptValidator.validateForUserVerification(
            review.receipt,
            review.reconciliationReason,
        )
        return current.copy(
            screen = screen,
            selectedWorkflow = OcrWorkflowType.PRICE_TRACE_RECEIPT,
            isPreparingScanner = false,
            isImportingPages = false,
            isProcessingOcr = false,
            isExporting = false,
            currentDocumentId = review.receipt.document.id,
            receipt = review.receipt,
            nutritionDraft = null,
            nutritionValidationErrors = emptyList(),
            ocrDocument = ocrDocument,
            validation = validation,
            progress = ReceiptReviewProgress.of(review.receipt, validation),
            diagnosis = ReconciliationDiagnostics.analyze(review.receipt),
            correctionProvider = correctionSuggester.provider,
            canUndo = review.canUndo,
            canRedo = review.canRedo,
            reconciliationReason = review.reconciliationReason,
            jsonPreview = ReceiptV2Json.encodePretty(
                review.receipt.forExternalShare(current.includeRawTextInShare),
            ),
            message = message,
            reviewedAt = reviewedAt,
        )
    }

    private fun persistDraft() {
        viewModelScope.launch {
            try {
                val newlyPersistedEditIds = persistenceMutex.withLock {
                    val review = reviewController?.state?.value ?: return@withLock emptyList<String>()
                    val receipt = review.receipt
                    val key = "${receipt.document.id}/draft/receipt.json"
                    val pendingEdits = review.edits.filterNot { it.id in persistedEditIds }
                    fileStore.writeText(key, ReceiptV2Json.encodeCanonical(receipt))
                    val session = repository.getSession(receipt.document.id) ?: return@withLock emptyList<String>()
                    repository.persistDraftSnapshot(
                        session.copy(
                            updatedAt = OffsetDateTime.now().toString(),
                            reviewStatus = receipt.document.source.transcriptionStatus.wireValue,
                            jsonRevision = ReceiptV2Json.revisionHash(receipt),
                            merchantName = receipt.merchant.name,
                            displayTitle = receipt.merchant.name,
                            issuedOn = receipt.document.issuedOn,
                            grandTotalAmountMinor = receipt.totals.grandTotalAmountMinor,
                            receiptStorageKey = key,
                            workflowDraftStorageKey = key,
                            reviewedAt = mutableUiState.value.reviewedAt.takeIf {
                                receipt.document.source.transcriptionStatus == TranscriptionStatus.USER_VERIFIED
                            },
                            lastError = null,
                        ),
                        pendingEdits,
                    )
                    pendingEdits.map { it.id }
                }
                persistedEditIds += newlyPersistedEditIds
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordDraftPersistenceFailure()
            }
        }
    }

    private fun exportVerified(shareAfterExport: Boolean) {
        val state = mutableUiState.value
        val receipt = state.receipt ?: return
        if (state.isExporting) return
        if (receipt.document.source.transcriptionStatus != TranscriptionStatus.USER_VERIFIED) {
            mutableUiState.value = state.copy(message = "user_verified 확정 후에만 외부로 내보낼 수 있습니다.")
            return
        }
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(isExporting = true, message = null)
            try {
                val bundle = persistenceMutex.withLock {
                    val exportedReceipt = receipt.forExternalShare(state.includeRawTextInShare)
                    val pages = repository.getPages(receipt.document.id)
                    val bundle = exportService.export(
                        receipt = exportedReceipt,
                        pages = pages,
                        ocrEngine = state.ocrDocument?.engine ?: com.pricetrace.receiptscanner.ocr.OcrEngineInfo(
                            MlKitDocumentOcrEngine.ENGINE_NAME,
                            MlKitDocumentOcrEngine.ENGINE_VERSION,
                        ),
                        parserVersion = receipt.document.source.parserVersion() ?: "unknown",
                        reviewedAt = state.reviewedAt,
                        ocrDocument = state.ocrDocument,
                    )
                    val publication = publisher.finalizeVerifiedReceipt(
                        receipt.document.id,
                        exportedReceipt,
                        bundle.idempotencyKey,
                    )
                    val session = requireNotNull(repository.getSession(receipt.document.id))
                    repository.updateSession(
                        session.copy(
                            updatedAt = OffsetDateTime.now().toString(),
                            jsonRevision = bundle.jsonRevision,
                            exportStatus = "exported",
                            uploadStatus = publication.state.wireValue,
                            receiptStorageKey = bundle.receipt.storageKey,
                            manifestStorageKey = bundle.manifest.storageKey,
                            reviewedAt = session.reviewedAt,
                            lastError = null,
                        ),
                    )
                    bundle
                }
                mutableUiState.value = mutableUiState.value.copy(
                    isExporting = false,
                    message = if (shareAfterExport) {
                        "공유할 JSON을 생성했습니다. 원본 이미지와 OCR debug는 포함되지 않습니다."
                    } else {
                        "검증본을 앱 전용 저장소에 저장했습니다. revision ${bundle.jsonRevision.take(12)}…"
                    },
                )
                if (shareAfterExport) mutableEvents.emit(ReceiptUiEvent.ShareJson(bundle.receipt.storageKey))
            } catch (cancelled: CancellationException) {
                mutableUiState.value = mutableUiState.value.copy(isExporting = false, message = null)
                throw cancelled
            } catch (error: Exception) {
                try {
                    val session = repository.getSession(receipt.document.id)
                    if (session != null) {
                        repository.updateSession(
                            session.copy(
                                updatedAt = OffsetDateTime.now().toString(),
                                exportStatus = "failed",
                                lastError = error::class.java.simpleName,
                                retryCount = session.retryCount + 1,
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The original export failure remains the actionable error; UI recovery must still complete.
                }
                mutableUiState.value = mutableUiState.value.copy(
                    isExporting = false,
                    message = "JSON 내보내기에 실패했습니다. 원본 세션은 유지됩니다.",
                )
            }
        }
    }

    private suspend fun recordDraftPersistenceFailure() {
        val documentId = reviewController?.state?.value?.receipt?.document?.id
        if (documentId != null) {
            val session = repository.getSession(documentId)
            if (session != null) {
                try {
                    repository.updateSession(
                        session.copy(
                            updatedAt = OffsetDateTime.now().toString(),
                            lastError = "draft_persist_failed",
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
            }
        }
        mutableUiState.value = mutableUiState.value.copy(
            message = "변경 내용을 저장하지 못했습니다. 현재 화면은 유지되므로 다시 시도하세요.",
        )
    }

    private suspend fun recordOcrFailure(documentId: String, reason: String) {
        try {
            val session = repository.getSession(documentId)
            if (session != null) {
                repository.updateSession(
                    session.copy(
                        updatedAt = OffsetDateTime.now().toString(),
                        ocrStatus = "failed",
                        lastError = reason,
                        retryCount = session.retryCount + 1,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the UI recoverable even if recording the failure also fails.
        }
        mutableUiState.value = mutableUiState.value.copy(
            screen = AppScreen.IMAGE_CONFIRM,
            isProcessingOcr = false,
            message = ocrFailureMessage(reason),
        )
    }

    private suspend fun cleanEmptyPendingSessionIfNeeded() {
        val documentId = pendingDocumentId ?: return
        if (pendingSessionWasNew && repository.getPages(documentId).isEmpty()) {
            repository.deleteSession(documentId)
            selectedDocumentId.value = null
        }
    }

    private fun clearPendingCapture() {
        pendingDocumentId = null
        pendingSessionWasNew = false
    }

    private fun ReceiptV2.forExternalShare(includeRawText: Boolean): ReceiptV2 = copy(
        document = document.copy(
            source = document.source.copy(rawText = document.source.rawText.takeIf { includeRawText }),
        ),
    )

    private fun captureFailureMessage(reason: String): String = when (reason) {
        "unsupported" -> "이 기기는 ML Kit 문서 스캐너를 지원하지 않습니다."
        "model_download_required" -> "문서 스캐너 구성요소 다운로드가 필요합니다. 네트워크 연결 후 다시 시도하세요."
        "user_cancelled" -> "스캔을 취소했습니다."
        else -> "스캔 결과를 가져오지 못했습니다. 다시 시도하세요."
    }

    private fun ocrFailureMessage(reason: String): String = when (reason) {
        "model_download_required" -> "한국어 OCR 모델을 사용할 수 없습니다. 네트워크 연결 후 다시 시도하세요."
        "image_decode_failed", "image_read_failed" -> "보존된 이미지를 읽지 못했습니다. 원본 페이지를 확인하세요."
        "parse_or_persist_failed" -> "OCR 결과를 초안으로 저장하지 못했습니다. 세션을 보존했으므로 다시 시도하세요."
        else -> "OCR 처리에 실패했습니다. 세션을 보존했으므로 다시 시도할 수 있습니다."
    }

    private fun nutritionFailureMessage(reason: NutritionGatewayFailure): String = when (reason) {
        NutritionGatewayFailure.NOT_CONFIGURED ->
            "Nutrition Supabase 연결 정보를 저장하고 Fitness 계정으로 로그인하세요."
        NutritionGatewayFailure.AUTHENTICATION ->
            "Nutrition DB 인증이 만료되었거나 로그인 정보가 올바르지 않습니다. 다시 로그인하세요."
        NutritionGatewayFailure.RATE_LIMITED ->
            "Nutrition DB 요청 한도에 도달했습니다. 잠시 후 다시 시도하세요."
        NutritionGatewayFailure.NETWORK ->
            "네트워크 문제로 Nutrition DB에 연결하지 못했습니다."
        NutritionGatewayFailure.CONTRACT ->
            "Nutrition DB 계약 또는 마이그레이션 상태가 앱이 기대하는 최신 형식과 다릅니다."
        NutritionGatewayFailure.CONFLICT ->
            "Fitness App에서 같은 식품이 먼저 수정되어 덮어쓰지 않았습니다. 최신 값을 확인한 뒤 다시 시도하세요."
        NutritionGatewayFailure.SERVER ->
            "Nutrition DB 서버가 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요."
    }

    private fun aiCorrectionFailureMessage(reason: ReceiptCorrectionFailureReason): String = when (reason) {
        ReceiptCorrectionFailureReason.NOT_CONFIGURED -> "Gemini API 키를 먼저 저장하세요."
        ReceiptCorrectionFailureReason.NO_ELIGIBLE_EVIDENCE -> "Gemini에 보낼 수 있는 OCR 상품 행 근거가 없습니다."
        ReceiptCorrectionFailureReason.AUTHENTICATION -> "Gemini API 키가 유효하지 않거나 이 API를 사용할 권한이 없습니다."
        ReceiptCorrectionFailureReason.RATE_LIMITED -> "Gemini API 요청 한도에 도달했습니다. 할당량을 확인한 뒤 다시 시도하세요."
        ReceiptCorrectionFailureReason.NETWORK -> "네트워크 문제로 Gemini 제안을 받지 못했습니다."
        ReceiptCorrectionFailureReason.PROVIDER -> "Gemini API 요청이 실패했습니다. 모델 가용성과 서비스 상태를 확인하세요."
        ReceiptCorrectionFailureReason.INVALID_RESPONSE -> "Gemini 응답이 교정 제안 형식과 맞지 않습니다."
    }

    companion object {
        private const val PENDING_DOCUMENT_ID = "pending_document_id"
        private const val PENDING_SESSION_WAS_NEW = "pending_session_was_new"
    }
}
