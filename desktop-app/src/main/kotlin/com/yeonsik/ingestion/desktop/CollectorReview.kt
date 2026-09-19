package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.CanonicalFieldRegistry
import com.pricetrace.receiptscanner.review.CanonicalFieldType
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import com.pricetrace.receiptscanner.review.ReviewDestinationBadge
import com.pricetrace.receiptscanner.review.ReviewDestinationStatus
import com.pricetrace.receiptscanner.review.ReviewRow
import com.pricetrace.receiptscanner.review.ReviewViewModel
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import java.nio.file.Files

@Composable
fun ReviewWorkspace(
    state: DesktopUiState,
    selectedProjections: Set<IngestionProjection>,
    busy: Boolean,
    verificationBasis: VerificationBasis,
    revisionReady: Boolean,
    developerInfoVisible: Boolean,
    submitConfirmationOpen: Boolean,
    onChooseBundles: () -> Unit,
    onDropBundles: (List<java.nio.file.Path>) -> Unit,
    onVerificationBasisSelected: (VerificationBasis) -> Unit,
    onProjectionSelected: (IngestionProjection) -> Unit,
    onEdit: ((CanonicalReviewController) -> Boolean) -> Unit,
    onVerify: () -> Unit,
    onOpenSubmitConfirmation: () -> Unit,
    onDismissSubmitConfirmation: () -> Unit,
    onConfirmSubmit: () -> Unit,
    onRetryArchive: () -> Unit,
    onRetryRevision: () -> Unit,
    onDeveloperInfoVisibleChanged: (Boolean) -> Unit,
    onImportJson: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                when {
                    submitConfirmationOpen -> {
                        onDismissSubmitConfirmation()
                        true
                    }
                    developerInfoVisible -> {
                        onDeveloperInfoVisibleChanged(false)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        },
        shape = CollectorTokens.panelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val envelope = state.envelope
        if (envelope == null) {
            EmptyReview(
                busy = busy,
                error = state.error,
                onChooseBundles = onChooseBundles,
                onDropBundles = onDropBundles,
            )
            return@Surface
        }
        val model = remember(envelope, state.session, state.evidence, selectedProjections) {
            ReviewViewModel.fromCanonical(
                envelope = envelope,
                session = state.session,
                evidence = state.evidence.map {
                    LocalEvidence(
                        attachmentId = it.attachmentId,
                        type = it.type,
                        fileReadable = Files.isReadable(it.path),
                        pageId = it.pageId,
                    )
                },
                selectedProjections = selectedProjections,
            )
        }
        val canVerify = !busy && state.session != null &&
            (state.bundleMetadata == null || state.bundleMetadata.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED) &&
            revisionReady
        val canSubmit = !busy && state.session != null &&
            state.session.verifiedCanonicalFingerprint == state.session.canonicalFingerprint &&
            (state.bundleMetadata == null ||
                state.bundleMetadata.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED &&
                state.bundleMetadata.verificationEventRecorded) &&
            revisionReady && selectedProjections.isNotEmpty()

        Column(
            modifier = Modifier.fillMaxSize().padding(CollectorTokens.space4).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CollectorTokens.space5),
        ) {
            ReviewHeader(
                state = state,
                title = reviewTitle(state, model.rows),
                onDeveloperInfoVisibleChanged = onDeveloperInfoVisibleChanged,
                developerInfoVisible = developerInfoVisible,
            )
            state.error?.let { ValidationMessage(it, CollectorStatusKind.ERROR) }
            state.notice?.let { ValidationMessage(it, CollectorStatusKind.PROCESSING, recoveryHint = null) }

            if (state.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.FAILED) {
                InlineRecoveryAction(
                    title = "증거 보관을 완료해야 합니다",
                    message = state.bundleMetadata.archiveError ?: "증거 보관에 실패했습니다.",
                    buttonLabel = "보관 다시 시도",
                    busy = busy,
                    onAction = onRetryArchive,
                )
            }
            if (state.bundleMetadata?.pendingRevision != null ||
                state.bundleMetadata?.revisionArchiveStatus == DesktopCanonicalRevisionArchiveStatus.FAILED
            ) {
                InlineRecoveryAction(
                    title = "수정본 보관을 완료해야 합니다",
                    message = state.bundleMetadata.revisionArchiveError
                        ?: "수정한 canonical revision이 아직 보관되지 않았습니다.",
                    buttonLabel = "수정본 보관 다시 시도",
                    busy = busy,
                    onAction = onRetryRevision,
                )
            }

            SourceSummary(state, model.rows)
            ReceiptSection(model.rows)
            NutritionSection(model.rows)
            PurchaseAndGenericSections(model.rows)
            EditableCanonicalSection(
                envelope = envelope,
                edits = state.reviewEdits,
                fieldErrors = state.reviewFieldErrors,
                busy = busy,
                onEdit = onEdit,
            )
            DestinationSection(
                state = state,
                destinations = model.destinations,
                selectedProjections = selectedProjections,
                busy = busy,
                onProjectionSelected = onProjectionSelected,
            )
            ConfirmAndSubmitSection(
                state = state,
                busy = busy,
                canVerify = canVerify,
                canSubmit = canSubmit,
                verificationBasis = verificationBasis,
                selectedProjections = selectedProjections,
                submitConfirmationOpen = submitConfirmationOpen,
                onVerificationBasisSelected = onVerificationBasisSelected,
                onVerify = onVerify,
                onOpenSubmitConfirmation = onOpenSubmitConfirmation,
                onDismissSubmitConfirmation = onDismissSubmitConfirmation,
                onConfirmSubmit = onConfirmSubmit,
            )
            if (developerInfoVisible) {
                DeveloperInfoPanel(
                    state = state,
                    onImportJson = onImportJson,
                    onClose = { onDeveloperInfoVisibleChanged(false) },
                )
            }
        }
    }
}

@Composable
private fun EmptyReview(
    busy: Boolean,
    error: String?,
    onChooseBundles: () -> Unit,
    onDropBundles: (List<java.nio.file.Path>) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(CollectorTokens.space5),
        verticalArrangement = Arrangement.spacedBy(CollectorTokens.space4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("검토할 항목이 없습니다", style = MaterialTheme.typography.displaySmall)
        Text(
            "파일을 선택하거나 .yeonsik 파일을 놓으면 자동으로 파싱하고 검증합니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        ImportDropZone(
            enabled = !busy,
            label = ".yeonsik 파일을 여기에 놓으세요",
            description = "파일을 놓으면 자동 검증을 시작합니다.",
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            onFiles = onDropBundles,
        )
        CollectorButton(
            label = "파일 선택",
            onClick = onChooseBundles,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            contentDescription = ".yeonsik 파일 선택. 선택 즉시 가져오기와 검증을 시작합니다.",
        )
        error?.let { ValidationMessage(it, CollectorStatusKind.ERROR) }
    }
}

@Composable
fun ReviewHeader(
    state: DesktopUiState,
    title: String,
    developerInfoVisible: Boolean,
    onDeveloperInfoVisibleChanged: (Boolean) -> Unit,
) {
    val kind = overallStatusKind(state)
    val statusText = overallStatusLabel(state, kind)
    Column(verticalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space3),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1)) {
                Text("Review", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${state.schema ?: "스키마 확인 중"} · ${state.envelope?.mode?.wireValue ?: "mode 확인 중"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusBadge(kind, statusText)
        }
        WorkflowProgress(state, kind)
        CollectorButton(
            label = if (developerInfoVisible) "개발자 정보 닫기" else "개발자 정보",
            onClick = { onDeveloperInfoVisibleChanged(!developerInfoVisible) },
            emphasized = false,
            modifier = Modifier.fillMaxWidth(),
            contentDescription = if (developerInfoVisible) "개발자 정보 닫기" else "개발자 정보 열기. canonical, manifest, validation, projection 상태를 확인합니다.",
        )
    }
}

@Composable
private fun WorkflowProgress(state: DesktopUiState, kind: CollectorStatusKind) {
    val verified = state.session?.verifiedCanonicalFingerprint == state.session?.canonicalFingerprint
    val steps = listOf(
        "1 가져오기" to CollectorStatusKind.COMPLETE,
        "2 자동 검증" to if (state.error == null && state.envelope != null) CollectorStatusKind.COMPLETE else CollectorStatusKind.ERROR,
        "3 검토·수정" to if (verified) CollectorStatusKind.COMPLETE else kind,
        "4 확정·전송" to if (kind == CollectorStatusKind.COMPLETE) CollectorStatusKind.COMPLETE else CollectorStatusKind.NEUTRAL,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2),
    ) {
        steps.forEach { (label, stepKind) -> StatusBadge(stepKind, label) }
    }
}

@Composable
fun SourceSummary(state: DesktopUiState, rows: List<ReviewRow>) {
    val envelope = state.envelope ?: return
    val merchant = envelope.receipt?.merchant?.name
        ?: envelope.merchantCandidate?.name
        ?: envelope.purchaseRecords.firstOrNull()?.seller
        ?: envelope.productCandidates.firstOrNull()?.productName
        ?: "이름 미확인"
    val date = rows.firstOrNull { it.item in setOf("구매일", "주문일·시각", "결제일·시각") }?.value ?: "날짜 미확인"
    val amount = rows.firstOrNull { it.item in setOf("최종 결제금액", "총 주문금액", "실제 결제금액") }?.value ?: "금액 미확인"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CollectorTokens.panelShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        CollectorSection(
            title = "Source summary",
            subtitle = "원본에서 읽은 핵심 값입니다. 수정 전 evidence inspector와 대조하세요.",
            modifier = Modifier.padding(CollectorTokens.space3),
        ) {
            SummaryFact("판매처 / 대상", merchant)
            SummaryFact("날짜", date)
            SummaryFact("금액", amount)
            SummaryFact("원본 증거", if (state.evidence.isEmpty()) "첨부 없음" else "${state.evidence.size}개 첨부")
        }
    }
}

@Composable
private fun SummaryFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space3)) {
        Text(label, modifier = Modifier.weight(0.38f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ReceiptSection(rows: List<ReviewRow>) {
    val merchantRows = rows.filter { it.section == "판매처" || it.section == "판매처 후보" }
    val documentRows = rows.filter { it.section == "문서" || it.section == "금액" }
    val lineSections = setOf("상품", "서비스", "할인", "수수료", "세금", "팁", "환불", "반올림", "기타")
    val lineRows = rows.filter { it.section in lineSections }
    if (merchantRows.isEmpty() && documentRows.isEmpty() && lineRows.isEmpty()) return
    CollectorSection("영수증", "판매처·문서·항목·금액을 원본과 대조합니다.") {
        if (merchantRows.isNotEmpty()) ReviewRowGroup("판매처", merchantRows)
        if (documentRows.isNotEmpty()) ReviewRowGroup("문서 및 금액", documentRows)
        if (lineRows.isNotEmpty()) ReviewRowGroup("라인 항목", lineRows)
    }
}

@Composable
fun NutritionSection(rows: List<ReviewRow>) {
    val nutritionRows = rows.filter { it.section == "영양" || it.section == "섭취" }
    if (nutritionRows.isEmpty()) return
    CollectorSection("영양", "제품·메뉴의 영양 값과 섭취 정보를 검토합니다.") {
        ReviewRowGroup("영양 정보", nutritionRows)
    }
}

@Composable
private fun PurchaseAndGenericSections(rows: List<ReviewRow>) {
    val purchaseRows = rows.filter { it.section.startsWith("구매") }
    val productRows = rows.filter { it.section == "상품 후보" || it.section == "가격 관측" }
    if (purchaseRows.isNotEmpty()) {
        CollectorSection("구매", "플랫폼·판매자·주문·결제·상품을 확인합니다.") {
            ReviewRowGroup("구매 정보", purchaseRows)
        }
    }
    if (productRows.isNotEmpty()) {
        CollectorSection("상품 및 가격 관측", "상품 후보와 관측 가격을 확인합니다.") {
            ReviewRowGroup("상품 정보", productRows)
        }
    }
}

@Composable
private fun ReviewRowGroup(title: String, rows: List<ReviewRow>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CollectorTokens.panelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(CollectorTokens.space3), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            rows.forEach { row -> HumanReviewRow(row) }
        }
    }
}

@Composable
private fun HumanReviewRow(row: ReviewRow) {
    var detailsVisible by remember(row.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space3), verticalAlignment = Alignment.Top) {
            Text(row.item, modifier = Modifier.weight(0.34f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1)) {
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
                row.confidence?.let { Text("인식 신뢰도: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                if (row.evidence.isNotEmpty()) {
                    Text(
                        "근거: ${row.evidence.joinToString { it.kind.label }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (row.destinations.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space1),
                    ) {
                        row.destinations.forEach { DestinationStatus(it) }
                    }
                }
            }
        }
        if (row.details.isNotEmpty()) {
            CollectorButton(
                label = if (detailsVisible) "상세 접기" else "상세 보기",
                onClick = { detailsVisible = !detailsVisible },
                emphasized = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (detailsVisible) {
            row.details.forEach { detail ->
                SummaryFact(detail.item, detail.value)
            }
        }
    }
}

@Composable
fun DestinationStatus(badge: ReviewDestinationBadge) {
    val destination = badge.destination.fullLabel
    val accent = destinationAccent(destination, MaterialTheme.colorScheme)
    val icon = when (destination) {
        "PriceTrace" -> "PT"
        "CashOS" -> "CO"
        else -> "FT"
    }
    val status = when (badge.status) {
        ReviewDestinationStatus.PLANNED -> "전송 예정"
        ReviewDestinationStatus.UNSELECTED -> "선택 해제"
        ReviewDestinationStatus.CONDITION_UNMET -> "조건 미충족"
        ReviewDestinationStatus.NOT_APPLICABLE -> "대상 아님"
    }
    Surface(
        modifier = Modifier.semantics {
            contentDescription = "$destination, $status${badge.reason?.let { ", 이유: $it" }.orEmpty()}"
        },
        shape = CollectorTokens.controlShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent),
    ) {
        Row(Modifier.padding(horizontal = CollectorTokens.space2, vertical = CollectorTokens.space1), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space1)) {
            Text(icon, modifier = Modifier.clearAndSetSemantics { }, fontWeight = FontWeight.Bold, color = accent)
            Text("$destination · $status", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EditableCanonicalSection(
    envelope: com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope,
    edits: List<com.pricetrace.receiptscanner.review.CanonicalReviewEdit>,
    fieldErrors: Map<String, String>,
    busy: Boolean,
    onEdit: ((CanonicalReviewController) -> Boolean) -> Unit,
) {
    val fields = remember(envelope) { CanonicalFieldRegistry.fields(envelope) }
    if (fields.isEmpty()) return
    var editorVisible by remember(envelope) { mutableStateOf(false) }
    CollectorSection(
        "수정",
        "허용된 canonical 필드만 수정합니다. source·evidence·식별자·전송 대상은 변경하지 않습니다.",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
            CollectorButton(
                label = if (editorVisible) "수정 패널 닫기" else "수정 가능한 값 열기",
                onClick = { editorVisible = !editorVisible },
                enabled = !busy,
                emphasized = false,
                modifier = Modifier.weight(1f),
            )
            CollectorButton(
                label = "실행 취소",
                onClick = { onEdit { it.undo() } },
                enabled = !busy && edits.isNotEmpty(),
                emphasized = false,
                modifier = Modifier.weight(1f),
            )
            CollectorButton(
                label = "다시 실행",
                onClick = { onEdit { it.redo() } },
                enabled = !busy,
                emphasized = false,
                modifier = Modifier.weight(1f),
            )
        }
        if (editorVisible) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CollectorTokens.panelShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(CollectorTokens.space3), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space4)) {
                    fields.forEach { field ->
                        AccessibleField(
                            field = field,
                            modified = CanonicalFieldRegistry.isModified(field, edits),
                            initialValue = edits.firstOrNull { it.fieldPath == field.path }?.previousValue,
                            error = fieldErrors[field.path],
                            busy = busy,
                            onApply = { value -> onEdit { it.updateField(field.path, value) } },
                        )
                    }
                }
            }
        }
    }
}

/** Common field semantics: permanent label, error text, field type, and change status. */
@Composable
fun AccessibleField(
    field: CanonicalEditableField,
    modified: Boolean,
    initialValue: String?,
    error: String?,
    busy: Boolean,
    onApply: (String?) -> Unit,
) {
    var draft by remember(field.path, field.value) { mutableStateOf(field.value.orEmpty()) }
    Column(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = buildString {
                append(field.label)
                append(", ${field.type.wireValue}")
                if (modified) append(", 수정됨")
                error?.let { append(", 오류: $it") }
            }
        },
        verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
            Text(field.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(
                buildString {
                    append(field.type.wireValue)
                    if (modified) append(" · 수정됨")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (modified) {
            Text(
                "최초 값: ${initialValue ?: "없음"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (field.type == CanonicalFieldType.ENUM) {
            EnumAccessibleField(field, error, busy, onApply)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).collectorFocusOutline(CollectorTokens.controlShape),
                    enabled = !busy,
                    label = { Text("${field.label} 새 값") },
                    singleLine = field.type != CanonicalFieldType.DATETIME,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                )
                Column(verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1)) {
                    CollectorButton(
                        label = "적용",
                        onClick = { onApply(draft.takeIf(String::isNotBlank)) },
                        enabled = !busy && (field.nullable || draft.isNotBlank()),
                        emphasized = false,
                        contentDescription = "${field.label} 값 적용",
                    )
                    if (field.nullable) {
                        CollectorButton(
                            label = "값 지우기",
                            onClick = { draft = ""; onApply(null) },
                            enabled = !busy,
                            emphasized = false,
                            contentDescription = "${field.label} 값 지우기",
                        )
                    }
                }
            }
        }
        error?.let {
            Text("수정 방법: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EnumAccessibleField(
    field: CanonicalEditableField,
    error: String?,
    busy: Boolean,
    onApply: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics {
            contentDescription = "${field.label} 선택"
        },
        horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2),
    ) {
        if (field.nullable) {
            EnumOption(field.label, "없음", selected = field.value == null, enabled = !busy) { onApply(null) }
        }
        field.enumValues.forEach { option ->
            EnumOption(field.label, option, selected = field.value == option, enabled = !busy) { onApply(option) }
        }
    }
    error?.let { Text("수정 방법: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun EnumOption(
    fieldLabel: String,
    value: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = CollectorTokens.compactControlHeight)
            .collectorFocusOutline()
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "$fieldLabel: $value${if (selected) ", 선택됨" else ""}"
            },
        shape = CollectorTokens.controlShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Text(value, Modifier.padding(horizontal = CollectorTokens.space3, vertical = CollectorTokens.space2), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DestinationSection(
    state: DesktopUiState,
    destinations: List<ReviewDestinationBadge>,
    selectedProjections: Set<IngestionProjection>,
    busy: Boolean,
    onProjectionSelected: (IngestionProjection) -> Unit,
) {
    CollectorSection("전송 대상", "대상 이름과 상태를 확인하고 전송할 항목을 선택합니다.") {
        if (destinations.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
                destinations.forEach { DestinationStatus(it) }
            }
        }
        val projections = state.session?.projections.orEmpty().filterNot { it.status == ProjectionStatus.DISABLED }
        if (projections.isEmpty()) {
            Text("활성 전송 대상이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            projections.forEach { projection ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CollectorTokens.panelShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = CollectorTokens.compactControlHeight).padding(horizontal = CollectorTokens.space2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2),
                    ) {
                        Checkbox(
                            checked = projection.projection in selectedProjections,
                            onCheckedChange = { onProjectionSelected(projection.projection) },
                            enabled = !busy,
                            modifier = Modifier.collectorFocusOutline(),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(DesktopUiLabels.projection(projection.projection), style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${DesktopUiLabels.projectionStatus(projection.status)} · 시도 ${projection.attemptCount}회",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            projection.lastError?.let { ValidationMessage(it, CollectorStatusKind.ERROR, compact = true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmAndSubmitSection(
    state: DesktopUiState,
    busy: Boolean,
    canVerify: Boolean,
    canSubmit: Boolean,
    verificationBasis: VerificationBasis,
    selectedProjections: Set<IngestionProjection>,
    submitConfirmationOpen: Boolean,
    onVerificationBasisSelected: (VerificationBasis) -> Unit,
    onVerify: () -> Unit,
    onOpenSubmitConfirmation: () -> Unit,
    onDismissSubmitConfirmation: () -> Unit,
    onConfirmSubmit: () -> Unit,
) {
    CollectorSection("확정 및 전송", "검수 확정 후에만 선택한 대상에 전송합니다.") {
        if (state.bundleMetadata == null) {
            Text("검수 기준", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
                VerificationBasis.entries.forEach { basis ->
                    CollectorButton(
                        label = DesktopUiLabels.verificationBasis(basis),
                        onClick = { onVerificationBasisSelected(basis) },
                        enabled = !busy,
                        emphasized = basis == verificationBasis,
                        modifier = Modifier.weight(1f).semantics { selected = basis == verificationBasis },
                    )
                }
            }
        } else {
            Text("검수 기준: 원본 증거 기반 (번들)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
            CollectorButton(
                label = "검수 확정",
                onClick = onVerify,
                enabled = canVerify,
                modifier = Modifier.weight(1f),
                contentDescription = "검수 확정. 현재 수정과 증거를 다시 검증합니다.",
            )
            CollectorButton(
                label = "전송",
                onClick = onOpenSubmitConfirmation,
                enabled = canSubmit,
                emphasized = false,
                modifier = Modifier.weight(1f),
                contentDescription = "전송 확인을 엽니다. 선택한 ${selectedProjections.size}개 대상에 전송합니다.",
            )
        }
        if (submitConfirmationOpen) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CollectorTokens.panelShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Column(Modifier.padding(CollectorTokens.space3), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
                    Text("전송 확인", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "검수된 canonical 자료를 ${selectedProjections.size}개 대상으로 전송합니다. 전송 후 대상 시스템의 상태가 변경될 수 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
                        CollectorButton("전송 취소", onDismissSubmitConfirmation, emphasized = false, modifier = Modifier.weight(1f))
                        CollectorButton("전송 확인", onConfirmSubmit, enabled = !busy, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineRecoveryAction(
    title: String,
    message: String,
    buttonLabel: String,
    busy: Boolean,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CollectorTokens.panelShape,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(Modifier.padding(CollectorTokens.space3), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Text("수정 방법: 연결 상태를 확인한 뒤 다시 시도하세요.", style = MaterialTheme.typography.bodySmall)
            CollectorButton(buttonLabel, onAction, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ValidationMessage(
    message: String,
    kind: CollectorStatusKind,
    compact: Boolean = false,
    recoveryHint: String? = suggestedRecovery(message),
) {
    val colors = MaterialTheme.colorScheme
    val (container, content, border) = when (kind) {
        CollectorStatusKind.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, colors.error)
        CollectorStatusKind.REVIEW -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary)
        CollectorStatusKind.PROCESSING -> Triple(colors.primaryContainer, colors.onPrimaryContainer, colors.primary)
        CollectorStatusKind.COMPLETE -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary)
        CollectorStatusKind.NEUTRAL -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, colors.outline)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${kind.label}: $message${recoveryHint?.let { ". 수정 방법: $it" }.orEmpty()}" },
        shape = CollectorTokens.panelShape,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, border),
    ) {
        Row(Modifier.padding(CollectorTokens.space2), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2), verticalAlignment = Alignment.Top) {
            Text(kind.icon, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1)) {
                Text(kind.label, style = MaterialTheme.typography.labelLarge)
                Text(message, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
                recoveryHint?.let { Text("수정 방법: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun DeveloperInfoPanel(
    state: DesktopUiState,
    onImportJson: () -> Unit,
    onClose: () -> Unit,
) {
    CollectorSection(
        "개발자 정보",
        "이 영역은 기본 검토 UI에서 숨겨집니다. canonical·manifest·validation·projection 상태를 읽기 전용으로 보여줍니다.",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
            CollectorButton("외부 JSON 열기", onImportJson, emphasized = false, modifier = Modifier.weight(1f))
            CollectorButton("개발자 정보 닫기", onClose, emphasized = false, modifier = Modifier.weight(1f))
        }
        DeveloperValue("Canonical JSON", state.canonicalJson.ifBlank { "없음" })
        DeveloperValue("Manifest JSON", state.bundleMetadata?.manifestJson ?: "번들 manifest 없음")
        DeveloperValue(
            "Validation",
            listOfNotNull(
                state.schema?.let { "schema=$it" },
                state.session?.reviewStatus?.let { "review=${DesktopUiLabels.ingestionReviewStatus(it)}" },
                state.bundleValidationStatus?.let { "bundle=${DesktopUiLabels.bundleValidationStatus(it)}" },
                state.bundleMetadata?.archiveStatus?.let { "archive=${DesktopUiLabels.evidenceArchiveStatus(it)}" },
            ).joinToString("\n").ifBlank { "없음" },
        )
        DeveloperValue(
            "Projection",
            state.session?.projections?.joinToString("\n") {
                "${it.projection.wireValue}: ${it.status.wireValue}, attempts=${it.attemptCount}, remoteId=${it.remoteId ?: "—"}${it.lastError?.let { error -> ", error=$error" }.orEmpty()}"
            } ?: "없음",
        )
    }
}

@Composable
private fun DeveloperValue(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CollectorTokens.controlShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        SelectionContainer {
            Text(
                value,
                modifier = Modifier.padding(CollectorTokens.space2).horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun reviewTitle(state: DesktopUiState, rows: List<ReviewRow>): String =
    state.envelope?.receipt?.merchant?.name
        ?: state.envelope?.merchantCandidate?.name
        ?: state.envelope?.purchaseRecords?.firstOrNull()?.seller
        ?: state.envelope?.productCandidates?.firstOrNull()?.productName
        ?: rows.firstOrNull()?.value
        ?: "검토 항목"

private fun overallStatusKind(state: DesktopUiState): CollectorStatusKind = when {
    state.error != null -> CollectorStatusKind.ERROR
    state.busy -> CollectorStatusKind.PROCESSING
    state.session?.projections?.filterNot { it.status == ProjectionStatus.DISABLED }?.isNotEmpty() == true &&
        state.session.projections.filterNot { it.status == ProjectionStatus.DISABLED }.all { it.status == ProjectionStatus.UPLOADED } -> CollectorStatusKind.COMPLETE
    else -> CollectorStatusKind.REVIEW
}

private fun overallStatusLabel(state: DesktopUiState, kind: CollectorStatusKind): String = when (kind) {
    CollectorStatusKind.COMPLETE -> "완료"
    CollectorStatusKind.ERROR -> "오류"
    CollectorStatusKind.PROCESSING -> "처리 중"
    CollectorStatusKind.REVIEW -> if (state.session?.verifiedCanonicalFingerprint == state.session?.canonicalFingerprint) "전송 가능" else "검토 필요"
    CollectorStatusKind.NEUTRAL -> "대기"
}

private fun suggestedRecovery(message: String): String = when {
    message.contains("required", ignoreCase = true) || message.contains("증거", ignoreCase = true) ->
        "Evidence inspector에서 필요한 원본을 첨부하고 다시 검수하세요."
    message.contains("revision", ignoreCase = true) ->
        "수정본 보관을 다시 시도한 뒤 검수하세요."
    message.contains("unsupported", ignoreCase = true) || message.contains("유효하지", ignoreCase = true) ->
        "지원되는 .yeonsik 파일인지 확인하고 해당 항목만 다시 가져오세요."
    message.contains("인증", ignoreCase = true) || message.contains("auth", ignoreCase = true) ->
        "연결 설정과 로그인 상태를 확인한 뒤 다시 시도하세요."
    else -> "메시지의 원인을 확인하고 필요한 값을 수정한 뒤 다시 시도하세요."
}
