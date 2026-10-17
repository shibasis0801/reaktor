package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun SignalScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(state).padding(end = MachineSignal.Space.s2), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = LocalScrollbarStyle.current.copy(
                unhoverColor = MachineSignal.Editor.Line, hoverColor = MachineSignal.Editor.Muted,
                thickness = MachineSignal.Space.s2,
            ))
    }
}
