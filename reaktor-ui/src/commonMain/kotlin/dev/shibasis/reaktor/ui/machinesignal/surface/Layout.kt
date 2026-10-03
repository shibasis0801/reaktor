package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.compose.ComposeFeedback
import dev.shibasis.reaktor.surface.compose.HandleState
import dev.shibasis.reaktor.surface.compose.SplitAxis
import dev.shibasis.reaktor.surface.compose.SplitterAppearance
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.machineSignal

val SignalSplitter: SplitterAppearance = object : SplitterAppearance {
    @Composable
    override fun Content(properties: SplitAxis, state: HandleState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val signal = theme.machineSignal
        Box(
            Modifier.fillMaxSize().focusRing(state.focusVisible, feedback, FocusRing(signal.colors.accent, signal.metrics.focusRing, RectangleShape)),
            contentAlignment = Alignment.Center,
        ) {
            val hairline = MachineSignal.Space.s1 / 4
            val line = if (properties == SplitAxis.Horizontal) Modifier.width(hairline).fillMaxHeight() else Modifier.height(hairline).fillMaxWidth()
            Box(line.background(signal.colors.line))
        }
    }
}
