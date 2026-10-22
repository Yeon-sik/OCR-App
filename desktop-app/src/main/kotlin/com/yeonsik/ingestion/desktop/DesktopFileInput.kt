package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.border
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
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
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

internal fun desktopDropFiles(transferable: java.awt.datatransfer.Transferable): List<Path> = runCatching {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) emptyList()
    else (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
        .orEmpty().filterIsInstance<java.io.File>().map { it.toPath().toAbsolutePath().normalize() }
}.getOrDefault(emptyList())

/** Compose owns both rendering and OS drag events: no native child window covers the text. */
@OptIn(ExperimentalComposeUiApi::class)
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
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragActive = currentEnabled }
            override fun onExited(event: DragAndDropEvent) { dragActive = false }
            override fun onEnded(event: DragAndDropEvent) { dragActive = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragActive = false
                if (!currentEnabled) return false
                val files = desktopDropFiles(event.awtTransferable)
                if (files.isEmpty()) return false
                currentFiles(files)
                return true
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().heightIn(min = 112.dp)
            .border(if (dragActive) 2.dp else 1.dp,
                if (dragActive && enabled) colors.primary else colors.outlineVariant, CollectorTokens.panelShape)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { currentEnabled && it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
                target = target,
            )
            .semantics { contentDescription = "$label. $description" }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(if (dragActive && enabled) "여기에 놓으세요" else label,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(description, color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}
