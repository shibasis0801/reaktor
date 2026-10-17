package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Text

@Composable
fun SignalPropertyRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.treeRowHeight)
        .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s1),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
        Text(label, Modifier.weight(.35f), role = Type.Body, ink = Ink.Muted, lines = Int.MAX_VALUE)
        SelectionContainer(Modifier.weight(.65f)) {
            Text(value, role = Type.Body, ink = Ink.Text, lines = Int.MAX_VALUE)
        }
    }
}
