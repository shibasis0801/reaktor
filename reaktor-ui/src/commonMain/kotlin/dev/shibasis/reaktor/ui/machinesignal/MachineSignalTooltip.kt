package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TooltipState
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.Tooltip
import dev.shibasis.reaktor.surface.compose.TooltipAppearance
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.ui.machinesignal.surface.SignalTooltip

@Composable
fun MachineSignalTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = Tooltip(modifier, appearance = SignalTooltip, tip = { Text(text) }, content = content)

@Composable
fun MachineSignalTooltip(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = Tooltip(modifier, appearance = Unframed, tip = tooltip, content = content)

private val Unframed: TooltipAppearance = object : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: TooltipState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) = slots.tip()
}
