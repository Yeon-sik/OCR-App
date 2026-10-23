package com.pricetrace.receiptocr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.review.ProductFactField
import com.pricetrace.receiptscanner.review.ProductFactReview
import com.pricetrace.receiptscanner.review.ProductFactStatus

@Composable
internal fun ProductInformationCard(
    product: ProductCandidate,
    facts: List<ProductFactReview>,
    busy: Boolean,
    errors: Map<String, String>,
    onFieldChanged: (String, String?) -> Unit,
    onResolve: (String, ProductFactField, String?) -> Unit,
    onPendingChanged: (String, Boolean) -> Unit = { _, _ -> },
) {
    var detailsOpen by rememberSaveable(product.clientKey) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("product_candidate_${product.clientKey}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("관측된 상품 정보", style = MaterialTheme.typography.titleMedium)
            facts.forEach { fact ->
                ProductFactControl(fact, busy, errors, onFieldChanged, onResolve, onPendingChanged)
            }
            val brandActions=LocalProductBrandReview.current
            if(brandActions!=null) ProductBrandResolutionEditor(product,brandActions.drafts,brandActions.configured,busy,brandActions.search,brandActions.change)
            product.specification?.let { Text("규격 · $it") }
            product.contentAmount?.let { Text("내용량 · ${if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()} ${product.contentUnit.orEmpty()}") }
            if (facts.any { it.linked } && facts.all { it.status == ProductFactStatus.SAME }) {
                Text("상품 정보와 라벨이 일치합니다", color = ReceiptPositive,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("product_facts_match_${product.clientKey}"))
            }
            TextButton(onClick = { detailsOpen = !detailsOpen }, modifier = Modifier.heightIn(min = 48.dp).testTag("product_details_${product.clientKey}")) {
                Text(if (detailsOpen) "상세 정보 접기" else "상세 정보")
            }
            if (detailsOpen) {
                product.manufacturer?.let { Text("제조사 · $it") }
                product.variant?.let { Text("옵션 · $it") }
                product.packageCount?.let { Text("포장 수량 · $it") }
                product.barcodes.forEach { Text("바코드 · ${it.value}") }
                Text("개발자 정보", style = MaterialTheme.typography.labelMedium)
                Text("client key · ${product.clientKey}", style = MaterialTheme.typography.bodySmall)
                brandActions?.drafts?.filter { it.matches(product) }?.forEach { Text("${it.sourceField.label} Brand ID · ${it.brandId ?: "연결 없음"}",style=MaterialTheme.typography.bodySmall) }
                Text("confidence · ${product.confidence}", style = MaterialTheme.typography.bodySmall)
                Text("source attachment IDs · ${product.effectiveSourceAttachmentIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun ProductFactControl(
    fact: ProductFactReview,
    busy: Boolean,
    errors: Map<String, String>,
    onFieldChanged: (String, String?) -> Unit,
    onResolve: (String, ProductFactField, String?) -> Unit,
    onPendingChanged: (String, Boolean) -> Unit = { _, _ -> },
) {
    val tag = "product_fact_${fact.productClientKey}_${fact.field.name}"
    val error = fact.sources.firstNotNullOfOrNull { errors[it.path] }
    var value by rememberSaveable(fact.productClientKey, fact.field, fact.sources) {
        mutableStateOf(fact.primarySource.value.orEmpty())
    }
    var choice by rememberSaveable(fact.productClientKey, fact.field, fact.sources) { mutableStateOf<String?>(null) }
    LaunchedEffect(value, fact.primarySource.value, fact.status) {
        onPendingChanged(tag,
            fact.status != ProductFactStatus.CONFLICT && value != fact.primarySource.value.orEmpty())
    }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fact.status == ProductFactStatus.CONFLICT) {
            Text("${fact.field.label}${if (fact.field == ProductFactField.NAME) "이" else "가"} 서로 달라요",
                color = ReceiptWarning, style = MaterialTheme.typography.titleSmall)
            Text("원본을 보고 사용할 값을 선택하세요.", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.selectableGroup()) {
                fact.sources.forEach { source ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(choice == source.path, enabled = !busy, role = Role.RadioButton,
                                onClick = { choice = source.path; value = source.value.orEmpty() })
                            .testTag("${tag}_source_${fact.sources.indexOf(source)}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == source.path, onClick = null, enabled = !busy)
                        Column { Text(source.label, style = MaterialTheme.typography.labelSmall); Text(source.value ?: "미확인") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(choice == "manual", enabled = !busy,
                        role = Role.RadioButton, onClick = { choice = "manual" }).testTag("${tag}_manual"),
                    verticalAlignment = Alignment.CenterVertically,
                ) { RadioButton(choice == "manual", onClick = null, enabled = !busy); Text("직접 수정") }
            }
            if (choice == "manual") {
                OutlinedTextField(value, { value = it }, label = { Text(fact.field.label) }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("${tag}_input"))
            }
            OutlinedButton(
                onClick = { onResolve(fact.productClientKey, fact.field, value.takeIf(String::isNotBlank)) },
                enabled = !busy && choice != null && (fact.field != ProductFactField.NAME || value.isNotBlank()),
                modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_resolve"),
            ) { Text("${fact.field.label} 확정") }
        } else {
            OutlinedTextField(
                value, { value = it }, label = { Text(fact.field.label + if (fact.field == ProductFactField.NAME) " *" else " (선택)") },
                enabled = !busy, singleLine = true, isError = error != null,
                modifier = Modifier.fillMaxWidth().testTag("${tag}_input"),
            )
            if (value != fact.primarySource.value.orEmpty()) {
                OutlinedButton(onClick = { onFieldChanged(fact.primarySource.path, value.takeIf(String::isNotBlank)) },
                    enabled = !busy && (fact.field != ProductFactField.NAME || value.isNotBlank()),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_apply")) { Text("수정 적용") }
            }
            when (fact.status) {
                ProductFactStatus.INCOMPLETE -> Text(
                    fact.sources.filter { it.value.isNullOrBlank() }.joinToString { it.label.removeSuffix("에서 읽음") } + "에는 ${fact.field.label} 정보가 없습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProductFactStatus.MISSING -> if (fact.field == ProductFactField.NAME) Text("상품명 미확인 · 원본을 보고 입력하세요.", color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
