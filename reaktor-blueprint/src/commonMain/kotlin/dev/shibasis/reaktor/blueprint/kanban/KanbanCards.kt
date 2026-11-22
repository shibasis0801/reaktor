package dev.shibasis.reaktor.blueprint.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
                        onSelect: () -> Unit, onAgentOpen: (BlueprintAgent) -> Unit, modifier: Modifier = Modifier, tag: String = "kanban-card-${node.id}") {
    val colors = MachineSignal.colors
    val shape = RoundedCornerShape(MachineSignal.Radius.node)
    val status = look.outline
    Column(modifier.fillMaxWidth().clip(shape).background(status?.let { lerp(colors.card, it, StatusFill) } ?: colors.surface)
        .border(if (selected) MachineSignal.Stroke.bar else MachineSignal.Stroke.hairline,
            status ?: if (selected) colors.controlAccent else colors.line, shape)
        .drawWithContent {
            drawContent()
            if (selected && status != null) {
                val inset = MachineSignal.Space.s1.toPx()
                drawRoundRect(colors.textStrong, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
                    CornerRadius((MachineSignal.Radius.node.toPx() - inset).coerceAtLeast(0f)), style = Stroke(MachineSignal.Stroke.bar.toPx()))
            }
        }
        .clickable(onClick = onSelect).semantics { this.selected = selected }.testTag(tag)
        .padding(MachineSignal.Space.s3), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
        look.eyebrow?.takeIf(String::isNotBlank)?.let {
            Text(it, Modifier.fillMaxWidth().testTag("kanban-eyebrow-${node.id}"), role = Type.Meta, ink = if (status != null) Ink.Strong else Ink.Muted)
        }
        Text(look.title, Modifier.fillMaxWidth().testTag("kanban-title-${node.id}"), role = Type.Body, lines = Int.MAX_VALUE)
        if (look.subtitle.isNotBlank()) Text(look.subtitle, Modifier.fillMaxWidth(), role = Type.Meta, ink = if (status != null) Ink.Strong else Ink.Muted, lines = Int.MAX_VALUE)
        look.marks.forEachIndexed { index, mark ->
            Row(Modifier.clip(shape).background(mark.color.copy(alpha = .18f))
                .padding(horizontal = MachineSignal.Space.s2, vertical = MachineSignal.Space.s1),
                horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(MachineSignal.Space.s1).clip(CircleShape).background(mark.color))
                Text(mark.text, Modifier.testTag("kanban-mark-${node.id}-$index"), role = Type.Meta, ink = Ink.Strong, lines = Int.MAX_VALUE)
            }
        }
        if (closingReason != null) Text(closingReason, Modifier.fillMaxWidth().testTag("kanban-reason-${node.id}"), role = Type.Meta, ink = if (status != null) Ink.Strong else Ink.Muted, lines = Int.MAX_VALUE)
        look.agent?.let { agent -> BlueprintAgentBadge(agent, { onAgentOpen(agent) }, Modifier.fillMaxWidth(), tag = "kanban-agent-${node.id}", onParticipantOpen = onAgentOpen) }
    }
}

private const val StatusFill = .24f
