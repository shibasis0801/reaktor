package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import dev.shibasis.reaktor.surface.DisclosureProperties
import dev.shibasis.reaktor.surface.DisclosureState
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.BarAppearance
import dev.shibasis.reaktor.surface.compose.BarSlots
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.ItemAppearance
import dev.shibasis.reaktor.surface.compose.ItemProperties
import dev.shibasis.reaktor.surface.compose.ItemSlots
import dev.shibasis.reaktor.surface.compose.ListRowAppearance
import dev.shibasis.reaktor.surface.compose.ListRowSlots
import dev.shibasis.reaktor.surface.compose.PanelAppearance
import dev.shibasis.reaktor.surface.compose.PanelSlots
import dev.shibasis.reaktor.surface.compose.RowAppearance
import dev.shibasis.reaktor.surface.compose.RowProperties
import dev.shibasis.reaktor.surface.compose.RowSlots
import dev.shibasis.reaktor.surface.compose.RowState
import dev.shibasis.reaktor.surface.compose.SectionAppearance
import dev.shibasis.reaktor.surface.compose.SectionSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.machineSignal
import dev.shibasis.reaktor.ui.machinesignal.matrixBackdrop
import dev.shibasis.reaktor.ui.machinesignal.shapes

internal data class MatrixSection(val inset: Dp = MachineSignal.Space.s3, val framed: Boolean = true) : SectionAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) {
        val signal = theme.machineSignal
        Column(Modifier.fillMaxWidth().then(if (framed) Modifier.background(signal.colors.surface, signal.shapes.panel).matrixBackdrop(signal).matrixFrame(signal, scanlines = true, shape = signal.shapes.panel) else Modifier)) {
            Row(Modifier.fillMaxWidth().background(signal.colors.surfaceAlt).matrixFrame(signal, scanlines = true, shape = RectangleShape)
                .heightIn(min = MachineSignal.Editor.sectionHeaderHeight).padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s1),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
                MatrixGlyph("//", signal, signal.colors.accent)
                Box(Modifier.weight(1f)) {
                    ProvideLabel(signal.colors.accentText, matrixTextStyle(signal, signal.colors.accentText, signal.type.label, FontWeight.Medium).copy(letterSpacing = .14.em), slots.heading)
                }
                slots.trailing?.invoke()
            }
            Column(Modifier.padding(inset)) { slots.content(this) }
        }
    }
}

internal val MatrixSegmentTrack: BarAppearance = object : BarAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BarSlots) {
        Row(Modifier.heightIn(min = theme.machineSignal.metrics.controlHeight).width(IntrinsicSize.Min)
            .matrixFrame(theme.machineSignal).padding(MachineSignal.Stroke.hairline), verticalAlignment = Alignment.CenterVertically) { slots.content(this) }
    }
}

internal data class MatrixNavigationButton(val selected: Boolean) : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) =
        MatrixNavigation(theme.machineSignal, selected, properties.enabled && state.hovered, state.focusVisible, feedback, slots.content)
}

internal val MatrixNavigationRow: RowAppearance = object : RowAppearance {
    @Composable
    override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) =
        MatrixNavigation(theme.machineSignal, properties.selected, properties.enabled && state.hovered, state.focusVisible, feedback, slots.content)
}

@Composable
private fun MatrixNavigation(signal: MachineSignalSnapshot, selected: Boolean, hovered: Boolean, focused: Boolean, feedback: ComposeFeedback, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.layerRowHeight)
        .focusRing(focused, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape))
        .matrixInteractive(signal, hovered, feedback)
            .matrixSelection(signal, selected, hovered).padding(horizontal = MachineSignal.Space.s2),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
        MatrixGlyph(if (selected) ">" else "·", signal, if (selected) signal.colors.accent else signal.colors.textFaint)
        Box(Modifier.weight(1f)) { MatrixLabel(signal, if (selected) signal.colors.textStrong else signal.colors.text, content) }
    }
}

internal data class MatrixRow(val table: Boolean = false) : RowAppearance {
    @Composable
    override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
        val signal = theme.machineSignal
        Row(Modifier.fillMaxWidth()
            .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape))
            .heightIn(min = if (table) signal.metrics.tableRow else signal.metrics.itemRow)
            .matrixInteractive(signal, state.hovered, feedback, properties.enabled)
            .matrixSelection(signal, properties.selected, state.hovered && properties.enabled)
            .padding(horizontal = if (table) MachineSignal.Space.none else MachineSignal.Space.s3,
                vertical = MachineSignal.Space.s1),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1), verticalAlignment = Alignment.CenterVertically) {
            slots.toggle?.let { toggle ->
                Box(toggle.size(signal.metrics.indent), contentAlignment = Alignment.Center) {
                    if (properties.expandable) MatrixGlyph(if (properties.expanded) "-" else "+", signal)
                }
            }
            Box(Modifier.weight(1f).padding(start = if (table) MachineSignal.Space.none else signal.metrics.indent * properties.depth)) {
                MatrixLabel(signal, if (properties.enabled) signal.colors.text else signal.colors.textFaint, slots.content)
            }
        }
    }
}

internal data class MatrixListRow(val selected: Boolean) : ListRowAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ListRowSlots) {
        val signal = theme.machineSignal
        Row(Modifier.fillMaxWidth().focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape))
            .matrixInteractive(signal, state.hovered, feedback, properties.enabled)
            .matrixSelection(signal, selected, state.hovered && properties.enabled).padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
            MatrixGlyph(if (selected) ">" else "·", signal)
            slots.leading?.invoke()
            Column(Modifier.weight(1f)) {
                MatrixLabel(signal, if (properties.enabled) signal.colors.text else signal.colors.textFaint, slots.headline)
                slots.supporting?.let { MatrixLabel(signal, signal.colors.textMuted, it) }
            }
            slots.trailing?.let { MatrixLabel(signal, signal.colors.textMuted, it) }
        }
    }
}

internal val MatrixMenuPanel: PanelAppearance = object : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        val signal = theme.machineSignal
        Column(Modifier.widthIn(min = MachineSignal.Editor.navigatorWidth / 2, max = MachineSignal.Editor.navigatorWidth * 2)
            .width(IntrinsicSize.Max).clip(signal.shapes.panel).background(signal.colors.surface)
            .matrixBackdrop(signal).matrixFrame(signal, active = true, scanlines = true, shape = signal.shapes.panel).padding(MachineSignal.Space.s1).verticalScroll(rememberScrollState())) {
            CompositionLocalProvider(LocalContentColor provides signal.colors.text) { slots.content() }
        }
    }
}

internal val MatrixMenuItem: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        Row(Modifier.fillMaxWidth().heightIn(min = signal.metrics.controlHeight)
            .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape))
            .matrixInteractive(signal, state.hovered, feedback, properties.enabled)
            .matrixSelection(signal, selected = false, hovered = state.hovered && properties.enabled)
            .padding(horizontal = MachineSignal.Space.s2, vertical = MachineSignal.Space.s1),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
            MatrixGlyph(if (state.hovered) ">" else "·", signal, if (properties.enabled) signal.colors.accent else signal.colors.textFaint)
            MatrixLabel(signal, if (properties.enabled) signal.colors.text else signal.colors.textFaint) {
                Box(Modifier.weight(1f)) { slots.content() }
            }
        }
    }
}

internal data class MatrixPanel(val dialog: Boolean = false, val sheet: Boolean = false) : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        val signal = theme.machineSignal
        Column(Modifier.then(if (sheet) Modifier.fillMaxWidth().navigationBarsPadding() else Modifier.widthIn(max = MachineSignal.Editor.navigatorWidth * 2))
            .clip(signal.shapes.panel).background(signal.colors.surface)
            .matrixBackdrop(signal).matrixFrame(signal, active = true, scanlines = true, shape = signal.shapes.panel).padding(if (dialog || sheet) MachineSignal.Space.s4 else MachineSignal.Space.s3),
            verticalArrangement = Arrangement.spacedBy(if (dialog || sheet) MachineSignal.Space.s3 else MachineSignal.Space.s2)) {
            MatrixLabel(signal, content = slots.content)
        }
    }
}
