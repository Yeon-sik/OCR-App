package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.pricetrace.receiptocr.pricetrace.ManufacturerProductPublicationDraft

@Composable
internal fun ManufacturerPublicationFields(
    draft: ManufacturerProductPublicationDraft,
    onChange: (ManufacturerProductPublicationDraft) -> Unit,
    reviewed: Boolean,
    onReviewed: (Boolean) -> Unit,
) {
    val gap = CollectorTokens.space2
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PublicationText("공개 상품명", draft.productName, { onChange(draft.copy(productName = it)) }, Modifier.weight(1f))
            PublicationText("브랜드", draft.brandName, { onChange(draft.copy(brandName = it)) }, Modifier.weight(1f))
        }
        PublicationText("제조사 공식 상품 페이지", draft.manufacturerUrl, { onChange(draft.copy(manufacturerUrl = it)) }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PublicationText("제조사 SKU", draft.manufacturerSku, { onChange(draft.copy(manufacturerSku = it)) }, Modifier.weight(1f))
            PublicationText("공개 규격 표기", draft.specification, { onChange(draft.copy(specification = it)) }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PublicationText("한 포장 내용량", draft.contentAmount, { onChange(draft.copy(contentAmount = it)) }, Modifier.weight(1f))
            PublicationText("묶음의 포장 수", draft.packageCount, { onChange(draft.copy(packageCount = it)) }, Modifier.weight(1f))
        }
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            Text("내용량 단위", style = MaterialTheme.typography.bodySmall)
            ManufacturerProductPublicationDraft.CONTENT_UNITS.forEach { unit ->
                Row(
                    Modifier.selectable(draft.contentUnit == unit, role = Role.RadioButton,
                        onClick = { onChange(draft.copy(contentUnit = unit)) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(draft.contentUnit == unit, onClick = null)
                    Text(if (unit == "each") "개·정" else unit)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(reviewed, onCheckedChange = onReviewed)
            Text("제조사 페이지의 상품과 SKU·규격이 일치하며 위 정보를 공개하는 데 동의합니다.")
        }
        draft.validationMessage()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PublicationText(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, onChange, modifier = modifier, label = { Text(label) }, singleLine = true)
}
