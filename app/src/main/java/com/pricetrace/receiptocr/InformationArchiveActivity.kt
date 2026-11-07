package com.pricetrace.receiptocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.pricetrace.receiptocr.information.InformationArchiveViewModel

class InformationArchiveActivity : ComponentActivity() {
    private val model: InformationArchiveViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.getString("information_export_id")?.let { model.pendingExportId = it }
        model.pendingExportIsSource = savedInstanceState?.getBoolean("information_export_source") ?: model.pendingExportIsSource
        val sourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.captureUri(it, false) } }
        val recordPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.captureUri(it, true) } }
        val exportRecord = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
        val exportSource = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let(model::export) }
        setContent {
            ReceiptOcrTheme {
                InformationArchiveScreen(model,
                    onPickSource = { sourcePicker.launch(arrayOf("*/*")) },
                    onImportRecord = { recordPicker.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")) },
                    onExportRecord = { id -> model.pendingExportId = id; model.pendingExportIsSource = false; exportRecord.launch("$id.information-record.json") },
                    onExportSource = { source -> model.pendingExportId = source.id; model.pendingExportIsSource = true; exportSource.launch(source.name) },
                    onClose = { finish() },
                )
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("information_export_id", model.pendingExportId)
        outState.putBoolean("information_export_source", model.pendingExportIsSource)
        super.onSaveInstanceState(outState)
    }
}
