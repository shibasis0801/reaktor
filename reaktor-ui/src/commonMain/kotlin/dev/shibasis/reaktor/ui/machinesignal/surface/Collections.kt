package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.RowAppearance
import dev.shibasis.reaktor.surface.compose.RowProperties
import dev.shibasis.reaktor.surface.compose.RowSlots
import dev.shibasis.reaktor.surface.compose.RowState
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val SignalItemRow: RowAppearance = object : RowAppearance {
    @Composable
    override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val metrics = signal.metrics
        Row(
            Modifier
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, metrics.focusRing, RectangleShape))
                .fillMaxWidth()
                .heightIn(min = metrics.itemRow)
                .background(
                    when {
                        properties.selected -> colors.controlAccentSoft
                        state.hovered && properties.enabled -> colors.raised
                        else -> Color.Transparent
                    },
                )
                .drawBehind {
                    if (!properties.selected) return@drawBehind
                    val bar = SelectedBar.toPx()
                    drawRect(colors.controlAccent, Offset(if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - bar, 0f), Size(bar, size.height))
                }
                .padding(start = MachineSignal.Space.s3 + metrics.indent * properties.depth, end = MachineSignal.Space.s3),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.toggle?.let { toggle ->
                Box(toggle.size(metrics.indent), contentAlignment = Alignment.Center) {
                    if (properties.expandable) Chevron(properties.expanded, colors.textMuted)
                }
            }
            Box(Modifier.weight(1f)) {
                ProvideLabel(
                    Label(if (properties.enabled) colors.text else colors.textFaint, signal.fonts.ui, MachineSignal.Type.body, if (properties.selected) FontWeight.SemiBold else null),
                    slots.content,
                )
            }
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean, color: Color) = Canvas(Modifier.size(ChevronSize)) {
    val forward = layoutDirection == LayoutDirection.Ltr
    val path = Path().apply {
        if (expanded) {
            moveTo(0f, size.height * .25f)
            lineTo(size.width, size.height * .25f)
            lineTo(size.width / 2f, size.height * .8f)
        } else {
            val tip = if (forward) size.width * .8f else size.width * .2f
            val back = if (forward) size.width * .25f else size.width * .75f
            moveTo(back, 0f)
            lineTo(tip, size.height / 2f)
            lineTo(back, size.height)
        }
        close()
    }
    drawPath(path, color)
}

private val SelectedBar = 2.dp
private val ChevronSize = 8.dp
