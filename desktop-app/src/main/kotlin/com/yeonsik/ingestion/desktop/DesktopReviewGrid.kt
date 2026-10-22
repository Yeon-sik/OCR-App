package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.CanonicalFieldType

/** Row-major order stays stable while typing; only the navigation queue prioritizes errors. */
@Composable
internal fun ReviewFieldGrid(fields: List<CanonicalEditableField>, content: @Composable (CanonicalEditableField) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when { maxWidth >= 900.dp -> 3; maxWidth >= 520.dp -> 2; else -> 1 }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.chunked(columns).forEach { row ->
                key(row.first().path) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { field -> key(field.path) { Column(Modifier.weight(1f)) { content(field) } } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReviewDraftToolbar(busy: Boolean) {
    val actions = LocalReviewDraftActions.current ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("변경 ${actions.values.size}개", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        CollectorButton("변경사항 모두 적용", actions.applyAll, enabled = !busy && actions.values.isNotEmpty())
        TextButton(actions.discardAll, enabled = !busy && actions.values.isNotEmpty()) { Text("변경 취소") }
    }
}

@Composable
internal fun CompactDraftField(
    field: CanonicalEditableField,
    modified: Boolean,
    initialValue: String?,
    error: String?,
    busy: Boolean,
    actions: DesktopReviewDraftActions,
) {
    val draft = actions.values[field.path] ?: field.value.orEmpty()
    val pending = field.path in actions.values
    val sourceValue = LocalDesktopSourceValue.current
    val label = field.label + when { pending -> " · 변경"; !field.nullable -> " *"; else -> "" }
    ReviewTarget(field.path) {
        Column(Modifier.fillMaxWidth().semantics {
            contentDescription = canonicalFieldContentDescription(field, modified, error)
        }) {
            if (field.type == CanonicalFieldType.ENUM) {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton({ open = true }, enabled = !busy, shape = CollectorTokens.controlShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                            Text(draft.ifEmpty { "없음" }, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("선택", style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(open, { open = false }) {
                        val options = if (field.nullable) listOf("") + field.enumValues else field.enumValues
                        options.forEach { value ->
                            DropdownMenuItem(text = { Text(value.ifEmpty { "없음" }) }, onClick = {
                                actions.change(field.path, value)
                                sourceValue("${field.label} · 편집값: ${value.ifEmpty { "없음" }}")
                                open = false
                            })
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { actions.change(field.path, it); sourceValue("${field.label} · 편집값: $it") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (it.isFocused) sourceValue("${field.label} · 편집값: $draft")
                    },
                    label = { Text(label, maxLines = 1) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    trailingIcon = if (field.nullable && draft.isNotEmpty()) ({
                        TextButton({ actions.change(field.path, "") }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "${field.label} 값 지우기" }) {
                            Text("지우기", style = MaterialTheme.typography.labelSmall)
                        }
                    }) else null,
                )
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (modified) Text("이전: ${initialValue ?: "없음"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
