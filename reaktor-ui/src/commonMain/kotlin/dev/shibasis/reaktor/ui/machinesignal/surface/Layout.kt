package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.HandleState
import dev.shibasis.reaktor.surface.compose.ItemAppearance
import dev.shibasis.reaktor.surface.compose.ItemProperties
import dev.shibasis.reaktor.surface.compose.ItemSlots
import dev.shibasis.reaktor.surface.compose.SplitAxis
import dev.shibasis.reaktor.surface.compose.SplitterAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val SignalSplitter: SplitterAppearance = object : SplitterAppearance {
    @Composable
    override fun Content(properties: SplitAxis, state: HandleState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val signal = theme.machineSignal
        Box(
            Modifier.fillMaxSize().focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape)),
            contentAlignment = Alignment.Center,
        ) {
            val hairline = MachineSignal.Space.s1 / 4
            val line = if (properties == SplitAxis.Horizontal) Modifier.width(hairline).fillMaxHeight() else Modifier.height(hairline).fillMaxWidth()
            Box(line.background(signal.colors.line))
        }
    }
}

val SignalDocumentTab: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Row(
            Modifier
                .fillMaxHeight()
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, RectangleShape))
                .background(if (properties.selected) colors.canvas else Color.Transparent)
                .drawBehind {
                    if (!properties.selected) return@drawBehind
                    val stroke = SelectedLine.toPx()
                    drawLine(colors.controlAccent, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
                }
                .padding(horizontal = MachineSignal.Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        ) {
            ProvideLabel(Label(if (properties.selected) colors.text else colors.textMuted, signal.fonts.ui, MachineSignal.Editor.label)) {
                slots.icon?.invoke()
                slots.content()
            }
        }
    }
}

val SignalTabClose: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        Box(Modifier.size(MachineSignal.Editor.controlHeight), contentAlignment = Alignment.Center) {
            ProvideLabel(Label(signal.colors.textMuted, signal.fonts.ui, MachineSignal.Editor.label), slots.content)
        }
    }
}

val SignalChromeButton: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) =
        ChromeFocus(state, theme, feedback, slots.content)
}

val SignalChromeItem: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) =
        ChromeFocus(state, theme, feedback, slots.content)
}

@Composable
private fun ChromeFocus(state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, content: @Composable () -> Unit) {
    val signal = theme.machineSignal
    Box(
        Modifier.focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape)),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) { content() }
}

private val SelectedLine = 2.dp
