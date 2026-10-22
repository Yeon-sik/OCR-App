package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.CanonicalFieldType

/** Row-major order stays stable while typing; only the navigation queue prioritizes errors. */
@Composable
internal fun ReviewFieldGrid(fields: List<CanonicalEditableField>, content: @Composable (CanonicalEditableField) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = reviewGridColumns(maxWidth.value.toInt())
        val rows = mutableListOf<List<Pair<CanonicalEditableField, Int>>>()
        var row = mutableListOf<Pair<CanonicalEditableField, Int>>()
        var used = 0
        fields.forEach { field ->
            val span = if (wideReviewField(field)) minOf(2, columns) else 1
            if (used + span > columns) { rows += row; row = mutableListOf(); used = 0 }
            row += field to span
            used += span
        }
        if (row.isNotEmpty()) rows += row
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { cells ->
                key(cells.first().first.path) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        cells.forEach { (field, span) -> key(field.path) {
                            Column(Modifier.weight(span.toFloat())) { content(field) }
                        } }
                        val remaining = columns - cells.sumOf { it.second }
                        if (remaining > 0) Spacer(Modifier.weight(remaining.toFloat()))
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryAccent(category: ReviewFieldCategory): Color = when (category) {
    ReviewFieldCategory.BASIS, ReviewFieldCategory.NUTRIENTS, ReviewFieldCategory.CONSUMPTION -> OcrSemanticTokens.success
    ReviewFieldCategory.PURCHASE, ReviewFieldCategory.ITEMS -> MaterialTheme.colorScheme.tertiary
    ReviewFieldCategory.PAYMENT, ReviewFieldCategory.TOTALS -> OcrSemanticTokens.payment
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
internal fun CategorizedReviewFields(fields: List<CanonicalEditableField>, content: @Composable (CanonicalEditableField) -> Unit) {
    val groups = fields.groupBy(::reviewFieldCategory)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReviewFieldCategory.entries.forEach { category ->
            val categoryFields = groups[category] ?: return@forEach
            val accent = categoryAccent(category)
            Surface(Modifier.fillMaxWidth(), shape = CollectorTokens.controlShape,
                color = accent.copy(alpha = 0.07f).compositeOver(MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(category.title, Modifier.weight(1f).semantics { heading() },
                            color = accent, style = MaterialTheme.typography.labelLarge)
                        Text("${categoryFields.size}개 항목", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    ReviewFieldGrid(categoryFields, content)
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
        Button(actions.applyAll, enabled = !busy && actions.values.isNotEmpty(), shape = CollectorTokens.controlShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary)) { Text("변경사항 모두 적용") }
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
    val unit = reviewFieldUnit(field, LocalReviewEnvelope.current, actions.values)
    val timeField = field.type == CanonicalFieldType.DATETIME
    val displayedDraft = if (timeField) reviewTimestamp(draft) else draft
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
                    value = displayedDraft,
                    onValueChange = {
                        actions.change(field.path, if (timeField) reviewTimestampInput(it, field.value) else it)
                        sourceValue("${field.label} · 편집값: $it${unit?.let { u -> " $u" }.orEmpty()}")
                    },
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (it.isFocused) sourceValue("${field.label} · 편집값: $displayedDraft${unit?.let { u -> " $u" }.orEmpty()}")
                    },
                    label = { Text(label, maxLines = 1) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    suffix = unit?.let { { Text(it, color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelMedium) } },
                    placeholder = if (timeField) ({ Text("YYYY-MM-DD-HH:MM:SS", style = MaterialTheme.typography.bodySmall) }) else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (pending) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedContainerColor = if (pending) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f) else Color.Transparent,
                    ),
                    trailingIcon = if (field.nullable && draft.isNotEmpty()) ({
                        TextButton({ actions.change(field.path, "") }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "${field.label} 값 지우기" }) {
                            Text("지우기", style = MaterialTheme.typography.labelSmall)
                        }
                    }) else null,
                )
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (modified) Text("이전: ${initialValue?.let { if (timeField) reviewTimestamp(it) else it } ?: "없음"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (timeField) Text(reviewTimestampOffset(draft)?.let { "원본 시차 $it" } ?: "원본 시차 포함 시각 입력",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}
