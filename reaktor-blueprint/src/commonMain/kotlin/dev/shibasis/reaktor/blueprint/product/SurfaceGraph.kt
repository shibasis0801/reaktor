package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.compose.interaction.FlowViewportGestureConfig
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformGestureEffect
import dev.shibasis.composeflow.compose.interaction.LocalFlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.flowPointerViewportGestures
import dev.shibasis.composeflow.compose.interaction.flowViewportPointerTracking
import dev.shibasis.composeflow.compose.interaction.flowWheelAndTrackpadViewportGestures
import dev.shibasis.composeflow.compose.interaction.rememberFlowViewportInteractionState
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.graph.core.PortWiring
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** Product-owned labels and tones; the graph only arranges and paints them. */
data class SurfaceGraphBadge(val text: String, val background: Color, val foreground: Color, val rounded: Boolean = false)
data class SurfaceGraphRow(val id: String, val title: String, val key: String = id, val aliases: List<String> = emptyList(),
    val badges: List<SurfaceGraphBadge> = emptyList(), val marker: Color? = null, val background: Color? = null, val completed: Boolean = false,
    val input: Color? = null, val output: Color? = null)
data class SurfaceGraphNode(val id: String, val title: String, val subtitle: String, val scope: String = "", val color: Color? = null,
    val rows: List<SurfaceGraphRow> = emptyList(), val folded: Int = 0, val owner: String = id, val farTitle: String = title, val foldedLabel: String = "units", val symbol: String = "book", val lane: Int = 0,
    val badges: List<SurfaceGraphBadge> = emptyList(), val progress: Float = 0f, val activity: Color? = null, val counts: List<SurfaceGraphCount> = emptyList())
fun SurfaceGraphNode.pins() = rows.mapIndexed { i, row -> Pin(row.key, "", false, BlueprintEngine.HeaderHeight + i * BlueprintEngine.RowHeight + BlueprintEngine.RowHeight / 2, PortWiring.Connected, row.id, row.aliases) }
enum class SurfaceGraphMark { Done, Session, Note }
data class SurfaceGraphCount(val text: String, val mark: SurfaceGraphMark? = null)
data class SurfaceGraphGroup(val key: String, val title: String, val subtitle: String = "", val color: Color? = null, val parent: String? = null, val owner: String? = null, val lanes: Boolean = parent != null, val loose: Boolean = false, val muted: Boolean = false)

data class SurfaceGraphLink(val id: String, val from: String, val to: String, val label: String = "", val proposed: Boolean = false, val color: Color? = null)

data class SurfaceGraphAppearance(
    val canvas: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val selected: Color,
    val width: Double = 220.0,
    val height: Double = 88.0,
    val fontFamily: FontFamily? = null,
    val titleFontFamily: FontFamily? = null,
    val grid: Boolean = false,
    val textPlatformStyle: androidx.compose.ui.text.PlatformTextStyle? = null,
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

internal class SurfaceGraphSketch(val nodes: List<SurfaceGraphNode>, val box: FloatArray, val routes: FloatArray, val proposed: BooleanArray)

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
    val flow = canvas.flow
    val density = LocalDensity.current.density
    val base = LocalTextStyle.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val painter = remember(graphics, resolver, direction, appearance, base) { SurfaceGraphCanvasPainter(graphics, TextMeasurer(resolver, Density(1f), direction), appearance, base) }
    DisposableEffect(painter) { onDispose(painter::release) }
    LaunchedEffect(painter) { painter.run() }
    val sketch = remember(nodes, links, appearance.width, appearance.height) {
        val at = surfaceGraphPositions(nodes, appearance.width, appearance.height)
        val byId = nodes.associateBy { it.id }
        val shown = at.keys.map(byId::getValue)
        val routed = links.filter { it.from in at && it.to in at }.distinctBy { it.id }
        val box = FloatArray(shown.size * 4)
        shown.forEachIndexed { i, node -> val (x, y) = at.getValue(node.id); doubleArrayOf(x, y, x + appearance.width, y + appearance.height).forEachIndexed { k, v -> box[i * 4 + k] = v.toFloat() } }
        val routes = FloatArray(routed.size * 8)
        routed.forEachIndexed { i, link ->
            val (fx, fy) = at.getValue(link.from); val (tx, ty) = at.getValue(link.to)
            val sx = fx + appearance.width / 2; val sy = fy + appearance.height; val ex = tx + appearance.width / 2; val mid = (sy + ty) / 2
            doubleArrayOf(sx, sy, sx, mid, ex, mid, ex, ty).forEachIndexed { k, v -> routes[i * 8 + k] = v.toFloat() }
        }
        SurfaceGraphSketch(shown, box, routes, BooleanArray(routed.size) { routed[it].proposed })
    }
    SideEffect { canvas.density(density.toDouble()) }
    val fitKey = sceneKey to nodes.size
    LaunchedEffect(fitKey, flow.canvasSize, sketch) {
        if (sketch.nodes.isEmpty()) return@LaunchedEffect
        canvas.fitOnce(fitKey) {
            val box = sketch.box; val cards = sketch.nodes.indices
            val left = cards.minOf { box[it * 4] }; val top = cards.minOf { box[it * 4 + 1] }
            canvas.fit(left.toDouble(), top.toDouble(), (cards.maxOf { box[it * 4 + 2] } - left).toDouble(), (cards.maxOf { box[it * 4 + 3] } - top).toDouble())
        }
    }
    val interaction = rememberFlowViewportInteractionState()
    val bridge = LocalFlowViewportPlatformBridge.current
    val config = remember(density) { FlowViewportGestureConfig(minZoom = .05 * density, maxZoom = 2.5 * density) }
    FlowViewportPlatformGestureEffect(flow, interaction, config, bridge)
    val select by rememberUpdatedState(onSelect)
    val tap = remember(canvas, painter) { { point: Offset ->
        val at = flow.screenToFlowPosition(point.x.toDouble(), point.y.toDouble())
        painter.cardAt(at.x.toFloat(), at.y.toFloat())?.let { select(it); true } ?: false
    } }
    Canvas(modifier.clipToBounds()
        .onSizeChanged { flow.setCanvasSize(it) }
        .onGloballyPositioned { interaction.updateCanvasGeometry(it.positionInWindow(), it.boundsInWindow(), density) }
        .flowViewportPointerTracking(interaction)
        .flowWheelAndTrackpadViewportGestures(flow, interaction, config, bridge)
        .flowPointerViewportGestures(flow, interaction, config, onPaneTap = tap)) {
        painter.version.intValue
        painter.sync(sketch, selectedId)
        withTransform({ scale(density, density, Offset.Zero) }) {
            painter.draw(this, canvas.viewport(), Size(size.width / density, size.height / density), density)
        }
    }
}

internal class SurfaceGraphCanvasPainter(graphics: GraphicsContext, private val measurer: TextMeasurer, private val appearance: SurfaceGraphAppearance, base: TextStyle) : SurfaceGraphEngine(graphics, 1, scaled = true) {
    private val title = base.merge(color = appearance.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    private val subtitle = base.merge(color = appearance.muted, fontSize = 11.sp)
    private val tone = appearance.selected.toArgb()
    private val dots = ShaderBrush(ImageShader(ImageBitmap(26, 26).also { image ->
        CanvasDrawScope().draw(unit, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(26f, 26f)) { drawCircle(Color(0x1F8CA0C8), 1.2f, Offset(13.5f, 13.5f)) }
    }, TileMode.Repeated, TileMode.Repeated))
    private var sketch = SurfaceGraphSketch(emptyList(), FloatArray(0), FloatArray(0), BooleanArray(0))
    private var selected: String? = null
    private var shown = BooleanArray(0)

    override val warmups = 0
    override val warmable = false

    fun sync(sketch: SurfaceGraphSketch, selected: String?) {
        this.selected = selected
        if (sketch === this.sketch) return
        remap(IntArray(sketch.nodes.size) { -1 })
        this.sketch = sketch; cardBox = sketch.box; shown = BooleanArray(sketch.nodes.size); dirty.fill(true)
    }

    fun cardAt(x: Float, y: Float) = sketch.nodes.indices.lastOrNull { x >= cardBox[it * 4] && x <= cardBox[it * 4 + 2] && y >= cardBox[it * 4 + 1] && y <= cardBox[it * 4 + 3] }?.let { sketch.nodes[it].id }

    fun draw(scope: DrawScope, camera: Viewport, viewport: Size, density: Float) = with(scope) { frame(camera, viewport, density) }

    override fun DrawScope.paint(camera: Viewport, deadline: TimeSource.Monotonic.ValueTimeMark) {
        drawRect(appearance.canvas, size = viewport)
        val px = density; val dx = (camera.x * px).roundToInt().mod(26) - 39f; val dy = (camera.y * px).roundToInt().mod(26) - 39f
        withTransform({ scale(1 / px, 1 / px, Offset.Zero); translate(dx, dy) }) { drawRect(dots, size = Size(viewport.width * px + 52, viewport.height * px + 52)) }
        mesh(0, camera)
        val l = (-camera.x / camera.zoom).toFloat(); val t = (-camera.y / camera.zoom).toFloat()
        val r = ((viewport.width - camera.x) / camera.zoom).toFloat(); val b = ((viewport.height - camera.y) / camera.zoom).toFloat()
        world(camera) { for (i in sketch.nodes.indices) if (inside(cardBox, i, l, t, r, b)) layer(i, 0, deadline, urgent = true)?.let { drawLayer(it) } }
    }

    override fun tessellate(wired: Boolean) {
        if (!dirty[0]) return
        if (!wired) { pending = true; return }
        val z = zoom.toFloat(); val fringe = 1f / density; val routes = sketch.routes
        clear(true)
        for (i in sketch.proposed.indices) {
            val at = i * 8
            stroker.clear()
            if (routes[at] == routes[at + 4]) { stroker.point(routes[at] * z - wl, routes[at + 1] * z - wt); stroker.point(routes[at + 6] * z - wl, routes[at + 7] * z - wt) }
            else stroker.corners(routes, at, at + 8, z, wl, wt, 5f / density, .2f / density)
            val mesh = if (sketch.proposed[i]) dash(7.4f, 2.6f) else solid
            stroker.stroke(mesh, .7f * z, fringe, tone, .65f, round = mesh === solid, dashed = mesh !== solid)
            arrow(routes[at + 6], routes[at + 7], atan2(routes[at + 7] - routes[at + 5], 0f), 10f / density, z, fringe, tone, .65f)
        }
        commit(0, true)
    }

    override fun DrawScope.warm(step: Int) = true

    override fun slot(zoom: Double, ahead: Boolean) = if (ahead) -1 else 0

    override fun current(i: Int, slot: Int) = layers[i * 2] != null && shown[i] == (sketch.nodes[i].id == selected)

    override fun record(i: Int, slot: Int, deadline: TimeSource.Monotonic.ValueTimeMark?) {
        val node = sketch.nodes[i]; val chosen = node.id == selected
        val width = cardBox[i * 4 + 2] - cardBox[i * 4]; val height = cardBox[i * 4 + 3] - cardBox[i * 4 + 1]
        val room = Constraints(maxWidth = (width - 24).toInt())
        val heading = measurer.measure(node.title, title, TextOverflow.Ellipsis, maxLines = 2, constraints = room)
        val detail = measurer.measure(node.subtitle, subtitle, TextOverflow.Ellipsis, maxLines = 1, constraints = room)
        val edge = if (chosen) 2f else 1f
        tile(i, 0, cardBox[i * 4].toDouble(), cardBox[i * 4 + 1].toDouble(), width.toDouble(), height.toDouble()) { x, y ->
            drawRoundRect(appearance.card, Offset(x, y), Size(width, height), CornerRadius(12f))
            drawRoundRect(if (chosen) appearance.selected else appearance.line, Offset(x + edge / 2, y + edge / 2), Size(width - edge, height - edge), CornerRadius(12f - edge / 2), style = Stroke(edge))
            paint(heading, x + 12, y + 12)
            paint(detail, x + 12, y + 18 + heading.size.height)
        }
        shown[i] = chosen
    }
}
