package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.DisclosureProperties
import dev.shibasis.reaktor.surface.DisclosureState
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ButtonAppearance
import dev.shibasis.reaktor.surface.compose.ButtonSlots
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.PanelAppearance
import dev.shibasis.reaktor.surface.compose.PanelSlots
import dev.shibasis.reaktor.surface.compose.SeparatorAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val ContextMenuPanel: PanelAppearance = object : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        val colors = theme.machineSignal.colors
        val shape = MenuDefaults.shape
        Column(
            Modifier
                .shadow(MenuDefaults.ShadowElevation, shape, clip = false)
                .background(MenuDefaults.containerColor, shape)
                .clip(shape)
                .background(colors.menu)
                .padding(vertical = PaneMenuPadding)
                .width(IntrinsicSize.Max)
                .verticalScroll(rememberScrollState()),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.onMenu, content = slots.content)
        }
    }
}

val ContextMenuItem: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Row(
            Modifier
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, RectangleShape))
                .stateLayer(LocalContentColor.current, properties.enabled && state.hovered, feedback)
                .fillMaxWidth()
                .sizeIn(minWidth = PaneMenuMinWidth, maxWidth = PaneMenuMaxWidth, minHeight = PaneMenuRowHeight)
                .padding(horizontal = PaneMenuInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideLabel(
                if (properties.enabled) colors.onMenu else colors.onMenu.copy(alpha = DisabledContent),
                MaterialTheme.typography.labelLarge,
            ) {
                Box(Modifier.weight(1f)) { slots.content() }
            }
        }
    }
}

val DenseMenuPanel: PanelAppearance = object : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        val colors = theme.machineSignal.colors
        Column(
            Modifier
                .widthIn(min = DenseMenuMinWidth, max = MachineSignal.Editor.navigatorWidth * 2)
                .width(IntrinsicSize.Max)
                .background(colors.surface, MachineSignal.Shape.Control)
                .border(1.dp, colors.line, MachineSignal.Shape.Control)
                .padding(vertical = MachineSignal.Space.s1)
                .verticalScroll(rememberScrollState()),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.text, content = slots.content)
        }
    }
}

val DenseMenuItem: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        Row(
            Modifier
                .focusRing(state.focusVisible, feedback, FocusRing(colors.accent, signal.metrics.focusRing, RectangleShape))
                .stateLayer(LocalContentColor.current, properties.enabled && state.hovered, feedback)
                .fillMaxWidth()
                .heightIn(min = signal.metrics.controlHeight)
                .padding(horizontal = MachineSignal.Space.s3),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideLabel(Label(if (properties.enabled) colors.text else colors.textFaint, signal.fonts.ui, signal.metrics.label), slots.content)
        }
    }
}

val RuleSeparator: SeparatorAppearance = object : SeparatorAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = MachineSignal.Space.s1)
                .height(1.dp)
                .background(theme.machineSignal.colors.line),
        )
    }
}

private val PaneMenuPadding = 8.dp
private val PaneMenuInset = 12.dp
private val PaneMenuRowHeight = 48.dp
private val PaneMenuMinWidth = 112.dp
private val PaneMenuMaxWidth = 280.dp
private val DenseMenuMinWidth = 160.dp
private const val DisabledContent = 0.38f
