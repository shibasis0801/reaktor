package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The editor's quiet chrome, shared by all tool windows. Component reference boards retain their authored style. */
val LocalSignalWorkspaceStyle = staticCompositionLocalOf { false }

@Composable
internal fun workspaceColor(color: Color): Color =
    if (!LocalSignalWorkspaceStyle.current) color else MachineSignalColors.Editor.remap(color)
