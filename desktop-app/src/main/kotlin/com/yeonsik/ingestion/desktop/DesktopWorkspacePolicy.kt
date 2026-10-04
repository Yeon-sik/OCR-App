package com.yeonsik.ingestion.desktop

import java.awt.Rectangle
import java.util.prefs.Preferences

internal enum class WorkspaceLayout { COMPACT, MEDIUM, LARGE }
internal fun workspaceLayout(width: Int): WorkspaceLayout = when {
    width >= 1360 -> WorkspaceLayout.LARGE
    width >= 1040 -> WorkspaceLayout.MEDIUM
    else -> WorkspaceLayout.COMPACT
}

/** AWT user-space coordinates throughout; no mixing device pixels and Compose dp. */
internal fun clampWindowBounds(saved: Rectangle, displays: List<Rectangle>): Rectangle {
    val display = displays.maxByOrNull { saved.intersection(it).let { r -> if (r.isEmpty) 0L else r.width.toLong() * r.height } }
        ?: Rectangle(0, 0, 1440, 900)
    val width = saved.width.coerceIn(minOf(800, display.width), display.width)
    val height = saved.height.coerceIn(minOf(560, display.height), display.height)
    return Rectangle(saved.x.coerceIn(display.x, display.x + display.width - width),
        saved.y.coerceIn(display.y, display.y + display.height - height), width, height)
}

internal object WorkspacePreferences {
    private val prefs = Preferences.userRoot().node("com/yeonsik/collector/workspace")
    fun bounds() = Rectangle(prefs.getInt("x", 40), prefs.getInt("y", 40), prefs.getInt("width", 1440), prefs.getInt("height", 900))
    fun saveBounds(r: Rectangle) { prefs.putInt("x", r.x); prefs.putInt("y", r.y); prefs.putInt("width", r.width); prefs.putInt("height", r.height) }
    var maximized: Boolean
        get() = prefs.getBoolean("maximized", false)
        set(value) = prefs.putBoolean("maximized", value)
    var sourceWidth: Int
        get() = prefs.getInt("sourceWidth", 420).coerceIn(320, 560)
        set(value) = prefs.putInt("sourceWidth", value.coerceIn(320, 560))
}
