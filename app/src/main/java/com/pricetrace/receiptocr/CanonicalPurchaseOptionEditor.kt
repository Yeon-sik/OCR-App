package com.pricetrace.receiptocr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.review.CanonicalFieldRegistry
import com.pricetrace.receiptscanner.review.CanonicalReviewEdit
import com.pricetrace.receiptscanner.review.PurchaseOptionListMutation

@Composable
internal fun CanonicalPurchaseOptionEditor(
    envelope: YeonsikOcrEnvelope,
    edits: List<CanonicalReviewEdit>,
    fieldErrors: Map<String, String>,
    busy: Boolean,
    onFieldChanged: (String, String?) -> Unit,
    onMutation: (String, String, PurchaseOptionListMutation) -> Unit,
) {
    val fields = CanonicalFieldRegistry.fields(envelope)
    envelope.purchaseRecords.forEach { record ->
        record.lineItems.forEachIndexed { lineIndex, line ->
            val lineKey = line.lineKey ?: lineIndex.toString()
            val path = "purchase_records[${record.clientKey}].line_items[$lineKey].option_items"
            var description by rememberSaveable(path) { mutableStateOf("") }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${line.description} · 구매 옵션", style = MaterialTheme.typography.titleSmall)
                if (edits.any { it.fieldPath == path }) Text("검수자가 옵션 목록을 수정했습니다.", style = MaterialTheme.typography.bodySmall)
                Text("수량·금액이 원본에 없으면 비워 두세요. 실제 0원은 0을 입력하세요.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(description, { description = it }, label = { Text("새 옵션 설명") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("purchase_option_add_description_$lineKey"))
                TextButton(
                    onClick = { onMutation(record.clientKey, lineKey, PurchaseOptionListMutation.Add(description)) },
                    enabled = !busy && description.isNotBlank() && line.optionText == null,
                    modifier = Modifier.testTag("purchase_option_add_$lineKey"),
                ) { Text("옵션 추가") }
                if (line.optionText != null) Text("기존 옵션 텍스트를 지운 뒤 구조화 옵션을 추가할 수 있습니다.")
                fieldErrors[path]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                line.optionItems.forEachIndexed { index, option ->
                    val prefix = "$path[${option.optionKey}]"
                    Text("옵션 ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    fields.filter { it.path.startsWith("$prefix.") }.forEach { field ->
                        CanonicalTypedReviewField(field, CanonicalFieldRegistry.isModified(field, edits),
                            edits.firstOrNull { it.fieldPath == field.path }?.previousValue, fieldErrors[field.path],
                            onApply = { onFieldChanged(field.path, it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun reorder(target: Int) {
                            val keys = line.optionItems.map { it.optionKey }.toMutableList()
                            keys.add(target, keys.removeAt(index))
                            onMutation(record.clientKey, lineKey, PurchaseOptionListMutation.Reorder(keys))
                        }
                        TextButton({ reorder(index - 1) }, enabled = !busy && index > 0,
                            modifier = Modifier.testTag("purchase_option_up_${option.optionKey}")) { Text("위로") }
                        TextButton({ reorder(index + 1) }, enabled = !busy && index < line.optionItems.lastIndex,
                            modifier = Modifier.testTag("purchase_option_down_${option.optionKey}")) { Text("아래로") }
                        TextButton({ onMutation(record.clientKey, lineKey, PurchaseOptionListMutation.Delete(option.optionKey)) },
                            enabled = !busy, modifier = Modifier.testTag("purchase_option_delete_${option.optionKey}")) { Text("옵션 삭제") }
                    }
                }
            }
        }
    }
}
