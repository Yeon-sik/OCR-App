package com.pricetrace.receiptocr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptocr.information.InformationArchiveViewModel
import com.pricetrace.receiptscanner.information.InformationDeliveryState

@Composable
internal fun InformationDeliveryPanel(model: InformationArchiveViewModel) {
    var settings by remember(model.deliveryRecordId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("소유 앱 검토함으로 전송", style = MaterialTheme.typography.titleMedium)
        Text("구조화 내용과 발췌·참조만 전송합니다. 원본 파일은 OCR에 남습니다. 실제 기록·통계 반영은 수신 앱에서 따로 승인하세요.")
        TextButton(enabled = !model.busy, onClick = { settings = !settings }) { Text("서버·로그인 설정") }
        if (settings) {
            OutlinedTextField(model.deliveryUrl, { model.deliveryUrl = it; model.resetDeliveryConfirmation() },
                label = { Text("수신 서버 주소") }, modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
            OutlinedTextField(model.deliveryKey, { model.deliveryKey = it; model.resetDeliveryConfirmation() },
                label = { Text("공개 연결 키") }, modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
            OutlinedButton(enabled = !model.busy, onClick = model::configureDelivery, modifier = Modifier.fillMaxWidth()) { Text("서버 설정 저장") }
            OutlinedTextField(model.deliveryEmail, { model.deliveryEmail = it; model.resetDeliveryConfirmation() },
                label = { Text("수신 계정 이메일") }, modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
            OutlinedTextField(model.deliveryPassword, { model.deliveryPassword = it; model.resetDeliveryConfirmation() },
                label = { Text("비밀번호") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !model.busy, onClick = model::loginDelivery) { Text("로그인") }
                TextButton(enabled = !model.busy, onClick = model::logoutDelivery) { Text("로그아웃") }
            }
        }
        Button(enabled = !model.busy, onClick = model::prepareDelivery, modifier = Modifier.fillMaxWidth().testTag("information_prepare_delivery")) { Text("수신 계정 확인") }
        model.deliveryPrepared?.let { ready ->
            Text("${ready.target.owner.label} · ${ready.target.email}")
            Text("${ready.target.origin}\n계정 ID: ${ready.target.userId}", style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(model.deliveryConfirmed, { model.deliveryConfirmed = it }, enabled = !model.busy, modifier = Modifier.testTag("information_delivery_confirm"))
                Text("확인한 계정의 검토함으로 이 기록을 전송합니다.", modifier = Modifier.weight(1f).padding(top = 12.dp))
            }
            Button(enabled = !model.busy && model.deliveryConfirmed, onClick = model::sendDelivery,
                modifier = Modifier.fillMaxWidth().testTag("information_send_delivery")) { Text("확인한 계정으로 전송") }
        }
        model.deliveryHistory.forEach { attempt ->
            Text("${if (attempt.state == InformationDeliveryState.DELIVERED) "검토함 도착" else "완료 확인 필요"} · ${attempt.target.email}", style = MaterialTheme.typography.bodySmall)
            Text("${attempt.attemptedAt}\n${attempt.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
