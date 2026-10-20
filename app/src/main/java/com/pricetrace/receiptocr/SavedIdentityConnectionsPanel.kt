package com.pricetrace.receiptocr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.*

@Composable
internal fun SavedIdentityConnectionsPanel(session: IngestionSession?, enabled: Boolean, onCommand: (SavedIdentityCommand) -> Unit) {
    val state = remember(session) { SavedIdentityConnections.state(session) }
    val recordCount = remember(session) { session?.let { SavedIdentityConnections.recordIds(it).size } ?: 0 }
    val hasRecords = recordCount > 0
    val withinRecordLimit = recordCount <= SavedIdentityConnections.MAX_RECORD_IDS
    val canEdit = enabled && withinRecordLimit
    var open by rememberSaveable(session?.ingestionId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(),verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) { Text(if (open) "브랜드·가게·식당 연결 접기" else "브랜드·가게·식당 연결") }
        if (open) {
            Text("원래 입력을 보존하고 기존 항목에 연결합니다. 연결은 내 계정에만 적용됩니다.")
            OutlinedButton(onClick = { onCommand(SavedIdentityCommand()) },enabled = canEdit && hasRecords) { Text("이 수집 기록의 연결 정보 불러오기") }
            if (!hasRecords) Text("PriceTrace에 기록을 저장한 뒤 연결할 수 있습니다.")
            if (!withinRecordLimit) Text("연결 대상이 ${SavedIdentityConnections.MAX_RECORD_IDS}개를 넘었습니다. 수집 기록을 나누어 확인하세요.")
            if (state?.truncated == true) Text("표시 한도 200개를 넘었습니다. 수집 기록을 나누어 확인하세요.")
            if (state != null && state.sources.isEmpty()) Text("이 기록에 연결할 브랜드·가게·식당이 없습니다.")
            state?.sources?.forEach { source -> key(source.key) { SavedIdentitySourceEditor(source,state,canEdit,onCommand) } }
        }
    }
}

@Composable
private fun SavedIdentitySourceEditor(source: SavedIdentitySource, state: SavedIdentityState, enabled: Boolean, onCommand: (SavedIdentityCommand) -> Unit) {
    var query by rememberSaveable(source.key) { mutableStateOf(source.name.take(200)) }
    var pending by remember(source.key,state) { mutableStateOf<SavedIdentityChoice?>(null) }
    val matches = state.searchEntity == source.entity && state.searchSourceId == source.sourceId && state.query == query.trim()
    HorizontalDivider()
    Text(source.label,style = MaterialTheme.typography.titleSmall)
    val selected = source.selected
    if (selected != null) Text("연결됨: ${selected.label}") else {
        OutlinedTextField(query,{ query = it.take(200); pending = null },label = { Text("기존 ${source.entityLabel} 검색") },
            enabled = enabled,singleLine = true,modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onCommand(SavedIdentityCommand(SavedIdentityAction.SEARCH,source.entity,source.sourceId,query.trim())) },
            enabled = enabled && query.trim().length >= 2) { Text("기존 항목 검색") }
        if (matches) {
            Text("유사도는 이름 비교 점수입니다. 지점과 주소를 확인하세요.")
            if (state.choices.isEmpty()) Text("비슷한 항목이 없습니다. 짧은 이름으로 다시 검색해 보세요.")
            state.choices.forEach { choice -> OutlinedButton(onClick = { pending = choice },enabled = enabled,modifier = Modifier.fillMaxWidth()) {
                Text("${choice.label} · ${choice.matchLabel}")
            } }
            pending?.let { choice ->
                Text("‘${source.name}’을 ‘${choice.label}’과 같은 ${source.entityLabel}로 연결합니다.")
                Button(onClick = { onCommand(SavedIdentityCommand(SavedIdentityAction.CONNECT,source.entity,source.sourceId,query.trim(),choice.id)) },enabled = enabled) {
                    Text("같은 항목으로 확인하고 연결")
                }
                TextButton(onClick = { pending = null },enabled = enabled) { Text("선택 취소") }
            }
        }
    }
}
