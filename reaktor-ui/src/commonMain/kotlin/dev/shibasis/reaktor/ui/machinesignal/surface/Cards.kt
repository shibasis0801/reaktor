package dev.shibasis.reaktor.ui.machinesignal.surface

import dev.shibasis.reaktor.ui.machinesignal.shapes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.LayoutDirection
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.RowAppearance
import dev.shibasis.reaktor.surface.compose.RowProperties
import dev.shibasis.reaktor.surface.compose.RowSlots
import dev.shibasis.reaktor.surface.compose.RowState
import dev.shibasis.reaktor.surface.compose.SectionAppearance
import dev.shibasis.reaktor.surface.compose.SectionSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.machineSignal
import dev.shibasis.reaktor.ui.machinesignal.matrixBackdrop

fun boardLane(ink: InkRole): SectionAppearance = BoardLane(ink)

private data class BoardLane(val ink: InkRole) : SectionAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) {
        val signal = theme.machineSignal
        Column(Modifier.fillMaxHeight().padding(MachineSignal.Space.s2)
            .then(if (signal.mode == MachineSignalMode.Matrix) Modifier.background(signal.colors.surface.copy(alpha = .45f), signal.shapes.panel)
                .matrixBackdrop(signal).matrixFrame(signal, recessed = true, shape = signal.shapes.panel) else Modifier), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
            Row(Modifier.fillMaxWidth().then(if (signal.mode == MachineSignalMode.Matrix) Modifier.matrixFrame(signal, scanlines = true) else Modifier).padding(horizontal = MachineSignal.Space.s1, vertical = MachineSignal.Space.s2),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
                Box(Modifier.size(MachineSignal.Dot.regular).background(signal.ink(ink), if (signal.mode == MachineSignalMode.Matrix) androidx.compose.ui.graphics.RectangleShape else CircleShape))
                Box(Modifier.weight(1f)) { slots.heading() }
                slots.trailing?.invoke()
            }
            slots.content(this)
        }
    }
}

fun boardCard(lane: InkRole, mark: InkRole? = null): RowAppearance = BoardCard(lane, mark)

private data class BoardCard(val lane: InkRole, val mark: InkRole?) : RowAppearance {
    @Composable
    override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val tone = signal.ink(lane)
        val stripe = mark?.let(signal::ink)
        val shape = MachineSignal.shapes.panel
        val hovered = state.hovered && properties.enabled
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = MachineSignal.Space.s1)
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, shape))
                .shadow(if (signal.mode == MachineSignalMode.Matrix) MachineSignal.Space.none else if (hovered) MachineSignal.Space.s1 else MachineSignal.Space.s0_5, shape, clip = false)
                .clip(shape)
                .background(when {
                    hovered -> colors.raised
                    signal.mode == MachineSignalMode.Matrix -> colors.surfaceAlt.copy(alpha = .65f)
                    else -> colors.surfaceAlt.copy(alpha = .65f).compositeOver(colors.canvas)
                })
                .background(if (properties.selected) tone.copy(alpha = SelectedTint) else Color.Transparent)
                .border(MachineSignal.Stroke.hairline, if (properties.selected) tone.copy(alpha = .65f) else if (hovered) colors.lineSubtle else Color.Transparent, shape)
                .drawBehind {
                    if (stripe == null) return@drawBehind
                    val bar = MachineSignal.Stroke.bar.toPx()
                    val inset = MachineSignal.Space.s3.toPx()
                    drawRoundRect(stripe, Offset(if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - bar, inset), Size(bar, (size.height - inset * 2).coerceAtLeast(0f)), androidx.compose.ui.geometry.CornerRadius(if (signal.mode == MachineSignalMode.Matrix) 0f else bar))
                }
                .then(if (signal.mode == MachineSignalMode.Matrix) Modifier.matrixFrame(signal, properties.selected || hovered, scanlines = true, shape = shape) else Modifier)
                .stateLayer(LocalContentColor.current, false, feedback)
                .padding(start = MachineSignal.Space.s3 + if (stripe == null) MachineSignal.Space.none else MachineSignal.Stroke.bar, end = MachineSignal.Space.s3,
                    top = MachineSignal.Space.s2_5, bottom = MachineSignal.Space.s2_5),
        ) {
            ProvideLabel(Label(if (properties.enabled) colors.text else colors.textFaint, signal.fonts.ui, MachineSignal.Type.body), slots.content)
        }
    }
}

private const val SelectedTint = .1f
