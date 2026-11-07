package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.information.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun InformationDeliveryPanel(record: InformationRecord, store: DesktopInformationStore,
    delivery: DesktopInformationDelivery, journal: InformationDeliveryJournal, onBusy: (Boolean) -> Unit) {
    val recordId = record.id
    val scope = rememberCoroutineScope()
    val initial = remember(recordId) { delivery.connections.read(record.owner) }
    var url by remember(recordId) { mutableStateOf(initial.url) }
    var key by remember(recordId) { mutableStateOf(initial.publishableKey) }
    var email by remember(recordId) { mutableStateOf(initial.email) }
    var password by remember(recordId) { mutableStateOf("") }
    var showSettings by remember(recordId) { mutableStateOf(false) }
    var prepared by remember(recordId) { mutableStateOf<PreparedInformationDelivery?>(null) }
    var confirmed by remember(recordId) { mutableStateOf(false) }
    var busy by remember(recordId) { mutableStateOf(false) }
    var message by remember(recordId) { mutableStateOf<String?>(null) }
    var history by remember(recordId) { mutableStateOf<List<InformationDeliveryAttempt>>(emptyList()) }
    LaunchedEffect(recordId) { runCatching { withContext(Dispatchers.IO) { journal.attempts(recordId) } }
        .onSuccess { history = it }.onFailure { message = "전송 이력을 읽지 못했습니다. 보관함 파일을 확인하세요." } }
    fun run(action: suspend () -> String) {
        if (busy) return
        busy = true; onBusy(true)
        scope.launch {
            try {
                message = try { action() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { error.message ?: "처리하지 못했습니다." }
                runCatching { withContext(Dispatchers.IO) { journal.attempts(recordId) } }
                    .onSuccess { history = it }.onFailure { message = "전송 이력을 읽지 못했습니다. 같은 기록으로 수신 여부를 다시 확인하세요." }
            } finally { busy = false; onBusy(false) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${record.owner.label} 검토함으로 직접 전송", style = MaterialTheme.typography.titleMedium)
        Text("구조화 내용과 연결된 발췌·참조만 전송합니다. 원본 파일은 이 보관함에 남습니다. 실제 기록·통계 반영은 수신 앱에서 별도로 승인하세요.")
        TextButton(enabled = !busy, onClick = { showSettings = !showSettings }) { Text("수신 서버·로그인 설정") }
        if (showSettings) {
            Text("현재 창의 연결 설정입니다. 재실행 시에는 로컬 환경 설정을 다시 읽습니다.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(url, { url = it; prepared = null; confirmed = false }, label = { Text("${record.owner.label} 서버 주소") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedTextField(key, { key = it; prepared = null; confirmed = false }, label = { Text("공개 연결 키") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedButton(enabled = !busy, onClick = { run {
                withContext(Dispatchers.IO) { delivery.gateway.configure(record.owner, url, key) }
                prepared = null; confirmed = false; "서버 설정을 적용했습니다. 수신 계정을 확인하세요."
            } }) { Text("서버 설정 적용") }
            OutlinedTextField(email, { email = it; prepared = null; confirmed = false }, label = { Text("수신 계정 이메일") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedTextField(password, { password = it; prepared = null; confirmed = false }, label = { Text("비밀번호") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { val secret = password; password = ""; run {
                    delivery.gateway.signIn(record.owner, email, secret); prepared = null; confirmed = false; "로그인했습니다. 수신 계정 확인을 진행하세요."
                } }) { Text("로그인") }
                TextButton(enabled = !busy, onClick = { run {
                    withContext(Dispatchers.IO) { delivery.gateway.signOut(record.owner) }; prepared = null; confirmed = false; "이 창의 로그인 정보를 지웠습니다."
                } }) { Text("로그아웃") }
            }
        }
        Button(enabled = !busy, onClick = { confirmed = false; prepared = null; run {
            prepared = delivery.prepare(withContext(Dispatchers.IO) { store.reviewedRecord(recordId) }); "아래 수신 계정과 전송 내용을 확인하세요."
        } }) { Text("수신 계정 확인") }
        prepared?.let { ready ->
            Text("${ready.target.owner.label} · ${ready.target.email}")
            Text("${ready.target.origin}\n계정 ID: ${ready.target.userId}", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(confirmed, { confirmed = it }, enabled = !busy)
                Text("이 계정의 검토함으로 현재 기록을 전송합니다.", modifier = Modifier.padding(top = 12.dp)) }
            Button(enabled = !busy && confirmed, onClick = { confirmed = false; run {
                val current = withContext(Dispatchers.IO) { store.reviewedRecord(recordId) }
                withContext(Dispatchers.IO) { journal.deliver(current, ready, delivery.gateway) }
                prepared = null; "검토함 도착을 확인했습니다. ${record.owner.label}에서 내용을 검토하고 실제 기록 반영을 승인하세요."
            } }) { Text("확인한 계정으로 전송") }
        }
        message?.let { Text(it) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        history.forEach { attempt ->
            Text("${if (attempt.state == InformationDeliveryState.DELIVERED) "검토함 도착" else "완료 확인 필요"} · ${attempt.target.email} · ${attempt.attemptedAt}", style = MaterialTheme.typography.bodySmall)
            Text(attempt.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
