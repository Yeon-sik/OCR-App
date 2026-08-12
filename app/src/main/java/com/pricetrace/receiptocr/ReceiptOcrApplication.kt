package com.pricetrace.receiptocr

import android.app.Application
import com.pricetrace.receiptocr.gemini.DirectGeminiReceiptCorrectionSuggester
import com.pricetrace.receiptocr.gemini.EncryptedGeminiApiKeyStore
import com.pricetrace.receiptocr.gemini.GeminiApiKeyStore
import com.pricetrace.receiptocr.fitness.AndroidNutritionSupabaseStore
import com.pricetrace.receiptocr.fitness.NutritionSupabaseGateway
import com.pricetrace.receiptocr.pricetrace.AndroidPriceTraceSupabaseStore
import com.pricetrace.receiptocr.pricetrace.PriceObservationGateway
import com.pricetrace.receiptscanner.capture.MlKitDocumentCaptureProvider
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionSuggester
import com.pricetrace.receiptscanner.export.ReceiptExportService
import com.pricetrace.receiptscanner.ocr.MlKitDocumentOcrEngine
import com.pricetrace.receiptscanner.nutrition.NutritionLabelParser
import com.pricetrace.receiptscanner.parser.GenericReceiptParser
import com.pricetrace.receiptscanner.publisher.LocalOnlyReceiptPublisher
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
import com.pricetrace.receiptscanner.storage.PriceObservationQueueProcessor
import com.pricetrace.receiptscanner.storage.RoomPriceObservationQueueRepository
import com.pricetrace.receiptscanner.storage.RoomReceiptSessionRepository

class ReceiptOcrApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val fileStore = ReceiptFileStore(application)
    val sessionRepository = RoomReceiptSessionRepository.create(application, fileStore)
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
    internal val nutritionSupabaseStore = AndroidNutritionSupabaseStore(application)
    internal val nutritionGateway = NutritionSupabaseGateway(nutritionSupabaseStore)
    internal val priceTraceSupabaseStore = AndroidPriceTraceSupabaseStore(application)
    internal val priceObservationGateway = PriceObservationGateway(priceTraceSupabaseStore)
    internal val priceObservationProcessor = PriceObservationQueueProcessor(
        queue = priceObservationQueue,
        submitter = priceObservationGateway,
    )
}
