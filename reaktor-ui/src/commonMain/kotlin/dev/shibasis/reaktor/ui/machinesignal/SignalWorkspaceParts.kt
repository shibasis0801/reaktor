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
import androidx.compose.ui.unit.dp

/** Deliberate empty or unavailable workspace, with one clear explanation and room for a real action. */
@Composable
fun SignalWorkspaceEmptyState(
    title: String, description: String, modifier: Modifier = Modifier,
    symbol: String = "◇", detail: String = "", actions: (@Composable RowScope.() -> Unit)? = null,
) = BoxWithConstraints(modifier.fillMaxSize().background(MachineSignal.Editor.Canvas), contentAlignment = Alignment.Center) {
    val compact = maxHeight < MachineSignal.Editor.toolbarHeight * 7
    Column(Modifier.widthIn(max = MachineSignal.Editor.inspectorWidth + MachineSignal.Editor.navigatorWidth / 2)
        .padding(if (compact) MachineSignal.Space.s3 else MachineSignal.Space.s6), verticalArrangement = Arrangement.spacedBy(if (compact) MachineSignal.Space.s2 else MachineSignal.Space.s4)) {
        if (!compact) Box(Modifier.size(MachineSignal.Editor.toolHitSize + MachineSignal.Space.s3)
            .background(MachineSignal.Editor.Surface, MachineSignal.Shape.Panel)
            .border(1.dp, MachineSignal.Editor.Line, MachineSignal.Shape.Panel), contentAlignment = Alignment.Center) {
            Text(symbol, role = Type.Display, ink = Ink.Accent)
        }
        Text(title, role = Type.Title.strong, ink = Ink.Text, lines = 3)
        Text(description, role = Type.Body, ink = Ink.Muted, lines = Int.MAX_VALUE)
        if (!compact && detail.isNotBlank()) Text(detail, role = Type.Label, ink = Ink.Unknown, lines = Int.MAX_VALUE)
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2), content = actions)
    }
}
