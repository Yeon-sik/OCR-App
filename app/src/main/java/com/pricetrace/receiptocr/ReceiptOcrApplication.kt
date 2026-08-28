package com.pricetrace.receiptocr

import android.app.Application
import com.pricetrace.receiptocr.gemini.DirectGeminiReceiptCorrectionSuggester
import com.pricetrace.receiptocr.gemini.DirectGeminiNutritionCorrectionSuggester
import com.pricetrace.receiptocr.gemini.EncryptedGeminiApiKeyStore
import com.pricetrace.receiptocr.gemini.GeminiApiKeyStore
import com.pricetrace.receiptocr.fitness.AndroidNutritionSupabaseStore
import com.pricetrace.receiptocr.fitness.NutritionSupabaseGateway
import com.pricetrace.receiptocr.pricetrace.AndroidPriceTraceSupabaseStore
import com.pricetrace.receiptocr.pricetrace.AndroidCashOsSupabaseStore
import com.pricetrace.receiptocr.pricetrace.AndroidCashOsLedgerSelectionStore
import com.pricetrace.receiptocr.pricetrace.PriceObservationGateway
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
import com.pricetrace.receiptscanner.ingestion.IdentityResolution
import com.pricetrace.receiptscanner.ingestion.IdentityResolutionStatus
import com.pricetrace.receiptscanner.ingestion.IngestionIdentityResolver
import com.pricetrace.receiptscanner.ingestion.IngestionOrchestrator
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope

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
    internal val ingestionOrchestrator = IngestionOrchestrator(
        store = ingestionSessionStore,
        identityResolver = object : IngestionIdentityResolver {
            override suspend fun resolve(projection: IngestionProjection, envelope: YeonsikOcrEnvelope) =
                IdentityResolution(IdentityResolutionStatus.NOT_FOUND, message = "explicit_projection_identity_required")
        },
        submitters = emptyMap(),
    )
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
    internal val priceObservationProcessor = PriceObservationQueueProcessor(
        queue = priceObservationQueue,
        submitter = priceObservationGateway,
    )
}
