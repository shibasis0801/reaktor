package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.*
import dev.shibasis.reaktor.surface.ThemeSnapshot

@Composable
fun SignalPropertyRow(label: String, value: String, modifier: Modifier = Modifier) {
    Property(label = { Text(label, role = Type.Body, ink = Ink.Muted, lines = Int.MAX_VALUE) }, modifier = modifier, appearance = WorkspaceProperty) {
        SelectionContainer { Text(value, role = Type.Body, ink = Ink.Text, lines = Int.MAX_VALUE) }
    }
}

private val WorkspaceProperty: PropertyAppearance = object : PropertyAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PropertySlots) {
        Row(Modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.treeRowHeight)
            .padding(horizontal = MachineSignal.Space.s3, vertical = MachineSignal.Space.s1),
            horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
            Box(Modifier.weight(.35f)) { slots.label() }
            Row(Modifier.weight(.65f)) { slots.value(this) }
        }
    }
}
