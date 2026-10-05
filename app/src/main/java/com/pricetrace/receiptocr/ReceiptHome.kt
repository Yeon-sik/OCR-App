package com.pricetrace.receiptocr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.storage.ReceiptSession
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType

internal fun workflowTitle(workflow: OcrWorkflowType): String = when (workflow) {
    OcrWorkflowType.PRICE_TRACE_RECEIPT -> "영수증"
    OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT -> "식당 영수증"
    OcrWorkflowType.PRICE_TRACE_MERCHANT -> "가게 정보"
    OcrWorkflowType.FITNESS_NUTRITION -> "영양성분표"
    OcrWorkflowType.PRICE_TRACE_PRICE_OBSERVATION -> "가격 기록"
}

internal fun workflowDescription(workflow: OcrWorkflowType): String = when (workflow) {
    OcrWorkflowType.PRICE_TRACE_MERCHANT -> "영수증에 표시된 가게 이름·주소·연락처를 확인합니다."
    OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT -> "식당과 메뉴·옵션 가격을 원본과 비교합니다."
    OcrWorkflowType.FITNESS_NUTRITION -> "상품의 영양성분과 기준량을 라벨과 비교합니다."
    else -> "판매처·구매일·상품·금액을 영수증과 비교합니다."
}

@Composable
internal fun SessionListScreen(
    sessions: List<ReceiptSession>, selectedWorkflow: OcrWorkflowType, isBusy: Boolean,
    onScan: () -> Unit, onPickImages: () -> Unit, onPickCanonicalBundle: () -> Unit,
    onWorkflowSelected: (OcrWorkflowType) -> Unit, onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit, onShowApiSettings: () -> Unit,
) {
    var newWork by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = newWork) { newWork = false }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(if (newWork) "new_work" else "session_list"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (newWork) "새 작업" else "OCR · 작업", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = if (newWork) ({ newWork = false }) else onShowApiSettings,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (newWork) "new_work_back" else "api_settings_button")) {
                    Text(if (newWork) "닫기" else "설정")
                }
            }
        }
        if (newWork) {
            item { Text("어떤 자료를 확인할까요?", style = MaterialTheme.typography.titleLarge) }
            val choices = listOf(
                OcrWorkflowType.PRICE_TRACE_RECEIPT to "workflow_pricetrace",
                OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT to "workflow_restaurant",
                OcrWorkflowType.PRICE_TRACE_MERCHANT to "workflow_merchant",
                OcrWorkflowType.FITNESS_NUTRITION to "workflow_fitness",
            )
            items(choices, key = { it.second }) { (workflow, tag) ->
                OutlinedButton(onClick = { onWorkflowSelected(workflow) }, enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
                        selected = selectedWorkflow == workflow
                    }) {
                    Text((if (selectedWorkflow == workflow) "선택됨 · " else "") + workflowTitle(workflow))
                }
            }
            item {
                Text(workflowDescription(selectedWorkflow), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Button(onClick = { newWork = false; onScan() }, enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scan_button")) {
                    Text("${workflowTitle(selectedWorkflow)} 촬영")
                }
            }
            item {
                OutlinedButton(onClick = { newWork = false; onPickImages() }, enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pick_images_button")) { Text("사진 선택") }
            }
            item {
                TextButton(onClick = { newWork = false; onPickCanonicalBundle() }, enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pick_canonical_bundle_button")) {
                    Text("검수 자료 가져오기")
                }
            }
        } else {
            item {
                Text("원본을 확인하고, 필요한 곳으로.", style = MaterialTheme.typography.titleLarge)
                Text("확인·수정 → 검수 확정 → 보내기", modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Button(onClick = { newWork = true }, enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("new_work_button")) { Text("새로 인식하기") }
            }
            item {
                OutlinedButton(onClick = onPickCanonicalBundle, enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pick_canonical_bundle_button")) { Text("검수 자료 가져오기") }
                Text(".yeonsik 파일 · 여러 자료를 한 번에 가져올 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val attention = sessions.filter { it.lastError != null }
            if (attention.isNotEmpty()) item {
                Text("확인 필요한 작업 ${attention.size}개", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { heading() })
            }
            item { Text("진행 중 · 최근 작업", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            if (sessions.isEmpty()) item { Text("아직 작업이 없습니다. 촬영하거나 검수 자료를 가져오세요.") }
            items(sessions.sortedBy { it.lastError == null }, key = { it.documentId }) { session ->
                SessionCard(session, { onSelectSession(session.documentId) }, { onDeleteSession(session.documentId) })
            }
        }
    }
}

@Composable
internal fun AdvancedToolsScreen(onBack: () -> Unit, onJson: () -> Unit, onValidator: () -> Unit, onEvaluation: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("advanced_tools"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(onClick = onBack) { Text("설정으로") } }
        item { Text("고급 도구", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        item { Text("외부 데이터 형식을 직접 확인하거나 인식 품질을 평가합니다.") }
        item { OutlinedButton(onClick = onJson, modifier = Modifier.fillMaxWidth().testTag("pick_json_button")) { Text("외부 JSON 가져오기") } }
        item { OutlinedButton(onClick = onValidator, modifier = Modifier.fillMaxWidth().testTag("pick_canonical_json_button")) { Text("JSON 검증기 열기") } }
        item { OutlinedButton(onClick = onEvaluation, modifier = Modifier.fillMaxWidth().testTag("evaluation_button")) { Text("정확도 평가") } }
    }
}
