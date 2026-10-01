package dev.shibasis.reaktor.flow.graph.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.ReactFlow
import dev.shibasis.composeflow.compose.primitives.EdgePathStyle
import dev.shibasis.composeflow.compose.primitives.EdgeRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.MarkerType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import kotlin.time.TimeSource

data class CanvasFrame(val id: String, val x: Double, val y: Double, val width: Double, val height: Double, val depth: Int = 0)

data class CanvasCard(val id: String, val x: Double, val y: Double, val width: Double, val height: Double)

enum class LinkArrow { End, Start, None }

data class CanvasLink(
    val id: String,
    val from: String,
    val to: String,
    val points: List<Pair<Double, Double>> = emptyList(),
    val label: String? = null,
    val arrow: LinkArrow = LinkArrow.End,
)

data class LinkStyle(
    val color: Color,
    val width: Float = 1.3f,
    val alpha: Float = 0.5f,
    val glow: Color? = null,
    val glowWidth: Float = 6f,
    val dash: Pair<Float, Float>? = null,
    val flowing: Boolean = false,
)

data class CardStyle(val alpha: Float = 1f, val glow: Color? = null)

enum class LinkShape { Routed, Curved }

data class CanvasGrid(val minor: Color, val major: Color, val step: Double = 16.0, val every: Int = 8)

data class CanvasPulse(val link: String, val born: TimeSource.Monotonic.ValueTimeMark, val color: Color, val travelMillis: Long = 900)

@Stable
class GraphCanvasState {
    internal val flow = ReactFlowState()
    internal var density = 1.0
    internal var fitted: Any? = null
    internal var inset = 0.0
    internal var start = 0.0

    val zoom: Double get() = flow.viewport.zoom

    fun fit(x: Double, y: Double, width: Double, height: Double, padding: Double = 0.03, maxZoom: Double = 1.0) =
        frame(x * density, y * density, width * density, height * density, padding, maxZoom)

    fun centre(x: Double, y: Double, width: Double, height: Double, readable: Double = 0.45, fallback: Double = 0.9) =
        place((x + width / 2) * density, (y + height / 2) * density, flow.viewport.zoom.takeIf { it >= readable } ?: fallback)

    fun zoomTo(zoom: Double) {
        flow.zoomTo(zoom, flow.canvasSize.width / 2.0, flow.canvasSize.height / 2.0, minZoom = 0.05, maxZoom = 4.0)
    }

    internal fun frame(x: Double, y: Double, width: Double, height: Double, padding: Double, maxZoom: Double) {
        val room = flow.canvasSize.height - inset
        val span = flow.canvasSize.width - start
        if (span <= 0 || room <= 0) return
        val zoom = minOf(
            span * (1 - 2 * padding) / width.coerceAtLeast(1.0),
            room * (1 - 2 * padding) / height.coerceAtLeast(1.0),
        ).coerceIn(0.05, maxZoom)
        place(x + width / 2, y + height / 2, zoom)
    }

    private fun place(centreX: Double, centreY: Double, zoom: Double) {
        val room = flow.canvasSize.height - inset
        val span = flow.canvasSize.width - start
        flow.setViewport(Viewport(x = start + span / 2.0 - centreX * zoom, y = inset + room / 2.0 - centreY * zoom, zoom = zoom))
    }
}

private class FrameData(val frame: CanvasFrame)
private class CardData(val card: CanvasCard)
private class LinkData(val link: CanvasLink)

@Composable
fun GraphCanvas(
    frames: List<CanvasFrame>,
    cards: List<CanvasCard>,
    links: List<CanvasLink>,
    state: GraphCanvasState,
    background: Color,
    linkStyle: (CanvasLink) -> LinkStyle,
    frameContent: @Composable (CanvasFrame) -> Unit,
    cardContent: @Composable (CanvasCard) -> Unit,
    modifier: Modifier = Modifier,
    fitKey: Any? = null,
    topInset: Dp = 0.dp,
    startInset: Dp = 0.dp,
    shape: LinkShape = LinkShape.Routed,
    cardStyle: (CanvasCard) -> CardStyle = { CardStyle() },
    onCardClick: (String) -> Unit = {},
    onLinkClick: (String) -> Unit = {},
    onPaneClick: () -> Unit = {},
    grid: CanvasGrid? = null,
    pulses: List<CanvasPulse> = emptyList(),
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current.density.toDouble()
    state.density = density
    val nodes = remember(frames, cards, density) {
        frames.sortedBy { it.depth }.map { frame ->
            Node(
                id = frame.id, position = XYPosition(frame.x * density, frame.y * density), data = FrameData(frame), type = "frame",
                width = frame.width * density, height = frame.height * density, draggable = false, selectable = false,
                connectable = false, deletable = false, zIndex = -10 + frame.depth, showDefaultHandles = false,
            )
        } + cards.map { card ->
            Node(
                id = card.id, position = XYPosition(card.x * density, card.y * density), data = CardData(card), type = "card",
                width = card.width * density, height = card.height * density, draggable = false, connectable = false, deletable = false,
                showDefaultHandles = false,
            )
        }
    }
    val edges = remember(links, density) {
        links.map { link ->
            Edge(
                id = link.id, source = link.from, target = link.to, data = LinkData(link), label = link.label,
                markerEnd = if (link.arrow == LinkArrow.End) EdgeMarker(MarkerType.ArrowClosed, width = 10.0, height = 10.0) else null,
                markerStart = if (link.arrow == LinkArrow.Start) EdgeMarker(MarkerType.ArrowClosed, width = 10.0, height = 10.0) else null,
                points = link.points.map { (x, y) -> XYPosition(x * density, y * density) },
                selectable = true, deletable = false,
            )
        }
    }
    state.inset = topInset.value * density
    state.start = startInset.value * density
    LaunchedEffect(fitKey, state.flow.canvasSize, nodes) {
        if (state.fitted != fitKey && state.flow.canvasSize.width > 0 && nodes.isNotEmpty()) {
            val left = nodes.minOf { it.position.x }
            val top = nodes.minOf { it.position.y }
            val right = nodes.maxOf { it.position.x + (it.width ?: 0.0) }
            val bottom = nodes.maxOf { it.position.y + (it.height ?: 0.0) }
            state.frame(left, top, right - left, bottom - top, 0.03, 1.0)
            state.fitted = fitKey
        }
    }
    val scale = density.toFloat()
    val paths = remember(links, cards, frames, density) { linkPaths(links, cards, frames, density) }
    Box(modifier) {
        if (grid != null) GridLayer(grid, background, state, Modifier.matchParentSize())
        ReactFlow(
            nodes = nodes,
            edges = edges,
            modifier = Modifier.fillMaxSize(),
            state = state.flow,
            nodeTypes = mapOf(
                "frame" to { props -> (props.data as? FrameData)?.let { frameContent(it.frame) } },
                "card" to { props -> (props.data as? CardData)?.let { cardContent(it.card) } },
            ),
            onNodeClick = { node -> if (node.type == "card") onCardClick(node.id) },
            onEdgeClick = { edge -> onLinkClick(edge.id) },
            onPaneClick = onPaneClick,
            fitView = false,
            showControls = false,
            showMiniMap = false,
            minZoom = 0.05,
            maxZoom = 2.5,
            defaultNodeWidth = 240.dp,
            defaultNodeHeight = 48.dp,
            nodeRenderStyle = { node ->
                if (node.type == "frame") NodeRenderStyle(backgroundColor = Color.Transparent, borderColor = Color.Transparent, cornerRadius = 12.dp, borderWidth = 0.dp)
                else (node.data as? CardData)?.card?.let(cardStyle).let { style ->
                    NodeRenderStyle(alpha = style?.alpha ?: 1f, backgroundColor = Color.Transparent, borderColor = Color.Transparent,
                        cornerRadius = 8.dp, borderWidth = 0.dp, glowColor = style?.glow)
                }
            },
            edgeRenderStyle = { edge ->
                val style = (edge.data as? LinkData)?.link?.let(linkStyle) ?: LinkStyle(Color.Gray)
                EdgeRenderStyle(
                    alpha = style.alpha, color = style.color, width = style.width * scale,
                    glowColor = style.glow, glowWidth = style.glow?.let { style.glowWidth * scale },
                    flowAnimated = style.flowing,
                    dashOn = style.dash?.first?.times(scale), dashOff = style.dash?.second?.times(scale),
                )
            },
            edgePathStyle = if (shape == LinkShape.Routed) EdgePathStyle.Orthogonal else EdgePathStyle.SmoothStep,
            overlay = { overlay() },
            viewportOverlay = { if (pulses.isNotEmpty()) PulseLayer(pulses, paths, scale) },
            showBackground = grid == null,
            canvasBackground = if (grid == null) background else Color.Transparent,
        )
    }
}

@Composable
private fun GridLayer(grid: CanvasGrid, background: Color, state: GraphCanvasState, modifier: Modifier) {
    Canvas(modifier.background(background)) {
        val viewport = state.flow.viewport
        val minor = (grid.step * state.density * viewport.zoom).toFloat()
        if (minor <= 0f) return@Canvas
        val major = minor * grid.every
        fun lines(step: Float, color: Color, width: Float) {
            var x = ((viewport.x % step) + step).toFloat() % step
            while (x < size.width) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), width)
                x += step
            }
            var y = ((viewport.y % step) + step).toFloat() % step
            while (y < size.height) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), width)
                y += step
            }
        }
        if (minor >= 7f) lines(minor, grid.minor, 1f)
        if (major >= 7f) lines(major, grid.major, 1.4f)
    }
}

@Composable
private fun PulseLayer(pulses: List<CanvasPulse>, paths: Map<String, List<Offset>>, scale: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pulses) {
        while (pulses.any { it.born.elapsedNow().inWholeMilliseconds < it.travelMillis }) withFrameNanos { tick = it }
    }
    Canvas(Modifier.fillMaxSize()) {
        tick
        pulses.forEach { pulse ->
            val progress = pulse.born.elapsedNow().inWholeMilliseconds.toFloat() / pulse.travelMillis
            if (progress < 0f || progress > 1f) return@forEach
            val path = paths[pulse.link] ?: return@forEach
            val point = along(path, progress) ?: return@forEach
            val fade = if (progress > 0.85f) (1f - progress) / 0.15f else 1f
            drawCircle(pulse.color.copy(alpha = 0.25f * fade), radius = 9f * scale, center = point)
            drawCircle(pulse.color.copy(alpha = 0.9f * fade), radius = 4f * scale, center = point)
            drawCircle(Color.White.copy(alpha = 0.85f * fade), radius = 1.6f * scale, center = point)
        }
    }
}

private fun along(path: List<Offset>, fraction: Float): Offset? {
    if (path.size < 2) return path.firstOrNull()
    val lengths = path.zipWithNext { a, b -> (b - a).getDistance() }
    var remaining = lengths.sum() * fraction
    lengths.forEachIndexed { index, length ->
        if (remaining <= length) {
            val t = if (length == 0f) 0f else remaining / length
            return path[index] + (path[index + 1] - path[index]) * t
        }
        remaining -= length
    }
    return path.last()
}

private fun linkPaths(links: List<CanvasLink>, cards: List<CanvasCard>, frames: List<CanvasFrame>, density: Double): Map<String, List<Offset>> {
    val boxes = cards.associate { it.id to doubleArrayOf(it.x, it.y, it.width, it.height) } + frames.associate { it.id to doubleArrayOf(it.x, it.y, it.width, it.height) }
    fun point(x: Double, y: Double) = Offset((x * density).toFloat(), (y * density).toFloat())
    return links.associate { link ->
        link.id to if (link.points.size >= 2) link.points.map { (x, y) -> point(x, y) } else {
            val from = boxes[link.from]
            val to = boxes[link.to]
            if (from == null || to == null) emptyList()
            else listOf(point(from[0] + from[2], from[1] + from[3] / 2), point(to[0], to[1] + to[3] / 2))
        }
    }
}
