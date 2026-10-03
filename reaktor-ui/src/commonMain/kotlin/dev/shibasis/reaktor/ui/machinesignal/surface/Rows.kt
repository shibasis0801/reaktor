package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.ListRowAppearance
import dev.shibasis.reaktor.surface.compose.ListRowSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

fun selectableRow(selected: Boolean): ListRowAppearance = if (selected) SelectedRow else SelectableRow

val SelectableRow: ListRowAppearance = rowLook(selected = false)

private val SelectedRow: ListRowAppearance = rowLook(selected = true)

private fun rowLook(selected: Boolean): ListRowAppearance = object : ListRowAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ListRowSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val hovered = properties.enabled && state.hovered
        Row(
            Modifier
                .focusRing(feedback, FocusRing(colors.accent, signal.metrics.focusRing, RectangleShape))
                .fillMaxWidth()
                .background(
                    when {
                        selected -> colors.rowSelected
                        hovered -> colors.rowHover
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = TextStyle(fontFamily = signal.fonts.ui, fontSize = MachineSignal.Type.body)
            slots.leading?.invoke()
            Column(Modifier.weight(1f)) {
                ProvideLabel(if (properties.enabled) colors.text else colors.textFaint, text, slots.headline)
                slots.supporting?.let { ProvideLabel(colors.textFaint, text.copy(fontSize = MachineSignal.Type.label), it) }
            }
            slots.trailing?.let { ProvideLabel(colors.textMuted, text.copy(fontSize = MachineSignal.Type.label), it) }
        }
    }
}
