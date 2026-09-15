package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.domain.ReceiptBenefitKind
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import com.pricetrace.receiptscanner.review.ReviewViewModel
import com.pricetrace.receiptscanner.review.ReviewRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "영식 영수증 수집 콘솔",
    ) {
        YeonsikIngestionConsole(DesktopIngestionController())
    }
}

@Composable
private fun YeonsikIngestionConsole(controller: DesktopIngestionController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var evidenceType by remember { mutableStateOf(SourceAttachmentType.RECEIPT) }
    var verificationBasis by remember { mutableStateOf(VerificationBasis.SOURCE_EVIDENCE) }
    var selectedProjections by remember { mutableStateOf<Set<IngestionProjection>?>(null) }
    var reviewTab by remember { mutableStateOf(ReviewTab.STRUCTURED) }
    val activeProjections = state.session?.projections.orEmpty()
        .filterNot { it.status == ProjectionStatus.DISABLED }
        .map { it.projection }
        .toSet()
    val effectiveSelectedProjections = selectedProjections?.intersect(activeProjections) ?: activeProjections
    val bundleActive = state.bundleMetadata != null || state.bundleValidationStatus != null
    LaunchedEffect(state.ingestionId) {
        selectedProjections = null
    }
    val launchIo: (suspend () -> Unit) -> Unit = remember(scope) {
        { block -> scope.launch(Dispatchers.IO) { block() } }
    }
    val editStructured: ((CanonicalReviewController) -> Boolean) -> Unit = remember(launchIo) {
        { mutation -> launchIo { controller.reviseStructuredReview(mutation) } }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1.35f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("영식 영수증 수집 콘솔", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "가져오기 → 파싱 → 검증 → 사용자 검수 → 검수 완료 → 대상 생성 → 전송",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.busy,
                            onClick = {
                                chooseBundleFile()?.let { file ->
                                    launchIo { controller.importBundle(file) }
                                }
                            },
                        ) { Text(".yeonsik 파일 열기") }
                        OutlinedButton(
                            enabled = !state.busy,
                            onClick = {
                                chooseJsonFile()?.let { file ->
                                    launchIo { controller.importJson(Files.readString(file)) }
                                }
                            },
                        ) { Text("새 수집을 위한 JSON 열기") }
                        Button(
                            enabled = !state.busy && !bundleActive && state.rawJson.isNotBlank(),
                            onClick = { launchIo { controller.parseJson() } },
                        ) { Text("파싱 및 검증") }
                        OutlinedButton(
                            enabled = !state.busy,
                            onClick = { launchIo { controller.loadLatest() } },
                        ) { Text("최신 항목 불러오기") }
                    }
                    JsonDropZone(
                        enabled = !state.busy,
                        onJson = { value -> launchIo { controller.importJson(value) } },
                    )
                    ReviewTabs(selected = reviewTab, onSelected = { reviewTab = it })
                    if (reviewTab == ReviewTab.STRUCTURED) {
                        Column(
                            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        ) {
                            DesktopReviewTable(state, effectiveSelectedProjections, editStructured)
                        }
                    } else {
                        TextField(
                            value = state.rawJson,
                            onValueChange = controller::updateRawJson,
                            enabled = !state.busy && !bundleActive,
                            readOnly = bundleActive,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            label = { Text(if (bundleActive) "번들 정본 JSON (읽기 전용)" else "외부 JSON (편집 가능)") },
                            placeholder = { Text("yeonsik-ocr.v1/v2/v3 JSON 파일을 열거나 여기에 놓으세요") },
                            minLines = 14,
                            maxLines = 40,
                        )
                    }
                }

                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SummaryCard(state)
                    EvidenceCard(
                        state = state,
                        selectedType = evidenceType,
                        onTypeSelected = { evidenceType = it },
                        onChoose = {
                            chooseEvidenceFiles()?.let { files ->
                                launchIo { controller.attachEvidence(files, evidenceType) }
                            }
                        },
                        onDrop = { files -> launchIo { controller.attachEvidence(files, evidenceType) } },
                    )
                    if (state.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.FAILED) {
                        Button(
                            enabled = !state.busy,
                            onClick = { launchIo { controller.archiveEvidence() } },
                        ) { Text("보관 / 재시도") }
                    }
                    ReviewCard(state)
                    ProjectionCard(
                        state = state,
                        selectedProjections = effectiveSelectedProjections,
                        onProjectionSelected = { projection ->
                            val next = effectiveSelectedProjections.toMutableSet()
                            if (!next.add(projection)) next.remove(projection)
                            selectedProjections = next
                        },
                    )
                    Text("검수 기준", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val availableBases = if (state.bundleMetadata == null) VerificationBasis.entries
                        else listOf(VerificationBasis.SOURCE_EVIDENCE)
                        availableBases.forEach { basis ->
                            if (basis == verificationBasis) {
                                Button(onClick = { verificationBasis = basis }) {
                                    Text(DesktopUiLabels.verificationBasis(basis))
                                }
                            } else {
                                OutlinedButton(onClick = { verificationBasis = basis }) {
                                    Text(DesktopUiLabels.verificationBasis(basis))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.busy && state.session != null &&
                                (state.bundleMetadata == null ||
                                    state.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED),
                            onClick = {
                                launchIo {
                                    controller.verify(
                                        if (state.bundleMetadata == null) verificationBasis
                                        else VerificationBasis.SOURCE_EVIDENCE,
                                    )
                                }
                            },
                        ) { Text("검수 완료 처리") }
                        Button(
                            enabled = !state.busy && state.session != null &&
                                state.session?.verifiedCanonicalFingerprint == state.session?.canonicalFingerprint &&
                                (state.bundleMetadata == null ||
                                    state.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED &&
                                        state.bundleMetadata?.verificationEventRecorded == true),
                            onClick = {
                                launchIo { controller.submit(effectiveSelectedProjections) }
                            },
                        ) { Text("전송 / 재시도") }
                    }
                    if (state.busy) Text("처리 중…", color = MaterialTheme.colorScheme.primary)
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private enum class ReviewTab { STRUCTURED, RAW_JSON }

@Composable
private fun ReviewTabs(selected: ReviewTab, onSelected: (ReviewTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected == ReviewTab.STRUCTURED) Button(onClick = { onSelected(ReviewTab.STRUCTURED) }) { Text("정리 보기") }
        else OutlinedButton(onClick = { onSelected(ReviewTab.STRUCTURED) }) { Text("정리 보기") }
        if (selected == ReviewTab.RAW_JSON) Button(onClick = { onSelected(ReviewTab.RAW_JSON) }) { Text("원본 JSON") }
        else OutlinedButton(onClick = { onSelected(ReviewTab.RAW_JSON) }) { Text("원본 JSON") }
    }
}

@Composable
private fun DesktopReviewTable(
    state: DesktopUiState,
    selectedProjections: Set<IngestionProjection>,
    onEdit: (((CanonicalReviewController) -> Boolean) -> Unit),
) {
    val model = state.envelope?.let { envelope ->
        ReviewViewModel.fromCanonical(
            envelope = envelope,
            session = state.session,
            evidence = state.evidence.map { LocalEvidence(it.attachmentId, it.type, Files.isReadable(it.path), it.pageId) },
            selectedProjections = selectedProjections,
        )
    }
    if (model == null) {
        Text("JSON을 파싱하면 인식 정보와 전송 계획을 여기에서 확인할 수 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("인식 정보 및 전송 계획 · ${model.schema}", style = MaterialTheme.typography.titleMedium)
        if (
            state.envelope.schemaVersion == "yeonsik-ocr.v2" &&
            state.envelope.receipt != null
        ) {
            DesktopStructuredReviewEditor(state.envelope, onEdit)
        }
        ReviewTableHeader()
        if (model.rows.isEmpty()) Text("표시할 인식 정보가 없습니다.")
        else model.rows.forEach { row -> DesktopReviewTableRow(row) }
    }
}

@Composable
private fun DesktopStructuredReviewEditor(
    envelope: com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope,
    onEdit: (((CanonicalReviewController) -> Boolean) -> Unit),
) {
    val receipt = envelope.receipt ?: return
    val restaurant = receipt.merchant.businessKind == com.pricetrace.receiptscanner.domain.BusinessKind.FOOD_SERVICE
    var merchantDraft by remember(receipt.merchant.name) { mutableStateOf(receipt.merchant.name.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("구조화 편집", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onEdit { it.undo() } }) { Text("실행 취소") }
                    TextButton(onClick = { onEdit { it.redo() } }) { Text("다시 실행") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(
                    value = merchantDraft,
                    onValueChange = { merchantDraft = it },
                    label = { Text("판매처명") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { onEdit { it.updateMerchantName(merchantDraft) } },
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) { Text("적용") }
            }
            receipt.lineItems.forEach { line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("line ${line.id}", style = MaterialTheme.typography.labelMedium)
                        var descriptionDraft by remember(line.id, line.description) {
                            mutableStateOf(line.description.orEmpty())
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextField(
                                value = descriptionDraft,
                                onValueChange = { descriptionDraft = it },
                                label = { Text("설명") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { onEdit { it.updateLineDescription(line.id, descriptionDraft) } },
                                modifier = Modifier.align(Alignment.CenterVertically),
                            ) { Text("적용") }
                        }
                        Text("line type", style = MaterialTheme.typography.labelSmall)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ReceiptLineType.entries.forEach { type ->
                                if (line.type == type) {
                                    Button(onClick = { onEdit { it.updateLineType(line.id, type) } }) {
                                        Text(type.desktopDisplayName())
                                    }
                                } else {
                                    OutlinedButton(onClick = { onEdit { it.updateLineType(line.id, type) } }) {
                                        Text(type.desktopDisplayName())
                                    }
                                }
                            }
                        }
                        if (restaurant && line.type == ReceiptLineType.PRODUCT) {
                            Text("food_service role", style = MaterialTheme.typography.labelSmall)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FoodServiceRole.entries.forEach { role ->
                                    if (line.foodService?.role == role) {
                                        Button(onClick = { onEdit { it.updateFoodServiceRole(line.id, role) } }) {
                                            Text(role.desktopDisplayName())
                                        }
                                    } else {
                                        OutlinedButton(onClick = { onEdit { it.updateFoodServiceRole(line.id, role) } }) {
                                            Text(role.desktopDisplayName())
                                        }
                                    }
                                }
                            }
                            if (line.foodService?.role == FoodServiceRole.OPTION) {
                                var parentDraft by remember(line.id, line.foodService?.appliesToLineId) {
                                    mutableStateOf(line.foodService?.appliesToLineId.orEmpty())
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextField(
                                        value = parentDraft,
                                        onValueChange = { parentDraft = it },
                                        label = { Text("옵션 부모 line id") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedButton(
                                        onClick = { onEdit { it.updateFoodServiceOptionParent(line.id, parentDraft.takeIf(String::isNotBlank)) } },
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                    ) { Text("적용") }
                                }
                            }
                            val benefit = line.foodService?.benefitKind
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = benefit == ReceiptBenefitKind.REVIEW_EVENT,
                                    onCheckedChange = { checked -> onEdit { it.setRestaurantReviewEvent(line.id, checked) } },
                                )
                                Text("리뷰 이벤트")
                                benefit?.let { Text(" · 혜택 ${it.desktopDisplayName()}") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ReceiptLineType.desktopDisplayName(): String = when (this) {
    ReceiptLineType.PRODUCT -> "상품"
    ReceiptLineType.SERVICE -> "서비스"
    ReceiptLineType.DISCOUNT -> "할인"
    ReceiptLineType.FEE -> "수수료"
    ReceiptLineType.TAX -> "세금"
    ReceiptLineType.TIP -> "팁"
    ReceiptLineType.REFUND -> "환불"
    ReceiptLineType.ROUNDING -> "반올림"
    ReceiptLineType.OTHER -> "기타"
}

private fun FoodServiceRole.desktopDisplayName(): String = when (this) {
    FoodServiceRole.MAIN -> "메인"
    FoodServiceRole.OPTION -> "옵션"
    FoodServiceRole.SIDE -> "사이드"
}

private fun ReceiptBenefitKind.desktopDisplayName(): String = when (this) {
    ReceiptBenefitKind.INCLUDED -> "포함"
    ReceiptBenefitKind.COMPLIMENTARY -> "무료"
    ReceiptBenefitKind.REVIEW_EVENT -> "리뷰 이벤트"
    ReceiptBenefitKind.PROMOTION -> "프로모션"
    ReceiptBenefitKind.OTHER -> "기타"
}

@Composable
private fun ReviewTableHeader() {
    Row(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)).padding(8.dp)) {
        Text("구분", Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium)
        Text("항목", Modifier.weight(1.5f), style = MaterialTheme.typography.labelMedium)
        Text("인식 값", Modifier.weight(2f), style = MaterialTheme.typography.labelMedium)
        Text("근거", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium)
        Text("전송 대상", Modifier.weight(2.2f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DesktopReviewTableRow(row: ReviewRow) {
    var expanded by remember(row.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
            Text(row.section, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall)
            Text(row.item, Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(2f)) {
                Text(row.value)
                row.confidence?.let { Text("confidence $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (row.details.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "상세 접기" else "상세 보기") }
                }
            }
            Row(Modifier.weight(1.4f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.evidence.forEach { badge -> ReviewEvidenceBadgeText(badge.kind.label) }
            }
            Row(Modifier.weight(2.2f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.destinations.forEach { badge -> ReviewDestinationBadgeText(badge) }
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                row.details.forEach { detail -> Text("${detail.item}: ${detail.value}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ReviewEvidenceBadgeText(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReviewDestinationBadgeText(badge: com.pricetrace.receiptscanner.review.ReviewDestinationBadge) {
    val color = Color(badge.destination.colorHex.removePrefix("#").toLong(16) or 0xFF000000L)
    Surface(color = color, contentColor = Color.Black, shape = MaterialTheme.shapes.small) {
        Text(
            "${badge.destination.shortLabel} · ${badge.status.label}" +
                badge.reason?.let { " · $it" }.orEmpty(),
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SummaryCard(state: DesktopUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("검증 결과", style = MaterialTheme.typography.titleMedium)
            Text("스키마: ${state.schema ?: "—"}")
            Text("수집 ID: ${state.ingestionId ?: "—"}")
            Text("로컬 문서 ID: ${state.localDocumentId ?: "—"}")
            Text("세션: ${state.session?.reviewStatus?.let(DesktopUiLabels::ingestionReviewStatus) ?: "가져오지 않음"}")
            Text("번들: ${state.bundleValidationStatus?.let(DesktopUiLabels::bundleValidationStatus) ?: "기존 형식 / 없음"}")
            Text("증거 보관: ${state.bundleMetadata?.archiveStatus?.let(DesktopUiLabels::evidenceArchiveStatus) ?: "시작 전"}")
        }
    }
}

@Composable
private fun EvidenceCard(
    state: DesktopUiState,
    selectedType: SourceAttachmentType,
    onTypeSelected: (SourceAttachmentType) -> Unit,
    onChoose: () -> Unit,
    onDrop: (List<Path>) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("원본 증거", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.bundleMetadata == null) "기존 형식의 증거는 번들 흐름에서 명시적으로 보관하기 전까지 로컬에만 남습니다."
                else "번들 증거는 매니페스트 ID/유형으로 연결되며 검수 전에 보관됩니다.",
            )
            if (state.bundleMetadata == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceAttachmentType.entries.forEach { type ->
                        if (type == selectedType) {
                            Button(onClick = { onTypeSelected(type) }) { Text(DesktopUiLabels.sourceAttachmentType(type)) }
                        } else {
                            OutlinedButton(onClick = { onTypeSelected(type) }) { Text(DesktopUiLabels.sourceAttachmentType(type)) }
                        }
                    }
                }
                Button(enabled = state.session != null && !state.busy, onClick = onChoose) { Text("증거 파일 선택") }
                EvidenceDropZone(enabled = state.session != null && !state.busy, onFiles = onDrop)
            }
            state.evidence.forEach { evidence ->
                Text("${DesktopUiLabels.sourceAttachmentType(evidence.type)}: ${evidence.path.fileName} (${DesktopUiLabels.fileAvailability(Files.isReadable(evidence.path))})")
            }
        }
    }
}

@Composable
private fun ReviewCard(state: DesktopUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("자료 검수", style = MaterialTheme.typography.titleMedium)
            if (state.artifacts.isEmpty()) {
                Text("파싱된 자료가 없습니다.")
            } else {
                state.artifacts.forEach { artifact ->
                    val status = when {
                        else -> DesktopUiLabels.artifactStatus(
                            verified = artifact.verified,
                            evidenceReady = artifact.evidenceReady,
                            issues = artifact.evidenceIssues,
                        )
                    }
                    Text("${artifact.label}: $status")
                }
            }
        }
    }
}

@Composable
private fun ProjectionCard(
    state: DesktopUiState,
    selectedProjections: Set<IngestionProjection>,
    onProjectionSelected: (IngestionProjection) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("활성 전송 대상", style = MaterialTheme.typography.titleMedium)
            val projections = state.session?.projections.orEmpty().filterNot { it.status == ProjectionStatus.DISABLED }
            if (projections.isEmpty()) {
                Text("활성 전송 대상이 없습니다.")
            } else {
                projections.forEach { projection ->
                    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = projection.projection in selectedProjections,
                                onCheckedChange = { onProjectionSelected(projection.projection) },
                            )
                            Text(DesktopUiLabels.projection(projection.projection), style = MaterialTheme.typography.labelLarge)
                        }
                        Text("상태: ${DesktopUiLabels.projectionStatus(projection.status)}, 시도 횟수: ${projection.attemptCount}")
                        Text("원격 ID: ${projection.remoteId ?: "—"}")
                        projection.lastError?.let { Text("오류: $it", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonDropZone(enabled: Boolean, onJson: (String) -> Unit) {
    DropZone(
        enabled = enabled,
        label = "JSON 파일을 여기에 놓으세요",
        onFiles = { files ->
            files.firstOrNull { it.fileName.toString().lowercase().endsWith(".json") }?.let { file ->
                runCatching { onJson(Files.readString(file)) }
            }
        },
    )
}

@Composable
private fun EvidenceDropZone(enabled: Boolean, onFiles: (List<Path>) -> Unit) {
    DropZone(enabled = enabled, label = "증거 이미지를 여기에 놓으세요", onFiles = onFiles)
}

@Composable
private fun DropZone(enabled: Boolean, label: String, onFiles: (List<Path>) -> Unit) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCallback by rememberUpdatedState(onFiles)
    Box(
        Modifier.fillMaxWidth().height(58.dp).border(1.dp, MaterialTheme.colorScheme.outline),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray)
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            factory = {
                JPanel().apply {
                    isOpaque = false
                    dropTarget = DropTarget(this, object : DropTargetAdapter() {
                        override fun drop(event: DropTargetDropEvent) {
                            if (!currentEnabled || !event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                event.rejectDrop()
                                return
                            }
                            try {
                                event.acceptDrop(DnDConstants.ACTION_COPY)
                                val files = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
                                    .filterIsInstance<java.io.File>()
                                    .map { it.toPath() }
                                currentCallback(files)
                                event.dropComplete(true)
                            } catch (_: Exception) {
                                event.dropComplete(false)
                            }
                        }
                    })
                }
            },
        )
    }
}

private fun chooseJsonFile(): Path? = JFileChooser().run {
    fileFilter = FileNameExtensionFilter("JSON 파일", "json")
    isMultiSelectionEnabled = false
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile?.toPath() else null
}

private fun chooseBundleFile(): Path? = JFileChooser().run {
    fileFilter = FileNameExtensionFilter("영식 번들 파일", "yeonsik")
    isMultiSelectionEnabled = false
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile?.toPath() else null
}

private fun chooseEvidenceFiles(): List<Path>? = JFileChooser().run {
    fileFilter = FileNameExtensionFilter("이미지 파일", "png", "jpg", "jpeg", "webp", "heic")
    isMultiSelectionEnabled = true
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFiles?.map { it.toPath() } else null
}
