package com.yeonsik.ingestion.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Yeonsik Collector",
    ) {
        CollectorTheme {
            val controller = remember { DesktopIngestionController() }
            val batchCoordinator = remember(controller) { DesktopBatchCoordinator(controller) }
            YeonsikCollectorApp(controller, batchCoordinator)
        }
    }
}
