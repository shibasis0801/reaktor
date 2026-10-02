package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.blueprint.canvas.CanvasCard
import dev.shibasis.reaktor.blueprint.canvas.CanvasLink
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvas
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.LinkShape
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle

data class SurfaceGraphNode(val id: String, val title: String, val subtitle: String, val scope: String = "", val color: Color? = null)

data class SurfaceGraphLink(val id: String, val from: String, val to: String, val label: String = "", val proposed: Boolean = false)

data class SurfaceGraphAppearance(
    val canvas: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val selected: Color,
    val width: Double = 220.0,
    val height: Double = 88.0,
)

fun surfaceGraphPositions(nodes: List<SurfaceGraphNode>, width: Double = 220.0, height: Double = 88.0): Map<String, Pair<Double, Double>> {
    val out = linkedMapOf<String, Pair<Double, Double>>()
    var rowOffset = 0
    for ((_, group) in nodes.groupBy { it.scope }.entries.sortedBy { it.key }) {
        group.sortedBy { it.id }.forEachIndexed { index, node -> out[node.id] = (index % 3) * (width + 64) to (rowOffset + index / 3) * (height + 68) }
        rowOffset += (group.size + 2) / 3 + 1
    }
    return out
}

@Composable
fun SurfaceGraphCanvas(
    nodes: List<SurfaceGraphNode>,
    links: List<SurfaceGraphLink>,
    selectedId: String?,
    appearance: SurfaceGraphAppearance,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    sceneKey: String = "",
) {
    val canvas = remember(sceneKey) { GraphCanvasState() }
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val cards = remember(nodes, appearance.width, appearance.height) {
        surfaceGraphPositions(nodes, appearance.width, appearance.height).map { (id, position) ->
            CanvasCard(id, position.first, position.second, appearance.width, appearance.height)
        }
    }
    val proposed = remember(links) { links.filter { it.proposed }.map { it.id }.toSet() }
    val canvasLinks = remember(links, byId) {
        links.filter { it.from in byId && it.to in byId }.distinctBy { it.id }.map { CanvasLink(it.id, it.from, it.to, label = it.label.ifBlank { null }) }
    }
    val shape = RoundedCornerShape(12.dp)
    GraphCanvas(
        frames = emptyList(),
        cards = cards,
        links = canvasLinks,
        state = canvas,
        background = appearance.canvas,
        fitKey = sceneKey to nodes.size,
        shape = LinkShape.Curved,
        linkStyle = { link -> LinkStyle(appearance.selected, width = 1.4f, alpha = .65f, dash = if (link.id in proposed) 6f to 4f else null) },
        frameContent = {},
        cardContent = { card ->
            val node = byId.getValue(card.id)
            val selected = node.id == selectedId
            Column(
                Modifier.fillMaxSize().clip(shape).background(appearance.card)
                    .border(if (selected) 2.dp else 1.dp, if (selected) appearance.selected else appearance.line, shape).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(node.title, color = appearance.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(node.subtitle, color = appearance.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        onCardClick = onSelect,
        modifier = modifier,
    )
}
