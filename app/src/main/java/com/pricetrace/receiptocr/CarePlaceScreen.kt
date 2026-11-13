package com.pricetrace.receiptocr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptocr.information.CarePlaceViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun CarePlaceScreen(model: CarePlaceViewModel, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 20.dp)) {
                Text("병원·약국", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(top = 12.dp).semantics { heading() })
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("care_content"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("영수증에서 가져온 기관 정보를 검토하거나 직접 입력하세요. 먼저 등록된 장소를 찾아 같은 곳을 선택하면 중복 등록을 줄일 수 있습니다.") }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("hospital" to "병원", "pharmacy" to "약국").forEach { (value, label) -> FilterChip(selected = model.category == value, enabled = !model.busy, onClick = { model.edit("category", value) }, label = { Text(label) }) } } }
                item { OutlinedTextField(model.name, { model.edit("name", it) }, label = { Text("병원·약국 이름") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedButton(onClick = model::search, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("PriceTrace에서 기존 장소 찾기") } }
                if (model.searchCompleted && model.places.isEmpty()) item { Text("검색 결과가 없습니다. 아래 주소를 확인하고 새 장소로 등록할 수 있습니다.") }
                items(model.places, key = { it.id }) { place ->
                    OutlinedButton(onClick = { model.select(place) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Column { Text(place.input.name); Text(place.input.address, style = MaterialTheme.typography.bodySmall) } }
                }
                item { OutlinedTextField(model.address, { model.edit("address", it) }, label = { Text("기관 주소·지점") }, minLines = 2, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(model.phone, { model.edit("phone", it) }, label = { Text("대표 전화 (선택)") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) }
                item { Row { Checkbox(model.confirmed, { model.confirmed = it }, enabled = !model.busy, modifier = Modifier.testTag("care_public_confirm")); Text("기관의 이름·주소·대표 전화만 공개됨을 확인했습니다. 환자 정보와 처방 내용을 입력하지 않았습니다.", modifier = Modifier.weight(1f).padding(top = 12.dp)) } }
                item { Button(onClick = model::register, enabled = !model.busy && model.confirmed && model.name.isNotBlank() && model.address.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("care_register")) { Text("PriceTrace에 장소 등록") } }
                item { Text("등록에는 PriceTrace 로그인이 필요합니다. 연결 오류가 나면 OCR App 홈의 설정에서 PriceTrace·CashOS 연결 계정을 확인하세요.", style = MaterialTheme.typography.bodySmall) }
                model.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                model.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                model.selected?.let { place ->
                    item { HorizontalDivider(); Text("선택한 장소 · ${place.input.name}", style = MaterialTheme.typography.titleMedium); Text(place.input.address); Text("PriceTrace 홈에서 조회할 수 있습니다. HealthTrace 처방약 편집의 병원·약국 찾기에서 이 장소를 선택하세요.") }
                    item { Text("기존 CashOS 결제 연결", style = MaterialTheme.typography.titleMedium) }
                    item { OutlinedTextField(model.month, model::updateMonth, label = { Text("결제한 달 (YYYY-MM)") }, enabled = !model.busy, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedButton(onClick = model::loadExpenses, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("care_load_payments")) { Text("CashOS 결제 불러오기") } }
                    items(model.expenses, key = { it.id }) { expense ->
                        Row { RadioButton(selected = model.expense?.id == expense.id, onClick = { model.selectExpense(expense) }, enabled = !model.busy, modifier = Modifier.testTag("care_expense_${expense.id}")); Column(Modifier.weight(1f).padding(top = 8.dp)) { Text(expense.title); Text("${expense.date} · ${NumberFormat.getIntegerInstance(Locale.KOREA).format(expense.amountKrw)}원") } }
                    }
                    if (model.expense != null) {
                        item { Row { Checkbox(model.paymentConfirmed, { model.paymentConfirmed = it }, enabled = !model.busy, modifier = Modifier.testTag("care_payment_confirm")); Text("선택한 결제가 이 병원·약국의 결제임을 확인했습니다.", modifier = Modifier.weight(1f).padding(top = 12.dp)) } }
                        item { Button(onClick = model::link, enabled = !model.busy && model.paymentConfirmed, modifier = Modifier.fillMaxWidth().testTag("care_link")) { Text("기존 결제에 연결") } }
                    }
                    item { TextButton(enabled = !model.busy, onClick = model::newPlace) { Text("다른 병원·약국 입력") } }
                }
            }
        }
    }
}
