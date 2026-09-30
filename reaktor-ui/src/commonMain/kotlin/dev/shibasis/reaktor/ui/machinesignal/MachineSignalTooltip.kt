package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Hover/focus help with no passive anchor geometry or paint; also wrap unavailable actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineSignalTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        state = rememberTooltipState(),
        modifier = modifier,
        focusable = false,
        tooltip = {
            Box(
                Modifier.widthIn(max = MachineSignal.Editor.navigatorWidth)
                    .background(MachineSignal.Editor.Raised, MachineSignal.Shape.Tight)
                    .border(MachineSignal.Space.s1 / 4, MachineSignal.Editor.Line, MachineSignal.Shape.Tight)
                    .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
            ) {
                Text(
                    text = text,
                    color = MachineSignal.Editor.Text,
                    fontFamily = LocalMachineSignalFonts.current.ui,
                    fontSize = MachineSignal.Editor.label,
                    lineHeight = MachineSignal.Editor.label * MachineSignal.Editor.lineHeight,
                )
            }
        },
        content = content,
    )
}
