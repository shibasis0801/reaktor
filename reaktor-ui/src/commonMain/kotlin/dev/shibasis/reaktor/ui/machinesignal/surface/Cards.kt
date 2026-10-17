package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.RowAppearance
import dev.shibasis.reaktor.surface.compose.RowProperties
import dev.shibasis.reaktor.surface.compose.RowSlots
import dev.shibasis.reaktor.surface.compose.RowState
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

fun boardCard(lane: InkRole, mark: InkRole? = null): RowAppearance = BoardCard(lane, mark)

private data class BoardCard(val lane: InkRole, val mark: InkRole?) : RowAppearance {
    @Composable
    override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val tone = signal.ink(lane)
        val stripe = mark?.let(signal::ink)
        val shape = MachineSignal.Shape.Panel
        val hovered = state.hovered && properties.enabled
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = MachineSignal.Space.s1)
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, shape))
                .clip(shape)
                .background(if (hovered) colors.raisedAlt else colors.raised)
                .background(if (properties.selected) tone.copy(alpha = SelectedTint) else Color.Transparent)
                .border(MachineSignal.Stroke.hairline, tone.copy(alpha = if (properties.selected || hovered) 1f else RestingEdge), shape)
                .drawBehind {
                    if (stripe == null) return@drawBehind
                    val bar = MachineSignal.Stroke.bar.toPx() * 2
                    drawRect(stripe, Offset(if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - bar, 0f), Size(bar, size.height))
                }
                .stateLayer(LocalContentColor.current, false, feedback)
                .padding(start = MachineSignal.Space.s3 + if (stripe == null) MachineSignal.Space.none else MachineSignal.Stroke.bar, end = MachineSignal.Space.s3,
                    top = MachineSignal.Space.s2_5, bottom = MachineSignal.Space.s2_5),
        ) {
            ProvideLabel(Label(if (properties.enabled) colors.text else colors.textFaint, signal.fonts.ui, MachineSignal.Type.body), slots.content)
        }
    }
}

private const val SelectedTint = .14f
private const val RestingEdge = .42f
