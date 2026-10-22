package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import dev.shibasis.reaktor.ui.machinesignal.shapes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import dev.shibasis.reaktor.surface.compose.CheckProperties
import dev.shibasis.reaktor.surface.compose.CheckState
import dev.shibasis.reaktor.surface.compose.ItemProperties
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToggleProperties
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.CheckboxAppearance
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.FieldAppearance
import dev.shibasis.reaktor.surface.compose.FieldProperties
import dev.shibasis.reaktor.surface.compose.FieldSlots
import dev.shibasis.reaktor.surface.compose.ItemAppearance
import dev.shibasis.reaktor.surface.compose.ItemSlots
import dev.shibasis.reaktor.surface.compose.SwitchAppearance
import dev.shibasis.reaktor.surface.compose.SwitchSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.SignalTone
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

internal data class MatrixButton(val tone: SignalTone, val active: Boolean = false) : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val primary = tone == SignalTone.Primary
        val color = when {
            !properties.enabled -> colors.textFaint
            primary -> colors.onAccent
            tone == SignalTone.Danger -> colors.error
            else -> colors.text
        }
        Row(Modifier.pointerHoverIcon(if (properties.enabled && !properties.busy) PointerIcon.Hand else PointerIcon.Default)
            .matrixInteractive(signal, state.hovered, feedback, properties.enabled && !properties.busy)
            .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, signal.shapes.control))
            .alpha(if (properties.busy) .45f else 1f)
            .heightIn(min = signal.metrics.controlHeight)
            .clip(signal.shapes.control)
            .background(when {
                primary && properties.enabled -> if (state.hovered) colors.accentHover else colors.accent
                active || state.hovered && properties.enabled -> colors.controlAccentSoft
                else -> if (tone == SignalTone.Ghost) Color.Transparent else colors.surface
            })
            .then(if (tone != SignalTone.Ghost || state.hovered || active) Modifier.matrixFrame(signal, primary || state.focusVisible || active) else Modifier)
            .padding(horizontal = if (tone == SignalTone.Ghost) MachineSignal.Space.s1_5 else MachineSignal.Space.s2),
            horizontalArrangement = Arrangement.spacedBy(signal.metrics.controlGap), verticalAlignment = Alignment.CenterVertically) {
            if (primary) MatrixGlyph(">_", signal, color)
            MatrixLabel(signal, color, slots.content)
        }
    }
}

internal val MatrixTab: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        val signal = theme.machineSignal
        Row(Modifier.width(IntrinsicSize.Max).heightIn(min = signal.metrics.tabHeight)
            .matrixInteractive(signal, state.hovered, feedback, properties.enabled)
            .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, signal.shapes.control))
            .matrixSelection(signal, properties.selected, state.hovered && properties.enabled)
            .then(if (properties.selected) Modifier.matrixFrame(signal, active = true) else Modifier)
            .padding(horizontal = MachineSignal.Space.s2, vertical = MachineSignal.Space.s1),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1), verticalAlignment = Alignment.CenterVertically) {
            MatrixLabel(signal, if (!properties.enabled) signal.colors.textFaint else if (properties.selected) signal.colors.accent else signal.colors.textMuted) {
                slots.icon?.invoke()
                slots.content()
            }
        }
    }
}

internal val MatrixChip: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        val signal = theme.machineSignal
        MatrixChoiceFrame(signal, state, feedback, if (properties.selected) "[x]" else "[ ]", properties.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1), verticalAlignment = Alignment.CenterVertically) {
                slots.icon?.invoke()
                slots.content()
            }
        }
    }
}

internal val MatrixRadio: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        MatrixChoiceFrame(theme.machineSignal, state, feedback, if (properties.selected) "(*)" else "( )", properties.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) { slots.icon?.invoke(); slots.content() }
        }
    }
}

internal val MatrixSwitch: SwitchAppearance = object : SwitchAppearance {
    @Composable
    override fun Content(properties: ToggleProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SwitchSlots) {
        MatrixChoiceFrame(theme.machineSignal, state, feedback, if (properties.checked) "[1]" else "[0]", properties.enabled, slots.content)
    }
}

internal val MatrixCheckbox: CheckboxAppearance = object : CheckboxAppearance {
    @Composable
    override fun Content(properties: CheckProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SwitchSlots) {
        MatrixChoiceFrame(theme.machineSignal, state, feedback, when (properties.state) {
            CheckState.Checked -> "[x]"
            CheckState.Mixed -> "[-]"
            CheckState.Unchecked -> "[ ]"
        }, properties.enabled, slots.content)
    }
}

internal val MatrixDocumentTab: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        val signal = theme.machineSignal
        Row(Modifier.fillMaxHeight()
            .matrixInteractive(signal, state.hovered, feedback, properties.enabled)
            .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, signal.shapes.control))
            .matrixSelection(signal, properties.selected, state.hovered && properties.enabled)
            .then(if (properties.selected) Modifier.matrixFrame(signal, active = true) else Modifier)
            .padding(horizontal = MachineSignal.Space.s3), horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
            MatrixLabel(signal, if (properties.selected) signal.colors.accent else signal.colors.textMuted) {
                slots.icon?.invoke()
                slots.content()
            }
        }
    }
}

internal fun FieldAppearance.withMatrixField(): FieldAppearance {
    val regular = this
    return object : FieldAppearance {
        private fun appearance(theme: ThemeSnapshot) = if ((theme as? MachineSignalSnapshot)?.mode == MachineSignalMode.Matrix) MatrixField else regular
        @Composable
        override fun textStyle(properties: FieldProperties, theme: ThemeSnapshot): TextStyle = appearance(theme).textStyle(properties, theme)
        @Composable
        override fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush = appearance(theme).cursor(properties, theme)
        @Composable
        override fun Content(properties: FieldProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FieldSlots) =
            appearance(theme).Content(properties, state, theme, feedback, slots)
    }
}

private val MatrixField: FieldAppearance = object : FieldAppearance {
    @Composable
    override fun textStyle(properties: FieldProperties, theme: ThemeSnapshot): TextStyle {
        val signal = theme.machineSignal
        return matrixTextStyle(signal, if (properties.enabled) signal.colors.textStrong else signal.colors.textFaint)
    }
    @Composable
    override fun cursor(properties: FieldProperties, theme: ThemeSnapshot): Brush = SolidColor(theme.machineSignal.colors.accent)
    @Composable
    override fun Content(properties: FieldProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FieldSlots) {
        val signal = theme.machineSignal
        val color = if (properties.error) signal.colors.error else signal.colors.accent
        Row(Modifier.heightIn(min = signal.metrics.controlHeight).clip(signal.shapes.control).background(signal.colors.canvas)
            .focusRing(state.focused, feedback, FocusRing(color, signal.metrics.focusRing, signal.shapes.control))
            .matrixFrame(signal, active = properties.enabled && (state.focused || state.hovered), scanlines = true, recessed = true)
            .then(if (properties.error) Modifier.border(MachineSignal.Stroke.hairline, color, signal.shapes.control) else Modifier)
            .padding(horizontal = MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
            MatrixGlyph(">", signal, if (properties.enabled) color else signal.colors.textFaint)
            slots.leading?.invoke()
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (properties.empty) slots.placeholder?.let { MatrixLabel(signal, signal.colors.textMuted, it) }
                slots.editor()
            }
            slots.trailing?.invoke()
        }
    }
}
