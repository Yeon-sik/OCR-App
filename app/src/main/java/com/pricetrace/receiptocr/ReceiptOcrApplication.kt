package com.pricetrace.receiptocr

import android.app.Application
import com.pricetrace.receiptocr.gemini.DirectGeminiReceiptCorrectionSuggester
import com.pricetrace.receiptocr.gemini.DirectGeminiNutritionCorrectionSuggester
import com.pricetrace.receiptocr.gemini.EncryptedGeminiApiKeyStore
import com.pricetrace.receiptocr.gemini.GeminiApiKeyStore
import com.pricetrace.receiptocr.fitness.AndroidNutritionSupabaseStore
import com.pricetrace.receiptocr.fitness.FitnessCanonicalProjectionSubmitter
import com.pricetrace.receiptocr.fitness.FitnessMealProjectionSubmitter
import com.pricetrace.receiptocr.fitness.FitnessProductNutritionLinkProjectionSubmitter
import com.pricetrace.receiptocr.fitness.NutritionSupabaseGateway
import com.pricetrace.receiptocr.fitness.NutritionGatewayFailure
import com.pricetrace.receiptocr.fitness.ProductRevisionReadOutcome
import com.pricetrace.receiptocr.fitness.ProductRevisionReadResult
import com.pricetrace.receiptocr.fitness.PriceTraceProductRevisionReader
import com.pricetrace.receiptocr.pricetrace.AndroidPriceTraceSupabaseStore
import com.pricetrace.receiptocr.pricetrace.AndroidCashOsSupabaseStore
import com.pricetrace.receiptocr.pricetrace.AndroidCashOsLedgerSelectionStore
import com.pricetrace.receiptocr.pricetrace.CashOsCanonicalProjectionSubmitter
import com.pricetrace.receiptocr.pricetrace.PriceTraceCanonicalGateway
import com.pricetrace.receiptocr.pricetrace.PriceTraceCanonicalProjectionSubmitter
import com.pricetrace.receiptocr.pricetrace.PriceObservationGateway
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.capture.MlKitDocumentCaptureProvider
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionSuggester
import com.pricetrace.receiptscanner.export.ReceiptExportService
import com.pricetrace.receiptscanner.ocr.MlKitDocumentOcrEngine
import com.pricetrace.receiptscanner.nutrition.NutritionCorrectionSuggester
import com.pricetrace.receiptscanner.nutrition.NutritionLabelParser
import com.pricetrace.receiptscanner.parser.GenericReceiptParser
import com.pricetrace.receiptscanner.publisher.LocalOnlyReceiptPublisher
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
import com.pricetrace.receiptscanner.storage.PriceObservationQueueProcessor
import com.pricetrace.receiptscanner.storage.RoomPriceObservationQueueRepository
import com.pricetrace.receiptscanner.storage.RoomReceiptSessionRepository
import com.pricetrace.receiptscanner.storage.RoomIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionOrchestrator
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter

class ReceiptOcrApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val fileStore = ReceiptFileStore(application)
    val sessionRepository = RoomReceiptSessionRepository.create(application, fileStore)
    val ingestionSessionStore = RoomIngestionSessionStore.create(application)
    /**
     * The app keeps payload construction in the existing gateways. This coordinator owns
     * durable lifecycle state and refuses implicit cross-service identity inference.
     */
    val captureProvider = MlKitDocumentCaptureProvider(
        context = application,
        fileStore = fileStore,
        sessionRepository = sessionRepository,
    )
    fun createOcrEngine() = MlKitDocumentOcrEngine()
    val parser = GenericReceiptParser()
    val nutritionParser = NutritionLabelParser()
    val exportService = ReceiptExportService(fileStore)
    /** receipt.v2 export remains local-only; price observation submission is a separate boundary. */
    val publisher = LocalOnlyReceiptPublisher()
    val priceObservationQueue = RoomPriceObservationQueueRepository.create(application)
    val geminiApiKeyStore: GeminiApiKeyStore = EncryptedGeminiApiKeyStore(application)
    val correctionSuggester: ReceiptCorrectionSuggester =
        DirectGeminiReceiptCorrectionSuggester(
            apiKeyProvider = geminiApiKeyStore::read,
            modelName = BuildConfig.DEFAULT_GEMINI_MODEL,
        )
    val nutritionCorrectionSuggester: NutritionCorrectionSuggester =
        DirectGeminiNutritionCorrectionSuggester(
            apiKeyProvider = geminiApiKeyStore::read,
            modelName = BuildConfig.DEFAULT_GEMINI_MODEL,
        )
    internal val nutritionSupabaseStore = AndroidNutritionSupabaseStore(application)
    internal val nutritionGateway = NutritionSupabaseGateway(nutritionSupabaseStore)
    internal val priceTraceSupabaseStore = AndroidPriceTraceSupabaseStore(application)
    internal val priceObservationGateway = PriceObservationGateway(priceTraceSupabaseStore)
    /** CashOS has an independent encrypted connection/session store; it never reuses PriceTrace credentials. */
    internal val cashOsSupabaseStore = AndroidCashOsSupabaseStore(application)
    internal val cashOsLedgerSelectionStore = AndroidCashOsLedgerSelectionStore(application)
    internal val cashOsReceiptGateway = com.pricetrace.receiptocr.pricetrace.CashOsReceiptGateway(cashOsSupabaseStore)
    internal val priceTraceCanonicalGateway = PriceTraceCanonicalGateway(priceTraceSupabaseStore)
    private val productRevisionReader = PriceTraceProductRevisionReader { catalogProductId ->
        when (val result = priceTraceCanonicalGateway.readExactProductRevision(catalogProductId)) {
            is com.pricetrace.receiptocr.pricetrace.PriceTraceProductReadOutcome.Success ->
                ProductRevisionReadOutcome.Success(ProductRevisionReadResult(result.revision))
            is com.pricetrace.receiptocr.pricetrace.PriceTraceProductReadOutcome.Failure ->
                ProductRevisionReadOutcome.Failure(
                    reason = result.kind.toNutritionGatewayFailure(),
                    message = result.message,
                )
        }
    }
    internal val canonicalProjectionSubmitters: Map<IngestionProjection, IngestionProjectionSubmitter> = mapOf(
        IngestionProjection.PRICETRACE_RECEIPT to PriceTraceCanonicalProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION to PriceTraceCanonicalProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE to PriceTraceCanonicalProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to com.pricetrace.receiptocr.pricetrace.PriceTraceProductCandidateProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.FITNESS_NUTRITION to FitnessCanonicalProjectionSubmitter(nutritionGateway),
        IngestionProjection.FITNESS_MEAL to FitnessMealProjectionSubmitter(nutritionGateway),
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK to FitnessProductNutritionLinkProjectionSubmitter(
            nutritionGateway = nutritionGateway,
            productRevisionReader = productRevisionReader,
        ),
        IngestionProjection.CASHOS_RECEIPT to CashOsCanonicalProjectionSubmitter(cashOsReceiptGateway),
    )
    internal val ingestionOrchestrator = IngestionOrchestrator(
        store = ingestionSessionStore,
        identityResolver = null,
        submitters = canonicalProjectionSubmitters,
    )
    internal val priceObservationProcessor = PriceObservationQueueProcessor(
        queue = priceObservationQueue,
        submitter = priceObservationGateway,
    )
}

private fun PriceObservationFailureKind.toNutritionGatewayFailure(): NutritionGatewayFailure = when (this) {
    PriceObservationFailureKind.NOT_CONFIGURED -> NutritionGatewayFailure.NOT_CONFIGURED
    PriceObservationFailureKind.AUTHENTICATION -> NutritionGatewayFailure.AUTHENTICATION
    PriceObservationFailureKind.NETWORK,
    PriceObservationFailureKind.NETWORK_TIMEOUT -> NutritionGatewayFailure.NETWORK
    PriceObservationFailureKind.SERVER -> NutritionGatewayFailure.SERVER
    PriceObservationFailureKind.IDEMPOTENCY_MISMATCH,
    PriceObservationFailureKind.INVALID_SELECTION,
    PriceObservationFailureKind.CONTRACT -> NutritionGatewayFailure.CONTRACT
}
