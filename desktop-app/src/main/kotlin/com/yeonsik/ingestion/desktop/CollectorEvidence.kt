package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

internal val LocalDesktopSourceSelection = staticCompositionLocalOf<(List<String>, String) -> Unit> { { _, _ -> } }
internal val LocalDesktopSourceContext = staticCompositionLocalOf { "" }

@Composable
fun EvidenceInspector(
    state: DesktopUiState,
    selectedType: SourceAttachmentType,
    selectedEvidenceId: String?,
    busy: Boolean,
    onTypeSelected: (SourceAttachmentType) -> Unit,
    onEvidenceSelected: (String) -> Unit,
    onChooseEvidence: () -> Unit,
    onDropEvidence: (List<Path>) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = CollectorTokens.panelShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(CollectorTokens.space3).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CollectorTokens.space4),
        ) {
            Text(LocalDesktopSourceContext.current, style = MaterialTheme.typography.bodyMedium)
            val selected = state.evidence.firstOrNull { it.attachmentId == selectedEvidenceId } ?: state.evidence.firstOrNull()
            if (selected != null) EvidencePreview(selected)
            var attachOpen by rememberSaveable { mutableStateOf(false) }
            CollectorButton(if (attachOpen) "원본 추가 닫기" else "원본 추가", { attachOpen = !attachOpen }, emphasized = false)
            if (attachOpen) CollectorSection("원본 추가", "검수 자료에 원본을 연결합니다.") {
                if (state.session == null) {
                    Text(
                        "검토할 항목을 열면 연결된 영수증·라벨·사진을 여기에서 확인할 수 있습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    EvidenceTypeSelector(selectedType, !busy, onTypeSelected)
                    CollectorButton(
                        label = "원본 파일 선택",
                        onClick = onChooseEvidence,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        contentDescription = "${DesktopUiLabels.sourceAttachmentType(selectedType)} 원본 파일 선택",
                    )
                    ImportDropZone(
                        enabled = !busy,
                        label = "원본 증거를 여기에 놓으세요",
                        description = "${DesktopUiLabels.sourceAttachmentType(selectedType)} 원본을 첨부합니다.",
                        modifier = Modifier.heightIn(min = 108.dp),
                        onFiles = onDropEvidence,
                    )
                }
            }

            CollectorSection("연결된 원본", "원본을 클릭하면 미리보기가 바뀝니다.") {
                if (state.evidence.isEmpty()) {
                    Text("첨부된 원본이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    state.evidence.forEach { evidence ->
                        EvidenceListItem(
                            evidence = evidence,
                            selected = evidence.attachmentId == selectedEvidenceId ||
                                selectedEvidenceId == null && evidence == state.evidence.first(),
                            onClick = { onEvidenceSelected(evidence.attachmentId) },
                        )
                    }
                }
            }

            state.bundleMetadata?.archiveStatus?.let { status ->
                ValidationMessage(
                    message = "증거 보관 상태: ${DesktopUiLabels.evidenceArchiveStatus(status)}",
                    kind = when (status) {
                        DesktopEvidenceArchiveStatus.ARCHIVED -> CollectorStatusKind.COMPLETE
                        DesktopEvidenceArchiveStatus.FAILED -> CollectorStatusKind.ERROR
                        DesktopEvidenceArchiveStatus.ARCHIVING -> CollectorStatusKind.PROCESSING
                        DesktopEvidenceArchiveStatus.NOT_STARTED -> CollectorStatusKind.REVIEW
                    },
                    recoveryHint = if (status == DesktopEvidenceArchiveStatus.FAILED) "검토 화면에서 보관 다시 시도를 선택하세요." else null,
                )
            }
        }
    }
}

@Composable
private fun EvidenceTypeSelector(
    selectedType: SourceAttachmentType,
    enabled: Boolean,
    onTypeSelected: (SourceAttachmentType) -> Unit,
) {
    Text("첨부할 원본 유형", style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2),
    ) {
        SourceAttachmentType.entries.forEach { type ->
            CollectorButton(
                label = DesktopUiLabels.sourceAttachmentType(type),
                onClick = { onTypeSelected(type) },
                enabled = enabled,
                emphasized = type == selectedType,
                modifier = Modifier.semantics {
                    contentDescription = "${DesktopUiLabels.sourceAttachmentType(type)} 유형${if (type == selectedType) ", 선택됨" else ""}"
                },
            )
        }
    }
}

@Composable
private fun EvidenceListItem(
    evidence: DesktopEvidenceAttachment,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val readable = Files.isRegularFile(evidence.path) && Files.isReadable(evidence.path)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = CollectorTokens.compactControlHeight)
            .collectorFocusOutline(CollectorTokens.panelShape)
            .semantics {
                contentDescription = "${DesktopUiLabels.sourceAttachmentType(evidence.type)} 원본 ${evidence.path.fileName}. ${if (readable) "클릭하여 미리보기" else "파일을 읽을 수 없음"}${if (selected) ", 선택됨" else ""}"
            },
        shape = CollectorTokens.panelShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(CollectorTokens.space2),
            horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space2),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                if (readable) "▣" else "!",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.titleMedium,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CollectorTokens.space1)) {
                Text(DesktopUiLabels.sourceAttachmentType(evidence.type), style = MaterialTheme.typography.labelLarge)
                Text(evidence.path.fileName.toString(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (readable) "선택하여 원본 미리보기" else "파일을 읽을 수 없음",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EvidencePreview(evidence: DesktopEvidenceAttachment) {
    var loading by remember(evidence.path) { mutableStateOf(true) }
    val bitmap by produceState<ImageBitmap?>(null, evidence.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(Files.readAllBytes(evidence.path)).toComposeImageBitmap()
            }.getOrElse { runCatching { ImageIO.read(evidence.path.toFile())?.toComposeImageBitmap() }.getOrNull() }
        }
        loading = false
    }
    var enlarged by rememberSaveable(evidence.path.toString()) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("원본 · ${evidence.path.fileName}", style = MaterialTheme.typography.labelLarge)
        if (loading) Text("원본을 불러오는 중")
        else if (bitmap == null) ValidationMessage("이 원본은 미리볼 수 없습니다.", CollectorStatusKind.REVIEW,
            recoveryHint = "파일 연결은 유지됩니다. 파일 위치와 이미지 형식을 확인하세요.")
        else {
            SourceImageCanvas(bitmap!!, evidence.path.toString(), Modifier.fillMaxWidth().height(440.dp))
            CollectorButton("원본 크게 보기", { enlarged = true }, emphasized = false)
        }
    }
    if (enlarged && bitmap != null) Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text(LocalDesktopSourceContext.current)
                CollectorButton("검수로 돌아가기", { enlarged = false }, emphasized = false)
                SourceImageCanvas(bitmap!!, evidence.path.toString(), Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SourceImageCanvas(bitmap: ImageBitmap, identity: String, modifier: Modifier) {
    var scale by rememberSaveable(identity) { mutableStateOf(1f) }
    var pan by remember(identity) { mutableStateOf(Offset.Zero) }
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CollectorButton("−", { scale = (scale / 1.25f).coerceAtLeast(1f); if (scale == 1f) pan = Offset.Zero }, emphasized = false, contentDescription = "원본 축소")
            CollectorButton("+", { scale = (scale * 1.25f).coerceAtMost(8f) }, emphasized = false, contentDescription = "원본 확대")
            CollectorButton("화면 맞춤", { scale = 1f; pan = Offset.Zero }, emphasized = false)
            Text("${(scale * 100).toInt()}%", Modifier.align(Alignment.CenterVertically))
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(identity, scale) {
            detectDragGestures { change, delta -> change.consume(); if (scale > 1f) pan += delta }
        }) {
            Image(bitmap, "원본 이미지. 확대 후 끌어서 이동", Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y), contentScale = ContentScale.Fit)
        }
    }
}
