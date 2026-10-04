package com.yeonsik.ingestion.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
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

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Yeonsik Collector",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        DisposableEffect(window) {
            val displays = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                val config = device.defaultConfiguration
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
                java.awt.Rectangle(config.bounds).apply {
                    x += insets.left; y += insets.top
                    width -= insets.left + insets.right; height -= insets.top + insets.bottom
                }
            }
            val bounds = clampWindowBounds(WorkspacePreferences.bounds(), displays)
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
            val controller = remember { DesktopIngestionController() }
            val batchCoordinator = remember(controller) { DesktopBatchCoordinator(controller) }
            YeonsikCollectorApp(controller, batchCoordinator)
        }
    }
}
