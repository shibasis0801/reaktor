package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.FieldAppearance
import dev.shibasis.reaktor.surface.compose.FieldProperties
import dev.shibasis.reaktor.surface.compose.FieldSlots
import dev.shibasis.reaktor.surface.compose.ProgressAppearance
import dev.shibasis.reaktor.surface.compose.ProgressProperties
import dev.shibasis.reaktor.surface.compose.ProgressSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val ToolbarSearchField: FieldAppearance = object : FieldAppearance {
    @Composable
    override fun textStyle(properties: FieldProperties, theme: ThemeSnapshot): TextStyle {
        val signal = theme.machineSignal
        return TextStyle(
            color = if (properties.enabled) signal.colors.text else signal.colors.textFaint,
            fontSize = signal.metrics.label,
            fontFamily = signal.fonts.mono,
        )
    }

    @Composable
    override fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush = SolidColor(theme.machineSignal.colors.controlAccent)

    @Composable
    override fun Content(properties: FieldProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FieldSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val shape = RoundedCornerShape(MachineSignal.Editor.controlRadius)
        Row(
            Modifier
                .focusRing(feedback, FocusRing(colors.accent, signal.metrics.focusRing, shape))
                .height(signal.metrics.controlHeight)
                .clip(shape)
                .background(colors.canvas)
                .border(1.dp, if (properties.error) colors.error else colors.line, shape)
                .padding(horizontal = MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(MachineSignal.Space.s1))
            slots.leading?.invoke()
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (properties.empty) slots.placeholder?.let { ProvideLabel(colors.line, TextStyle(fontFamily = signal.fonts.ui, fontSize = signal.metrics.label), it) }
                slots.editor()
            }
            slots.trailing?.invoke()
        }
    }
}

val BoardSearchField: FieldAppearance = object : FieldAppearance {
    @Composable
    override fun textStyle(properties: FieldProperties, theme: ThemeSnapshot): TextStyle {
        val signal = theme.machineSignal
        return TextStyle(
            color = if (properties.enabled) signal.colors.text else signal.colors.textFaint,
            fontSize = MachineSignal.Type.label,
            fontFamily = signal.fonts.ui,
        )
    }

    @Composable
    override fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush = SolidColor(theme.machineSignal.colors.accent)

    @Composable
    override fun Content(properties: FieldProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FieldSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Row(
            Modifier
                .focusRing(feedback, FocusRing(colors.accent, signal.metrics.focusRing, MachineSignal.Shape.Control))
                .height(signal.metrics.controlHeight)
                .background(colors.surface, MachineSignal.Shape.Control)
                .border(1.dp, if (properties.error) colors.error else colors.line, MachineSignal.Shape.Control)
                .padding(horizontal = MachineSignal.Metrics.searchPaddingX),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.searchFieldGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.leading?.invoke()
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (properties.empty) slots.placeholder?.let { ProvideLabel(colors.textMuted, TextStyle(fontFamily = signal.fonts.ui, fontSize = MachineSignal.Type.label), it) }
                slots.editor()
            }
            slots.trailing?.invoke()
        }
    }
}

val LineProgress: ProgressAppearance = object : ProgressAppearance {
    @Composable
    override fun Content(properties: ProgressProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ProgressSlots) {
        val colors = theme.machineSignal.colors
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.raised),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(properties.value ?: IndeterminateSpan).background(if (properties.value == null) colors.accent.copy(alpha = 0.6f) else colors.accent))
        }
    }
}

private const val IndeterminateSpan = 0.3f
