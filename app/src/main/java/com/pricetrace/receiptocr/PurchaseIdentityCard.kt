package com.pricetrace.receiptocr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.*

@Composable
internal fun PurchaseIdentityCard(card: PurchaseIdentityReviewCard, enabled: Boolean, onCommand: (PurchaseIdentityCommand) -> Unit) {
    var query by rememberSaveable(card.command.purchaseRecordClientKey,card.command.lineKey,card.command.entity,card.state?.query) {
        mutableStateOf(card.state?.query ?: card.command.query)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${card.command.entity.label} 연결",style = MaterialTheme.typography.titleMedium)
            Text("관측값: ${card.observed}")
            Text("${card.command.purchaseRecordClientKey} / ${card.command.lineKey}",style = MaterialTheme.typography.bodySmall)
            Text(card.statusLabel)
            OutlinedTextField(query,{ query = it.take(200) },label = { Text("검색어") },singleLine = true,modifier = Modifier.fillMaxWidth(),enabled = enabled)
            OutlinedButton(onClick = { onCommand(card.command.copy(query = query)) },enabled = enabled,modifier = Modifier.fillMaxWidth()) {
                Text(if (card.state == null) "기존 ${card.command.entity.label} 검색" else "다시 검색")
            }
            card.state?.choices.orEmpty().forEach { choice ->
                OutlinedButton(onClick = { onCommand(card.command.copy(action = PurchaseIdentityAction.SELECT,query = query,selectedId = choice.id,selectedKind = choice.identityKind)) },
                    enabled = enabled && card.linkedLabel == null,modifier = Modifier.fillMaxWidth()) { Text("기존 ${card.command.entity.label}에 연결 · ${choice.label}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCommand(card.command.copy(action = PurchaseIdentityAction.CREATE,query = query)) },enabled = enabled && card.canCreate && query == card.state?.query) { Text("새 ${card.command.entity.label} 등록") }
                TextButton(onClick = { onCommand(card.command.copy(action = PurchaseIdentityAction.DEFER,query = query)) },enabled = enabled && card.linkedLabel == null) { Text("보류") }
            }
            if (!enabled) Text("검수를 확정하고 구매 기록을 저장한 뒤 연결할 수 있습니다.",style = MaterialTheme.typography.bodySmall)
        }
    }
}
