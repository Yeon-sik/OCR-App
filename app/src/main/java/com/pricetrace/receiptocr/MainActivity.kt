package com.pricetrace.receiptocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.pricetrace.receiptscanner.capture.MlKitDocumentCaptureProvider

class MainActivity : ComponentActivity() {
    private val viewModel: ReceiptAppViewModel by viewModels()
    private var pickImagesForAppend = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            viewModel.consumeScannerResult(result.resultCode, result.data)
        }

        val imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MlKitDocumentCaptureProvider.DEFAULT_PAGE_LIMIT),
        ) { uris ->
            val appendToCurrent = pickImagesForAppend
            pickImagesForAppend = false
            viewModel.consumeSelectedImages(uris, appendToCurrent)
        }
        setContent {
            ReceiptOcrTheme {
                ReceiptOcrApp(
                    viewModel = viewModel,
                    onLaunchScanner = { intentSender ->
                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    },
                    onLaunchImagePicker = { appendToCurrent ->
                        pickImagesForAppend = appendToCurrent
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }
        }
    }
}
