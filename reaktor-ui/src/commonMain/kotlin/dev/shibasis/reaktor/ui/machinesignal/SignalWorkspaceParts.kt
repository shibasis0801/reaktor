package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.ViewState
import dev.shibasis.reaktor.surface.compose.StateView
import dev.shibasis.reaktor.ui.machinesignal.surface.SignalStateView

@Composable
fun SignalWorkspaceEmptyState(
    title: String, description: String, modifier: Modifier = Modifier,
    symbol: String = "◇", detail: String = "", actions: (@Composable RowScope.() -> Unit)? = null,
    state: ViewState = ViewState.Empty,
) = BoxWithConstraints(modifier.fillMaxSize().background(MachineSignal.colors.canvas), contentAlignment = Alignment.Center) {
    val compact = maxHeight < MachineSignal.Editor.toolbarHeight * 7
    StateView(state, if (state == ViewState.Stale) Modifier.fillMaxWidth() else Modifier.fillMaxSize(), appearance = SignalStateView,
        action = actions?.let { { Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), content = it) } }) {
        Column(Modifier.widthIn(max = MachineSignal.Editor.inspectorWidth + MachineSignal.Editor.navigatorWidth / 2),
            verticalArrangement = Arrangement.spacedBy(if (compact) MachineSignal.Space.s2 else MachineSignal.Space.s4)) {
            if (!compact) Text(symbol, role = Type.Display, ink = Ink.Accent)
            Text(title, role = Type.Title.strong, ink = Ink.Text, lines = 3)
            Text(description, role = Type.Body, ink = Ink.Muted, lines = Int.MAX_VALUE)
            if (!compact && detail.isNotBlank()) Text(detail, role = Type.Label, ink = Ink.Unknown, lines = Int.MAX_VALUE)
        }
    }
}
