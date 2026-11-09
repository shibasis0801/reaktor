package dev.shibasis.reaktor.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlatformSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    colors: PlatformControlColors,
    modifier: Modifier,
) = MaterialPlatformSwitch(checked, onCheckedChange, colors, modifier)

@Composable
actual fun PlatformChoice(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    colors: PlatformControlColors,
    modifier: Modifier,
) = MaterialPlatformChoice(options, selected, onSelect, colors, modifier)
