package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private enum class CompactCollectorPane(val label: String) {
    INBOX("Inbox"),
    REVIEW("검토"),
    EVIDENCE("원본"),
}

@Composable
fun YeonsikCollectorApp(
    controller: DesktopIngestionController,
    batchCoordinator: DesktopBatchCoordinator,
) {
    val state by controller.state.collectAsState()
    val batchState by batchCoordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    var recentSessions by remember { mutableStateOf(controller.recentSessions()) }
    var selectedProjections by remember { mutableStateOf<Set<IngestionProjection>?>(null) }
    var verificationBasis by remember { mutableStateOf(VerificationBasis.SOURCE_EVIDENCE) }
    var evidenceType by remember { mutableStateOf(SourceAttachmentType.RECEIPT) }
    var selectedEvidenceId by remember { mutableStateOf<String?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    var showDeveloperInfo by remember { mutableStateOf(false) }
    var submitConfirmationOpen by remember { mutableStateOf(false) }
    var compactPane by remember { mutableStateOf(CompactCollectorPane.REVIEW) }

    val activeProjections = state.session?.projections.orEmpty()
        .filterNot { it.status == ProjectionStatus.DISABLED }
        .map { it.projection }
        .toSet()
    val effectiveSelectedProjections = selectedProjections?.intersect(activeProjections) ?: activeProjections
    val revisionReady = state.bundleMetadata?.let { metadata ->
        metadata.pendingRevision == null &&
            (state.reviewEdits.isEmpty() || metadata.revisionArchiveStatus == DesktopCanonicalRevisionArchiveStatus.ARCHIVED)
    } ?: true
    val anyBusy = state.busy || batchState.busy

    fun refreshRecent() {
        scope.launch {
            recentSessions = withContext(Dispatchers.IO) { controller.recentSessions() }
        }
    }

    val runIo: (suspend () -> Unit) -> Unit = remember(scope) {
        { action -> scope.launch(Dispatchers.IO) { action() } }
    }
    val importBundles: (List<Path>) -> Unit = { paths ->
        val bundles = paths.filter { it.fileName.toString().endsWith(".yeonsik", ignoreCase = true) }
        if (bundles.isEmpty()) {
            importNotice = "지원하지 않는 파일입니다. 확장자가 .yeonsik 인 파일을 선택하거나 놓으세요."
        } else {
            importNotice = null
            runIo {
                batchCoordinator.importBundles(bundles)
                refreshRecent()
            }
        }
    }
    val editStructured: ((CanonicalReviewController) -> Boolean) -> Unit = remember(runIo) {
        { mutation ->
            runIo {
                controller.reviseStructuredReview(mutation)
                batchCoordinator.syncActiveItem()
                refreshRecent()
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { controller.restoreLatestIfAvailable() }
        recentSessions = withContext(Dispatchers.IO) { controller.recentSessions() }
    }
    LaunchedEffect(state.ingestionId) {
        selectedProjections = null
        selectedEvidenceId = null
        submitConfirmationOpen = false
    }
    LaunchedEffect(state.busy, batchState.busy) {
        if (!state.busy && !batchState.busy) {
            recentSessions = withContext(Dispatchers.IO) { controller.recentSessions() }
        }
    }

    val inbox: @Composable () -> Unit = {
        InboxSidebar(
            batchState = batchState,
            recentSessions = recentSessions,
            selectedIngestionId = state.ingestionId,
            busy = anyBusy,
            inlineMessage = importNotice,
            onChooseBundles = {
                if (!anyBusy) importBundles(NativeFileDialogs.chooseBundles())
            },
            onDropBundles = importBundles,
            onSelectBatchItem = { itemId -> runIo { batchCoordinator.openItem(itemId) } },
            onRetryBatchItem = { itemId -> runIo { batchCoordinator.retryItem(itemId); refreshRecent() } },
            onSelectRecentSession = { ingestionId -> runIo { controller.load(ingestionId) } },
        )
    }
    val review: @Composable () -> Unit = {
        ReviewWorkspace(
            state = state,
            selectedProjections = effectiveSelectedProjections,
            busy = anyBusy,
            verificationBasis = verificationBasis,
            revisionReady = revisionReady,
            developerInfoVisible = showDeveloperInfo,
            submitConfirmationOpen = submitConfirmationOpen,
            onChooseBundles = {
                if (!anyBusy) importBundles(NativeFileDialogs.chooseBundles())
            },
            onDropBundles = importBundles,
            onVerificationBasisSelected = { verificationBasis = it },
            onProjectionSelected = { projection ->
                val next = effectiveSelectedProjections.toMutableSet()
                if (!next.add(projection)) next.remove(projection)
                selectedProjections = next
            },
            onEdit = editStructured,
            onVerify = {
                runIo {
                    controller.verify(
                        if (state.bundleMetadata == null) verificationBasis else VerificationBasis.SOURCE_EVIDENCE,
                    )
                    batchCoordinator.syncActiveItem()
                    refreshRecent()
                }
            },
            onOpenSubmitConfirmation = { submitConfirmationOpen = true },
            onDismissSubmitConfirmation = { submitConfirmationOpen = false },
            onConfirmSubmit = {
                submitConfirmationOpen = false
                runIo {
                    controller.submit(effectiveSelectedProjections)
                    batchCoordinator.syncActiveItem()
                    refreshRecent()
                }
            },
            onRetryArchive = {
                runIo {
                    controller.archiveEvidence()
                    batchCoordinator.syncActiveItem()
                    refreshRecent()
                }
            },
            onRetryRevision = {
                runIo {
                    controller.retryCanonicalRevision()
                    batchCoordinator.syncActiveItem()
                    refreshRecent()
                }
            },
            onDeveloperInfoVisibleChanged = { showDeveloperInfo = it },
            onImportJson = {
                NativeFileDialogs.chooseJson().firstOrNull()?.let { file ->
                    runIo { controller.importJson(java.nio.file.Files.readString(file)); refreshRecent() }
                }
            },
        )
    }
    val evidence: @Composable () -> Unit = {
        EvidenceInspector(
            state = state,
            selectedType = evidenceType,
            selectedEvidenceId = selectedEvidenceId,
            busy = anyBusy,
            onTypeSelected = { evidenceType = it },
            onEvidenceSelected = { selectedEvidenceId = it },
            onChooseEvidence = {
                if (state.session != null && !anyBusy) {
                    NativeFileDialogs.chooseEvidence().takeIf { it.isNotEmpty() }?.let { paths ->
                        runIo {
                            controller.attachEvidence(paths, evidenceType)
                            batchCoordinator.syncActiveItem()
                            refreshRecent()
                        }
                    }
                }
            },
            onDropEvidence = { paths ->
                if (state.session != null) {
                    val accepted = paths.filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in evidenceExtensions }
                    if (accepted.isNotEmpty()) {
                        runIo {
                            controller.attachEvidence(accepted, evidenceType)
                            batchCoordinator.syncActiveItem()
                            refreshRecent()
                        }
                    }
                }
            },
        )
    }

    AppShell(
        compactPane = compactPane,
        onCompactPaneSelected = { compactPane = it },
        inbox = inbox,
        review = review,
        evidence = evidence,
    )
}

@Composable
private fun AppShell(
    compactPane: CompactCollectorPane,
    onCompactPaneSelected: (CompactCollectorPane) -> Unit,
    inbox: @Composable () -> Unit,
    review: @Composable () -> Unit,
    evidence: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(CollectorTokens.panePadding)) {
            if (maxWidth >= 1080.dp) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(CollectorTokens.paneGap),
                ) {
                    Box(Modifier.width(276.dp).fillMaxHeight()) { inbox() }
                    Box(Modifier.weight(1f).fillMaxHeight()) { review() }
                    Box(Modifier.width(332.dp).fillMaxHeight()) { evidence() }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space2)) {
                    CompactPaneTabs(compactPane, onCompactPaneSelected)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (compactPane) {
                            CompactCollectorPane.INBOX -> inbox()
                            CompactCollectorPane.REVIEW -> review()
                            CompactCollectorPane.EVIDENCE -> evidence()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPaneTabs(
    activePane: CompactCollectorPane,
    onSelected: (CompactCollectorPane) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2),
    ) {
        CompactCollectorPane.entries.forEach { pane ->
            CollectorButton(
                label = pane.label,
                onClick = { onSelected(pane) },
                emphasized = pane == activePane,
                modifier = Modifier.weight(1f).semantics {
                    role = Role.Tab
                    this.selected = pane == activePane
                    contentDescription = "${pane.label} 탭${if (pane == activePane) ", 선택됨" else ""}"
                },
            )
        }
    }
}

@Composable
fun InboxSidebar(
    batchState: DesktopBatchState,
    recentSessions: List<DesktopRecentSession>,
    selectedIngestionId: String?,
    busy: Boolean,
    inlineMessage: String?,
    onChooseBundles: () -> Unit,
    onDropBundles: (List<Path>) -> Unit,
    onSelectBatchItem: (String) -> Unit,
    onRetryBatchItem: (String) -> Unit,
    onSelectRecentSession: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = CollectorTokens.panelShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(CollectorTokens.space3).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CollectorTokens.space3),
        ) {
            CollectorSection("Inbox", "가져온 항목은 자동으로 파싱·검증됩니다.") {
                CollectorButton(
                    label = "파일 선택",
                    onClick = onChooseBundles,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = ".yeonsik 파일 선택. 선택 즉시 가져오기와 검증을 시작합니다.",
                )
                ImportDropZone(
                    enabled = !busy,
                    label = ".yeonsik 파일을 여기로 끌어 놓으세요",
                    description = "여러 파일을 한 번에 놓을 수 있으며 즉시 가져오기와 검증을 시작합니다.",
                    modifier = Modifier.heightIn(min = 112.dp),
                    onFiles = onDropBundles,
                )
                inlineMessage?.let { ValidationMessage(message = it, kind = CollectorStatusKind.ERROR) }
            }

            if (batchState.items.isNotEmpty()) {
                CollectorSection(
                    "이번 작업",
                    "전체 ${batchState.summary.total} · 검토 필요 ${batchState.summary.reviewRequired} · 완료 ${batchState.summary.completed} · 오류 ${batchState.summary.failed}",
                ) {
                    batchState.items.forEach { item ->
                        InboxBatchItem(
                            item = item,
                            selected = item.ingestionId == selectedIngestionId,
                            enabled = !busy && item.ingestionId != null,
                            onSelect = { onSelectBatchItem(item.itemId) },
                            onRetry = { onRetryBatchItem(item.itemId) },
                        )
                    }
                }
            }

            val batchIngestionIds = batchState.items.mapNotNull { it.ingestionId }.toSet()
            val history = recentSessions.filterNot { it.ingestionId in batchIngestionIds }
            if (history.isNotEmpty()) {
                CollectorSection("최근 열었던 항목", "이 PC에 저장된 로컬 세션입니다.") {
                    history.forEach { item ->
                        InboxRecentItem(
                            item = item,
                            selected = item.ingestionId == selectedIngestionId,
                            enabled = !busy,
                            onSelect = { onSelectRecentSession(item.ingestionId) },
                        )
                    }
                }
            }
            if (batchState.items.isEmpty() && history.isEmpty()) {
                Text(
                    "아직 항목이 없습니다. 파일 선택 또는 드롭으로 시작하세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InboxBatchItem(
    item: DesktopBatchWorkItem,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
            .collectorFocusOutline(CollectorTokens.panelShape)
            .semantics {
                this.selected = selected
                contentDescription = "${item.sourceFileName}, ${DesktopUiLabels.batchItemStatus(item.status)}${item.error?.let { ", 오류: $it" }.orEmpty()}"
            },
        shape = CollectorTokens.panelShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(CollectorTokens.space2),
            verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2), verticalAlignment = Alignment.Top) {
                Text(
                    item.sourceFileName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(batchItemStatusKind(item.status), DesktopUiLabels.batchItemStatus(item.status))
            }
            item.merchantOrPlatform?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            item.error?.let { ValidationMessage(it, CollectorStatusKind.ERROR, compact = true) }
            if (item.status == DesktopBatchItemStatus.FAILED) {
                CollectorButton(
                    label = "이 항목 다시 시도",
                    onClick = onRetry,
                    enabled = enabled,
                    emphasized = false,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = "${item.sourceFileName} 항목 다시 시도",
                )
            }
        }
    }
}

@Composable
private fun InboxRecentItem(
    item: DesktopRecentSession,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val kind = when {
        item.hasError -> CollectorStatusKind.ERROR
        item.completed -> CollectorStatusKind.COMPLETE
        else -> CollectorStatusKind.REVIEW
    }
    Surface(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().collectorFocusOutline(CollectorTokens.panelShape).semantics {
            this.selected = selected
            contentDescription = "${item.title}, ${kind.label}, ${item.updatedAt}"
        },
        shape = CollectorTokens.panelShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(CollectorTokens.space2), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2), verticalAlignment = Alignment.Top) {
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(kind)
            }
            Text(item.updatedAt, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun batchItemStatusKind(status: DesktopBatchItemStatus): CollectorStatusKind = when (status) {
    DesktopBatchItemStatus.FAILED -> CollectorStatusKind.ERROR
    DesktopBatchItemStatus.COMPLETED -> CollectorStatusKind.COMPLETE
    DesktopBatchItemStatus.IMPORTING,
    DesktopBatchItemStatus.ARCHIVING,
    DesktopBatchItemStatus.SUBMITTING,
    -> CollectorStatusKind.PROCESSING
    DesktopBatchItemStatus.DUPLICATE -> CollectorStatusKind.NEUTRAL
    else -> CollectorStatusKind.REVIEW
}

private val evidenceExtensions = setOf("png", "jpg", "jpeg", "webp", "heic", "heif")
