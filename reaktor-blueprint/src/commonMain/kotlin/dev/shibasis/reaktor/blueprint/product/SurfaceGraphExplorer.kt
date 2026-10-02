package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.compose.interaction.FlowViewportGestureConfig
import dev.shibasis.composeflow.compose.interaction.flowPointerViewportGestures
import dev.shibasis.composeflow.compose.interaction.flowWheelAndTrackpadViewportGestures
import dev.shibasis.composeflow.compose.interaction.rememberFlowViewportInteractionState
import dev.shibasis.composeflow.model.FitViewOptions
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import kotlin.math.ceil
import kotlin.math.sqrt

fun expandedSurfaceGraphPositions(nodes: List<SurfaceGraphNode>, width: Double = 220.0, height: Double = 88.0): Map<String, XYPosition> {
    val groups = nodes.groupBy { it.scope }.entries.sortedBy { it.key }
    data class Group(val nodes: List<SurfaceGraphNode>, val columns: Int, val width: Double, val height: Double)
    val boxes = groups.map { (_, group) ->
        val columns = ceil(sqrt(group.size.toDouble())).toInt().coerceAtLeast(1)
        Group(group.sortedBy { it.id }, columns, columns * (width + 24) + 40, ceil(group.size.toDouble() / columns) * (height + 24) + 64)
    }
    val target = sqrt(boxes.sumOf { it.width * it.height }) * 1.3
    val out = linkedMapOf<String, XYPosition>()
    var x = 0.0; var y = 0.0; var rowHeight = 0.0
    boxes.forEach { box ->
        if (x > 0 && x + box.width > target) { x = 0.0; y += rowHeight + 80; rowHeight = 0.0 }
        box.nodes.forEachIndexed { index, node -> out[node.id] = XYPosition(x + 20 + (index % box.columns) * (width + 24), y + 44 + (index / box.columns) * (height + 24)) }
        x += box.width + 80; rowHeight = maxOf(rowHeight, box.height)
    }
    return out
}

@Stable
class SurfaceGraphExplorerState {
    internal val camera = ReactFlowState()
    internal var nodes: List<Node> = emptyList()
    private val history = mutableListOf<Viewport>()
    private var cursor = -1
    fun fit(ids: Set<String> = emptySet()) {
        val targets = if (ids.isEmpty()) nodes else nodes.filter { it.id in ids }
        if (targets.isNotEmpty()) { camera.fitView(targets, 220.0, 88.0, FitViewOptions(minZoom = .002, maxZoom = 1.4)); record() }
    }
    fun zoom(factor: Double) { camera.zoomBy(factor, camera.canvasSize.width / 2.0, camera.canvasSize.height / 2.0, .002, 3.0); record() }
    fun back() { record(); if (cursor > 0) camera.setViewport(history[--cursor]) }
    fun forward() { if (cursor < history.lastIndex) camera.setViewport(history[++cursor]) }
    internal fun record() {
        if (history.getOrNull(cursor) == camera.viewport) return
        while (history.lastIndex > cursor) history.removeAt(history.lastIndex)
        history += camera.viewport
        if (history.size > 80) history.removeAt(0)
        cursor = history.lastIndex
    }
}

@Composable
fun SurfaceGraphExplorer(nodes: List<SurfaceGraphNode>, links: List<SurfaceGraphLink>, selectedId: String?, appearance: SurfaceGraphAppearance,
    state: SurfaceGraphExplorerState, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val positions = remember(nodes) { expandedSurfaceGraphPositions(nodes, appearance.width, appearance.height) }
    val geometry = remember(nodes, positions) { nodes.map { node -> Node(id = node.id, position = positions.getValue(node.id), width = appearance.width, height = appearance.height) } }
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val edges = remember(links, positions) { links.filter { it.from in positions && it.to in positions } }
    val groups = remember(nodes, positions) { nodes.groupBy { it.scope }.map { (_, children) ->
        val minX = children.minOf { positions.getValue(it.id).x }; val minY = children.minOf { positions.getValue(it.id).y }
        val maxX = children.maxOf { positions.getValue(it.id).x + appearance.width }; val maxY = children.maxOf { positions.getValue(it.id).y + appearance.height }
        listOf(minX - 12, minY - 32, maxX - minX + 24, maxY - minY + 44) to children
    } }
    val interaction = rememberFlowViewportInteractionState()
    val config = remember { FlowViewportGestureConfig(minZoom = .002, maxZoom = 3.0) }
    val measurer = rememberTextMeasurer(cacheSize = 512)
    LaunchedEffect(geometry) { state.nodes = geometry; state.fit() }
    Canvas(modifier.onSizeChanged { size -> val first = state.camera.canvasSize == IntSize.Zero; state.camera.setCanvasSize(size); if (first) { state.nodes = geometry; state.fit() } }
        .flowWheelAndTrackpadViewportGestures(state.camera, interaction, config)
        .flowPointerViewportGestures(state.camera, interaction, config, onPaneTap = { point ->
            val world = state.camera.screenToFlowPosition(point.x.toDouble(), point.y.toDouble())
            val hit = nodes.asReversed().firstOrNull { val p = positions.getValue(it.id); world.x >= p.x && world.x <= p.x + appearance.width && world.y >= p.y && world.y <= p.y + appearance.height }
            hit?.let { onSelect(it.id) }; state.record(); hit != null
        })) {
        val camera = state.camera.viewport
        fun screen(x: Double, y: Double) = Offset((camera.x + x * camera.zoom).toFloat(), (camera.y + y * camera.zoom).toFloat())
        fun visible(x: Double, y: Double, w: Double, h: Double): Boolean { val point = screen(x, y); return point.x <= size.width && point.y <= size.height && point.x + w * camera.zoom >= 0 && point.y + h * camera.zoom >= 0 }
        drawRect(appearance.canvas)
        groups.forEach { (box, children) -> if (visible(box[0], box[1], box[2], box[3])) drawRect((children.first().color ?: appearance.selected).copy(alpha = .05f), screen(box[0], box[1]), Size((box[2] * camera.zoom).toFloat(), (box[3] * camera.zoom).toFloat()), style = Stroke(1f)) }
        edges.forEach { edge ->
            val from = positions.getValue(edge.from); val to = positions.getValue(edge.to)
            val active = edge.from == selectedId || edge.to == selectedId
            drawLine(appearance.selected.copy(alpha = if (active) .8f else if (selectedId != null) .025f else .12f), screen(from.x + appearance.width / 2, from.y + appearance.height / 2), screen(to.x + appearance.width / 2, to.y + appearance.height / 2), strokeWidth = if (active) 2f else .6f)
        }
        nodes.forEach { node -> val position = positions.getValue(node.id)
            if (!visible(position.x, position.y, appearance.width, appearance.height)) return@forEach
            val topLeft = screen(position.x, position.y); val nodeSize = Size((appearance.width * camera.zoom).toFloat(), (appearance.height * camera.zoom).toFloat())
            val detail = camera.zoom >= .45
            drawRect(if (detail) appearance.card else node.color ?: appearance.selected, topLeft, nodeSize)
            if (node.id == selectedId) drawRect(appearance.selected, topLeft, nodeSize, style = Stroke(3f))
            if (detail) {
                val title = measurer.measure(node.title, TextStyle(color = appearance.text, fontSize = (13 * camera.zoom).sp), maxLines = 2, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (nodeSize.width - 16).toInt().coerceAtLeast(1)))
                drawText(title, topLeft = topLeft + Offset(8f, 8f))
            }
        }
        groups.forEach { (box, children) -> if (box[2] * camera.zoom > 120 && visible(box[0], box[1], box[2], box[3])) {
            val name = byId[children.first().scope.substringAfterLast('/') ]?.title ?: children.first().subtitle
            val label = measurer.measure(name, TextStyle(color = appearance.text, fontSize = 11.sp), maxLines = 1, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (box[2] * camera.zoom).toInt().coerceAtLeast(1)))
            val position = screen(box[0], box[1]); drawRect(appearance.canvas, position, Size(label.size.width.toFloat(), label.size.height.toFloat())); drawText(label, topLeft = position)
        } }
    }
}
