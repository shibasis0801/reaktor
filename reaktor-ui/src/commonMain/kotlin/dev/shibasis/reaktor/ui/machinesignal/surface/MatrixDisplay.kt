package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.BadgeAppearance
import dev.shibasis.reaktor.surface.compose.BadgeSlots
import dev.shibasis.reaktor.surface.compose.BarAppearance
import dev.shibasis.reaktor.surface.compose.BarSlots
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.HeaderProperties
import dev.shibasis.reaktor.surface.compose.HeaderSlots
import dev.shibasis.reaktor.surface.compose.TableHeaderAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.ui.machinesignal.machineSignal
import dev.shibasis.reaktor.ui.machinesignal.shapes

internal data class MatrixBadge(val ink: InkRole, val filled: Boolean = false) : BadgeAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BadgeSlots) {
        val signal = theme.machineSignal
        MatrixBadgeFrame(signal, signal.ink(ink), filled, slots)
    }
}

@Composable
internal fun MatrixBadgeFrame(signal: MachineSignalSnapshot, color: Color, filled: Boolean, slots: BadgeSlots) {
    Box(Modifier.heightIn(min = MachineSignal.Metrics.kbdHeight)
        .clip(signal.shapes.control).background(if (filled) color else color.copy(alpha = .08f))
        .matrixFrame(signal).padding(horizontal = MachineSignal.Space.s1_5, vertical = MachineSignal.Space.s0_25), contentAlignment = Alignment.Center) {
        val ink = if (filled) signal.colors.canvas else color
        ProvideLabel(ink, matrixTextStyle(signal, ink, signal.type.meta, FontWeight.Medium), slots.content)
    }
}

internal data class MatrixBar(val height: Dp, val gap: Dp) : BarAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BarSlots) {
        Row(Modifier.fillMaxWidth().heightIn(min = height).background(theme.machineSignal.colors.surfaceAlt)
            .matrixFrame(theme.machineSignal, scanlines = true, shape = RectangleShape)
            .padding(horizontal = MachineSignal.Space.s2), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            slots.content(this)
        }
    }
}

internal val MatrixTableHeader: TableHeaderAppearance = object : TableHeaderAppearance {
    @Composable
    override fun Content(properties: HeaderProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: HeaderSlots) {
        val signal = theme.machineSignal
        Row(Modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.controlHeight)
            .matrixInteractive(signal, state.hovered, feedback)
            .background(signal.colors.surface).matrixFrame(signal, active = properties.descending != null, scanlines = true)
            .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape))
            .padding(horizontal = MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
            MatrixLabel(signal, if (properties.descending == null) signal.colors.textMuted else signal.colors.accent) {
                Box(Modifier.weight(1f, fill = false)) { slots.content() }
                properties.descending?.let { MatrixGlyph(if (it) "v" else "^", signal) }
            }
        }
    }
}
