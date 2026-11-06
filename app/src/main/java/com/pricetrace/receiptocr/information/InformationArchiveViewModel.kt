package com.pricetrace.receiptocr.information

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pricetrace.receiptscanner.information.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.UUID

class InformationArchiveViewModel @JvmOverloads constructor(application: Application, directory: File = File(application.filesDir, "information")) : AndroidViewModel(application) {
    val store = AndroidInformationStore(directory)
    var snapshot by mutableStateOf(AndroidInformationSnapshot()); private set
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var pane by mutableStateOf(0)
    var rawText by mutableStateOf("")
    var sourceName by mutableStateOf("원문.txt")
    var owner by mutableStateOf(InformationOwner.FITNESS)
    var linkedSourceId by mutableStateOf<String?>(null)
    var draft by mutableStateOf("")
    var ownerConfirmed by mutableStateOf(false)
    var recordTitle by mutableStateOf("")
    var fieldKey by mutableStateOf("")
    var fieldValue by mutableStateOf("")
    var fieldType by mutableStateOf("text")
    var fieldUnit by mutableStateOf("")
    var selectedRecordId by mutableStateOf<String?>(null)
    var pendingExportId: String? = null
    var pendingExportIsSource = false
    init { refresh() }

    fun refresh() = runAction { "보관함을 불러왔습니다." }
    fun clearDraft() { draft = ""; ownerConfirmed = false; recordTitle = ""; fieldKey = ""; fieldValue = ""; fieldUnit = "" }
    fun captureText() {
        val text = rawText; val name = sourceName
        runAction(onSuccess = { rawText = "" }) { store.captureText(text, name); "텍스트 원문을 보관했습니다." }
    }
    fun captureUri(uri: Uri, importJson: Boolean) = runAction {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "source.bin"
        resolver.openInputStream(uri)?.use { input ->
            if (importJson) {
                val result = store.importRecord(input, name)
                if (result == AndroidInformationSave.DUPLICATE) "이미 보관된 기록입니다. 원문 파일도 보관했습니다."
                else "기록을 가져왔습니다. 기록 탭에서 소유 앱과 출처를 확인하세요."
            } else {
                store.capture(input, name, resolver.getType(uri) ?: "application/octet-stream")
                "원문 파일을 보관했습니다. 형식을 해석하지 못해도 원본은 남습니다."
            }
        } ?: error("선택한 파일을 읽지 못했습니다. 다시 선택하세요.")
    }
    fun newDraft() {
        if (draft.isNotBlank()) return
        val source = snapshot.sources.firstOrNull { it.id == linkedSourceId }
        val payload = buildJsonObject {
            put("schema_version", "information-record.v1"); put("record_id", UUID.randomUUID().toString())
            put("owner_app", owner.wire); put("record_type", "${owner.wire}.observation")
            put("title", recordTitle.ifBlank { "새 관측 기록" }); put("captured_at", Instant.now().toString())
            putJsonArray("sources") { add(source?.reference() ?: buildJsonObject { put("source_id", "manual-1"); put("kind", "manual") }) }
            putJsonArray("fields") { add(buildJsonObject { put("key", "note"); put("value_type", "text"); put("value", ""); putJsonArray("source_ids") { add(source?.id ?: "manual-1") } }) }
            putJsonArray("references") { }
        }
        draft = InformationRecordCodec.pretty(payload); ownerConfirmed = false
    }
    fun updateTitle(title: String) {
        recordTitle = title
        if (draft.isNotBlank()) editDraft { JsonObject(it + ("title" to JsonPrimitive(title))) }
    }
    fun addField() = editDraft { payload ->
        require(fieldKey.isNotBlank()) { "항목 키를 입력하세요." }
        val value: JsonElement = when (fieldType) {
            "boolean" -> { require(fieldValue in setOf("true", "false")) { "참/거짓은 true 또는 false로 입력하세요." }; JsonPrimitive(fieldValue == "true") }
            "json" -> InformationRecordCodec.parseValue(fieldValue)
            else -> JsonPrimitive(fieldValue)
        }
        val field = buildJsonObject {
            put("key", fieldKey); put("value_type", fieldType); put("value", value)
            if (fieldType == "decimal" && fieldUnit.isNotBlank()) put("unit", fieldUnit)
            putJsonArray("source_ids") { payload.getValue("sources").jsonArray.forEach { add(it.jsonObject.getValue("source_id")) } }
        }
        val next = JsonObject(payload + ("fields" to JsonArray(payload.getValue("fields").jsonArray + field)))
        InformationRecordCodec.decode(InformationRecordCodec.pretty(next))
        fieldKey = ""; fieldValue = ""; fieldUnit = ""
        next
    }
    private fun editDraft(action: (JsonObject) -> JsonObject) {
        runCatching { action(InformationRecordCodec.parseObject(draft)) }
            .onSuccess { draft = InformationRecordCodec.pretty(it); ownerConfirmed = false; notice = null }
            .onFailure { notice = "입력을 확인하세요: ${it.message}" }
    }
    fun saveDraft() {
        val text = draft; val confirmed = ownerConfirmed
        runAction(onSuccess = { clearDraft(); pane = 2 }) {
            require(confirmed) { "소유 앱과 출처를 먼저 확인하세요." }
            val record = InformationRecordCodec.decode(text)
            val result = store.save(record); store.markReviewed(record.id)
            if (result == AndroidInformationSave.DUPLICATE) "이미 보관된 같은 기록입니다." else "검토한 기록을 보관했습니다. 해당 앱으로 내보낼 수 있습니다."
        }
    }
    fun review(id: String) = runAction { store.markReviewed(id); "소유 앱과 출처 검토를 기록했습니다. 원문 내용은 변경하지 않았습니다." }
    fun export(uri: Uri) {
        val id = pendingExportId ?: return
        val source = pendingExportIsSource
        pendingExportId = null
        runAction {
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { output ->
                if (source) store.exportSource(id, output) else store.exportRecord(id, output)
            } ?: error("선택한 위치에 파일을 쓰지 못했습니다.")
            "파일을 내보냈습니다. 대상 앱에서 직접 가져오세요."
        }
    }
    private fun runAction(onSuccess: () -> Unit = {}, action: () -> String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            notice = result.getOrElse { "처리하지 못했습니다: ${it.message}" }
            if (result.isSuccess) onSuccess()
            withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
                .onSuccess { snapshot = it }.onFailure { notice = "목록을 읽지 못했습니다: ${it.message}" }
            busy = false
        }
    }
}
