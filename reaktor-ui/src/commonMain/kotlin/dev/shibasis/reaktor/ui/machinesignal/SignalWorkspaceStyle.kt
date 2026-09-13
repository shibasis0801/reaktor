package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The editor's quiet chrome, shared by all tool windows. Component reference boards retain their authored style. */
val LocalSignalWorkspaceStyle = staticCompositionLocalOf { false }

@Composable
internal fun workspaceColor(color: Color): Color = if (!LocalSignalWorkspaceStyle.current) color else when (color) {
    MachineSignal.Bg0 -> MachineSignal.Editor.Canvas
    MachineSignal.Bg1, MachineSignal.Bg2 -> MachineSignal.Editor.Surface
    MachineSignal.Bg3, MachineSignal.Bg4 -> MachineSignal.Editor.Raised
    MachineSignal.Line1 -> MachineSignal.Editor.Code.GutterLine
    MachineSignal.Line2, MachineSignal.Line3 -> MachineSignal.Editor.Line
    MachineSignal.Text1, MachineSignal.Text2 -> MachineSignal.Editor.Text
    MachineSignal.Text3 -> MachineSignal.Editor.Muted
    MachineSignal.Text4 -> MachineSignal.Editor.Unknown
    else -> color
}
