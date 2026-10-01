package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.compose.ReactFlow
import dev.shibasis.composeflow.compose.primitives.EdgeRenderStyle
import dev.shibasis.composeflow.compose.primitives.HandleRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.FitViewOptions
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.XYPosition

data class SurfaceGraphNode(val id: String, val title: String, val subtitle: String, val scope: String = "", val color: Color? = null)
data class SurfaceGraphLink(val id: String, val from: String, val to: String, val label: String = "", val proposed: Boolean = false)
data class SurfaceGraphAppearance(val canvas: Color, val card: Color, val text: Color, val muted: Color, val line: Color, val selected: Color, val width: Double = 220.0, val height: Double = 88.0)

/** Stable scope-local layout; camera motion never rebuilds the scene. */
fun surfaceGraphPositions(nodes: List<SurfaceGraphNode>, width: Double = 220.0, height: Double = 88.0): Map<String, XYPosition> {
    val out = linkedMapOf<String, XYPosition>()
    var rowOffset = 0
    for ((_, group) in nodes.groupBy { it.scope }.entries.sortedBy { it.key }) {
        group.sortedBy { it.id }.forEachIndexed { index, node -> out[node.id] = XYPosition((index % 3) * (width + 64), (rowOffset + index / 3) * (height + 68)) }
        rowOffset += (group.size + 2) / 3 + 1
    }
    return out
}

@Composable
fun SurfaceGraphCanvas(nodes: List<SurfaceGraphNode>, links: List<SurfaceGraphLink>, selectedId: String?, appearance: SurfaceGraphAppearance,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier, sceneKey: String = "") {
    val positions = remember(nodes, appearance.width, appearance.height) { surfaceGraphPositions(nodes, appearance.width, appearance.height) }
    val scene = remember(nodes, positions, selectedId, appearance) { nodes.map { node -> Node(id = node.id, position = positions.getValue(node.id), data = node, type = "surface", width = appearance.width, height = appearance.height, selected = node.id == selectedId, draggable = false, connectable = false, deletable = false, showDefaultHandles = false) } }
    val edges = remember(links, nodes) { val ids = nodes.map { it.id }.toSet(); links.filter { it.from in ids && it.to in ids }.distinctBy { it.id }.map { edge -> Edge(id = edge.id, source = edge.from, target = edge.to, label = edge.label, data = edge, markerEnd = EdgeMarker()) } }
    key(sceneKey) {
        ReactFlow(nodes = scene, edges = edges, modifier = modifier, canvasBackground = appearance.canvas,
            onNodeClick = { onSelect(it.id) }, fitViewOptions = FitViewOptions(maxZoom = 1.15, minZoom = 0.55), showMiniMap = false,
            minZoom = 0.08, maxZoom = 2.0, defaultNodeWidth = appearance.width.dp, defaultNodeHeight = appearance.height.dp,
            nodeRenderStyle = { node -> NodeRenderStyle(backgroundColor = appearance.card, borderColor = if (node.selected) appearance.selected else appearance.line, cornerRadius = 12.dp, borderWidth = if (node.selected) 2.dp else 1.dp) },
            edgeRenderStyle = { edge -> EdgeRenderStyle(color = appearance.selected.copy(alpha = .65f), width = 1.4f, dashOn = if ((edge.data as? SurfaceGraphLink)?.proposed == true) 6f else null, dashOff = 4f) },
            handleRenderStyle = { _, _ -> HandleRenderStyle(alpha = 0f) },
            nodeTypes = mapOf("surface" to { props -> val node = props.data as SurfaceGraphNode
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(node.title, color = appearance.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(node.subtitle, color = appearance.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }),
        )
    }
}
