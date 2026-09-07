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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
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
        title = "Yeonsik Ingestion Console",
    ) {
        YeonsikIngestionConsole(DesktopIngestionController())
    }
}

@Composable
private fun YeonsikIngestionConsole(controller: DesktopIngestionController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var evidenceType by remember { mutableStateOf(SourceAttachmentType.RECEIPT) }
    val launchIo: (suspend () -> Unit) -> Unit = remember(scope) {
        { block -> scope.launch(Dispatchers.IO) { block() } }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1.35f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Yeonsik Ingestion Console", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "IMPORT → PARSE → VALIDATE → USER REVIEW → VERIFIED → PROJECT → SUBMIT",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.busy,
                            onClick = {
                                chooseJsonFile()?.let { file ->
                                    launchIo { controller.importJson(Files.readString(file)) }
                                }
                            },
                        ) { Text("Open JSON") }
                        Button(
                            enabled = !state.busy && state.rawJson.isNotBlank(),
                            onClick = { launchIo { controller.importJson() } },
                        ) { Text("Parse & Validate") }
                        OutlinedButton(
                            enabled = !state.busy,
                            onClick = { launchIo { controller.loadLatest() } },
                        ) { Text("Load Latest") }
                    }
                    JsonDropZone(
                        enabled = !state.busy,
                        onJson = { value -> launchIo { controller.importJson(value) } },
                    )
                    TextField(
                        value = state.rawJson,
                        onValueChange = controller::updateRawJson,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text("External JSON (editable)") },
                        placeholder = { Text("Open or drop a yeonsik-ocr.v1/v2 JSON file") },
                        minLines = 14,
                        maxLines = 40,
                    )
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
                    ReviewCard(state)
                    ProjectionCard(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.busy && state.session != null,
                            onClick = { launchIo { controller.verify() } },
                        ) { Text("Verify") }
                        Button(
                            enabled = !state.busy && state.session != null,
                            onClick = { launchIo { controller.submit() } },
                        ) { Text("Submit / Retry") }
                    }
                    if (state.busy) Text("Working…", color = MaterialTheme.colorScheme.primary)
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: DesktopUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Validation", style = MaterialTheme.typography.titleMedium)
            Text("Schema: ${state.schema ?: "—"}")
            Text("Ingestion: ${state.ingestionId ?: "—"}")
            Text("Local document: ${state.localDocumentId ?: "—"}")
            Text("Session: ${state.session?.reviewStatus?.wireValue ?: "not imported"}")
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
            Text("Source evidence", style = MaterialTheme.typography.titleMedium)
            Text("Evidence is local-only and the existing core gate remains mandatory.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceAttachmentType.entries.forEach { type ->
                    if (type == selectedType) {
                        Button(onClick = { onTypeSelected(type) }) { Text(type.wireValue) }
                    } else {
                        OutlinedButton(onClick = { onTypeSelected(type) }) { Text(type.wireValue) }
                    }
                }
            }
            Button(enabled = state.session != null && !state.busy, onClick = onChoose) { Text("Choose evidence files") }
            EvidenceDropZone(enabled = state.session != null && !state.busy, onFiles = onDrop)
            state.evidence.forEach { evidence ->
                Text("${evidence.type.wireValue}: ${evidence.path.fileName} (${if (Files.isReadable(evidence.path)) "readable" else "missing"})")
            }
        }
    }
}

@Composable
private fun ReviewCard(state: DesktopUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Artifact review", style = MaterialTheme.typography.titleMedium)
            if (state.artifacts.isEmpty()) {
                Text("No parsed artifacts.")
            } else {
                state.artifacts.forEach { artifact ->
                    val status = when {
                        artifact.verified -> "VERIFIED"
                        artifact.evidenceReady -> "READY FOR VERIFY"
                        else -> "BLOCKED: ${artifact.evidenceIssues.joinToString()}"
                    }
                    Text("${artifact.label}: $status")
                }
            }
        }
    }
}

@Composable
private fun ProjectionCard(state: DesktopUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Active projections", style = MaterialTheme.typography.titleMedium)
            val projections = state.session?.projections.orEmpty().filterNot { it.status == ProjectionStatus.DISABLED }
            if (projections.isEmpty()) {
                Text("No active projection.")
            } else {
                projections.forEach { projection ->
                    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)).padding(8.dp)) {
                        Text(projection.projection.wireValue, style = MaterialTheme.typography.labelLarge)
                        Text("status=${projection.status.wireValue}, attempts=${projection.attemptCount}")
                        Text("remote id=${projection.remoteId ?: "—"}")
                        projection.lastError?.let { Text("error=$it", color = MaterialTheme.colorScheme.error) }
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
        label = "Drop a .json file here",
        onFiles = { files ->
            files.firstOrNull { it.fileName.toString().lowercase().endsWith(".json") }?.let { file ->
                runCatching { onJson(Files.readString(file)) }
            }
        },
    )
}

@Composable
private fun EvidenceDropZone(enabled: Boolean, onFiles: (List<Path>) -> Unit) {
    DropZone(enabled = enabled, label = "Drop evidence images here", onFiles = onFiles)
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
    fileFilter = FileNameExtensionFilter("JSON files", "json")
    isMultiSelectionEnabled = false
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile?.toPath() else null
}

private fun chooseEvidenceFiles(): List<Path>? = JFileChooser().run {
    fileFilter = FileNameExtensionFilter("Image files", "png", "jpg", "jpeg", "webp", "heic")
    isMultiSelectionEnabled = true
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFiles?.map { it.toPath() } else null
}
