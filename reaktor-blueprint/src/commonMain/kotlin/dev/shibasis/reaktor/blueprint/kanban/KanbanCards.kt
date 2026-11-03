package dev.shibasis.reaktor.blueprint.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.shibasis.reaktor.blueprint.BlueprintAgent
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.canvas.BlueprintAgentBadge
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.colors

@Composable
internal fun KanbanCard(node: NodeShape, look: BlueprintCardLook, selected: Boolean, closingReason: String?,
                        onSelect: () -> Unit, onAgentOpen: (BlueprintAgent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MachineSignal.colors
    val shape = RoundedCornerShape(MachineSignal.Radius.node)
    Column(modifier.fillMaxWidth().clip(shape).background(colors.surface)
        .border(MachineSignal.Stroke.hairline, if (selected) colors.controlAccent else colors.line, shape)
        .clickable(onClick = onSelect).semantics { this.selected = selected }.testTag("kanban-card-${node.id}")
        .padding(MachineSignal.Space.s3), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
        Text(look.title, Modifier.fillMaxWidth().testTag("kanban-title-${node.id}"), role = Type.Body, lines = Int.MAX_VALUE)
        if (look.subtitle.isNotBlank()) Text(look.subtitle, Modifier.fillMaxWidth(), role = Type.Meta, ink = Ink.Muted, lines = Int.MAX_VALUE)
        if (look.marks.isNotEmpty()) Text(look.marks.joinToString(" · ") { it.text }, Modifier.fillMaxWidth(), role = Type.Meta, lines = Int.MAX_VALUE)
        if (closingReason != null) Text(closingReason, Modifier.fillMaxWidth().testTag("kanban-reason-${node.id}"), role = Type.Meta, ink = Ink.Muted, lines = Int.MAX_VALUE)
        look.agent?.let { agent -> BlueprintAgentBadge(agent, { onAgentOpen(agent) }, Modifier.fillMaxWidth(), tag = "kanban-agent-${node.id}") }
    }
}
