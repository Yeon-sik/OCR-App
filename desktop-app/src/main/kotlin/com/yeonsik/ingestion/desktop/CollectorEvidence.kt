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
            CollectorSection("Evidence inspector", "원본을 선택하면 이 패널에서 미리 볼 수 있습니다.") {
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

            val selected = state.evidence.firstOrNull { it.attachmentId == selectedEvidenceId } ?: state.evidence.firstOrNull()
            if (selected != null) {
                EvidencePreview(selected)
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
    val readable = Files.isRegularFile(evidence.path) && Files.isReadable(evidence.path)
    val bitmap = remember(evidence.path, readable) {
        if (!readable) {
            null
        } else {
            val bytes = Files.readAllBytes(evidence.path)
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrElse {
                runCatching { ImageIO.read(evidence.path.toFile())?.toComposeImageBitmap() }.getOrNull()
            }
        }
    }
    CollectorSection(
        "원본 미리보기",
        "${DesktopUiLabels.sourceAttachmentType(evidence.type)} · ${evidence.path.fileName}",
    ) {
        when {
            !readable -> ValidationMessage(
                "원본 파일을 읽을 수 없습니다: ${evidence.path.fileName}",
                CollectorStatusKind.ERROR,
                recoveryHint = "파일 위치와 접근 권한을 확인한 뒤 원본을 다시 첨부하세요.",
            )
            bitmap == null -> ValidationMessage(
                "이 원본 형식은 내장 미리보기를 만들 수 없습니다: ${evidence.path.fileName}",
                CollectorStatusKind.REVIEW,
                recoveryHint = "파일은 증거 연결에 유지됩니다. PNG/JPEG/WebP 형식이면 화면에서 바로 미리볼 수 있습니다.",
            )
            else -> Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp)
                    .semantics { contentDescription = "${DesktopUiLabels.sourceAttachmentType(evidence.type)} 원본 미리보기: ${evidence.path.fileName}" },
                shape = CollectorTokens.panelShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(Modifier.fillMaxWidth().padding(CollectorTokens.space2), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "${DesktopUiLabels.sourceAttachmentType(evidence.type)} 원본: ${evidence.path.fileName}",
                        modifier = Modifier.fillMaxWidth().sizeIn(maxHeight = 500.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
