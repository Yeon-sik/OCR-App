package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.datatransfer.DataFlavor
import java.io.FilenameFilter
import java.nio.file.Path

/**
 * Uses the platform file dialog (the Windows common dialog on Windows), rather than JFileChooser.
 * The filter is also enforced after selection because platform dialogs may allow typed filenames.
 */
object NativeFileDialogs {
    fun chooseBundles(): List<Path> = openFiles(
        title = "영식 파일 선택",
        extensions = setOf("yeonsik", "json"),
        multiple = true,
    )

    fun chooseJson(): List<Path> = openFiles(
        title = "JSON 파일 선택",
        extensions = setOf("json"),
        multiple = false,
    )

    fun chooseEvidence(): List<Path> = openFiles(
        title = "원본 증거 파일 선택",
        extensions = setOf("png", "jpg", "jpeg", "webp", "heic", "heif"),
        multiple = true,
    )

    private fun openFiles(title: String, extensions: Set<String>, multiple: Boolean): List<Path> {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        return try {
            dialog.isMultipleMode = multiple
            dialog.filenameFilter = FilenameFilter { _, name ->
                name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in extensions
            }
            dialog.isVisible = true
            dialog.files.orEmpty()
                .map { it.toPath().toAbsolutePath().normalize() }
                .filter { path -> path.fileName.toString().substringAfterLast('.', "").lowercase() in extensions }
        } finally {
            dialog.dispose()
        }
    }
}

/**
 * A transparent AWT drop adapter is necessary on current Compose Desktop for OS-level file drops.
 * It is not a Swing UI: all visible affordance, focus information, and text remain Compose-owned.
 */
@Composable
fun ImportDropZone(
    enabled: Boolean,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    onFiles: (List<Path>) -> Unit,
) {
    var dragActive by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentFiles by rememberUpdatedState(onFiles)
    val currentDragState by rememberUpdatedState<(Boolean) -> Unit> { dragActive = it }
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        !enabled -> colors.outlineVariant
        dragActive -> colors.primary
        else -> colors.outline
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .border(if (dragActive) 2.dp else 1.dp, borderColor, CollectorTokens.panelShape)
            .semantics {
                contentDescription = "$label. $description"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (dragActive) "여기에 놓으면 즉시 가져와 검증합니다" else label,
            modifier = Modifier.padding(CollectorTokens.space4),
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            factory = {
                javax.swing.JPanel().apply {
                    isOpaque = false
                    background = java.awt.Color(0, 0, 0, 0)
                    dropTarget = DropTarget(this, object : DropTargetAdapter() {
                        override fun dragEnter(event: java.awt.dnd.DropTargetDragEvent) {
                            if (currentEnabled && event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                currentDragState(true)
                            }
                        }

                        override fun dragExit(event: java.awt.dnd.DropTargetEvent) {
                            currentDragState(false)
                        }

                        override fun drop(event: DropTargetDropEvent) {
                            currentDragState(false)
                            if (!currentEnabled || !event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                event.rejectDrop()
                                return
                            }
                            try {
                                event.acceptDrop(DnDConstants.ACTION_COPY)
                                val files = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
                                    .filterIsInstance<java.io.File>()
                                    .map { it.toPath().toAbsolutePath().normalize() }
                                currentFiles(files)
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
