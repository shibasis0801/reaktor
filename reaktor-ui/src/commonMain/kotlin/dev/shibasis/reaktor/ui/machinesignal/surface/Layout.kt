package dev.shibasis.reaktor.ui.machinesignal.surface

import dev.shibasis.reaktor.ui.machinesignal.shapes

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import dev.shibasis.reaktor.surface.Axis
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
import dev.shibasis.reaktor.surface.compose.SeparatorAppearance
import dev.shibasis.reaktor.surface.compose.SplitAxis
import dev.shibasis.reaktor.surface.compose.SplitterAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal
import dev.shibasis.reaktor.ui.machinesignal.signalOrBoard

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
            Box(line.background(if (state.hovered || state.dragging) signal.colors.accent else signal.colors.lineSubtle))
        }
    }
}.withMatrix(MatrixSplitter)

fun divider(axis: Axis = Axis.Horizontal, strong: Boolean = false): SeparatorAppearance = Divider(axis, strong).withMatrix(MatrixSeparator(axis, strong))

private data class Divider(val axis: Axis, val strong: Boolean) : SeparatorAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val colors = theme.signalOrBoard.colors
        val line = if (axis == Axis.Horizontal) Modifier.fillMaxWidth().height(MachineSignal.Stroke.hairline) else Modifier.fillMaxHeight().width(MachineSignal.Stroke.hairline)
        Box(line.background(if (strong) colors.line else colors.lineSubtle))
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
                .padding(vertical = MachineSignal.Space.s1)
                .clip(MachineSignal.shapes.control)
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, RectangleShape))
                .background(when { properties.selected -> colors.raised; state.hovered -> colors.surfaceAlt; else -> Color.Transparent })
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
}.withMatrix(MatrixDocumentTab)

val SignalTabClose: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        Box(Modifier.size(MachineSignal.Editor.controlHeight), contentAlignment = Alignment.Center) {
            ProvideLabel(Label(signal.colors.textMuted, signal.fonts.ui, MachineSignal.Editor.label), slots.content)
        }
    }
}.withMatrix(MatrixButton(dev.shibasis.reaktor.ui.machinesignal.SignalTone.Ghost))

val SignalChromeButton: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        ChromeFocus(state, theme, feedback) {
            ProvideLabel(Label(if (properties.enabled) signal.colors.text else signal.colors.textFaint, signal.fonts.ui, signal.metrics.label), slots.content)
        }
    }
}.withMatrix(MatrixButton(dev.shibasis.reaktor.ui.machinesignal.SignalTone.Ghost))

val SignalChromeItem: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) =
        ChromeFocus(state, theme, feedback, slots.content)
}.withMatrix(MatrixTab)

@Composable
private fun ChromeFocus(state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, content: @Composable () -> Unit) {
    val signal = theme.machineSignal
    Box(
        Modifier.clip(MachineSignal.shapes.control)
            .background(if (state.hovered) signal.colors.raised else Color.Transparent)
            .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, MachineSignal.shapes.control)),
        contentAlignment = Alignment.Center,
    ) { content() }
}
