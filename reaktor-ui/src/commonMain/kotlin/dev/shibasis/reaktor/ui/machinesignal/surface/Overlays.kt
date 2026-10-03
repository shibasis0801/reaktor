package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.DisclosureProperties
import dev.shibasis.reaktor.surface.DisclosureState
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToastEntry
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.PanelAppearance
import dev.shibasis.reaktor.surface.compose.PanelSlots
import dev.shibasis.reaktor.surface.compose.TipContent
import dev.shibasis.reaktor.surface.compose.ToastAppearance
import dev.shibasis.reaktor.surface.compose.ToastSlots
import dev.shibasis.reaktor.surface.compose.TooltipAppearance
import dev.shibasis.reaktor.surface.compose.TooltipSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import dev.shibasis.reaktor.ui.machinesignal.StatusDot
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val SignalTooltip: TooltipAppearance = object : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) {
        val signal = theme.machineSignal
        val colors = signal.colors
        val size = signal.metrics.label
        Column(
            Modifier
                .widthIn(max = MachineSignal.Editor.navigatorWidth)
                .background(colors.raised, MachineSignal.Shape.Tight)
                .border(1.dp, colors.line, MachineSignal.Shape.Tight)
                .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s2),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1),
        ) {
            val tip = Label(colors.text, signal.fonts.ui, size, lineHeight = size * MachineSignal.Editor.lineHeight)
            val aside = tip.copy(color = colors.textMuted, size = MachineSignal.Editor.meta, lineHeight = MachineSignal.Editor.meta * MachineSignal.Editor.lineHeight)
            Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f, fill = false)) { ProvideLabel(tip, slots.tip) }
                properties.chord?.let { chord -> ProvideLabel(aside) { Text(chord, maxLines = 1, softWrap = false) } }
            }
            properties.reason?.let { reason -> ProvideLabel(aside) { Text(reason) } }
        }
    }
}

val RaisedPopover: PanelAppearance = object : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        val signal = theme.machineSignal
        Column(
            Modifier
                .widthIn(max = MachineSignal.Editor.navigatorWidth * 2)
                .background(signal.colors.raised, MachineSignal.Shape.Panel)
                .border(1.dp, signal.colors.line, MachineSignal.Shape.Panel)
                .padding(MachineSignal.Space.s3),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
        ) {
            ProvideLabel(Label(signal.colors.text, signal.fonts.ui, signal.metrics.label), slots.content)
        }
    }
}

val PanelDialog: PanelAppearance = object : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        val signal = theme.machineSignal
        Column(
            Modifier
                .padding(MachineSignal.Space.s5)
                .widthIn(max = DialogWidth)
                .fillMaxWidth()
                .background(signal.colors.raised, MachineSignal.Shape.Panel)
                .border(1.dp, signal.colors.line, MachineSignal.Shape.Panel)
                .padding(MachineSignal.Space.s4),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3),
        ) {
            ProvideLabel(Label(signal.colors.text, signal.fonts.ui, MachineSignal.Type.body), slots.content)
        }
    }
}

val PanelSheet: PanelAppearance = object : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        val signal = theme.machineSignal
        Column(
            Modifier
                .fillMaxWidth()
                .background(signal.colors.raised, SheetShape)
                .border(1.dp, signal.colors.line, SheetShape)
                .navigationBarsPadding()
                .padding(start = MachineSignal.Space.s4, end = MachineSignal.Space.s4, top = MachineSignal.Space.s2, bottom = MachineSignal.Space.s4),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(32.dp, 3.dp).background(signal.colors.lineStrong, CircleShape))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
                ProvideLabel(Label(signal.colors.text, signal.fonts.ui, MachineSignal.Type.body), slots.content)
            }
        }
    }
}

fun statusToast(tone: (MachineSignalColors) -> Color): ToastAppearance = object : ToastAppearance {
    @Composable
    override fun Content(properties: ToastEntry, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ToastSlots) {
        val signal = theme.machineSignal
        val color = tone(signal.colors)
        val shape = RoundedCornerShape(MachineSignal.Radius.statusPill)
        Row(
            Modifier
                .focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, shape))
                .height(MachineSignal.Metrics.statusPillHeight)
                .background(color.copy(alpha = 0.08f), shape)
                .border(1.dp, color.copy(alpha = 0.32f), shape)
                .padding(horizontal = MachineSignal.Metrics.statusPillPaddingX),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Metrics.statusPillGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color)
            ProvideLabel(Label(signal.colors.textStrong, signal.fonts.mono, MachineSignal.Type.data, FontWeight.SemiBold), slots.message)
        }
    }
}

val StatusToast: ToastAppearance = statusToast { it.accent }

private val SheetShape = RoundedCornerShape(topStart = MachineSignal.Radius.panel, topEnd = MachineSignal.Radius.panel)
private val DialogWidth = 440.dp
