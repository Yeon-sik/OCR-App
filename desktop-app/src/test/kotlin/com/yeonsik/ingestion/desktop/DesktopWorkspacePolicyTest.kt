package com.yeonsik.ingestion.desktop

import java.awt.Rectangle
import org.junit.Assert.*
import org.junit.Test

class DesktopWorkspacePolicyTest {
    @Test fun breakpointsKeepReviewUsable() {
        assertEquals(WorkspaceLayout.COMPACT, workspaceLayout(800))
        assertEquals(WorkspaceLayout.COMPACT, workspaceLayout(1039))
        assertEquals(WorkspaceLayout.MEDIUM, workspaceLayout(1040))
        assertEquals(WorkspaceLayout.LARGE, workspaceLayout(1360))
        assertEquals(WorkspaceLayout.LARGE, workspaceLayout(1600))
    }
    @Test fun disconnectedDisplayAndOversizedBoundsAreClamped() {
        assertEquals(Rectangle(0, 0, 1280, 720), clampWindowBounds(Rectangle(4000, -1200, 2200, 1400), listOf(Rectangle(0, 0, 1280, 720))))
    }
    @Test fun negativeMonitorCoordinatesAndSmallDisplaysAreSupported() {
        val screen = Rectangle(-1280, 0, 1280, 720)
        assertEquals(Rectangle(-1200, 30, 900, 600), clampWindowBounds(Rectangle(-1200, 30, 900, 600), listOf(screen, Rectangle(0, 0, 1920, 1080))))
        assertEquals(Rectangle(0, 0, 640, 480), clampWindowBounds(Rectangle(0, 0, 100, 100), listOf(Rectangle(0, 0, 640, 480))))
    }
}
