package com.pricetrace.receiptocr

import android.app.Application
import com.pricetrace.receiptocr.gemini.DirectGeminiReceiptCorrectionSuggester
import com.pricetrace.receiptocr.gemini.EncryptedGeminiApiKeyStore
import com.pricetrace.receiptocr.gemini.GeminiApiKeyStore
import com.pricetrace.receiptscanner.capture.MlKitDocumentCaptureProvider
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionSuggester
import com.pricetrace.receiptscanner.export.ReceiptExportService
import com.pricetrace.receiptscanner.ocr.MlKitReceiptOcrEngine
import com.pricetrace.receiptscanner.parser.GenericReceiptParser
import com.pricetrace.receiptscanner.publisher.LocalOnlyReceiptPublisher
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
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
    fun createOcrEngine() = MlKitReceiptOcrEngine()
    val parser = GenericReceiptParser()
    val exportService = ReceiptExportService(fileStore)
    val publisher = LocalOnlyReceiptPublisher()
    val geminiApiKeyStore: GeminiApiKeyStore = EncryptedGeminiApiKeyStore(application)
    val correctionSuggester: ReceiptCorrectionSuggester =
        DirectGeminiReceiptCorrectionSuggester(geminiApiKeyStore::read)
}
