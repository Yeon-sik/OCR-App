package com.pricetrace.receiptocr

import android.os.Bundle
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
                    onHealthSource = { source -> shareHealth(source.id, source.mediaType, true) },
                    onHealthPrescription = { id -> shareHealth(id, "application/json", false) },
                )
            }
        }
    }
    private fun shareHealth(id: String, mime: String, source: Boolean) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(cacheDir,"health-prescription-share").apply { mkdirs() }
                    val target = File(directory, "$id.${if (source) "image" else "json"}")
                    target.outputStream().use { if(source) model.store.exportSource(id,it) else model.store.exportRecord(id,it) }
                    require(target.length() <= if(source) 25L * 1024 * 1024 else 1024 * 1024) { "전달 가능한 파일 크기를 초과했습니다." }
                    target
                }
                val uri = FileProvider.getUriForFile(this@InformationArchiveActivity,"$packageName.files",file)
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type=mime
                    setPackage("com.yeonsik.health.health_observation_app")
                    putExtra(Intent.EXTRA_STREAM,uri)
                    clipData=ClipData.newRawUri("처방 정보",uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this@InformationArchiveActivity,"HealthTrace를 설치·업데이트한 뒤 다시 시도하세요.",Toast.LENGTH_LONG).show()
            } catch (_:Exception) {
                Toast.makeText(this@InformationArchiveActivity,"파일을 전달하지 못했습니다. 원문은 보관함에 유지됩니다.",Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("information_export_id", model.pendingExportId)
        outState.putBoolean("information_export_source", model.pendingExportIsSource)
        super.onSaveInstanceState(outState)
    }
}
