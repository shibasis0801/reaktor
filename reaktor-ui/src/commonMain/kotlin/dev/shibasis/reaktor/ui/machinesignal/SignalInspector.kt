package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SignalPropertyRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.treeRowHeight)
        .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s1),
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
        SignalText(label, Modifier.weight(.35f), color = MachineSignal.Editor.Muted, maxLines = Int.MAX_VALUE)
        SelectionContainer(Modifier.weight(.65f)) {
            SignalText(value, color = MachineSignal.Editor.Text, maxLines = Int.MAX_VALUE)
        }
    }
}
