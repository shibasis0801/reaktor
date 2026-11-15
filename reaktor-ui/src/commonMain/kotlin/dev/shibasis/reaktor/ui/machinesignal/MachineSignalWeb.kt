package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb

fun MachineSignalSnapshot.webChrome(): Map<String, String> {
    fun Color.css() = "#" + (compositeOver(colors.canvas).toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
    return mapOf(
        "--hangar-scheme" to if (mode == MachineSignalMode.Light) "light" else "dark",
        "--hangar-background" to colors.canvas.css(),
        "--hangar-surface" to colors.surface.css(),
        "--hangar-text" to colors.text.css(),
        "--hangar-muted" to colors.textMuted.css(),
        "--hangar-line" to colors.line.css(),
        "--hangar-focus" to colors.controlAccent.css(),
        "--hangar-font" to "system-ui,-apple-system,'Segoe UI',sans-serif",
        "--hangar-font-size" to "${type.label.value}px",
        "--hangar-caption-size" to "${type.caption.value}px",
        "--hangar-space" to "${metrics.controlPadding.value}px",
        "--hangar-gap" to "${metrics.controlGap.value}px",
        "--hangar-focus-width" to "${metrics.focusRing.value}px",
        "--hangar-control-height" to "${metrics.controlHeight.value}px",
    )
}
