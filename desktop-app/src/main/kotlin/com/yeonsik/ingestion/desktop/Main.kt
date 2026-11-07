package com.yeonsik.ingestion.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

fun main() = application {
    val controller = remember { DesktopIngestionController() }
    val batchCoordinator = remember(controller) { DesktopBatchCoordinator(controller) }
    var closeError by remember { mutableStateOf<String?>(null) }
    var informationOpen by remember { mutableStateOf(false) }
    var informationBusy by remember { mutableStateOf(false) }
    var informationHasInput by remember { mutableStateOf(false) }
    var discardInformation by remember { mutableStateOf(false) }
    val closeSafely: () -> Unit = {
        if (informationBusy || controller.state.value.busy || batchCoordinator.state.value.busy) closeError = "처리 중입니다. 작업이 끝난 뒤 다시 종료하세요."
        else if (informationHasInput) closeError = "정보 보관함에 편집 입력이 있습니다. 보관함에서 저장하거나 입력을 비운 뒤 종료하세요."
        else if (controller.flushReviewDrafts()) exitApplication()
        else closeError = "초안을 저장하지 못해 창을 열어 두었습니다. 저장 공간과 폴더 권한을 확인한 뒤 다시 시도하세요."
    }
    val startupBounds = remember {
        val displays = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val config = device.defaultConfiguration
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            java.awt.Rectangle(config.bounds).apply {
                x += insets.left; y += insets.top
                width -= insets.left + insets.right; height -= insets.top + insets.bottom
            }
        }
        clampWindowBounds(WorkspacePreferences.bounds(), displays)
    }
    Window(
        onCloseRequest = closeSafely,
        title = "Yeonsik Collector",
        state = rememberWindowState(width = startupBounds.width.dp, height = startupBounds.height.dp,
            position = androidx.compose.ui.window.WindowPosition.Absolute(startupBounds.x.dp, startupBounds.y.dp)),
    ) {
        DisposableEffect(window) {
            val bounds = startupBounds
            window.minimumSize = Dimension(minOf(800, bounds.width), minOf(560, bounds.height))
            window.bounds = bounds
            if (WorkspacePreferences.maximized) window.extendedState = Frame.MAXIMIZED_BOTH
            val listener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = saveNormal()
                override fun componentResized(e: ComponentEvent) = saveNormal()
                fun saveNormal() {
                    if (window.extendedState == Frame.NORMAL) WorkspacePreferences.saveBounds(window.bounds)
                }
            }
            val stateListener = WindowStateListener { event ->
                if (event.newState and Frame.ICONIFIED == 0)
                    WorkspacePreferences.maximized = event.newState and Frame.MAXIMIZED_BOTH != 0
            }
            window.addComponentListener(listener)
            window.addWindowStateListener(stateListener)
            onDispose { window.removeComponentListener(listener); window.removeWindowStateListener(stateListener) }
        }
        CollectorTheme {
            Column(Modifier.fillMaxSize()) {
                TextButton({ informationOpen = true }) { Text("정보 보관함 · 모든 형식의 원문과 확장 기록") }
                Box(Modifier.weight(1f)) { YeonsikCollectorApp(controller, batchCoordinator) }
            }
            closeError?.let { message ->
                AlertDialog(onDismissRequest = { closeError = null }, title = { Text("입력을 보호하고 있습니다") },
                    text = { Text(message) },
                    confirmButton = { TextButton(closeSafely) { Text("다시 저장하고 종료") } },
                    dismissButton = { TextButton({ closeError = null }) { Text("계속 검수") } })
            }
        }
    }
    if (informationOpen) Window(
        onCloseRequest = {
            if (!informationBusy) {
                if (informationHasInput) discardInformation = true else informationOpen = false
            }
        },
        title = "정보 보관함 · Yeonsik Collector",
        state = rememberWindowState(width = 1000.dp, height = 800.dp),
    ) {
        CollectorTheme {
            InformationArchiveScreen(onInputState = { busy, hasInput -> informationBusy = busy; informationHasInput = hasInput })
            if (discardInformation) AlertDialog(
                onDismissRequest = { discardInformation = false },
                title = { Text("편집 입력을 닫을까요?") },
                text = { Text("저장한 원문과 기록은 유지됩니다. 입력창의 내용은 닫으면 사라집니다.") },
                confirmButton = { TextButton({ discardInformation = false; informationHasInput = false; informationOpen = false }) { Text("입력 닫기") } },
                dismissButton = { TextButton({ discardInformation = false }) { Text("계속 편집") } },
            )
        }
    }
}
