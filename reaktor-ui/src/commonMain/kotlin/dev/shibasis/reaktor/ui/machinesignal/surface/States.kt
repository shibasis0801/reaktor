package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ViewState
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.IslandAppearance
import dev.shibasis.reaktor.surface.compose.IslandSlots
import dev.shibasis.reaktor.surface.compose.IslandState
import dev.shibasis.reaktor.surface.compose.StateViewAppearance
import dev.shibasis.reaktor.surface.compose.StateViewSlots
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.StatusDot
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val SignalIsland: IslandAppearance = object : IslandAppearance {
    @Composable
    override fun Content(properties: Unit, state: IslandState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: IslandSlots) {
        val signal = theme.machineSignal
        Box(
            Modifier.focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape)),
            propagateMinConstraints = true,
        ) { slots.content() }
    }
}

val SignalStateView: StateViewAppearance = object : StateViewAppearance {
    @Composable
    override fun Content(properties: ViewState, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: StateViewSlots) {
        if (properties == ViewState.Stale) Strip(properties, theme.machineSignal, slots) else Centred(theme.machineSignal, slots)
    }
}

val StripStateView: StateViewAppearance = object : StateViewAppearance {
    @Composable
    override fun Content(properties: ViewState, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: StateViewSlots) =
        Strip(properties, theme.machineSignal, slots)
}

@Composable
private fun Centred(signal: MachineSignalSnapshot, slots: StateViewSlots) = Column(
    Modifier.fillMaxSize().padding(MachineSignal.Space.s5),
    verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    ProvideLabel(Label(signal.colors.text, signal.fonts.ui, MachineSignal.Editor.label)) { slots.reason() }
    slots.action?.invoke()
}

@Composable
private fun Strip(state: ViewState, signal: MachineSignalSnapshot, slots: StateViewSlots) = Row(
    Modifier.fillMaxWidth().background(signal.colors.surface).padding(MachineSignal.Space.s3),
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
    verticalAlignment = Alignment.CenterVertically,
) {
    val color = signal.toneOf(state)
    StatusDot(color)
    Box(Modifier.weight(1f)) { ProvideLabel(Label(color, signal.fonts.ui, MachineSignal.Type.caption)) { slots.reason() } }
    slots.action?.invoke()
}

private fun MachineSignalSnapshot.toneOf(state: ViewState): Color = when (state) {
    ViewState.Failed -> colors.error
    ViewState.Stale -> colors.warn
    ViewState.Loading -> colors.accent
    ViewState.Empty, ViewState.Unavailable, ViewState.Gone -> colors.textFaint
}
