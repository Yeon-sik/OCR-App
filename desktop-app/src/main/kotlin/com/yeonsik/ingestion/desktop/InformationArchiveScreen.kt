package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.information.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Composable
fun InformationArchiveScreen(
    store: DesktopInformationStore = remember { DesktopInformationStore() },
    onInputState: (busy: Boolean, hasInput: Boolean) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(InformationArchiveSnapshot(emptyList(), emptyList(), emptyList())) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pane by remember { mutableStateOf(0) }
    var rawText by remember { mutableStateOf("") }
    var sourceName by remember { mutableStateOf("원문.txt") }
    var owner by remember { mutableStateOf(InformationOwner.FITNESS) }
    var source by remember { mutableStateOf<ArchivedInformationSource?>(null) }
    var draft by remember { mutableStateOf("") }
    var ownerConfirmed by remember { mutableStateOf(false) }
    var fieldKey by remember { mutableStateOf("") }
    var fieldValue by remember { mutableStateOf("") }
    var fieldType by remember { mutableStateOf("text") }
    var fieldUnit by remember { mutableStateOf("") }
    var showJson by remember { mutableStateOf(false) }
    SideEffect { onInputState(busy, rawText.isNotBlank() || draft.isNotBlank()) }

    fun run(onSuccess: () -> Unit = {}, action: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            notice = result.getOrElse { "처리하지 못했습니다: ${it.message}" }
            if (result.isSuccess) onSuccess()
            withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
                .onSuccess { snapshot = it }.onFailure { notice = "목록을 읽지 못했습니다: ${it.message}" }
            busy = false
        }
    }
    LaunchedEffect(store) { snapshot = withContext(Dispatchers.IO) { store.snapshot() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("정보 보관함", style = MaterialTheme.typography.headlineSmall)
        Text("원문을 보관하고, 내용의 소유 앱을 정해 구조화합니다. 검토한 기록은 JSON 파일로 내보내 해당 앱에서 가져오세요.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("원문 ${snapshot.sources.size}", "구조화", "기록 ${snapshot.records.size}").forEachIndexed { index, label ->
                FilterChip(selected = pane == index, onClick = { pane = index }, label = { Text(label) })
            }
            if (busy) Text("저장 중…", modifier = Modifier.padding(8.dp))
        }
        notice?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.primary) } }
        snapshot.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (pane) {
                0 -> {
                    Text("파일 형식에 관계없이 원본 바이트를 보관합니다. 파일당 최대 512 MiB. 자동 해석·실행·업로드는 하지 않습니다.")
                    Button(enabled = !busy, onClick = {
                        informationOpenFiles("원문 파일 보관", true).takeIf { it.isNotEmpty() }?.let { paths ->
                            run {
                                var count = 0
                                paths.forEach { store.captureFile(it); count++ }
                                "${count}개 원문을 보관했습니다. 소유 앱을 정하지 않은 원문도 이 목록에 남습니다."
                            }
                        }
                    }) { Text("파일 선택해 보관") }
                    OutlinedTextField(sourceName, { sourceName = it }, label = { Text("원문 이름") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(rawText, { rawText = it }, label = { Text("텍스트 원문") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10, enabled = !busy)
                    Button(enabled = !busy && rawText.isNotBlank(), onClick = {
                        val text = rawText; val name = sourceName
                        run(onSuccess = { rawText = "" }) { store.captureText(text, name); "텍스트 원문을 보관했습니다." }
                    }) { Text("텍스트 보관") }
                    TextButton(enabled = !busy, onClick = { rawText = "" }) { Text("텍스트 입력 비우기") }
                    Text("보관된 원문", style = MaterialTheme.typography.titleMedium)
                    if (snapshot.sources.isEmpty()) Text("문서·사진·음성·영상·텍스트 등 원본 파일부터 넣으세요. 자동 추출을 지원하지 않는 형식도 보관할 수 있습니다.")
                    snapshot.sources.forEach { item ->
                        HorizontalDivider()
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text("${item.size} bytes · ${item.capturedAt}", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text("SHA-256 ${item.hash}", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, onClick = { source = item; pane = 1; ownerConfirmed = false }) { Text("이 원문으로 구조화") }
                            TextButton(enabled = !busy, onClick = {
                                informationSaveFile(item.name)?.let { target -> run { store.exportSource(item.id, target); "원본 바이트를 내보냈습니다." } }
                            }) { Text("원본 내보내기") }
                        }
                    }
                }
                1 -> {
                    Text("소유 앱", style = MaterialTheme.typography.titleMedium)
                    InformationOwner.entries.forEach { option ->
                        Row {
                            RadioButton(owner == option, { owner = option; ownerConfirmed = false }, enabled = !busy)
                            Text("${option.label} · ${option.scope}", modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    Text(source?.let { "연결 원문: ${it.name}" } ?: "직접 입력한 사실로 시작합니다. 원문 파일은 원문 탭에서 연결할 수 있습니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && draft.isBlank(), onClick = {
                            draft = newInformationDraft(owner, source); ownerConfirmed = false
                        }) { Text("새 기록 양식") }
                        OutlinedButton(enabled = !busy, onClick = {
                            informationOpenFiles("구조화 JSON 가져오기", false).firstOrNull()?.let { file ->
                                run {
                                    val result = store.importFile(file)
                                    if (result == InformationSaveResult.DUPLICATE) "이미 보관된 기록입니다." else "기록을 가져왔습니다. 기록 탭에서 소유 앱과 출처를 확인하세요."
                                }
                            }
                        }) { Text("기록 JSON 가져오기") }
                    }
                    Text("숫자는 소수점을 포함한 문자열로 보존합니다. 모르는 값은 넣지 말고, 다른 앱의 사실은 references로 연결하세요.")
                    if (draft.isNotBlank()) {
                        val parsed = runCatching { InformationRecordCodec.parseObject(draft) }.getOrNull()
                        if (parsed != null) {
                            OutlinedTextField((parsed["title"] as? JsonPrimitive)?.content.orEmpty(), { title ->
                                draft = prettyInformation(JsonObject(parsed + ("title" to JsonPrimitive(title))))
                                ownerConfirmed = false
                            }, label = { Text("기록 제목") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                            parsed["fields"]?.let { values ->
                                if (values is JsonArray) values.forEach { value ->
                                    val field = value as? JsonObject
                                    if (field != null) SelectionContainer { Text("${(field["label"] as? JsonPrimitive)?.content ?: (field["key"] as? JsonPrimitive)?.content}: ${field["value"] ?: ""} ${(field["unit"] as? JsonPrimitive)?.content.orEmpty()}") }
                                }
                            }
                        }
                        TextButton({ showJson = !showJson }) { Text(if (showJson) "JSON 편집 닫기" else "시점·참조·전체 JSON 편집") }
                        if (showJson || parsed == null) OutlinedTextField(draft, { draft = it; ownerConfirmed = false }, label = { Text("구조화 기록 JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 10, maxLines = 22, enabled = !busy)
                        Text("필드 추가", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(fieldKey, { fieldKey = it }, label = { Text("필드 이름 (예: duration, systolic)") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("text" to "텍스트", "decimal" to "숫자", "boolean" to "참/거짓", "date" to "날짜", "datetime" to "시각", "json" to "JSON").forEach { (type, label) ->
                                FilterChip(selected = fieldType == type, onClick = { fieldType = type; fieldUnit = "" }, label = { Text(label) })
                            }
                        }
                        OutlinedTextField(fieldValue, { fieldValue = it }, label = { Text("값") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                        if (fieldType == "decimal") OutlinedTextField(fieldUnit, { fieldUnit = it }, label = { Text("단위 (알고 있을 때만)") })
                        OutlinedButton(enabled = !busy && fieldKey.isNotBlank(), onClick = {
                            runCatching { addInformationField(draft, fieldKey, fieldType, fieldValue, fieldUnit) }
                                .onSuccess { draft = it; ownerConfirmed = false; fieldKey = ""; fieldValue = ""; fieldUnit = ""; notice = "필드를 추가했습니다." }
                                .onFailure { notice = "필드를 추가하지 못했습니다: ${it.message}" }
                        }) { Text("필드 추가") }
                        Row {
                            Checkbox(ownerConfirmed, { ownerConfirmed = it }, enabled = !busy)
                            Text("선택한 앱(${owner.label})의 정보이며, 원문과 구조화 내용을 대조했습니다.", modifier = Modifier.padding(top = 12.dp))
                        }
                        Button(enabled = !busy && ownerConfirmed, onClick = {
                            val text = draft; val expected = owner
                            run(onSuccess = { draft = ""; ownerConfirmed = false; pane = 2 }) {
                                val record = InformationRecordCodec.decode(text, expected)
                                val result = store.save(record); store.markReviewed(record.id)
                                if (result == InformationSaveResult.DUPLICATE) "동일한 기록입니다. 검토 상태를 확인했습니다." else "검토 기록을 저장했습니다. 기록 탭에서 내보낼 수 있습니다."
                            }
                        }) { Text("검토하고 저장") }
                        TextButton(enabled = !busy, onClick = { draft = ""; ownerConfirmed = false; notice = "편집 내용을 비웠습니다. 저장된 기록과 원문은 유지됩니다." }) { Text("편집 내용 비우기") }
                    }
                }
                2 -> {
                    Text("이 보관함의 기록은 각 앱에 자동 전송되지 않습니다. 내보낸 JSON을 소유 앱의 정보 기록함에서 가져오세요.")
                    if (snapshot.records.isEmpty()) Text("구조화 탭에서 기록을 만들거나 information-record.v1 JSON을 가져오세요.")
                    snapshot.records.forEach { entry ->
                        val record = entry.record
                        HorizontalDivider()
                        Text(record.title, style = MaterialTheme.typography.titleMedium)
                        Text("${record.owner.label} · ${if (entry.reviewed) "검토 완료" else "소유 앱·출처 확인 필요"}")
                        SelectionContainer { Text(record.id, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy && draft.isBlank(), onClick = {
                                draft = InformationRecordCodec.pretty(record); owner = record.owner; ownerConfirmed = false; pane = 1
                            }) { Text("내용 검토") }
                            OutlinedButton(enabled = !busy && draft.isBlank(), onClick = {
                                draft = correctionInformationDraft(record); owner = record.owner; ownerConfirmed = false; pane = 1
                            }) { Text("정정 기록 만들기") }
                            Button(enabled = !busy && entry.reviewed, onClick = {
                                informationSaveFile("${record.owner.wire}-${record.id}.json")?.let { target ->
                                    run { store.exportRecord(record.id, target); "${record.owner.label}에서 가져올 JSON을 내보냈습니다." }
                                }
                            }) { Text("${record.owner.label}용 내보내기") }
                        }
                    }
                    if (draft.isNotBlank()) Text("편집 중인 내용이 있습니다. 구조화 탭에서 저장하거나 비운 뒤 다른 기록을 열 수 있습니다.")
                }
            }
        }
    }
    }
}

internal fun newInformationDraft(owner: InformationOwner, source: ArchivedInformationSource?): String = prettyInformation(buildJsonObject {
    put("schema_version", "information-record.v1"); put("record_id", UUID.randomUUID().toString())
    put("owner_app", owner.wire); put("record_type", owner.wire + ".observation")
    put("title", source?.name ?: "새 기록"); put("captured_at", Instant.now().toString())
    put("sources", JsonArray(listOf(source?.wire() ?: buildJsonObject { put("source_id", "manual-1"); put("kind", "manual") })))
    put("fields", JsonArray(listOf(buildJsonObject { put("key", "note"); put("value_type", "text"); put("value", "") })))
    put("references", JsonArray(emptyList()))
})

internal fun correctionInformationDraft(record: InformationRecord): String = prettyInformation(JsonObject(record.payload + mapOf(
    "record_id" to JsonPrimitive(UUID.randomUUID().toString()), "supersedes_record_id" to JsonPrimitive(record.id), "captured_at" to JsonPrimitive(Instant.now().toString()),
)))

internal fun addInformationField(draft: String, key: String, type: String, value: String, unit: String): String {
    val record = InformationRecordCodec.decode(draft)
    val field = buildJsonObject {
        put("key", key); put("value_type", type)
        put("value", if (type in setOf("boolean", "json")) InformationRecordCodec.parseValue(value) else JsonPrimitive(value))
        if (unit.isNotBlank()) put("unit", unit)
    }
    val result = prettyInformation(JsonObject(record.payload + ("fields" to JsonArray(record.payload.getValue("fields").jsonArray + field))))
    InformationRecordCodec.decode(result)
    return result
}

private fun prettyInformation(value: JsonObject): String = InformationRecordCodec.pretty(value)
private fun informationOpenFiles(title: String, multiple: Boolean): List<Path> = FileDialog(null as Frame?, title, FileDialog.LOAD).let { dialog ->
    try { dialog.isMultipleMode = multiple; dialog.isVisible = true; dialog.files.orEmpty().map { it.toPath() } } finally { dialog.dispose() }
}
private fun informationSaveFile(name: String): Path? = FileDialog(null as Frame?, "새 이름으로 내보내기", FileDialog.SAVE).let { dialog ->
    try { dialog.file = name; dialog.isVisible = true; dialog.file?.let { Path.of(dialog.directory, it) } } finally { dialog.dispose() }
}
