package com.yeonsik.ingestion.desktop

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
    Surface(Modifier.fillMaxWidth(),shape = MaterialTheme.shapes.medium,tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${card.command.entity.label} 연결",style = MaterialTheme.typography.titleMedium)
            Text("관측값: ${card.observed}")
            Text("${card.command.purchaseRecordClientKey} / ${card.command.lineKey}",style = MaterialTheme.typography.bodySmall)
            Text(card.statusLabel)
            OutlinedTextField(query,{ query = it.take(200) },label = { Text("검색어") },singleLine = true,modifier = Modifier.fillMaxWidth(),enabled = enabled)
            CollectorButton(if (card.state == null) "기존 ${card.command.entity.label} 검색" else "다시 검색",{ onCommand(card.command.copy(query = query)) },enabled = enabled,emphasized = false)
            card.state?.choices.orEmpty().forEach { choice ->
                CollectorButton("기존 ${card.command.entity.label}에 연결 · ${choice.label}",{
                    onCommand(card.command.copy(action = PurchaseIdentityAction.SELECT,query = query,selectedId = choice.id,selectedKind = choice.identityKind))
                },enabled = enabled && card.linkedLabel == null,emphasized = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CollectorButton("새 ${card.command.entity.label} 등록",{ onCommand(card.command.copy(action = PurchaseIdentityAction.CREATE,query = query)) },enabled = enabled && card.canCreate && query == card.state?.query,emphasized = false)
                CollectorButton("보류",{ onCommand(card.command.copy(action = PurchaseIdentityAction.DEFER,query = query)) },enabled = enabled && card.linkedLabel == null,emphasized = false)
            }
            if (!enabled) Text("검수를 확정하고 구매 기록을 저장한 뒤 연결할 수 있습니다.",style = MaterialTheme.typography.bodySmall)
        }
    }
}
