package com.pricetrace.receiptocr

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import com.pricetrace.receiptocr.BuildConfig
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionCandidate
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionProvider
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceAssessment
import com.pricetrace.receiptscanner.correction.ReceiptEvidenceVerdict
import com.pricetrace.receiptscanner.correction.ReceiptFieldCheck
import com.pricetrace.receiptscanner.correction.ReceiptFieldVerdict
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptEvaluationCalculator
import com.pricetrace.receiptscanner.domain.ReceiptEvaluationSample
import com.pricetrace.receiptscanner.domain.ReceiptEvaluationSummary
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.ReceiptReviewProgress
import com.pricetrace.receiptscanner.domain.ReceiptValidationResult
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.PlaceResolutionStatus
import com.pricetrace.receiptscanner.domain.ReconciliationDiagnosis
import com.pricetrace.receiptscanner.domain.ReconciliationHypothesis
import com.pricetrace.receiptscanner.domain.ReconciliationSuggestion
import com.pricetrace.receiptscanner.domain.ReviewAccuracySummary
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.domain.ValidationSeverity
import com.pricetrace.receiptscanner.domain.isUserEntered
import com.pricetrace.receiptscanner.domain.purchaseLocalTime
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.nutrition.NutritionContract
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionCandidate
import com.pricetrace.receiptscanner.nutrition.NutritionEvidenceAssessment
import com.pricetrace.receiptscanner.nutrition.NutritionEvidenceVerdict
import com.pricetrace.receiptscanner.nutrition.NutritionFieldVerdict
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.nutrition.NutritionUnit
import com.pricetrace.receiptscanner.publisher.PriceObservationProduct
import com.pricetrace.receiptscanner.publisher.PriceObservationSource
import com.pricetrace.receiptscanner.preflight.ReceiptAiReviewStatus
import com.pricetrace.receiptscanner.preflight.ReceiptPreflightDecision
import com.pricetrace.receiptscanner.preflight.ReceiptPreflightReason
import com.pricetrace.receiptscanner.preflight.ReceiptPreflightRoute
import com.pricetrace.receiptscanner.storage.PriceObservationQueueStatus
import com.pricetrace.receiptscanner.storage.ReceiptSession
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import java.util.Locale

@Suppress("LongParameterList")
@Composable
fun ReceiptOcrContent(
    uiState: ReceiptAppUiState,
    sessions: List<ReceiptSession> = emptyList(),
    pages: List<ReceiptPage> = emptyList(),
    resolvePageFile: (String) -> File = ::File,
    onScan: () -> Unit = {},
    onPickImages: () -> Unit = {},
    onWorkflowSelected: (OcrWorkflowType) -> Unit = {},
    onAppendScan: () -> Unit = {},
    onAppendPickImages: () -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onShowApiSettings: () -> Unit = {},
    onStartOcr: () -> Unit = {},
    onNutritionProductNameChanged: (String) -> Unit = {},
    onNutritionBrandChanged: (String) -> Unit = {},
    onNutritionCategoryChanged: (String) -> Unit = {},
    onNutritionBasisAmountChanged: (String) -> Unit = {},
    onNutritionBasisUnitChanged: (String) -> Unit = {},
    onNutritionValueChanged: (NutritionField, String) -> Unit = { _, _ -> },
    onSaveNutritionConnection: (String, String) -> Unit = { _, _ -> },
    onSignInNutrition: (String, String) -> Unit = { _, _ -> },
    onConfirmAndPublishNutrition: () -> Unit = {},
    onRequestNutritionAiCorrections: () -> Unit = {},
    onApplyNutritionAiCorrection: (String) -> Unit = {},
    onDismissNutritionAiCorrection: (String) -> Unit = {},
    onSavePriceTraceConnection: (String, String) -> Unit = { _, _ -> },
    onSignInPriceTrace: (String, String) -> Unit = { _, _ -> },
    onMerchantNameChanged: (String) -> Unit = {},
    onBranchNameChanged: (String) -> Unit = {},
    onBusinessRegistrationNumberChanged: (String) -> Unit = {},
    onAddressChanged: (String) -> Unit = {},
    onPhoneChanged: (String) -> Unit = {},
    onOriginalDocumentIdChanged: (String) -> Unit = {},
    onIssuedOnChanged: (String) -> Unit = {},
    onIssuedLocalTimeChanged: (String) -> Unit = {},
    onIssuedAtChanged: (String) -> Unit = {},
    onCurrencyChanged: (String) -> Unit = {},
    onGrandTotalChanged: (String) -> Unit = {},
    onSubtotalChanged: (String) -> Unit = {},
    onDiscountTotalChanged: (String) -> Unit = {},
    onTaxTotalChanged: (String) -> Unit = {},
    onFeeTotalChanged: (String) -> Unit = {},
    onPaymentMethodChanged: (Int, String) -> Unit = { _, _ -> },
    onPaymentAmountChanged: (Int, String) -> Unit = { _, _ -> },
    onLineDescriptionChanged: (String, String) -> Unit = { _, _ -> },
    onLineTypeChanged: (String, ReceiptLineType) -> Unit = { _, _ -> },
    onMerchantSkuChanged: (String, String) -> Unit = { _, _ -> },
    onLineQuantityChanged: (String, String) -> Unit = { _, _ -> },
    onLineUnitPriceChanged: (String, String) -> Unit = { _, _ -> },
    onLineNetAmountChanged: (String, String) -> Unit = { _, _ -> },
    onAddLineItem: (String?) -> Unit = {},
    onRemoveLineItem: (String) -> Unit = {},
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onShowOnlyAttentionItemsChanged: (Boolean) -> Unit = {},
    onShowAiCorrection: () -> Unit = {},
    onSaveGeminiApiKey: (String) -> Unit = {},
    onClearGeminiApiKey: () -> Unit = {},
    onRequestAiCorrections: () -> Unit = {},
    onContinueAiPreflight: () -> Unit = {},
    onApplyAiCorrection: (String) -> Unit = {},
    onDismissAiCorrection: (String) -> Unit = {},
    onApplySuggestion: (ReconciliationSuggestion) -> Unit = {},
    onReconciliationReasonChanged: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onShowFields: () -> Unit = {},
    onShowItems: () -> Unit = {},
    onShowReconciliation: () -> Unit = {},
    onShowJson: () -> Unit = {},
    onConfirmVerified: () -> Unit = {},
    onShowPriceObservationSubmit: () -> Unit = {},
    onPriceObservationQueryChanged: (String) -> Unit = {},
    onSearchPriceObservationProducts: () -> Unit = {},
    onPriceObservationStoreSelected: (String) -> Unit = {},
    onPriceObservationProductSelected: (String) -> Unit = {},
    onPriceObservationLineItemSelected: (String) -> Unit = {},
    onPriceObservationObservedOnChanged: (String) -> Unit = {},
    onPriceObservationUnitPriceChanged: (String) -> Unit = {},
    onSubmitPriceObservation: () -> Unit = {},
    onSubmitRestaurantReceipt: () -> Unit = {},
    onIncludeRawTextChanged: (Boolean) -> Unit = {},
    onSave: () -> Unit = {},
    onShare: () -> Unit = {},
    onShowEvaluation: () -> Unit = {},
    onRefreshAccuracy: () -> Unit = {},
    onShareAccuracy: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            uiState.message?.let { message ->
                MessageCard(message, onDismissMessage)
            }
            when (uiState.screen) {
                AppScreen.SESSION_LIST -> SessionListScreen(
                    sessions = sessions,
                    selectedWorkflow = uiState.selectedWorkflow,
                    isBusy = uiState.isPreparingScanner || uiState.isImportingPages,
                    onScan = onScan,
                    onPickImages = onPickImages,
                    onWorkflowSelected = onWorkflowSelected,
                    onSelectSession = onSelectSession,
                    onDeleteSession = onDeleteSession,
                    onShowApiSettings = onShowApiSettings,
                    onShowEvaluation = onShowEvaluation,
                )
                AppScreen.API_SETTINGS -> ApiSettingsScreen(
                    provider = uiState.correctionProvider,
                    supabaseUrl = uiState.nutritionSupabaseUrl,
                    isPublishableKeyConfigured = uiState.isNutritionPublishableKeyConfigured,
                    signedInEmail = uiState.nutritionSignedInEmail,
                    isSigningIn = uiState.isNutritionSigningIn,
                    onBack = onBack,
                    onSaveGeminiApiKey = onSaveGeminiApiKey,
                    onClearGeminiApiKey = onClearGeminiApiKey,
                    onSaveNutritionConnection = onSaveNutritionConnection,
                    onSignInNutrition = onSignInNutrition,
                    priceTraceUrl = uiState.priceTraceSupabaseUrl,
                    isPriceTracePublishableKeyConfigured = uiState.isPriceTracePublishableKeyConfigured,
                    priceTraceSignedInEmail = uiState.priceTraceSignedInEmail,
                    isPriceTraceSigningIn = uiState.isPriceTraceSigningIn,
                    onSavePriceTraceConnection = onSavePriceTraceConnection,
                    onSignInPriceTrace = onSignInPriceTrace,
                )
                AppScreen.IMAGE_CONFIRM -> ImageConfirmationScreen(
                    workflow = uiState.selectedWorkflow,
                    pages = pages,
                    duplicateCount = uiState.possibleDuplicatePageIds.size,
                    isBusy = uiState.isPreparingScanner || uiState.isImportingPages,
                    resolvePageFile = resolvePageFile,
                    onBack = onBack,
                    onAppendScan = onAppendScan,
                    onAppendPickImages = onAppendPickImages,
                    onStartOcr = onStartOcr,
                )
                AppScreen.OCR_PROGRESS -> OcrProgressScreen()
                AppScreen.FIELD_REVIEW -> uiState.receipt?.let { receipt ->
                    FieldReviewScreen(
                        receipt = receipt,
                        validation = uiState.validation,
                        progress = uiState.progress,
                        canUndo = uiState.canUndo,
                        canRedo = uiState.canRedo,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        pages = pages,
                        ocrDocument = uiState.ocrDocument,
                        resolvePageFile = resolvePageFile,
                        onBack = onBack,
                        onMerchantNameChanged = onMerchantNameChanged,
                        onBranchNameChanged = onBranchNameChanged,
                        onBusinessRegistrationNumberChanged = onBusinessRegistrationNumberChanged,
                        onAddressChanged = onAddressChanged,
                        onPhoneChanged = onPhoneChanged,
                        onOriginalDocumentIdChanged = onOriginalDocumentIdChanged,
                        onIssuedOnChanged = onIssuedOnChanged,
                        onIssuedLocalTimeChanged = onIssuedLocalTimeChanged,
                        onIssuedAtChanged = onIssuedAtChanged,
                        onCurrencyChanged = onCurrencyChanged,
                        onGrandTotalChanged = onGrandTotalChanged,
                        onSubtotalChanged = onSubtotalChanged,
                        onDiscountTotalChanged = onDiscountTotalChanged,
                        onTaxTotalChanged = onTaxTotalChanged,
                        onFeeTotalChanged = onFeeTotalChanged,
                        onPaymentMethodChanged = onPaymentMethodChanged,
                        onPaymentAmountChanged = onPaymentAmountChanged,
                        onNext = onShowItems,
                    )
                }
                AppScreen.ITEM_REVIEW -> uiState.receipt?.let { receipt ->
                    ItemReviewScreen(
                        receipt = receipt,
                        validation = uiState.validation,
                        progress = uiState.progress,
                        canUndo = uiState.canUndo,
                        canRedo = uiState.canRedo,
                        showOnlyAttentionItems = uiState.showOnlyAttentionItems,
                        attentionFilterIds = uiState.attentionFilterIds,
                        pages = pages,
                        ocrDocument = uiState.ocrDocument,
                        resolvePageFile = resolvePageFile,
                        onBack = onShowFields,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onShowAiCorrection = onShowAiCorrection,
                        onShowOnlyAttentionItemsChanged = onShowOnlyAttentionItemsChanged,
                        onDescriptionChanged = onLineDescriptionChanged,
                        onTypeChanged = onLineTypeChanged,
                        onMerchantSkuChanged = onMerchantSkuChanged,
                        onQuantityChanged = onLineQuantityChanged,
                        onUnitPriceChanged = onLineUnitPriceChanged,
                        onNetAmountChanged = onLineNetAmountChanged,
                        onAddLineItem = onAddLineItem,
                        onRemoveLineItem = onRemoveLineItem,
                        onNext = onShowReconciliation,
                    )
                }
                AppScreen.AI_CORRECTION -> AiCorrectionScreen(
                    provider = uiState.correctionProvider,
                    candidates = uiState.aiCorrectionCandidates,
                    rejectedCount = uiState.rejectedAiCorrectionCount,
                    isLoading = uiState.isRequestingAiCorrections,
                    isPreflight = uiState.isAiPreflight,
                    aiStatus = uiState.aiReviewStatus,
                    assessment = uiState.aiEvidenceAssessment,
                    decision = uiState.preflightDecision,
                    pages = pages,
                    ocrDocument = uiState.ocrDocument,
                    resolvePageFile = resolvePageFile,
                    onBack = onBack,
                    onSaveApiKey = onSaveGeminiApiKey,
                    onClearApiKey = onClearGeminiApiKey,
                    onRequest = onRequestAiCorrections,
                    onContinueReview = onContinueAiPreflight,
                    onRecapture = onScan,
                    onApply = onApplyAiCorrection,
                    onDismiss = onDismissAiCorrection,
                )
                AppScreen.RECONCILIATION -> uiState.receipt?.let { receipt ->
                    ReconciliationScreen(
                        receipt = receipt,
                        validation = uiState.validation,
                        progress = uiState.progress,
                        diagnosis = uiState.diagnosis,
                        canUndo = uiState.canUndo,
                        canRedo = uiState.canRedo,
                        reason = uiState.reconciliationReason.orEmpty(),
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onApplySuggestion = onApplySuggestion,
                        onReasonChanged = onReconciliationReasonChanged,
                        onBack = onShowItems,
                        onPreviewDraft = onShowJson,
                        onConfirmVerified = onConfirmVerified,
                    )
                }
                AppScreen.JSON_PREVIEW -> JsonPreviewScreen(
                    receipt = uiState.receipt,
                    json = uiState.jsonPreview.orEmpty(),
                    includeRawText = uiState.includeRawTextInShare,
                    isExporting = uiState.isExporting,
                    onIncludeRawTextChanged = onIncludeRawTextChanged,
                    onBack = onShowReconciliation,
                    onSave = onSave,
                    onShare = onShare,
                    onShowPriceObservationSubmit = onShowPriceObservationSubmit,
                    isRestaurantReceipt = uiState.selectedWorkflow == OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT,
                )
                AppScreen.PRICE_OBSERVATION_SUBMIT -> uiState.receipt?.let { receipt ->
                    PriceObservationSubmitScreen(
                        receipt = receipt,
                        sources = uiState.priceObservationSources,
                        products = uiState.priceObservationProducts,
                        query = uiState.priceObservationQuery,
                        selectedStoreId = uiState.priceObservationSelectedStoreId,
                        selectedCatalogProductId = uiState.priceObservationSelectedCatalogProductId,
                        selectedLineItemId = uiState.priceObservationSelectedLineItemId,
                        observedOn = uiState.priceObservationObservedOn,
                        unitPriceKrw = uiState.priceObservationUnitPriceKrw,
                        queueStatus = uiState.priceObservationQueueStatus,
                        appliedAction = uiState.priceObservationAppliedAction?.wireValue,
                        lastError = uiState.priceObservationLastError,
                        isSignedIn = uiState.priceTraceSignedInEmail != null,
                        isLoadingSources = uiState.isLoadingPriceObservationSources,
                        isLoadingProducts = uiState.isLoadingPriceObservationProducts,
                        isSubmitting = uiState.isSubmittingPriceObservation,
                        onBack = onBack,
                        onQueryChanged = onPriceObservationQueryChanged,
                        onSearchProducts = onSearchPriceObservationProducts,
                        onStoreSelected = onPriceObservationStoreSelected,
                        onProductSelected = onPriceObservationProductSelected,
                        onLineItemSelected = onPriceObservationLineItemSelected,
                        onObservedOnChanged = onPriceObservationObservedOnChanged,
                        onUnitPriceChanged = onPriceObservationUnitPriceChanged,
                        onSubmit = onSubmitPriceObservation,
                    )
                }
                AppScreen.RESTAURANT_RECEIPT_SUBMIT -> uiState.receipt?.let { receipt ->
                    RestaurantReceiptSubmitScreen(
                        receipt = receipt,
                        signedInEmail = uiState.priceTraceSignedInEmail,
                        isSubmitting = uiState.isSubmittingRestaurantReceipt,
                        submittedReceiptId = uiState.restaurantReceiptId,
                        submittedItemCount = uiState.restaurantReceiptItemCount,
                        replayed = uiState.restaurantReceiptReplayed,
                        lastError = uiState.restaurantReceiptLastError,
                        onBack = onBack,
                        onSubmit = onSubmitRestaurantReceipt,
                    )
                }
                AppScreen.NUTRITION_REVIEW -> uiState.nutritionDraft?.let { draft ->
                    NutritionReviewScreen(
                        draft = draft,
                        validationErrors = uiState.nutritionValidationErrors,
                        pages = pages,
                        resolvePageFile = resolvePageFile,
                        supabaseUrl = uiState.nutritionSupabaseUrl,
                        isPublishableKeyConfigured = uiState.isNutritionPublishableKeyConfigured,
                        signedInEmail = uiState.nutritionSignedInEmail,
                        isSigningIn = uiState.isNutritionSigningIn,
                        isPublishing = uiState.isNutritionPublishing,
                        onBack = onBack,
                        onProductNameChanged = onNutritionProductNameChanged,
                        onBrandChanged = onNutritionBrandChanged,
                        onCategoryChanged = onNutritionCategoryChanged,
                        onBasisAmountChanged = onNutritionBasisAmountChanged,
                        onBasisUnitChanged = onNutritionBasisUnitChanged,
                        onValueChanged = onNutritionValueChanged,
                        onSaveConnection = onSaveNutritionConnection,
                        onSignIn = onSignInNutrition,
                        onConfirmAndPublish = onConfirmAndPublishNutrition,
                        aiProvider = uiState.nutritionAiProvider,
                        aiCandidates = uiState.nutritionAiCandidates,
                        aiRejectedCount = uiState.nutritionAiRejectedCount,
                        isAiLoading = uiState.isRequestingNutritionAiCorrections,
                        aiAssessment = uiState.nutritionAiAssessment,
                        onSaveGeminiApiKey = onSaveGeminiApiKey,
                        onClearGeminiApiKey = onClearGeminiApiKey,
                        onRequestAi = onRequestNutritionAiCorrections,
                        onApplyAi = onApplyNutritionAiCorrection,
                        onDismissAi = onDismissNutritionAiCorrection,
                    )
                }
                AppScreen.EVALUATION -> EvaluationScreen(
                    accuracySummary = uiState.accuracySummary,
                    isBuildingAccuracyReport = uiState.isBuildingAccuracyReport,
                    onRefreshAccuracy = onRefreshAccuracy,
                    onShareAccuracy = onShareAccuracy,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("status_message"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    }
}

@Composable
private fun SessionListScreen(
    sessions: List<ReceiptSession>,
    selectedWorkflow: OcrWorkflowType,
    isBusy: Boolean,
    onScan: () -> Unit,
    onPickImages: () -> Unit,
    onWorkflowSelected: (OcrWorkflowType) -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onShowApiSettings: () -> Unit,
    onShowEvaluation: () -> Unit,
) {
    val visibleSessions = sessions.filter { it.workflowType == selectedWorkflow }
    val isFitness = selectedWorkflow == OcrWorkflowType.FITNESS_NUTRITION
    val isRestaurant = selectedWorkflow == OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("session_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "PRICETRACE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.4.sp,
            )
            Text(
                "OCR",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "촬영한 정보를 직접 확인하고 필요한 곳에만 보냅니다.",
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            WorkflowSelector(
                selectedWorkflow = selectedWorkflow,
                onWorkflowSelected = onWorkflowSelected,
            )
        }
        item {
            MonochromeHologramHero(
                eyebrow = when {
                    isFitness -> "FITNESS NUTRITION"
                    isRestaurant -> "RESTAURANT PRICE OBSERVATION"
                    else -> "PRODUCT PRICE OBSERVATION"
                },
                title = if (isFitness) {
                    "영양 라벨을\n정확하게 기록하세요"
                } else if (isRestaurant) {
                    "식당 영수증을\n메뉴별 가격으로 기록하세요"
                } else {
                    "영수증 가격을\n검증해 기록하세요"
                },
                description = if (isFitness) {
                    "상품 라벨을 인식한 뒤 영양성분을 직접 확인합니다."
                } else if (isRestaurant) {
                    "식당 이름·방문 날짜·메뉴와 옵션 추가 가격을 검수한 뒤 서버에 제출합니다."
                } else {
                    "영수증을 인식하고 항목·합계를 검수한 뒤 결과를 확정합니다."
                },
                footer = "로컬 우선 · 저장된 작업 ${visibleSessions.size}개",
                modifier = Modifier.testTag("home_hologram_hero"),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onScan,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).testTag("scan_button"),
                ) {
                    if (isBusy) BusyIndicator()
                    Text(
                        when {
                            isFitness -> "상품 영양성분 촬영·선택"
                            isRestaurant -> "식당 영수증 촬영·선택"
                            else -> "영수증 촬영·선택"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onPickImages,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).testTag("pick_images_button"),
                ) {
                    Text("기존 사진 선택")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onShowApiSettings,
                    modifier = Modifier.weight(1f).testTag("api_settings_button"),
                ) {
                    Text("연결 설정")
                }
                if (!isFitness) {
                    OutlinedButton(
                        onClick = onShowEvaluation,
                        modifier = Modifier.weight(1f).testTag("evaluation_button"),
                    ) {
                        Text("정확도 평가")
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("최근 작업")
                Text(
                    "${visibleSessions.size}개",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (visibleSessions.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (isFitness) "저장된 영양 라벨이 없습니다." else "저장된 영수증이 없습니다.",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "위 촬영 버튼으로 첫 작업을 시작하세요.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            items(visibleSessions, key = { it.documentId }) { session ->
                SessionCard(
                    session = session,
                    onClick = { onSelectSession(session.documentId) },
                    onDelete = { onDeleteSession(session.documentId) },
                )
            }
        }
    }
}

@Composable
private fun WorkflowSelector(
    selectedWorkflow: OcrWorkflowType,
    onWorkflowSelected: (OcrWorkflowType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WorkflowChoice(
            label = "가격 영수증",
            selected = selectedWorkflow == OcrWorkflowType.PRICE_TRACE_RECEIPT,
            onClick = { onWorkflowSelected(OcrWorkflowType.PRICE_TRACE_RECEIPT) },
            modifier = Modifier.testTag("workflow_pricetrace"),
        )
        WorkflowChoice(
            label = "식당",
            selected = selectedWorkflow == OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT,
            onClick = { onWorkflowSelected(OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT) },
            modifier = Modifier.testTag("workflow_restaurant"),
        )
        WorkflowChoice(
            label = "영양 라벨",
            selected = selectedWorkflow == OcrWorkflowType.FITNESS_NUTRITION,
            onClick = { onWorkflowSelected(OcrWorkflowType.FITNESS_NUTRITION) },
            modifier = Modifier.testTag("workflow_fitness"),
        )
    }
}

@Composable
private fun RowScope.WorkflowChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.weight(1f).heightIn(min = 44.dp),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ApiSettingsScreen(
    provider: ReceiptCorrectionProvider?,
    supabaseUrl: String,
    isPublishableKeyConfigured: Boolean,
    signedInEmail: String?,
    isSigningIn: Boolean,
    onBack: () -> Unit,
    onSaveGeminiApiKey: (String) -> Unit,
    onClearGeminiApiKey: () -> Unit,
    onSaveNutritionConnection: (String, String) -> Unit,
    onSignInNutrition: (String, String) -> Unit,
    priceTraceUrl: String,
    isPriceTracePublishableKeyConfigured: Boolean,
    priceTraceSignedInEmail: String?,
    isPriceTraceSigningIn: Boolean,
    onSavePriceTraceConnection: (String, String) -> Unit,
    onSignInPriceTrace: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("api_settings"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                "연결 설정",
                "AI 교정과 두 서비스의 로그인 세션을 각각 관리합니다.",
                onBack,
            )
        }
        item {
            GeminiApiSettingsCard(
                provider = provider,
                isBusy = isSigningIn,
                onSaveApiKey = onSaveGeminiApiKey,
                onClearApiKey = onClearGeminiApiKey,
            )
        }
        item {
            NutritionConnectionCard(
                supabaseUrl = supabaseUrl,
                isPublishableKeyConfigured = isPublishableKeyConfigured,
                signedInEmail = signedInEmail,
                isSigningIn = isSigningIn,
                isPublishing = false,
                onSaveConnection = onSaveNutritionConnection,
                onSignIn = onSignInNutrition,
            )
        }
        item {
            PriceTraceConnectionCard(
                supabaseUrl = priceTraceUrl,
                isPublishableKeyConfigured = isPriceTracePublishableKeyConfigured,
                signedInEmail = priceTraceSignedInEmail,
                isSigningIn = isPriceTraceSigningIn,
                onSaveConnection = onSavePriceTraceConnection,
                onSignIn = onSignInPriceTrace,
            )
        }
        item {
            Text(
                "API 키는 화면에 다시 표시하지 않으며, Supabase는 publishable/anon key와 로그인한 사용자 토큰으로만 호출합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GeminiApiSettingsCard(
    provider: ReceiptCorrectionProvider?,
    isBusy: Boolean,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
) {
    var apiKeyInput by remember { mutableStateOf(BuildConfig.DEFAULT_GEMINI_API_KEY) }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gemini API 키", fontWeight = FontWeight.SemiBold)
            Text(
                if (provider?.isAvailable == true) {
                    "빌드 기본 키 또는 이 기기에 저장된 키가 구성되어 있습니다. 현재 키 값은 다시 표시하지 않습니다."
                } else {
                    "Google AI Studio에서 발급한 API 키를 입력하세요."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { value ->
                    if (value.length <= 512 && value.none { it == '\n' || it == '\r' }) {
                        apiKeyInput = value
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_input"),
                label = { Text("새 API 키") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !isBusy,
            )
            Button(
                onClick = {
                    onSaveApiKey(apiKeyInput)
                    apiKeyInput = ""
                },
                enabled = apiKeyInput.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth().testTag("save_gemini_api_key_button"),
            ) {
                Text(if (provider?.isAvailable == true) "API 키 교체 저장" else "API 키 저장")
            }
            if (provider?.isAvailable == true) {
                OutlinedButton(
                    onClick = onClearApiKey,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("clear_gemini_api_key_button"),
                ) {
                    Text("저장된 키 삭제")
                }
            }
            Text(
                "빌드 기본 키는 APK에 포함되고, 교체 키는 Android Keystore에 저장됩니다. 직접 API를 호출하는 모바일 앱은 공개 배포용 비밀 저장소가 아닙니다.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NutritionConnectionCard(
    supabaseUrl: String,
    isPublishableKeyConfigured: Boolean,
    signedInEmail: String?,
    isSigningIn: Boolean,
    isPublishing: Boolean,
    onSaveConnection: (String, String) -> Unit,
    onSignIn: (String, String) -> Unit,
) {
    var connectionUrl by remember(supabaseUrl) { mutableStateOf(supabaseUrl) }
    var publishableKey by remember { mutableStateOf(BuildConfig.DEFAULT_NUTRITION_SUPABASE_PUBLISHABLE_KEY) }
    var email by remember(signedInEmail) {
        mutableStateOf(signedInEmail.orEmpty().ifBlank { BuildConfig.DEFAULT_NUTRITION_EMAIL })
    }
    var password by remember { mutableStateOf(BuildConfig.DEFAULT_NUTRITION_PASSWORD) }
    val isBusy = isSigningIn || isPublishing

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fitness Nutrition DB 연결", fontWeight = FontWeight.SemiBold)
            Text(
                "서비스 역할 키는 사용하지 않습니다. publishable/anon key와 로그인한 사용자 토큰으로 본인 소유 private 식품만 저장합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = connectionUrl,
                onValueChange = { connectionUrl = it },
                label = { Text("Nutrition Supabase URL") },
                placeholder = { Text("https://<project>.supabase.co") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("nutrition_supabase_url"),
            )
            OutlinedTextField(
                value = publishableKey,
                onValueChange = { publishableKey = it },
                label = { Text("publishable/anon key") },
                placeholder = {
                    Text(if (isPublishableKeyConfigured) "저장됨 — 변경할 때만 입력" else "런타임에 입력")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("nutrition_publishable_key"),
            )
            OutlinedButton(
                onClick = { onSaveConnection(connectionUrl, publishableKey) },
                enabled = connectionUrl.isNotBlank() &&
                    (publishableKey.isNotBlank() || isPublishableKeyConfigured) &&
                    !isBusy,
                modifier = Modifier.fillMaxWidth().testTag("save_nutrition_connection"),
            ) { Text("연결 정보 저장") }
            if (signedInEmail == null) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Fitness 계정 이메일") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().testTag("nutrition_email"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("비밀번호 (저장하지 않음)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("nutrition_password"),
                )
                Button(
                    onClick = { onSignIn(email, password) },
                    enabled = isPublishableKeyConfigured && email.isNotBlank() && password.isNotBlank() && !isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("nutrition_sign_in"),
                ) {
                    if (isSigningIn) BusyIndicator()
                    Text("Fitness 계정 로그인")
                }
            } else {
                Text("로그인: $signedInEmail", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PriceTraceConnectionCard(
    supabaseUrl: String,
    isPublishableKeyConfigured: Boolean,
    signedInEmail: String?,
    isSigningIn: Boolean,
    onSaveConnection: (String, String) -> Unit,
    onSignIn: (String, String) -> Unit,
) {
    var connectionUrl by remember(supabaseUrl) { mutableStateOf(supabaseUrl) }
    var publishableKey by remember {
        mutableStateOf(BuildConfig.DEFAULT_PRICETRACE_SUPABASE_PUBLISHABLE_KEY)
    }
    var email by remember(signedInEmail) { mutableStateOf(signedInEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PriceTrace 가격 관측 연결", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fitness Nutrition과 분리된 연결입니다. publishable key와 로그인한 사용자 세션만 사용하며 service_role/secret key는 거부합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = connectionUrl,
                onValueChange = { connectionUrl = it },
                label = { Text("PriceTrace Supabase URL") },
                placeholder = { Text("https://bpyvvcmdvgpwjrprkmtq.supabase.co") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pricetrace_supabase_url"),
            )
            OutlinedTextField(
                value = publishableKey,
                onValueChange = { publishableKey = it },
                label = { Text("PriceTrace publishable key") },
                placeholder = {
                    Text(if (isPublishableKeyConfigured) "저장됨 — 변경할 때만 입력" else "publishable key 입력")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pricetrace_publishable_key"),
            )
            OutlinedButton(
                onClick = { onSaveConnection(connectionUrl, publishableKey) },
                enabled = connectionUrl.isNotBlank() &&
                    (publishableKey.isNotBlank() || isPublishableKeyConfigured) &&
                    !isSigningIn,
                modifier = Modifier.fillMaxWidth().testTag("save_pricetrace_connection"),
            ) { Text("PriceTrace 연결 정보 저장") }
            if (signedInEmail == null) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("PriceTrace 계정 이메일") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().testTag("pricetrace_email"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("비밀번호 (저장하지 않음)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pricetrace_password"),
                )
                Button(
                    onClick = { onSignIn(email, password) },
                    enabled = isPublishableKeyConfigured && email.isNotBlank() && password.isNotBlank() && !isSigningIn,
                    modifier = Modifier.fillMaxWidth().testTag("pricetrace_sign_in"),
                ) {
                    if (isSigningIn) BusyIndicator()
                    Text("PriceTrace 계정 로그인")
                }
            } else {
                Text("로그인됨 · $signedInEmail", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SessionCard(session: ReceiptSession, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val statusLabel = sessionStatusLabel(session)
    val needsAttention = session.lastError != null
    val dateLabel = session.issuedOn ?: session.updatedAt.take(10)
    val amountLabel = session.grandTotalAmountMinor?.let { amount ->
        String.format(Locale.KOREA, "%,d원", amount)
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .testTag("session_${session.documentId}"),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        session.displayTitle ?: session.merchantName ?: if (
                            session.workflowType == OcrWorkflowType.FITNESS_NUTRITION
                        ) "미확인 상품" else "미확인 판매처",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        listOfNotNull(
                            if (session.workflowType == OcrWorkflowType.FITNESS_NUTRITION) "영양 라벨" else "가격 영수증",
                            dateLabel.takeIf { it.isNotBlank() },
                            amountLabel,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = if (needsAttention) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    contentColor = if (needsAttention) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        statusLabel,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            session.lastError?.let {
                Text(
                    "확인 필요: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "눌러서 계속하기",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    modifier = Modifier.testTag("delete_${session.documentId}"),
                ) { Text("삭제") }
            }
        }
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("검수 세션 삭제") },
            text = { Text("Room metadata, 이미지, JSON을 함께 삭제합니다. 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("confirm_delete_${session.documentId}"),
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("취소") }
            },
        )
    }
}

private fun sessionStatusLabel(session: ReceiptSession): String = when {
    session.lastError != null -> "확인 필요"
    session.workflowType == OcrWorkflowType.FITNESS_NUTRITION &&
        session.uploadStatus in setOf("uploaded", "published", "succeeded") -> "DB 저장 완료"
    session.reviewStatus in setOf("user_verified", "verified", "completed") -> "검수 완료"
    session.ocrStatus in setOf("completed", "succeeded", "recognized") -> "검수 대기"
    session.ocrStatus in setOf("processing", "in_progress", "running") -> "인식 중"
    else -> "로컬 초안"
}

@Composable
private fun ImageConfirmationScreen(
    workflow: OcrWorkflowType,
    pages: List<ReceiptPage>,
    duplicateCount: Int,
    isBusy: Boolean,
    resolvePageFile: (String) -> File,
    onBack: () -> Unit,
    onAppendScan: () -> Unit,
    onAppendPickImages: () -> Unit,
    onStartOcr: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("image_confirmation"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                if (workflow == OcrWorkflowType.FITNESS_NUTRITION) "상품 라벨 확인" else "영수증 이미지 확인",
                "페이지 순서와 가독성을 확인하세요.",
                onBack,
            )
        }
        if (duplicateCount > 0) {
            item {
                Text(
                    "다른 세션과 해시가 같은 페이지 후보 ${duplicateCount}건이 있습니다. 자동 삭제·병합하지 않았습니다.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("duplicate_warning"),
                )
            }
        }
        items(pages, key = { it.id }) { page ->
            EvidenceImage(page, resolvePageFile(page.storageKey), emptyList(), zoomEnabled = true)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onAppendScan,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).testTag("append_scan_button"),
                ) { Text("추가 촬영") }
                OutlinedButton(
                    onClick = onAppendPickImages,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).testTag("append_pick_images_button"),
                ) { Text("기존 사진 추가") }
            }
        }
        item {
            Button(
                onClick = onStartOcr,
                enabled = pages.isNotEmpty() && !isBusy,
                modifier = Modifier.fillMaxWidth().testTag("start_ocr_button"),
            ) {
                Text(
                    if (workflow == OcrWorkflowType.FITNESS_NUTRITION) {
                        "영양성분 OCR 시작"
                    } else {
                        "영수증 OCR 시작"
                    },
                )
            }
    }
}

}
@Composable
private fun NutritionReviewScreen(
    draft: NutritionLabelDraft,
    validationErrors: List<String>,
    pages: List<ReceiptPage>,
    resolvePageFile: (String) -> File,
    supabaseUrl: String,
    isPublishableKeyConfigured: Boolean,
    signedInEmail: String?,
    isSigningIn: Boolean,
    isPublishing: Boolean,
    onBack: () -> Unit,
    onProductNameChanged: (String) -> Unit,
    onBrandChanged: (String) -> Unit,
    onCategoryChanged: (String) -> Unit,
    onBasisAmountChanged: (String) -> Unit,
    onBasisUnitChanged: (String) -> Unit,
    onValueChanged: (NutritionField, String) -> Unit,
    onSaveConnection: (String, String) -> Unit,
    onSignIn: (String, String) -> Unit,
    onConfirmAndPublish: () -> Unit,
    aiProvider: ReceiptCorrectionProvider?,
    aiCandidates: List<NutritionCorrectionCandidate>,
    aiRejectedCount: Int,
    isAiLoading: Boolean,
    aiAssessment: NutritionEvidenceAssessment?,
    onSaveGeminiApiKey: (String) -> Unit,
    onClearGeminiApiKey: () -> Unit,
    onRequestAi: () -> Unit,
    onApplyAi: (String) -> Unit,
    onDismissAi: (String) -> Unit,
) {
    var basisAmount by remember(draft.documentId, draft.basisAmount) {
        mutableStateOf(formatNutritionNumber(draft.basisAmount))
    }
    val nutrientValues = remember(draft.documentId, draft.nutrients) {
        NutritionField.entries.associateWith { field ->
            mutableStateOf(formatNutritionNumber(draft.value(field)))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("nutrition_review"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                "Fitness 영양성분 검수",
                "OCR은 초안입니다. 라벨에 없는 값은 0이 아니라 모름으로 비워 두세요.",
                onBack,
            )
        }
        pages.firstOrNull()?.let { page ->
            item { EvidenceImage(page, resolvePageFile(page.storageKey), emptyList(), zoomEnabled = true) }
        }
        if (draft.parseWarnings.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("자동 확정하지 않은 항목", fontWeight = FontWeight.SemiBold)
                        draft.parseWarnings.forEach { Text("• $it") }
                    }
                }
            }
        }
        item {
            NutritionAiReviewPanel(
                provider = aiProvider,
                candidates = aiCandidates,
                rejectedCount = aiRejectedCount,
                isLoading = isAiLoading,
                assessment = aiAssessment,
                onSaveApiKey = onSaveGeminiApiKey,
                onClearApiKey = onClearGeminiApiKey,
                onRequest = onRequestAi,
                onApply = onApplyAi,
                onDismiss = onDismissAi,
            )
        }

        item { SectionTitle("상품 정보") }
        item {
            Text(
                "상품명과 브랜드는 OCR로 자동 입력하지 않습니다. 원본 라벨을 보고 직접 입력한 뒤 확정하세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = draft.productName,
                onValueChange = onProductNameChanged,
                label = { Text("상품명 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("nutrition_product_name"),
            )
        }
        item {
            OutlinedTextField(
                value = draft.brand.orEmpty(),
                onValueChange = onBrandChanged,
                label = { Text("브랜드 (선택)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("nutrition_brand"),
            )
        }
        item {
            NutritionOptionSelector(
                label = "상품 분류 *",
                selected = draft.category,
                options = NutritionContract.categories.toList().sorted(),
                testTag = "nutrition_category",
                onSelected = onCategoryChanged,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = basisAmount,
                    onValueChange = {
                        basisAmount = it
                        onBasisAmountChanged(it)
                    },
                    label = { Text("기준량 *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("nutrition_basis_amount"),
                )
                Box(Modifier.weight(1f)) {
                    NutritionOptionSelector(
                        label = "기준 단위 *",
                        selected = draft.basisUnit,
                        options = NutritionUnit.supported.toList(),
                        testTag = "nutrition_basis_unit",
                        onSelected = onBasisUnitChanged,
                    )
                }
            }
        }
        item { SectionTitle("필수 7종 영양성분") }
        items(NutritionField.entries, key = { it.wireKey }) { field ->
            val localValue = nutrientValues.getValue(field)
            OutlinedTextField(
                value = localValue.value,
                onValueChange = { value ->
                    localValue.value = value
                    onValueChanged(field, value)
                },
                label = {
                    Text("${field.koreanLabel}${if (field.required) " *" else " (선택)"}")
                },
                suffix = { Text(field.canonicalUnit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = if (field.required) null else {
                    { Text("라벨에 없으면 비워 두어 모름(null)으로 보존") }
                },
                modifier = Modifier.fillMaxWidth().testTag("nutrition_${field.wireKey}"),
            )
        }
        if (validationErrors.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("전송 전 확인 필요", fontWeight = FontWeight.SemiBold)
                        validationErrors.forEach { Text("• $it") }
                    }
                }
            }
        }
        item {
            NutritionConnectionCard(
                supabaseUrl = supabaseUrl,
                isPublishableKeyConfigured = isPublishableKeyConfigured,
                signedInEmail = signedInEmail,
                isSigningIn = isSigningIn,
                isPublishing = isPublishing,
                onSaveConnection = onSaveConnection,
                onSignIn = onSignIn,
            )
        }
        item {
            Button(
                onClick = onConfirmAndPublish,
                enabled = validationErrors.isEmpty() && signedInEmail != null && !isSigningIn && !isPublishing,
                modifier = Modifier.fillMaxWidth().testTag("confirm_publish_nutrition"),
            ) {
                if (isPublishing) BusyIndicator()
                Text(if (draft.status.wireValue == "user_verified") "확정본 다시 저장" else "원본 대조 확정 후 DB 저장")
            }
        }
        item {
            Text(
                "이 단계에서는 PriceTrace 상품 ID를 만들거나 이름으로 연결하지 않습니다. 상품 연결·공개는 " +
                    "Fitness App의 정확 식별자 승인 절차가 소유합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NutritionAiReviewPanel(
    provider: ReceiptCorrectionProvider?,
    candidates: List<NutritionCorrectionCandidate>,
    rejectedCount: Int,
    isLoading: Boolean,
    assessment: NutritionEvidenceAssessment?,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onRequest: () -> Unit,
    onApply: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI 성분표 대조", fontWeight = FontWeight.SemiBold)
            Text(
                "AI는 현재 초안·연결된 OCR 줄·원본 라벨 crop만 대조합니다. 값은 자동 확정하지 않으며, " +
                    "적용한 후보도 다시 원본과 확인해야 합니다.",
            )
            GeminiApiSettingsCard(
                provider = provider,
                isBusy = isLoading,
                onSaveApiKey = onSaveApiKey,
                onClearApiKey = onClearApiKey,
            )
            Button(
                onClick = onRequest,
                enabled = provider?.isAvailable == true && !isLoading,
                modifier = Modifier.fillMaxWidth().testTag("request_nutrition_ai_corrections_button"),
            ) {
                if (isLoading) BusyIndicator()
                Text(if (isLoading) "성분표 AI 대조 중" else "원본 근거를 확인하고 AI 대조 시작")
            }
            assessment?.let { result ->
                Text(
                    "AI 판정: ${nutritionAiVerdictLabel(result.verdict)}",
                    fontWeight = FontWeight.SemiBold,
                )
                result.fieldChecks
                    .filter { it.verdict != NutritionFieldVerdict.MATCHES_EVIDENCE }
                    .forEach { check ->
                        Text(
                            "• ${nutritionCorrectionFieldLabel(check.fieldPath)}: " +
                                "${nutritionFieldVerdictLabel(check.verdict)} — ${check.reason}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
            if (rejectedCount > 0) {
                Text(
                    "근거·현재값·형식 검사를 통과하지 못한 AI 제안 ${rejectedCount}건은 자동 폐기했습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            candidates.forEach { candidate ->
                NutritionAiCandidateCard(
                    candidate = candidate,
                    onApply = { onApply(candidate.id) },
                    onDismiss = { onDismiss(candidate.id) },
                )
            }
            if (!isLoading && assessment != null && candidates.isEmpty()) {
                Text(
                    "표시할 성분표 교정 제안이 없습니다. 원본과 필드 판정을 확인하세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NutritionAiCandidateCard(
    candidate: NutritionCorrectionCandidate,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(nutritionCorrectionFieldLabel(candidate.fieldPath), fontWeight = FontWeight.SemiBold)
            Text("현재: ${candidate.oldValue ?: "비어 있음"}")
            Text("제안: ${candidate.proposedValue}")
            Text(
                "신뢰도 ${candidate.confidencePercent}% · 근거 ${candidate.sourceLineIds.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(candidate.reason, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f).testTag("apply_nutrition_ai_candidate_${candidate.id}"),
                ) {
                    Text("적용")
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).testTag("dismiss_nutrition_ai_candidate_${candidate.id}"),
                ) {
                    Text("폐기")
                }
            }
        }
    }
}

private fun nutritionCorrectionFieldLabel(fieldPath: String): String = when (fieldPath) {
    "product_name" -> "상품명"
    "brand" -> "브랜드"
    "category" -> "상품 분류"
    "basis_amount" -> "기준량"
    "basis_unit" -> "기준 단위"
    else -> NutritionField.fromWireKey(fieldPath)?.koreanLabel ?: fieldPath
}

private fun nutritionAiVerdictLabel(verdict: NutritionEvidenceVerdict): String = when (verdict) {
    NutritionEvidenceVerdict.PLAUSIBLE -> "근거 일치"
    NutritionEvidenceVerdict.NEEDS_REVIEW -> "검토 필요"
    NutritionEvidenceVerdict.INSUFFICIENT_EVIDENCE -> "근거 부족"
}

private fun nutritionFieldVerdictLabel(verdict: NutritionFieldVerdict): String = when (verdict) {
    NutritionFieldVerdict.MATCHES_EVIDENCE -> "근거 일치"
    NutritionFieldVerdict.NEEDS_REVIEW -> "검토 필요"
    NutritionFieldVerdict.WRONG_FIELD_TYPE -> "필드 유형 불일치"
    NutritionFieldVerdict.INSUFFICIENT_EVIDENCE -> "근거 부족"
}

@Composable
private fun NutritionOptionSelector(
    label: String,
    selected: String,
    options: List<String>,
    testTag: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        ) { Text("$label: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

private fun formatNutritionNumber(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> value.toString()
}

@Composable
fun OcrProgressScreen() {
    Box(Modifier.fillMaxSize().testTag("ocr_progress"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text("한국어·영문·숫자 OCR 처리 중", fontWeight = FontWeight.SemiBold)
            Text("원본 전체 텍스트는 로그에 기록하지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FieldReviewScreen(
    receipt: ReceiptV2,
    validation: ReceiptValidationResult?,
    progress: ReceiptReviewProgress?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    pages: List<ReceiptPage>,
    ocrDocument: OcrDocument?,
    resolvePageFile: (String) -> File,
    onBack: () -> Unit,
    onMerchantNameChanged: (String) -> Unit,
    onBranchNameChanged: (String) -> Unit,
    onBusinessRegistrationNumberChanged: (String) -> Unit,
    onAddressChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onOriginalDocumentIdChanged: (String) -> Unit,
    onIssuedOnChanged: (String) -> Unit,
    onIssuedLocalTimeChanged: (String) -> Unit,
    onIssuedAtChanged: (String) -> Unit,
    onCurrencyChanged: (String) -> Unit,
    onGrandTotalChanged: (String) -> Unit,
    onSubtotalChanged: (String) -> Unit,
    onDiscountTotalChanged: (String) -> Unit,
    onTaxTotalChanged: (String) -> Unit,
    onFeeTotalChanged: (String) -> Unit,
    onPaymentMethodChanged: (Int, String) -> Unit,
    onPaymentAmountChanged: (Int, String) -> Unit,
    onNext: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("field_review"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("영수증 필드 검수", "OCR 초안을 원본과 직접 비교하세요.", onBack) }
        item { ReviewToolbar(progress, canUndo, canRedo, onUndo, onRedo) }
        pages.firstOrNull()?.let { page ->
            item { EvidenceImage(page, resolvePageFile(page.storageKey), emptyList(), zoomEnabled = true) }
        }
        item {
            Text(
                "OCR 줄 ${ocrDocument?.lines?.size ?: 0}개 · 확인 불가능한 값은 빈칸(null)으로 둡니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ReviewTextField(
                label = "판매처명",
                value = receipt.merchant.name.orEmpty(),
                onValueChange = onMerchantNameChanged,
                isError = validation.hasIssue("merchant"),
                testTag = "merchant_name_field",
            )
        }
        item { ReviewTextField("지점명", receipt.merchant.branchName.orEmpty(), onBranchNameChanged) }
        item {
            ReviewTextField(
                "사업자등록번호",
                receipt.merchant.businessRegistrationNumber.orEmpty(),
                onBusinessRegistrationNumberChanged,
            )
        }
        item { ReviewTextField("주소", receipt.merchant.address.orEmpty(), onAddressChanged) }
        item { ReviewTextField("전화번호", receipt.merchant.phone.orEmpty(), onPhoneChanged) }
        item {
            ReviewTextField(
                "인쇄된 거래번호",
                receipt.document.source.originalDocumentId.orEmpty(),
                onOriginalDocumentIdChanged,
            )
        }
        item {
            ReviewTextField(
                "구매일 (YYYY-MM-DD)",
                receipt.document.issuedOn.orEmpty(),
                onIssuedOnChanged,
                isError = validation.hasIssue("document.issued_on"),
                testTag = "issued_on_field",
            )
        }
        item {
            ReviewTextField(
                "구매 현지시각 (HH:mm:ss · 시간대 미확인)",
                receipt.document.source.purchaseLocalTime().orEmpty(),
                onIssuedLocalTimeChanged,
                testTag = "issued_local_time_field",
            )
        }
        item {
            ReviewTextField(
                "구매시각 (ISO-8601 offset · 근거가 있을 때만)",
                receipt.document.issuedAt.orEmpty(),
                onIssuedAtChanged,
                isError = validation.hasIssue("document.issued_at"),
                testTag = "issued_at_field",
            )
        }
        item {
            ReviewTextField(
                "통화",
                receipt.document.currency.orEmpty(),
                onCurrencyChanged,
                isError = validation.hasIssue("document.currency"),
                testTag = "currency_field",
            )
        }
        item {
            ReviewTextField(
                "소계 (KRW 정수)",
                receipt.totals.subtotalAmountMinor?.toString().orEmpty(),
                onSubtotalChanged,
            )
        }
        item {
            ReviewTextField(
                "할인 합계 (부호 포함)",
                receipt.totals.discountAmountMinor?.toString().orEmpty(),
                onDiscountTotalChanged,
            )
        }
        item {
            ReviewTextField(
                "세금 합계",
                receipt.totals.taxAmountMinor?.toString().orEmpty(),
                onTaxTotalChanged,
            )
        }
        item {
            ReviewTextField(
                "수수료 합계",
                receipt.totals.feeAmountMinor?.toString().orEmpty(),
                onFeeTotalChanged,
            )
        }
        item {
            ReviewTextField(
                "최종 결제금액 (KRW 정수)",
                receipt.totals.grandTotalAmountMinor?.toString().orEmpty(),
                onGrandTotalChanged,
                isError = validation.hasIssue("totals.grand_total_amount_minor"),
                testTag = "grand_total_field",
            )
        }
        if (receipt.payments.isNotEmpty()) {
            item { SectionTitle("결제수단") }
            itemsIndexed(receipt.payments) { index, payment ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReviewTextField(
                            "결제 방법 ${index + 1}",
                            payment.method.orEmpty(),
                            { onPaymentMethodChanged(index, it) },
                        )
                        ReviewTextField(
                            "결제 금액 ${index + 1}",
                            payment.amountMinor?.toString().orEmpty(),
                            { onPaymentAmountChanged(index, it) },
                        )
                    }
                }
            }
        }
        item { Button(onClick = onNext, modifier = Modifier.fillMaxWidth().testTag("fields_next_button")) { Text("상품 행 검수") } }
    }
}

@Suppress("LongParameterList")
@Composable
fun ItemReviewScreen(
    receipt: ReceiptV2,
    validation: ReceiptValidationResult?,
    progress: ReceiptReviewProgress?,
    canUndo: Boolean,
    canRedo: Boolean,
    showOnlyAttentionItems: Boolean,
    attentionFilterIds: List<String>,
    pages: List<ReceiptPage>,
    ocrDocument: OcrDocument?,
    resolvePageFile: (String) -> File,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShowAiCorrection: () -> Unit,
    onShowOnlyAttentionItemsChanged: (Boolean) -> Unit,
    onDescriptionChanged: (String, String) -> Unit,
    onTypeChanged: (String, ReceiptLineType) -> Unit,
    onMerchantSkuChanged: (String, String) -> Unit,
    onQuantityChanged: (String, String) -> Unit,
    onUnitPriceChanged: (String, String) -> Unit,
    onNetAmountChanged: (String, String) -> Unit,
    onAddLineItem: (String?) -> Unit,
    onRemoveLineItem: (String) -> Unit,
    onNext: () -> Unit,
) {
    val attentionIds = progress?.attentionLineItemIds.orEmpty().toSet()
    // The filter works off the set captured when it was switched on, so a row stays put while it is being
    // corrected. Indices address validation paths, so filtering keeps each row's original position.
    val filteredIds = attentionFilterIds.toSet()
    val visibleItems = receipt.lineItems.withIndex().filter { (_, item) ->
        !showOnlyAttentionItems || item.id in filteredIds
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("item_review"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("상품 행 검수", "할인·세금·수수료를 상품으로 바꾸지 마세요.", onBack) }
        item { ReviewToolbar(progress, canUndo, canRedo, onUndo, onRedo) }
        item {
            OutlinedButton(
                onClick = onShowAiCorrection,
                modifier = Modifier.fillMaxWidth().testTag("show_ai_correction_button"),
            ) {
                Text("Gemini 교정 제안")
            }
        }
        // Stays visible while the filter is on even after every row is settled, so the reviewer can
        // always get the full list back.
        if (attentionIds.isNotEmpty() || showOnlyAttentionItems) {
            item {
                ToggleRow(
                    label = if (showOnlyAttentionItems) {
                        "확인이 필요했던 ${filteredIds.size}행만 보는 중"
                    } else {
                        "확인이 필요한 ${attentionIds.size}행만 보기"
                    },
                    checked = showOnlyAttentionItems,
                    onChanged = onShowOnlyAttentionItemsChanged,
                )
            }
        }
        if (receipt.lineItems.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "파싱된 행이 없습니다. OCR 원문을 확인하고 초안 상태로 보존하거나, " +
                            "원본을 보고 행을 직접 추가하세요.",
                        Modifier.padding(16.dp),
                    )
                }
            }
        } else if (visibleItems.isEmpty()) {
            item { Text("확인이 필요한 행이 없습니다.", color = ReceiptPositive) }
        }
        items(visibleItems, key = { (_, item) -> item.id }) { (index, item) ->
            LineItemCard(
                item = item,
                hasIssue = validation?.issues?.any { issue -> issue.fieldPath.startsWith("line_items[$index]") } == true ||
                    item.confidence == ConfidenceLevel.LOW,
                pages = pages,
                ocrDocument = ocrDocument,
                resolvePageFile = resolvePageFile,
                onDescriptionChanged = { onDescriptionChanged(item.id, it) },
                onTypeChanged = { onTypeChanged(item.id, it) },
                onMerchantSkuChanged = { onMerchantSkuChanged(item.id, it) },
                onQuantityChanged = { onQuantityChanged(item.id, it) },
                onUnitPriceChanged = { onUnitPriceChanged(item.id, it) },
                onNetAmountChanged = { onNetAmountChanged(item.id, it) },
                onAddBelow = { onAddLineItem(item.id) },
                onRemove = { onRemoveLineItem(item.id) },
            )
        }
        item {
            OutlinedButton(
                onClick = { onAddLineItem(null) },
                modifier = Modifier.fillMaxWidth().testTag("add_line_item_button"),
            ) {
                Text("원본에 있는데 인식되지 않은 행 추가")
            }
        }
        item { Button(onClick = onNext, modifier = Modifier.fillMaxWidth().testTag("items_next_button")) { Text("합계 검증") } }
    }
}

@Composable
private fun AiCorrectionScreen(
    provider: ReceiptCorrectionProvider?,
    candidates: List<ReceiptCorrectionCandidate>,
    rejectedCount: Int,
    isLoading: Boolean,
    isPreflight: Boolean,
    aiStatus: ReceiptAiReviewStatus,
    assessment: ReceiptEvidenceAssessment?,
    decision: ReceiptPreflightDecision?,
    pages: List<ReceiptPage>,
    ocrDocument: OcrDocument?,
    resolvePageFile: (String) -> File,
    onBack: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onRequest: () -> Unit,
    onContinueReview: () -> Unit,
    onRecapture: () -> Unit,
    onApply: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("ai_correction_review"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                if (isPreflight) "AI 사전검토" else "Gemini 교정 제안",
                if (isPreflight) {
                    "OCR·AI·계산 규칙을 비교한 뒤 재촬영 또는 사용자 검증 경로를 결정합니다."
                } else {
                    "AI는 초안을 직접 확정하지 않습니다."
                },
                onBack,
            )
        }
        if (isPreflight) {
            item {
                AiPreflightDecisionCard(
                    status = aiStatus,
                    assessment = assessment,
                    decision = decision,
                    onContinueReview = onContinueReview,
                    onRecapture = onRecapture,
                )
            }
        }
        item {
            GeminiApiSettingsCard(
                provider = provider,
                isBusy = isLoading,
                onSaveApiKey = onSaveApiKey,
                onClearApiKey = onClearApiKey,
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("전송 범위", fontWeight = FontWeight.SemiBold)
                    Text(
                        "버튼을 누르면 판매처명·지점명·주소·전화번호·사업자번호와 상품 행 최대 7개의 " +
                            "현재값, 해당 OCR 줄, 해당 줄만 잘라낸 이미지가 Gemini Developer API로 직접 전송됩니다. " +
                            "전체 영수증 이미지·카드번호·승인번호·거래번호·회원번호는 요청 계약에서 제외합니다.",
                    )
                    Text(
                        "무료 Gemini Developer API 입력은 Google 제품 개선에 사용될 수 있습니다. " +
                            "민감한 구매 내역이면 요청하지 마세요.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "제공자: ${provider?.displayName ?: "미구성"} · 모델: ${provider?.model ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Button(
                onClick = onRequest,
                enabled = provider?.isAvailable == true &&
                    !isLoading &&
                    (!isPreflight || decision?.route != ReceiptPreflightRoute.RECAPTURE_RECOMMENDED),
                modifier = Modifier.fillMaxWidth().testTag("request_ai_corrections_button"),
            ) {
                Text(
                    when {
                        isLoading -> "Gemini 분석 중"
                        isPreflight -> "전송 범위를 확인하고 AI 사전검토 시작"
                        else -> "전송 범위를 확인하고 제안 요청"
                    },
                )
            }
        }
        if (provider?.isAvailable != true) {
            item {
                Text(
                    provider?.unavailableReason
                        ?: "Gemini API 키를 먼저 저장하세요.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (isLoading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("근거 crop과 구조화된 OCR 정보를 분석하고 있습니다.")
                }
            }
        }
        if (rejectedCount > 0) {
            item {
                Text(
                    "근거·현재값·산술 검사를 통과하지 못한 제안 ${rejectedCount}건은 자동 폐기했습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!isLoading && candidates.isEmpty() && (!isPreflight || aiStatus == ReceiptAiReviewStatus.COMPLETED)) {
            item { Text("표시할 교정 제안이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(candidates, key = ReceiptCorrectionCandidate::id) { candidate ->
            AiCorrectionCandidateCard(
                candidate = candidate,
                pages = pages,
                ocrDocument = ocrDocument,
                resolvePageFile = resolvePageFile,
                onApply = { onApply(candidate.id) },
                onDismiss = { onDismiss(candidate.id) },
            )
        }
    }
}

@Composable
private fun AiPreflightDecisionCard(
    status: ReceiptAiReviewStatus,
    assessment: ReceiptEvidenceAssessment?,
    decision: ReceiptPreflightDecision?,
    onContinueReview: () -> Unit,
    onRecapture: () -> Unit,
) {
    val route = decision?.route ?: ReceiptPreflightRoute.REQUEST_AI_REVIEW
    val containerColor = when (route) {
        ReceiptPreflightRoute.RECAPTURE_RECOMMENDED -> MaterialTheme.colorScheme.errorContainer
        ReceiptPreflightRoute.READY_FOR_USER_VERIFICATION -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("ai_preflight_decision"),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preflightRouteLabel(route), fontWeight = FontWeight.SemiBold)
            Text(
                "AI 상태: ${aiReviewStatusLabel(status)}" +
                    (assessment?.let { " · 근거 판정: ${it.verdict.wireValue}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            assessment?.merchantVerdict?.let { verdict ->
                Text(
                    "판매처 근거 판정: ${merchantVerdictLabel(verdict)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            assessment?.fieldChecks?.forEach { check ->
                Text(
                    "• ${aiFieldCheckLabel(check)}: ${fieldVerdictLabel(check.verdict)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (check.reason.isNotBlank()) {
                    Text(
                        "  ${check.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            decision?.reasons.orEmpty().forEach { reason ->
                Text("• ${preflightReasonLabel(reason)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "카탈로그 검색 결과는 촬영 품질 판정이나 자동 상품 연결의 근거로 사용하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (route == ReceiptPreflightRoute.RECAPTURE_RECOMMENDED) {
                Button(
                    onClick = onRecapture,
                    modifier = Modifier.fillMaxWidth().testTag("preflight_recapture_button"),
                ) {
                    Text("새로 촬영 · 기존 세션 보존")
                }
            }
            OutlinedButton(
                onClick = onContinueReview,
                modifier = Modifier.fillMaxWidth().testTag("preflight_continue_button"),
            ) {
                Text(
                    when (route) {
                        ReceiptPreflightRoute.READY_FOR_USER_VERIFICATION -> "사용자 검증 요청"
                        ReceiptPreflightRoute.RECAPTURE_RECOMMENDED -> "권장 무시하고 직접 검수"
                        ReceiptPreflightRoute.REQUEST_AI_REVIEW -> "AI 없이 직접 검수"
                        ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED -> "수동 검수로 이동"
                    },
                )
            }
        }
    }
}

private fun preflightRouteLabel(route: ReceiptPreflightRoute): String = when (route) {
    ReceiptPreflightRoute.REQUEST_AI_REVIEW -> "AI 사전검토 대기"
    ReceiptPreflightRoute.READY_FOR_USER_VERIFICATION -> "사용자 검증 준비"
    ReceiptPreflightRoute.MANUAL_REVIEW_REQUIRED -> "수동 검수 필요"
    ReceiptPreflightRoute.RECAPTURE_RECOMMENDED -> "재촬영 권장"
}

private fun aiReviewStatusLabel(status: ReceiptAiReviewStatus): String = when (status) {
    ReceiptAiReviewStatus.NOT_REQUESTED -> "요청 전"
    ReceiptAiReviewStatus.RUNNING -> "분석 중"
    ReceiptAiReviewStatus.COMPLETED -> "완료"
    ReceiptAiReviewStatus.FAILED -> "실패"
    ReceiptAiReviewStatus.UNAVAILABLE -> "사용 불가"
}
private fun merchantVerdictLabel(verdict: ReceiptEvidenceVerdict): String = when (verdict) {
    ReceiptEvidenceVerdict.PLAUSIBLE -> "영수증 근거상 판매처 형식 확인"
    ReceiptEvidenceVerdict.NEEDS_REVIEW -> "판매처 근거 추가 확인 필요"
    ReceiptEvidenceVerdict.INSUFFICIENT_EVIDENCE -> "판매처 근거 부족"
}

private fun fieldVerdictLabel(verdict: ReceiptFieldVerdict): String = when (verdict) {
    ReceiptFieldVerdict.MATCHES_EVIDENCE -> "근거 일치"
    ReceiptFieldVerdict.NEEDS_REVIEW -> "추가 확인 필요"
    ReceiptFieldVerdict.WRONG_FIELD_TYPE -> "필드 유형 오류"
    ReceiptFieldVerdict.INSUFFICIENT_EVIDENCE -> "근거 부족"
}

private fun aiFieldCheckLabel(check: ReceiptFieldCheck): String = when (check.fieldPath) {
    "merchant.name" -> "판매처명"
    "merchant.branch_name" -> "지점명"
    "merchant.business_registration_number" -> "사업자등록번호"
    "merchant.address" -> "주소"
    "merchant.phone" -> "전화번호"
    else -> check.fieldPath
}


private fun preflightReasonLabel(reason: ReceiptPreflightReason): String = when (reason) {
    ReceiptPreflightReason.AI_REVIEW_NOT_RUN -> "AI가 아직 OCR 상품행 근거를 검토하지 않았습니다."
    ReceiptPreflightReason.AI_REVIEW_IN_PROGRESS -> "AI가 OCR과 원본 crop을 비교하고 있습니다."
    ReceiptPreflightReason.AI_REVIEW_FAILED -> "AI 요청 실패는 촬영 실패로 간주하지 않고 수동 검수로 보냅니다."
    ReceiptPreflightReason.AI_REVIEW_UNAVAILABLE -> "AI가 구성되지 않아 수동 검수가 필요합니다."
    ReceiptPreflightReason.AI_EVIDENCE_PLAUSIBLE -> "제공된 상품행 근거에서 충돌을 찾지 못했습니다."
    ReceiptPreflightReason.AI_EVIDENCE_NEEDS_REVIEW -> "AI가 OCR 값과 원본 근거의 충돌을 찾았습니다."
    ReceiptPreflightReason.AI_FIELD_CHECKS_NEED_REVIEW -> "판매처명·주소·전화번호·사업자번호 중 근거 불일치 또는 필드 유형 오류가 있습니다."
    ReceiptPreflightReason.AI_EVIDENCE_INSUFFICIENT -> "AI가 제공된 근거만으로 안전하게 판단하지 못했습니다."
    ReceiptPreflightReason.AI_IMAGE_EVIDENCE_PARTIAL -> "일부 상품행은 원본 crop이 없어 수동 대조가 필요합니다."
    ReceiptPreflightReason.AI_CORRECTIONS_AVAILABLE -> "원본과 대조해야 할 AI 교정 후보가 있습니다."
    ReceiptPreflightReason.AI_RESPONSE_CANDIDATES_REJECTED -> "근거 또는 산술 검사를 통과하지 못한 AI 응답이 있습니다."
    ReceiptPreflightReason.OCR_EVIDENCE_MISSING -> "OCR 문자·행 근거가 거의 없습니다."
    ReceiptPreflightReason.OCR_EVIDENCE_SPARSE -> "OCR 근거가 희박해 모든 값을 직접 확인해야 합니다."
    ReceiptPreflightReason.CAPTURE_RESOLUTION_LOW -> "촬영 이미지의 짧은 변 해상도가 기준보다 낮습니다."
    ReceiptPreflightReason.BLOCKING_VALIDATION_ISSUES -> "필수 필드 또는 행 검증 오류가 남아 있습니다."
    ReceiptPreflightReason.TOTAL_RECONCILIATION_FAILED -> "행 합계와 최종 합계가 일치하지 않습니다."
}

@Composable
private fun AiCorrectionCandidateCard(
    candidate: ReceiptCorrectionCandidate,
    pages: List<ReceiptPage>,
    ocrDocument: OcrDocument?,
    resolvePageFile: (String) -> File,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val evidenceLines = ocrDocument?.lines?.filter { it.id in candidate.sourceLineIds }.orEmpty()
    val page = evidenceLines.firstOrNull()?.pageId?.let { id -> pages.firstOrNull { it.id == id } }
    Card(modifier = Modifier.fillMaxWidth().testTag("ai_candidate_${candidate.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(aiFieldLabel(candidate.fieldPath), fontWeight = FontWeight.SemiBold)
            page?.let {
                EvidenceImage(
                    page = it,
                    file = resolvePageFile(it.storageKey),
                    highlights = evidenceLines.mapNotNull { line -> line.boundingBox },
                    zoomEnabled = true,
                    height = 180,
                )
            }
            if (evidenceLines.isNotEmpty()) {
                Text(
                    evidenceLines.joinToString("\n") { "OCR: ${it.text}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("현재: ${candidate.oldValue ?: "null"}")
            Text("제안: ${candidate.proposedValue}", color = MaterialTheme.colorScheme.primary)
            Text(
                "신뢰도 ${candidate.confidencePercent}% · ${candidate.reason}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "source refs: ${candidate.sourceLineIds.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, modifier = Modifier.testTag("apply_ai_candidate_${candidate.id}")) {
                    Text("원본 확인 후 적용")
                }
                TextButton(onClick = onDismiss) { Text("폐기") }
            }
        }
    }
}

private fun aiFieldLabel(fieldPath: String): String = when {
    fieldPath == "merchant.name" -> "판매처명 교정"
    fieldPath == "merchant.branch_name" -> "지점명 교정"
    fieldPath == "merchant.business_registration_number" -> "사업자등록번호 교정"
    fieldPath == "merchant.address" -> "주소 교정"
    fieldPath == "merchant.phone" -> "전화번호 교정"
    fieldPath.endsWith(".description") -> "상품명 교정"
    fieldPath.endsWith(".quantity") -> "수량 교정"
    fieldPath.endsWith(".unit_price_amount_minor") -> "단가 교정"
    fieldPath.endsWith(".net_amount_minor") -> "행 금액 교정"
    else -> fieldPath
}

@Composable
private fun LineItemCard(
    item: ReceiptV2LineItem,
    hasIssue: Boolean,
    pages: List<ReceiptPage>,
    ocrDocument: OcrDocument?,
    resolvePageFile: (String) -> File,
    onDescriptionChanged: (String) -> Unit,
    onTypeChanged: (ReceiptLineType) -> Unit,
    onMerchantSkuChanged: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onUnitPriceChanged: (String) -> Unit,
    onNetAmountChanged: (String) -> Unit,
    onAddBelow: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmingRemoval by remember { mutableStateOf(false) }
    val highlightedLines = ocrDocument?.lines
        ?.filter { line -> line.id in item.sourceLineReferences }
        .orEmpty()
    val page = highlightedLines.firstOrNull()?.pageId?.let { pageId -> pages.firstOrNull { it.id == pageId } }
    val highlightBoxes = highlightedLines.mapNotNull { it.boundingBox }
    Card(
        modifier = Modifier.fillMaxWidth().testTag(
            if (item.confidence == ConfidenceLevel.LOW) "low_confidence_${item.id}" else "line_item_${item.id}",
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (hasIssue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.confidence.wireValue, style = MaterialTheme.typography.labelMedium)
                    if (item.isUserEntered()) {
                        Text(
                            "사용자 직접 입력 (OCR 근거 없음)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                LineTypeMenu(item.type, onTypeChanged)
            }
            page?.let {
                EvidenceImage(it, resolvePageFile(it.storageKey), highlightBoxes, zoomEnabled = true, height = 180)
            }
            if (highlightedLines.isNotEmpty()) {
                Text(
                    highlightedLines.joinToString("\n") { "OCR: ${it.text}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReviewTextField("설명", item.description.orEmpty(), onDescriptionChanged)
            ReviewTextField(
                "판매처 상품코드",
                item.identifiers.firstOrNull { it.scheme == "merchant_sku" }?.value.orEmpty(),
                onMerchantSkuChanged,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    ReviewTextField("수량", item.quantity?.value.orEmpty(), onQuantityChanged)
                }
                Box(Modifier.weight(1f)) {
                    ReviewTextField("단가", item.unitPriceAmountMinor?.toString().orEmpty(), onUnitPriceChanged)
                }
            }
            ReviewTextField("행 금액", item.netAmountMinor?.toString().orEmpty(), onNetAmountChanged)
            Text(
                "source refs: ${item.sourceLineReferences.joinToString().ifBlank { "없음" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onAddBelow,
                    modifier = Modifier.testTag("add_line_below_${item.id}"),
                ) { Text("아래에 행 추가") }
                TextButton(
                    onClick = { confirmingRemoval = true },
                    modifier = Modifier.testTag("remove_line_${item.id}"),
                ) { Text("행 삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text("이 행을 삭제할까요?") },
            text = {
                Text(
                    "‘${item.description ?: "설명 없는 행"}’을 상품 목록에서 뺍니다. " +
                        "삭제한 행 전체는 수정 이력에 남고 되돌리기로 복구할 수 있습니다.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRemoval = false
                        onRemove()
                    },
                    modifier = Modifier.testTag("confirm_remove_line"),
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun LineTypeMenu(current: ReceiptLineType, onChanged: (ReceiptLineType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("line_type_${current.wireValue}")) {
            Text(current.wireValue)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReceiptLineType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.wireValue) },
                    onClick = {
                        expanded = false
                        onChanged(type)
                    },
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ReconciliationScreen(
    receipt: ReceiptV2,
    validation: ReceiptValidationResult?,
    progress: ReceiptReviewProgress?,
    diagnosis: ReconciliationDiagnosis?,
    canUndo: Boolean,
    canRedo: Boolean,
    reason: String,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onApplySuggestion: (ReconciliationSuggestion) -> Unit,
    onReasonChanged: (String) -> Unit,
    onBack: () -> Unit,
    onPreviewDraft: () -> Unit,
    onConfirmVerified: () -> Unit,
) {
    val reconciliation = validation?.reconciliation
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("reconciliation"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("합계 검증", "행 합계 보존식과 필수 필드를 확인합니다.", onBack) }
        item { ReviewToolbar(progress, canUndo, canRedo, onUndo, onRedo) }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("행 net 합계: ${reconciliation?.lineNetTotalMinor ?: "계산 불가"}")
                    Text("영수증 최종 합계: ${receipt.totals.grandTotalAmountMinor ?: "누락"}")
                    Text(
                        "차이: ${reconciliation?.differenceMinor ?: "계산 불가"}",
                        color = if (reconciliation?.isBalanced == true) ReceiptPositive else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        val hypotheses = diagnosis?.hypotheses.orEmpty()
        if (hypotheses.isNotEmpty()) {
            item { SectionTitle("차액 원인 후보") }
            item {
                Text(
                    "아래는 초안의 숫자만으로 세운 가설입니다. 원본 영수증과 대조해 맞을 때만 적용하세요. " +
                        "적용하면 사용자의 수정으로 기록됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(hypotheses, key = { "${it.code}:${it.lineItemId}:${it.evidence}" }) { hypothesis ->
                HypothesisCard(hypothesis, onApplySuggestion)
            }
        }
        if (!validation?.issues.isNullOrEmpty()) {
            item { SectionTitle("검증 항목") }
            items(validation.issues, key = { "${it.code}:${it.fieldPath}" }) { issue ->
                Text(
                    "• ${issue.message} (${issue.fieldPath})",
                    color = if (issue.severity == ValidationSeverity.WARNING) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        } else {
            item { Text("모든 user_verified 필수 조건을 통과했습니다.", color = ReceiptPositive) }
        }
        item {
            ReviewTextField(
                label = "합계 불일치 검수 사유",
                value = reason,
                onValueChange = onReasonChanged,
                supporting = "차이가 남아 있다면 원본과 대조한 구체적인 사유를 기록해야 합니다.",
                testTag = "reconciliation_reason",
            )
        }
        item {
            OutlinedButton(onClick = onPreviewDraft, modifier = Modifier.fillMaxWidth().testTag("preview_draft_button")) {
                Text("초안 JSON 미리보기")
            }
        }
        item {
            Button(onClick = onConfirmVerified, modifier = Modifier.fillMaxWidth().testTag("confirm_verified_button")) {
                Text("검수 완료 · user_verified 확정")
            }
        }
    }
}

@Composable
fun JsonPreviewScreen(
    receipt: ReceiptV2?,
    json: String,
    includeRawText: Boolean,
    isExporting: Boolean,
    onIncludeRawTextChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onShowPriceObservationSubmit: () -> Unit = {},
    isRestaurantReceipt: Boolean = false,
) {
    val verified = receipt?.document?.source?.transcriptionStatus == TranscriptionStatus.USER_VERIFIED
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("json_preview"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("검증 결과", "저장하거나 공유하기 전에 포함 정보를 확인하세요.", onBack) }
        item {
            Surface(
                modifier = Modifier.testTag("json_status"),
                shape = CircleShape,
                color = if (verified) MaterialTheme.colorScheme.inverseSurface else {
                    MaterialTheme.colorScheme.errorContainer
                },
                contentColor = if (verified) MaterialTheme.colorScheme.inverseOnSurface else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            ) {
                Text(
                    if (verified) "검수 완료 · 저장 및 공유 가능" else "검수 전 초안 · 앱 내부에만 저장 가능",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(includeRawText) {
                    onIncludeRawTextChanged(it)
                }.testTag("raw_text_toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("private OCR raw_text 포함")
                    Text(
                        "기본값은 제외입니다. 원본 이미지와 ocr-debug.json은 항상 공유 대상에서 제외됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = includeRawText, onCheckedChange = null)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        json,
                        modifier = Modifier.fillMaxWidth().padding(14.dp).horizontalScroll(rememberScrollState())
                            .testTag("json_text"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSave,
                    enabled = verified && !isExporting,
                    modifier = Modifier.weight(1f).testTag("save_json_button"),
                ) { Text("로컬 저장") }
                Button(
                    onClick = onShare,
                    enabled = verified && !isExporting,
                    modifier = Modifier.weight(1f).testTag("share_json_button"),
                ) {
                    if (isExporting) BusyIndicator()
                    Text("공유")
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onShowPriceObservationSubmit,
                enabled = verified && !isExporting,
                modifier = Modifier.fillMaxWidth().testTag("price_observation_submit_button"),
            ) {
                Text(
                    if (isRestaurantReceipt) "검수된 식당 영수증을 PriceTrace에 제출"
                    else "검증된 가격 1건을 PriceTrace에 제출",
                )
            }
        }
        item {
            Text(
                if (isRestaurantReceipt) {
                    "식당 제출은 식당명·방문일·메뉴 가격만 전송하며 이미지와 OCR 원문은 기기에 남깁니다."
                } else {
                    "로컬 저장·공유와 서버 제출은 서로 다른 동작입니다. 위 버튼을 직접 눌러야 제출 화면으로 이동합니다."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun RestaurantReceiptSubmitScreen(
    receipt: ReceiptV2,
    signedInEmail: String?,
    isSubmitting: Boolean,
    submittedReceiptId: String?,
    submittedItemCount: Int?,
    replayed: Boolean?,
    lastError: String?,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val eligibleItems = receipt.lineItems.filter { item ->
        item.type !in setOf(
            ReceiptLineType.DISCOUNT,
            ReceiptLineType.FEE,
            ReceiptLineType.TAX,
            ReceiptLineType.TIP,
            ReceiptLineType.REFUND,
            ReceiptLineType.ROUNDING,
        )
    }
    val verified = receipt.document.source.transcriptionStatus == TranscriptionStatus.USER_VERIFIED
    val canSubmit = verified && signedInEmail != null && eligibleItems.isNotEmpty() && !isSubmitting
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("restaurant_receipt_submit"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                "식당 영수증 제출",
                "식당명·방문일·메뉴별 가격과 옵션 행을 검수 후 PriceTrace에 저장합니다.",
                onBack,
            )
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(receipt.merchant.name ?: "식당 이름 미확인", style = MaterialTheme.typography.titleLarge)
                    Text("방문일: ${receipt.document.issuedOn ?: "미확인"}")
                    Text("최종 금액: ${receipt.totals.grandTotalAmountMinor ?: "미확인"}원")
                    Text(
                        if (signedInEmail == null) "PriceTrace 로그인 필요"
                        else "로그인: $signedInEmail",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            val placeResolution = receipt.placeResolution
            val statusLabel = when (placeResolution.status) {
                PlaceResolutionStatus.USER_CONFIRMED -> "장소 확정"
                PlaceResolutionStatus.UNRESOLVED,
                PlaceResolutionStatus.CANDIDATES_READY,
                PlaceResolutionStatus.MANUAL_REQUIRED,
                -> "장소 미확정"
            }
            val ocrCandidateName = placeResolution.selectedCandidate?.displayName
                ?: placeResolution.candidates.firstOrNull()?.displayName
                ?: "미확인"
            Card(modifier = Modifier.fillMaxWidth().testTag("restaurant_place_resolution")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(statusLabel, style = MaterialTheme.typography.titleSmall)
                    Text("OCR 후보명: $ocrCandidateName")
                    Text(
                        "OCR 텍스트는 실제 장소 확정이 아닙니다. 외부 확인 전에는 확정 처리하지 마세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { SectionTitle("메뉴 및 옵션 ${eligibleItems.size}건") }
        items(eligibleItems, key = { it.id }) { item ->
            Card(modifier = Modifier.fillMaxWidth().testTag("restaurant_receipt_item_${item.id}")) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.description ?: "이름 없는 메뉴", style = MaterialTheme.typography.titleSmall)
                        Text(item.type.wireValue, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${item.quantity?.value ?: "?"} × ${item.unitPriceAmountMinor ?: "?"}원")
                        Text("${item.netAmountMinor ?: item.grossAmountMinor ?: "?"}원")
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (canSubmit) "제출 준비 완료" else "제출 조건을 확인하세요",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (canSubmit) {
                            "서버에는 검수된 구조화 정보만 전송됩니다. 이미지와 OCR 원문은 전송하지 않습니다."
                        } else {
                            listOfNotNull(
                                if (!verified) "user_verified 검수 완료" else null,
                                if (signedInEmail == null) "PriceTrace 로그인" else null,
                                if (eligibleItems.isEmpty()) "메뉴 가격 행" else null,
                            ).joinToString(" · ")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    submittedReceiptId?.let { id ->
                        Text(
                            "서버 영수증 ID: $id · ${submittedItemCount ?: eligibleItems.size}건" +
                                if (replayed == true) " · 재전송 확인" else "",
                            color = ReceiptPositive,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    lastError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().testTag("submit_restaurant_receipt_button"),
            ) {
                if (isSubmitting) BusyIndicator()
                Text("검수된 식당 영수증 서버 제출")
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun PriceObservationSubmitScreen(
    receipt: ReceiptV2,
    sources: List<PriceObservationSource>,
    products: List<PriceObservationProduct>,
    query: String,
    selectedStoreId: String?,
    selectedCatalogProductId: String?,
    selectedLineItemId: String?,
    observedOn: String,
    unitPriceKrw: String,
    queueStatus: PriceObservationQueueStatus?,
    appliedAction: String?,
    lastError: String?,
    isSignedIn: Boolean,
    isLoadingSources: Boolean,
    isLoadingProducts: Boolean,
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearchProducts: () -> Unit,
    onStoreSelected: (String) -> Unit,
    onProductSelected: (String) -> Unit,
    onLineItemSelected: (String) -> Unit,
    onObservedOnChanged: (String) -> Unit,
    onUnitPriceChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    var storeMenuExpanded by remember { mutableStateOf(false) }
    var lineMenuExpanded by remember { mutableStateOf(false) }
    val selectedStore = sources.firstOrNull { it.storeId == selectedStoreId }
    val selectedProduct = products.firstOrNull { it.catalogProductId == selectedCatalogProductId }
    val selectedLine = receipt.lineItems.firstOrNull { it.id == selectedLineItemId }
    val canSubmit = isSignedIn && selectedStore != null && selectedProduct != null && selectedLine != null &&
        observedOn.isNotBlank() && unitPriceKrw.isNotBlank() && !isSubmitting

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("price_observation_submit"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                "가격 관측 제출",
                "승인된 판매처와 정확한 상품을 직접 선택해야 합니다.",
                onBack,
            )
        }
        item {
            MonochromeHologramHero(
                eyebrow = "EXPLICIT SUBMISSION",
                title = "선택한 가격 1건만\nPriceTrace로 보냅니다",
                description = "판매처·관측일·정확한 상품 ID·단가와 임의 키만 전송합니다.",
                footer = if (isSignedIn) {
                    "PriceTrace 로그인 준비됨 · 원본 자료는 기기에 유지"
                } else {
                    "연결 설정에서 PriceTrace 로그인이 필요합니다"
                },
                modifier = Modifier.testTag("price_observation_hologram_hero"),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubmissionStepHeading(
                    number = "01",
                    title = "영수증 상품 행",
                    description = "제출할 가격이 적힌 행을 직접 고르세요.",
                )
                Box {
                    OutlinedButton(
                        onClick = { lineMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth().testTag("price_observation_line_selector"),
                    ) {
                        Text(selectedLine?.description ?: "영수증 상품 행 선택")
                    }
                    DropdownMenu(
                        expanded = lineMenuExpanded,
                        onDismissRequest = { lineMenuExpanded = false },
                    ) {
                        receipt.lineItems.forEach { line ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${line.description ?: "이름 없는 행"} · " +
                                            "${line.unitPriceAmountMinor ?: line.netAmountMinor ?: "가격 없음"}",
                                    )
                                },
                                onClick = {
                                    lineMenuExpanded = false
                                    onLineItemSelected(line.id)
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubmissionStepHeading(
                    number = "02",
                    title = "승인된 판매처",
                    description = "OCR 상호명으로 추정하지 않고 서버가 제공한 목록에서만 선택합니다.",
                )
                Box {
                    OutlinedButton(
                        onClick = { storeMenuExpanded = true },
                        enabled = sources.isNotEmpty() && !isLoadingSources,
                        modifier = Modifier.fillMaxWidth().testTag("price_observation_store_selector"),
                    ) {
                        Text(
                            selectedStore?.let { source ->
                                listOfNotNull(source.displayName, source.locationLabel).joinToString(" · ")
                            } ?: if (isLoadingSources) "승인 판매처 불러오는 중…" else "승인 판매처 선택",
                        )
                    }
                    DropdownMenu(
                        expanded = storeMenuExpanded,
                        onDismissRequest = { storeMenuExpanded = false },
                    ) {
                        sources.forEach { source ->
                            DropdownMenuItem(
                                text = {
                                    Text(listOfNotNull(source.displayName, source.locationLabel).joinToString(" · "))
                                },
                                onClick = {
                                    storeMenuExpanded = false
                                    onStoreSelected(source.storeId)
                                },
                            )
                        }
                    }
                }
                if (!isLoadingSources && sources.isEmpty()) {
                    Text(
                        "선택 가능한 승인 판매처가 없습니다. OCR 상호명으로 자동 생성하거나 추정하지 않습니다.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubmissionStepHeading(
                    number = "03",
                    title = "정확한 카탈로그 상품",
                    description = "상품명을 검색한 뒤 catalog_product_id가 표시된 결과를 직접 선택하세요.",
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChanged,
                        label = { Text("상품 검색") },
                        modifier = Modifier.weight(1f).testTag("price_observation_product_query"),
                        singleLine = true,
                    )
                    Button(
                        onClick = onSearchProducts,
                        enabled = query.isNotBlank() && !isLoadingProducts,
                        modifier = Modifier.align(Alignment.CenterVertically)
                            .testTag("price_observation_product_search"),
                    ) { Text(if (isLoadingProducts) "…" else "검색") }
                }
            }
        }
        if (products.isNotEmpty()) {
            items(products, key = { it.catalogProductId }) { product ->
                val selected = product.catalogProductId == selectedCatalogProductId
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onProductSelected(product.catalogProductId) }
                        .testTag("price_observation_product_${product.catalogProductId}"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.inverseSurface else {
                            MaterialTheme.colorScheme.surface
                        },
                        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (selected) "선택됨" else "선택",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.68f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(product.exactSelectionLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "catalog_product_id · ${product.catalogProductId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.68f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubmissionStepHeading(
                    number = "04",
                    title = "관측일과 단가",
                    description = "영수증 원본과 대조한 날짜와 원화 단가를 확인하세요.",
                )
                OutlinedTextField(
                    value = observedOn,
                    onValueChange = onObservedOnChanged,
                    label = { Text("관측일 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("price_observation_observed_on"),
                )
                OutlinedTextField(
                    value = unitPriceKrw,
                    onValueChange = onUnitPriceChanged,
                    label = { Text("단가 (원)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("price_observation_unit_price"),
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (canSubmit) MaterialTheme.colorScheme.surface else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (canSubmit) "제출 준비 완료" else "제출 전 선택을 완료하세요",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        listOfNotNull(
                            if (!isSignedIn) "PriceTrace 로그인" else null,
                            if (selectedLine == null) "영수증 상품 행" else null,
                            if (selectedStore == null) "승인 판매처" else null,
                            if (selectedProduct == null) "정확한 상품" else null,
                            if (observedOn.isBlank()) "관측일" else null,
                            if (unitPriceKrw.isBlank()) "단가" else null,
                        ).let { missing ->
                            if (missing.isEmpty()) {
                                "아래 버튼을 누르면 가격 관측 1건만 명시적으로 제출합니다."
                            } else {
                                "남은 항목 · ${missing.joinToString(" · ")}"
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    queueStatus?.let { status ->
                        Text(
                            "로컬 큐 · ${priceObservationStatusLabel(status)}" +
                                (appliedAction?.let { " · ${priceObservationActionLabel(it)}" } ?: ""),
                            color = when (status) {
                                PriceObservationQueueStatus.SUCCEEDED -> ReceiptPositive
                                PriceObservationQueueStatus.RETRYABLE_FAILURE -> ReceiptWarning
                                PriceObservationQueueStatus.NEEDS_REVIEW -> MaterialTheme.colorScheme.error
                                PriceObservationQueueStatus.PENDING -> MaterialTheme.colorScheme.onSurface
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("price_observation_queue_status"),
                        )
                    }
                    lastError?.let { error ->
                        Text(
                            "최근 오류 · $error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().testTag("submit_price_observation_button"),
            ) {
                if (isSubmitting) BusyIndicator()
                Text("선택한 가격 관측 제출")
            }
        }
        item {
            Text(
                "receipt.v2 전체, 이미지, OCR 원문, 영수증·문서·항목 ID와 결제정보는 서버로 보내지 않습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SubmissionStepHeading(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun priceObservationStatusLabel(status: PriceObservationQueueStatus): String = when (status) {
    PriceObservationQueueStatus.PENDING -> "제출 대기"
    PriceObservationQueueStatus.RETRYABLE_FAILURE -> "네트워크 재시도 가능"
    PriceObservationQueueStatus.NEEDS_REVIEW -> "직접 확인 필요"
    PriceObservationQueueStatus.SUCCEEDED -> "제출 완료"
}

private fun priceObservationActionLabel(action: String): String = when (action) {
    "created" -> "새 관측 생성"
    "deduplicated" -> "중복 제거"
    "replayed" -> "동일 요청 재처리"
    else -> action
}

@Composable
private fun EvaluationScreen(
    accuracySummary: ReviewAccuracySummary?,
    isBuildingAccuracyReport: Boolean,
    onRefreshAccuracy: () -> Unit,
    onShareAccuracy: () -> Unit,
    onBack: () -> Unit,
) {
    var boundaryAutomatic by remember { mutableStateOf(true) }
    var boundaryManual by remember { mutableStateOf(false) }
    var expectedText by remember { mutableStateOf("") }
    var recognizedText by remember { mutableStateOf("") }
    var expectedMerchant by remember { mutableStateOf("") }
    var parsedMerchant by remember { mutableStateOf("") }
    var expectedDate by remember { mutableStateOf("") }
    var parsedDate by remember { mutableStateOf("") }
    var expectedTotal by remember { mutableStateOf("") }
    var parsedTotal by remember { mutableStateOf("") }
    var expectedLines by remember { mutableStateOf("0") }
    var parsedLines by remember { mutableStateOf("0") }
    var matchedLines by remember { mutableStateOf("0") }
    var expectedSkus by remember { mutableStateOf("0") }
    var matchedSkus by remember { mutableStateOf("0") }
    var reconciliationSucceeded by remember { mutableStateOf(false) }
    var processingMillis by remember { mutableStateOf("0") }
    var modifiedFields by remember { mutableStateOf("0") }
    var samples by remember { mutableStateOf(emptyList<ReceiptEvaluationSample>()) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val summary = samples.takeIf { it.isNotEmpty() }?.let(ReceiptEvaluationCalculator::summarize)

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("evaluation_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("실제 기기 정확도 평가", "평가 자료는 Git에 포함하지 않습니다.", onBack) }
        item {
            ReviewAccuracyCard(
                summary = accuracySummary,
                isBuilding = isBuildingAccuracyReport,
                onRefresh = onRefreshAccuracy,
                onShare = onShareAccuracy,
            )
        }
        item { SectionTitle("수동 표본 입력 (선택)") }
        item {
            Text(
                "위 표는 확정한 영수증에서 자동으로 계산됩니다. 아래는 CER처럼 전체 전사 정답이 필요한 지표를 " +
                    "직접 입력할 때만 쓰세요. 이 입력은 저장하지 않으며 화면을 벗어나면 폐기됩니다.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            ToggleRow("문서 경계 자동 검출 성공", boundaryAutomatic) { boundaryAutomatic = it }
        }
        item {
            ToggleRow("수동 경계 수정 사용", boundaryManual) { boundaryManual = it }
        }
        item { ReviewTextField("정답 전체 텍스트", expectedText, { expectedText = it }, supporting = "CER 분모가 되는 직접 전사 정답") }
        item { ReviewTextField("OCR 전체 텍스트", recognizedText, { recognizedText = it }, supporting = "앱 로그나 Git에는 기록되지 않음") }
        item { ReviewTextField("정답 판매처", expectedMerchant, { expectedMerchant = it }) }
        item { ReviewTextField("파싱 판매처", parsedMerchant, { parsedMerchant = it }) }
        item { ReviewTextField("정답 날짜", expectedDate, { expectedDate = it }) }
        item { ReviewTextField("파싱 날짜", parsedDate, { parsedDate = it }) }
        item { ReviewTextField("정답 최종합계", expectedTotal, { expectedTotal = it }) }
        item { ReviewTextField("파싱 최종합계", parsedTotal, { parsedTotal = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ReviewTextField("정답 행 수", expectedLines, { expectedLines = it }) }
                Box(Modifier.weight(1f)) { ReviewTextField("파싱 행 수", parsedLines, { parsedLines = it }) }
                Box(Modifier.weight(1f)) { ReviewTextField("일치 행 수", matchedLines, { matchedLines = it }) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ReviewTextField("정답 SKU 수", expectedSkus, { expectedSkus = it }) }
                Box(Modifier.weight(1f)) { ReviewTextField("일치 SKU 수", matchedSkus, { matchedSkus = it }) }
            }
        }
        item {
            ToggleRow("합계 reconciliation 성공", reconciliationSucceeded) { reconciliationSucceeded = it }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ReviewTextField("처리시간 ms", processingMillis, { processingMillis = it }) }
                Box(Modifier.weight(1f)) { ReviewTextField("사용자 수정 필드 수", modifiedFields, { modifiedFields = it }) }
            }
        }
        inputError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("evaluation_input_error")) }
        }
        item {
            Button(
                onClick = {
                    val sample = runCatching {
                        ReceiptEvaluationSample(
                            boundaryDetectedAutomatically = boundaryAutomatic,
                            boundaryCorrectedManually = boundaryManual,
                            expectedText = expectedText,
                            recognizedText = recognizedText,
                            expectedMerchant = expectedMerchant.blankAsNull(),
                            parsedMerchant = parsedMerchant.blankAsNull(),
                            expectedDate = expectedDate.blankAsNull(),
                            parsedDate = parsedDate.blankAsNull(),
                            expectedGrandTotalMinor = expectedTotal.blankAsNull()?.toLong(),
                            parsedGrandTotalMinor = parsedTotal.blankAsNull()?.toLong(),
                            expectedLineCount = expectedLines.toInt(),
                            parsedLineCount = parsedLines.toInt(),
                            matchedLineCount = matchedLines.toInt(),
                            expectedSkuCount = expectedSkus.toInt(),
                            matchedSkuCount = matchedSkus.toInt(),
                            reconciliationSucceeded = reconciliationSucceeded,
                            processingTimeMillis = processingMillis.toLong(),
                            userModifiedFieldCount = modifiedFields.toInt(),
                        )
                    }
                    sample.onSuccess {
                        samples = samples + it
                        inputError = null
                    }.onFailure {
                        inputError = "수치 형식과 일치 수가 정답/파싱 수를 넘지 않는지 확인하세요."
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("add_evaluation_sample"),
            ) {
                Text("현재 영수증 샘플 추가")
            }
        }
        summary?.let { value ->
            item {
                EvaluationSummaryCard(value)
            }
        }
        item {
            Text(
                "측정 표본 없이 ‘vFlat 수준’ 또는 특정 정확도를 주장할 수 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Field-level error rates derived from confirmed receipts. No separate ground truth is entered here —
 * the corrections the reviewer already made are the labels.
 */
@Composable
private fun ReviewAccuracyCard(
    summary: ReviewAccuracySummary?,
    isBuilding: Boolean,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("review_accuracy_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("검수 이력 기반 필드 오류율", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                isBuilding -> Text("계산 중입니다…")
                summary == null -> Text(
                    "확정한 영수증이 아직 없습니다. 검수를 마친 영수증이 쌓이면 어느 필드가 가장 많은 " +
                        "수정을 유발했는지 자동으로 계산됩니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        "표본 ${summary.sampleCount}건 · 상품 행 ${summary.lineItemCount}개 · " +
                            "영수증당 평균 수정 ${String.format(Locale.US, "%.1f", summary.correctionsPerReceipt)}회",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        summary.medianReviewSeconds?.let { seconds ->
                            "검수 소요 시간 중앙값 ${seconds / 60}분 ${seconds % 60}초 (측정 ${summary.timedSampleCount}건)"
                        } ?: "검수 소요 시간: 측정된 표본 없음",
                        modifier = Modifier.testTag("review_duration"),
                    )
                    if (summary.parserVersions.isNotEmpty()) {
                        Text(
                            "파서 ${summary.parserVersions.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val worst = summary.worstFields
                    if (worst.isEmpty()) {
                        Text("기록된 수정이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        worst.forEach { field ->
                            Column(Modifier.testTag("accuracy_${field.group.name}")) {
                                Text(
                                    "${field.group.label} · 오류율 ${field.errorRate.asPercentOrNa()} " +
                                        "(${field.correctedCount}/${field.observedCount})",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "틀리게 읽음 ${field.misreadCount} · 못 읽음 ${field.missedCount} · " +
                                        "없는 값 생성 ${field.spuriousCount}" +
                                        (
                                            field.averageEditDistance?.let {
                                                " · 평균 ${String.format(Locale.US, "%.1f", it)}자 수정"
                                            } ?: ""
                                            ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        "이 수치는 사용자가 발견한 오류만 셉니다. 놓친 오류는 정답으로 집계되므로 실제 오류율의 하한입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isBuilding,
                    modifier = Modifier.weight(1f).testTag("refresh_accuracy_button"),
                ) { Text("다시 계산") }
                OutlinedButton(
                    onClick = onShare,
                    enabled = summary != null && !isBuilding,
                    modifier = Modifier.weight(1f).testTag("share_accuracy_button"),
                ) { Text("보고서 공유") }
            }
            Text(
                "보고서에는 건수와 비율만 담기며 판매처명·주소·상품명과 OCR 원문은 포함되지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, onValueChange = onChanged),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun EvaluationSummaryCard(summary: ReceiptEvaluationSummary) {
    Card(modifier = Modifier.fillMaxWidth().testTag("evaluation_summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("집계 ${summary.sampleCount}건", fontWeight = FontWeight.Bold)
            Text("문서 경계 자동 검출 성공률: ${summary.automaticBoundaryDetectionRate.asPercent()}")
            Text("수동 경계 수정률: ${summary.manualBoundaryCorrectionRate.asPercent()}")
            Text("OCR Character Error Rate: ${summary.characterErrorRate.asPercentOrNa()}")
            Text("판매처명 정확도: ${summary.merchantAccuracy.asPercentOrNa()}")
            Text("날짜 정확도: ${summary.dateAccuracy.asPercentOrNa()}")
            Text("최종 합계 정확도: ${summary.grandTotalAccuracy.asPercentOrNa()}")
            Text("상품 행 precision: ${summary.linePrecision.asPercentOrNa()}")
            Text("상품 행 recall: ${summary.lineRecall.asPercentOrNa()}")
            Text("상품코드 정확도: ${summary.skuAccuracy.asPercentOrNa()}")
            Text("합계 reconciliation 성공률: ${summary.reconciliationSuccessRate.asPercent()}")
            Text("평균 처리시간: ${String.format(Locale.US, "%.0f ms", summary.averageProcessingTimeMillis)}")
            Text("평균 사용자 수정 필드 수: ${String.format(Locale.US, "%.1f", summary.averageUserModifiedFieldCount)}")
        }
    }
}

private fun String.blankAsNull(): String? = trim().takeIf(String::isNotEmpty)
private fun Double.asPercent(): String = String.format(Locale.US, "%.1f%%", this * 100)
private fun Double?.asPercentOrNa(): String = this?.asPercent() ?: "N/A (분모 없음)"

@Composable
private fun EvidenceImage(
    page: ReceiptPage,
    file: File,
    highlights: List<BoundingBox>,
    zoomEnabled: Boolean,
    height: Int = 320,
) {
    val bitmap by produceState<ImageBitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(file)?.asImageBitmap() }
    }
    var scale by remember(page.id) { mutableFloatStateOf(1f) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        if (zoomEnabled) {
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + panChange
        }
    }
    Card {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(height.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    Text("이미지를 표시할 수 없습니다.")
                } else {
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            )
                            .transformable(transformState),
                    ) {
                        Image(
                            bitmap = requireNotNull(bitmap),
                            contentDescription = "영수증 ${page.pageIndex + 1}페이지",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        if (highlights.isNotEmpty()) {
                            Canvas(Modifier.fillMaxSize()) {
                                val imageAspect = page.width.toFloat() / page.height.toFloat()
                                val canvasAspect = size.width / size.height
                                val drawWidth: Float
                                val drawHeight: Float
                                val leftOffset: Float
                                val topOffset: Float
                                if (canvasAspect > imageAspect) {
                                    drawHeight = size.height
                                    drawWidth = drawHeight * imageAspect
                                    leftOffset = (size.width - drawWidth) / 2f
                                    topOffset = 0f
                                } else {
                                    drawWidth = size.width
                                    drawHeight = drawWidth / imageAspect
                                    leftOffset = 0f
                                    topOffset = (size.height - drawHeight) / 2f
                                }
                                highlights.forEach { box ->
                                    val left = leftOffset + box.left.toFloat() / page.width * drawWidth
                                    val top = topOffset + box.top.toFloat() / page.height * drawHeight
                                    val right = leftOffset + box.right.toFloat() / page.width * drawWidth
                                    val bottom = topOffset + box.bottom.toFloat() / page.height * drawHeight
                                    drawRect(
                                        color = Color(0xFFFF3B30),
                                        topLeft = Offset(left, top),
                                        size = androidx.compose.ui.geometry.Size(
                                            max(1f, right - left),
                                            max(1f, bottom - top),
                                        ),
                                        style = Stroke(width = 4f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "페이지 ${page.pageIndex + 1} · ${page.width}×${page.height} · SHA-256 ${page.sha256.take(12)}… · r${page.revision}",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun decodeSampledBitmap(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 2600) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun ReviewTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
    supporting: String? = null,
    testTag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        supportingText = when {
            supporting != null -> ({ Text(supporting) })
            isError -> ({ Text("확인이 필요한 필드입니다.") })
            else -> null
        },
        singleLine = supporting == null,
        modifier = Modifier.fillMaxWidth().let { modifier ->
            testTag?.let(modifier::testTag) ?: modifier
        },
    )
}

/** Shows how much of the draft still needs attention and lets the reviewer take back a wrong edit. */
@Composable
private fun ReviewToolbar(
    progress: ReceiptReviewProgress?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("review_toolbar")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress != null) {
                Text(
                    "행 ${progress.settledLineItemCount}/${progress.lineItemCount} 확인 · " +
                        "차단 ${progress.blockingIssueCount}건 · 주의 ${progress.warningIssueCount}건",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { progress.lineCompletionRatio.toFloat() },
                    modifier = Modifier.fillMaxWidth().testTag("review_progress"),
                )
                if (progress.userEnteredLineItemCount > 0) {
                    Text(
                        "사용자 직접 입력 ${progress.userEnteredLineItemCount}행은 OCR 근거가 없으므로 한 번 더 대조하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier.weight(1f).testTag("undo_button"),
                ) { Text("되돌리기") }
                OutlinedButton(
                    onClick = onRedo,
                    enabled = canRedo,
                    modifier = Modifier.weight(1f).testTag("redo_button"),
                ) { Text("다시 적용") }
            }
        }
    }
}

@Composable
private fun HypothesisCard(
    hypothesis: ReconciliationHypothesis,
    onApplySuggestion: (ReconciliationSuggestion) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("hypothesis_${hypothesis.code.name}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(hypothesis.message)
            Text(
                hypothesis.evidence,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hypothesis.suggestion?.let { suggestion ->
                OutlinedButton(
                    onClick = { onApplySuggestion(suggestion) },
                    modifier = Modifier.testTag("apply_${hypothesis.code.name}"),
                ) { Text("원본과 같다면 적용") }
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("← 이전") }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun BusyIndicator() {
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
    )
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialCard(
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

private fun ReceiptValidationResult?.hasIssue(pathPrefix: String): Boolean =
    this?.issues?.any { it.fieldPath.startsWith(pathPrefix) } == true
