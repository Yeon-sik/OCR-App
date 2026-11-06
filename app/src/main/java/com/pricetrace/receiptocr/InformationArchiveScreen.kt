package com.pricetrace.receiptocr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptocr.information.*
import com.pricetrace.receiptscanner.information.*
import kotlinx.serialization.json.*

@Composable
fun InformationArchiveScreen(
    model: InformationArchiveViewModel,
    onPickSource: () -> Unit,
    onImportRecord: () -> Unit,
    onExportRecord: (String) -> Unit,
    onExportSource: (AndroidInformationSource) -> Unit,
    onClose: () -> Unit,
) {
    var showDraftJson by remember { mutableStateOf(false) }
    var exitConfirmation by remember { mutableStateOf(false) }
    val hasDraft = model.rawText.isNotBlank() || model.draft.isNotBlank()
    fun close() { if (hasDraft || model.busy) exitConfirmation = true else onClose() }
    BackHandler { close() }
    if (exitConfirmation) AlertDialog(
        onDismissRequest = { exitConfirmation = false },
        title = { Text("입력 중인 내용을 확인하세요") },
        text = { Text(if (model.busy) "파일 보관이 끝난 뒤 닫아 주세요." else "보관하지 않은 입력은 닫으면 사라집니다. 이미 보관한 원문과 기록은 유지됩니다.") },
        confirmButton = { TextButton(enabled = !model.busy, onClick = onClose) { Text("입력을 버리고 닫기") } },
        dismissButton = { TextButton(onClick = { exitConfirmation = false }) { Text("계속 입력") } },
    )
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("정보 보관함", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(top = 12.dp).semantics { heading() })
                TextButton(onClick = { close() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
            }
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("원문", "구조화", "기록").forEachIndexed { index, label ->
                    FilterChip(selected = model.pane == index, onClick = { model.pane = index }, label = { Text(label) }, modifier = Modifier.testTag("information_tab_$index"))
                }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("information_busy"))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("information_content"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("원문을 보관하고 소유 앱을 정해 구조화합니다. 검토한 JSON을 해당 앱에서 가져오세요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                model.notice?.let { item { SelectionContainer { Text(it, modifier = Modifier.testTag("information_notice"), color = MaterialTheme.colorScheme.primary) } } }
                items(model.snapshot.errors) { Text(it, color = MaterialTheme.colorScheme.error) }
                when (model.pane) {
                    0 -> {
                        item { Text("문서·사진·음성·영상 등 원본 파일을 형식에 관계없이 보관합니다. 파일당 최대 512 MiB이며 자동 해석·실행·업로드하지 않습니다.") }
                        item { Button(onClick = onPickSource, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("information_pick_source")) { Text("원문 파일 선택") } }
                        item { OutlinedTextField(model.sourceName, { model.sourceName = it.take(255) }, label = { Text("원문 이름") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) }
                        item { OutlinedTextField(model.rawText, { model.rawText = it }, label = { Text("텍스트 원문") }, minLines = 3, maxLines = 8, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("information_source_text")) }
                        item { Button(onClick = model::captureText, enabled = !model.busy && model.rawText.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("information_capture_text")) { Text("텍스트 원문 보관") } }
                        item { Text("보관된 원문 ${model.snapshot.sources.size}개", style = MaterialTheme.typography.titleMedium) }
                        if (model.snapshot.sources.isEmpty()) item { Text("아직 원문이 없습니다. 소유 앱을 정하지 못한 자료도 먼저 보관할 수 있습니다.") }
                        items(model.snapshot.sources, key = { it.id }) { source ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider(); Text(source.name, style = MaterialTheme.typography.titleSmall)
                                Text("${source.size} bytes · ${source.capturedAt}", style = MaterialTheme.typography.bodySmall)
                                SelectionContainer { Text("SHA-256 ${source.sha256}", style = MaterialTheme.typography.bodySmall) }
                                OutlinedButton(enabled = !model.busy, onClick = { model.linkedSourceId = source.id; model.pane = 1; model.ownerConfirmed = false }, modifier = Modifier.fillMaxWidth()) { Text("이 원문으로 구조화") }
                                TextButton(enabled = !model.busy, onClick = { onExportSource(source) }) { Text("원본 파일 내보내기") }
                            }
                        }
                    }
                    1 -> {
                        item { Text("소유 앱", style = MaterialTheme.typography.titleMedium) }
                        items(InformationOwner.entries) { option ->
                            Row(Modifier.fillMaxWidth()) {
                                RadioButton(selected = model.owner == option, onClick = { model.owner = option; model.ownerConfirmed = false }, enabled = !model.busy && model.draft.isBlank(), modifier = Modifier.testTag("information_owner_${option.wire}"))
                                Text("${option.label} · ${option.scope}", modifier = Modifier.padding(top = 12.dp).weight(1f))
                            }
                        }
                        item { Text(model.snapshot.sources.firstOrNull { it.id == model.linkedSourceId }?.let { "다음 양식의 연결 원문: ${it.name}" } ?: "직접 입력한 사실로 시작합니다. 원문 탭에서 파일을 연결할 수 있습니다.") }
                        item { OutlinedTextField(model.recordTitle, model::updateTitle, label = { Text("기록 제목") }, modifier = Modifier.fillMaxWidth().testTag("information_title"), enabled = !model.busy, singleLine = true) }
                        item { Button(onClick = model::newDraft, enabled = !model.busy && model.draft.isBlank(), modifier = Modifier.fillMaxWidth().testTag("information_new_draft")) { Text("새 구조화 기록 만들기") } }
                        item { OutlinedButton(onClick = onImportRecord, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("information_import_record")) { Text("기록 JSON 가져오기") } }
                        if (model.draft.isNotBlank()) {
                            item { Text("추가 항목", style = MaterialTheme.typography.titleMedium); Text("소수 값은 문자열 그대로 보존합니다. 알 수 없는 값과 단위는 추측해서 넣지 마세요.") }
                            item { OutlinedTextField(model.fieldKey, { model.fieldKey = it }, label = { Text("항목 키") }, placeholder = { Text("예: observed_price") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("information_field_key"), enabled = !model.busy) }
                            item {
                                Column {
                                    listOf("text" to "텍스트", "decimal" to "정확한 소수", "boolean" to "참 / 거짓", "date" to "날짜", "datetime" to "시각", "json" to "JSON 구조").chunked(3).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (type, label) -> FilterChip(selected = model.fieldType == type, onClick = { model.fieldType = type; model.fieldValue = if (type == "boolean") "false" else ""; model.fieldUnit = "" }, label = { Text(label) }, modifier = Modifier.testTag("information_type_$type")) } }
                                    }
                                }
                            }
                            item { OutlinedTextField(model.fieldValue, { model.fieldValue = it }, label = { Text("항목 값") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("information_field_value"), enabled = !model.busy) }
                            if (model.fieldType == "decimal") item { OutlinedTextField(model.fieldUnit, { model.fieldUnit = it }, label = { Text("단위 (알 때만)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("information_field_unit"), enabled = !model.busy) }
                            item { OutlinedButton(onClick = model::addField, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("information_add_field")) { Text("항목 추가") } }
                            item { TextButton(onClick = { showDraftJson = !showDraftJson }) { Text(if (showDraftJson) "JSON 편집 접기" else "전체 JSON·참조 편집") } }
                            if (showDraftJson) item { OutlinedTextField(model.draft, { model.draft = it; model.ownerConfirmed = false }, label = { Text("기록 JSON") }, minLines = 6, maxLines = 14, modifier = Modifier.fillMaxWidth().testTag("information_draft_json"), enabled = !model.busy) }
                            item {
                                Row { Checkbox(model.ownerConfirmed, { model.ownerConfirmed = it }, modifier = Modifier.testTag("information_owner_confirm"), enabled = !model.busy)
                                    Text("이 기록의 소유 앱과 출처를 확인했습니다. 다른 앱의 사실은 references로 연결했습니다.", modifier = Modifier.padding(top = 12.dp).weight(1f)) }
                            }
                            item { Button(onClick = model::saveDraft, enabled = !model.busy && model.ownerConfirmed, modifier = Modifier.fillMaxWidth().testTag("information_save_draft")) { Text("검토한 기록 보관") } }
                            item { TextButton(onClick = model::clearDraft, enabled = !model.busy) { Text("입력 양식 비우기") } }
                        }
                    }
                    else -> {
                        item { Text("구조화 기록 ${model.snapshot.records.size}개", style = MaterialTheme.typography.titleMedium); Text("검토 표시는 출처나 사실이 검증되었다는 뜻이 아닙니다. 금융·운동·건강 기록이나 공개 카탈로그에 자동 반영하지 않습니다.") }
                        if (model.snapshot.records.isEmpty()) item { Text("구조화 탭에서 새 기록을 만들거나 JSON을 가져오세요.") }
                        items(model.snapshot.records, key = { it.record.id }) { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider()
                                TextButton(onClick = { model.selectedRecordId = if (model.selectedRecordId == entry.record.id) null else entry.record.id }, modifier = Modifier.fillMaxWidth().testTag("information_record_${entry.record.id}")) { Text(entry.record.title) }
                                Text("${entry.record.owner.label} · ${if (entry.reviewed) "검토함" else "검토 전"}")
                                if (model.selectedRecordId == entry.record.id) {
                                    SelectionContainer { Text(InformationRecordCodec.pretty(entry.record), modifier = Modifier.testTag("information_record_detail"), style = MaterialTheme.typography.bodySmall) }
                                    if (!entry.reviewed) {
                                        var confirmed by remember(entry.record.id) { mutableStateOf(false) }
                                        Row { Checkbox(confirmed, { confirmed = it }); Text("소유 앱과 출처를 확인했습니다.", modifier = Modifier.weight(1f).padding(top = 12.dp)) }
                                        OutlinedButton(enabled = !model.busy && confirmed, onClick = { model.review(entry.record.id) }, modifier = Modifier.fillMaxWidth()) { Text("검토 완료") }
                                    }
                                    Button(enabled = !model.busy && entry.reviewed, onClick = { onExportRecord(entry.record.id) }, modifier = Modifier.fillMaxWidth().testTag("information_export_record")) { Text("${entry.record.owner.label}로 가져갈 JSON 내보내기") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
