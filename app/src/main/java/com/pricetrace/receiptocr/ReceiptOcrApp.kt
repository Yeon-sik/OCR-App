package com.pricetrace.receiptocr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider

@Composable
fun ReceiptOcrApp(
    viewModel: ReceiptAppViewModel,
    onLaunchScanner: (IntentSender) -> Unit,
    onLaunchImagePicker: (Boolean) -> Unit,
    onLaunchJsonPicker: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val uiState by viewModel.uiState.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val pages by viewModel.selectedPages.collectAsState()

    BackHandler(enabled = uiState.screen != AppScreen.SESSION_LIST) {
        viewModel.goBack()
    }

    LaunchedEffect(viewModel, context) {
        viewModel.events.collect { event ->
            when (event) {
                is ReceiptUiEvent.ShareJson -> {
                    val file = viewModel.resolvePageFile(event.storageKey)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.files",
                        file,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "검증된 receipt.v2 공유"))
                }
                is ReceiptUiEvent.ShareAccuracyReport -> {
                    val file = viewModel.resolvePageFile(event.storageKey)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "정확도 보고서 공유"))
                }
            }
        }
    }

    ReceiptOcrContent(
        uiState = uiState,
        sessions = sessions,
        pages = pages,
        resolvePageFile = viewModel::resolvePageFile,
        onScan = {
            activity?.let { viewModel.prepareScanner(it, onLaunchScanner) }
        },
        onPickImages = { onLaunchImagePicker(false) },
        onPickJson = onLaunchJsonPicker,
        onWorkflowSelected = viewModel::selectWorkflow,
        onAppendScan = {
            activity?.let { viewModel.prepareScanner(it, onLaunchScanner, appendToCurrent = true) }
        },
        onAppendPickImages = { onLaunchImagePicker(true) },
        onStartImportReview = viewModel::startImportReview,
        onAttachImportImage = { onLaunchImagePicker(true) },
        onCancelImport = viewModel::cancelImportPreview,
        onSelectSession = viewModel::selectSession,
        onDeleteSession = viewModel::deleteSession,
        onShowApiSettings = viewModel::showApiSettings,
        onStartOcr = viewModel::startOcr,
        onNutritionProductNameChanged = viewModel::updateNutritionProductName,
        onNutritionBrandChanged = viewModel::updateNutritionBrand,
        onNutritionCategoryChanged = viewModel::updateNutritionCategory,
        onNutritionBasisAmountChanged = viewModel::updateNutritionBasisAmount,
        onNutritionBasisUnitChanged = viewModel::updateNutritionBasisUnit,
        onNutritionValueChanged = viewModel::updateNutritionValue,
        onSaveNutritionConnection = viewModel::saveNutritionConnection,
        onSignInNutrition = viewModel::signInNutrition,
        onConfirmAndPublishNutrition = viewModel::confirmAndPublishNutrition,
        onRequestNutritionAiCorrections = viewModel::requestNutritionAiCorrections,
        onApplyNutritionAiCorrection = viewModel::applyNutritionAiCorrection,
        onDismissNutritionAiCorrection = viewModel::dismissNutritionAiCorrection,
        onSavePriceTraceConnection = viewModel::savePriceTraceConnection,
        onSignInPriceTrace = viewModel::signInPriceTrace,
        onSaveCashOsConnection = viewModel::saveCashOsConnection,
        onSignInCashOs = viewModel::signInCashOs,
        onLoadCashOsLedgerCandidates = viewModel::loadCashOsLedgerCandidates,
        onSelectCashOsLedgerEntry = viewModel::selectCashOsLedgerEntry,
        onSubmitCashOsReceipt = viewModel::submitCashOsReceipt,
        onMerchantNameChanged = viewModel::updateMerchantName,
        onBranchNameChanged = viewModel::updateBranchName,
        onBusinessRegistrationNumberChanged = viewModel::updateBusinessRegistrationNumber,
        onAddressChanged = viewModel::updateAddress,
        onPhoneChanged = viewModel::updatePhone,
        onOriginalDocumentIdChanged = viewModel::updateOriginalDocumentId,
        onIssuedOnChanged = viewModel::updateIssuedOn,
        onIssuedLocalTimeChanged = viewModel::updateIssuedLocalTime,
        onIssuedAtChanged = viewModel::updateIssuedAt,
        onCurrencyChanged = viewModel::updateCurrency,
        onGrandTotalChanged = viewModel::updateGrandTotal,
        onSubtotalChanged = viewModel::updateSubtotal,
        onDiscountTotalChanged = viewModel::updateDiscountTotal,
        onTaxTotalChanged = viewModel::updateTaxTotal,
        onFeeTotalChanged = viewModel::updateFeeTotal,
        onPaymentMethodChanged = viewModel::updatePaymentMethod,
        onPaymentAmountChanged = viewModel::updatePaymentAmount,
        onLineDescriptionChanged = viewModel::updateLineDescription,
        onLineTypeChanged = viewModel::updateLineType,
        onMerchantSkuChanged = viewModel::updateMerchantSku,
        onLineQuantityChanged = viewModel::updateLineQuantity,
        onLineUnitPriceChanged = viewModel::updateLineUnitPrice,
        onLineNetAmountChanged = viewModel::updateLineNetAmount,
        onAddLineItem = viewModel::addLineItem,
        onRemoveLineItem = viewModel::removeLineItem,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onShowOnlyAttentionItemsChanged = viewModel::setShowOnlyAttentionItems,
        onShowAiCorrection = viewModel::showAiCorrectionReview,
        onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
        onClearGeminiApiKey = viewModel::clearGeminiApiKey,
        onRequestAiCorrections = viewModel::requestAiCorrections,
        onContinueAiPreflight = viewModel::continueFromAiPreflight,
        onApplyAiCorrection = viewModel::applyAiCorrection,
        onDismissAiCorrection = viewModel::dismissAiCorrection,
        onApplySuggestion = viewModel::applyReconciliationSuggestion,
        onReconciliationReasonChanged = viewModel::updateReconciliationReason,
        onBack = { viewModel.goBack() },
        onShowFields = viewModel::showFieldReview,
        onShowItems = viewModel::showItemReview,
        onShowReconciliation = viewModel::showReconciliation,
        onShowJson = viewModel::showJsonPreview,
        onConfirmVerified = viewModel::confirmUserVerified,
        onShowPriceObservationSubmit = viewModel::showPriceObservationSubmit,
        onPriceObservationQueryChanged = viewModel::updatePriceObservationQuery,
        onSearchPriceObservationProducts = viewModel::searchPriceObservationProducts,
        onPriceObservationStoreSelected = viewModel::selectPriceObservationStore,
        onPriceObservationProductSelected = viewModel::selectPriceObservationProduct,
        onPriceObservationLineItemSelected = viewModel::selectPriceObservationLineItem,
        onPriceObservationObservedOnChanged = viewModel::updatePriceObservationObservedOn,
        onPriceObservationUnitPriceChanged = viewModel::updatePriceObservationUnitPrice,
         onSubmitPriceObservation = viewModel::submitPriceObservation,
         onSubmitRestaurantReceipt = viewModel::submitRestaurantReceipt,
        onIncludeRawTextChanged = viewModel::setIncludeRawTextInShare,
        onSave = viewModel::saveVerifiedJson,
        onShare = viewModel::shareVerifiedJson,
        onShowEvaluation = viewModel::showEvaluation,
        onRefreshAccuracy = viewModel::buildAccuracyReport,
        onShareAccuracy = viewModel::shareAccuracyReport,
        onDismissMessage = viewModel::clearMessage,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
