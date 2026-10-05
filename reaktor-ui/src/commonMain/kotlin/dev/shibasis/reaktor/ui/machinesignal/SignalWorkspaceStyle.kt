package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot

@Composable
internal fun workspaceColor(color: Color): Color = (LocalThemeSnapshot.current as? MachineSignalSnapshot)?.colors?.remap(color) ?: color
