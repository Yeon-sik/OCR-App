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
    var verificationBasis by remember { mutableStateOf(VerificationBasis.SOURCE_EVIDENCE) }
    var selectedProjections by remember { mutableStateOf<Set<com.pricetrace.receiptscanner.ingestion.IngestionProjection>?>(null) }
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
                                chooseBundleFile()?.let { file ->
                                    launchIo { controller.importBundle(file) }
                                }
                            },
                        ) { Text("Open .yeonsik") }
                        OutlinedButton(
                            enabled = !state.busy,
                            onClick = {
                                chooseJsonFile()?.let { file ->
                                    launchIo { controller.importJson(Files.readString(file)) }
                                }
                            },
                        ) { Text("Open JSON (new ingestion)") }
                        Button(
                            enabled = !state.busy && !bundleActive && state.rawJson.isNotBlank(),
                            onClick = { launchIo { controller.parseJson() } },
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
                        enabled = !state.busy && !bundleActive,
                        readOnly = bundleActive,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text(if (bundleActive) "Bundle canonical JSON (read-only)" else "External JSON (editable)") },
                        placeholder = { Text("Open or drop a yeonsik-ocr.v1/v2/v3 JSON file") },
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
                    if (state.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.FAILED) {
                        Button(
                            enabled = !state.busy,
                            onClick = { launchIo { controller.archiveEvidence() } },
                        ) { Text("Archive / Retry") }
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
                    Text("Verification basis", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val availableBases = if (state.bundleMetadata == null) VerificationBasis.entries
                        else listOf(VerificationBasis.SOURCE_EVIDENCE)
                        availableBases.forEach { basis ->
                            if (basis == verificationBasis) {
                                Button(onClick = { verificationBasis = basis }) {
                                    Text(basis.wireValue)
                                }
                            } else {
                                OutlinedButton(onClick = { verificationBasis = basis }) {
                                    Text(basis.wireValue)
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
                        ) { Text("Verify") }
                        Button(
                            enabled = !state.busy && state.session != null &&
                                state.session?.verifiedCanonicalFingerprint == state.session?.canonicalFingerprint &&
                                (state.bundleMetadata == null ||
                                    state.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED &&
                                        state.bundleMetadata?.verificationEventRecorded == true),
                            onClick = {
                                launchIo { controller.submit(effectiveSelectedProjections) }
                            },
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
            Text("Bundle: ${state.bundleValidationStatus?.name ?: "LEGACY / NONE"}")
            Text("Evidence archive: ${state.bundleMetadata?.archiveStatus?.name ?: "NOT_STARTED"}")
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
            Text(
                if (state.bundleMetadata == null) "Legacy evidence remains local until explicitly archived by a bundle flow."
                else "Bundle evidence is bound by manifest id/type and archived before verification.",
            )
            if (state.bundleMetadata == null) {
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
            }
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
private fun ProjectionCard(
    state: DesktopUiState,
    selectedProjections: Set<com.pricetrace.receiptscanner.ingestion.IngestionProjection>,
    onProjectionSelected: (com.pricetrace.receiptscanner.ingestion.IngestionProjection) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Active projections", style = MaterialTheme.typography.titleMedium)
            val projections = state.session?.projections.orEmpty().filterNot { it.status == ProjectionStatus.DISABLED }
            if (projections.isEmpty()) {
                Text("No active projection.")
            } else {
                projections.forEach { projection ->
                    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = projection.projection in selectedProjections,
                                onCheckedChange = { onProjectionSelected(projection.projection) },
                            )
                            Text(projection.projection.wireValue, style = MaterialTheme.typography.labelLarge)
                        }
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

private fun chooseBundleFile(): Path? = JFileChooser().run {
    fileFilter = FileNameExtensionFilter("Yeonsik bundles", "yeonsik")
    isMultiSelectionEnabled = false
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile?.toPath() else null
}

private fun chooseEvidenceFiles(): List<Path>? = JFileChooser().run {
    fileFilter = FileNameExtensionFilter("Image files", "png", "jpg", "jpeg", "webp", "heic")
    isMultiSelectionEnabled = true
    if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFiles?.map { it.toPath() } else null
}
