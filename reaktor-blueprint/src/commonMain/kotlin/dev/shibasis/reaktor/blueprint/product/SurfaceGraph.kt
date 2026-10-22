package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.canvas.GraphMarkers
import dev.shibasis.reaktor.blueprint.canvas.GraphView
import dev.shibasis.reaktor.blueprint.canvas.Said
import dev.shibasis.reaktor.blueprint.canvas.at
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

internal class SurfaceGraphSketch(val nodes: List<SurfaceGraphNode>, val cards: List<Card>, val box: FloatArray, val links: List<Link>, val proposed: BooleanArray)

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
    val base = LocalTextStyle.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val graphics = LocalGraphicsContext.current
    val painter = remember(graphics, resolver, direction, appearance, base) { SurfaceGraphCanvasPainter(graphics, TextMeasurer(resolver, Density(1f), direction), appearance, base) }
    val sketch = remember(nodes, links, appearance.width, appearance.height) {
        val at = surfaceGraphPositions(nodes, appearance.width, appearance.height)
        val byId = nodes.associateBy { it.id }
        val shown = at.keys.map(byId::getValue)
        val routed = links.filter { it.from in at && it.to in at }.distinctBy { it.id }
        val cards = shown.map { node -> at.getValue(node.id).let { (x, y) -> Card(node.id, x, y, appearance.width, appearance.height, emptyList(), 0) } }
        val box = FloatArray(cards.size * 4)
        cards.forEachIndexed { i, card -> doubleArrayOf(card.x, card.y, card.x + card.width, card.y + card.height).forEachIndexed { k, v -> box[i * 4 + k] = v.toFloat() } }
        val routes = routed.map { link ->
            val (fx, fy) = at.getValue(link.from); val (tx, ty) = at.getValue(link.to)
            val sx = fx + appearance.width / 2; val sy = fy + appearance.height; val ex = tx + appearance.width / 2; val mid = (sy + ty) / 2
            Link(link.id, link.from, link.to, null, null, LinkKind.Wire, listOf(link.id), listOf(sx to sy, sx to mid, ex to mid, ex to ty))
        }
        SurfaceGraphSketch(shown, cards, box, routes, BooleanArray(routed.size) { routed[it].proposed })
    }
    SideEffect { canvas.extent = sketch.cards }
    val fitKey = sceneKey to nodes.size
    LaunchedEffect(fitKey, canvas.flow.canvasSize, sketch) {
        if (sketch.nodes.isNotEmpty()) canvas.fitOnce(fitKey) { canvas.fit(sketch.cards) }
    }
    val select by rememberUpdatedState(onSelect)
    GraphView(painter, canvas,
        hit = { x, y, _ -> painter.cardAt(x, y) != null },
        tap = { x, y, _ -> painter.cardAt(x, y)?.let { select(it); true } ?: false },
        modifier = modifier,
        selection = { sketch.cards.firstOrNull { it.id == selectedId } },
        step = { by -> sketch.cards.sortedWith(compareBy({ it.y }, { it.x })).takeIf { it.isNotEmpty() }?.let { order ->
            val at = order.indexOfFirst { it.id == selectedId }
            order[(at + if (at < 0 && by < 0) 0 else by).mod(order.size)].let { select(it.id); canvas.reveal(it.x, it.y, it.width, it.height) }
        } },
        open = { x, y -> painter.cardAt(x, y)?.let { id -> sketch.cards.firstOrNull { it.id == id } }?.let { canvas.centre(it.x, it.y, it.width, it.height); true } ?: false },
        markers = { focus ->
            GraphMarkers(canvas, sketch.cards, { sketch.cards[it].id }) { i, _ ->
                val node = sketch.nodes[i]; val card = sketch.cards[i]
                Box(Modifier.at(card.x, card.y, card.width, card.height).semantics(mergeDescendants = true) {
                    selected = node.id == selectedId
                    onClick { focus(); select(node.id); true }
                }) {
                    Said(node.title)
                    Said(node.subtitle)
                }
            }
        },
    ) { camera, viewport, scale ->
        painter.sync(sketch, selectedId)
        painter.draw(this, camera, viewport, scale)
    }
}

internal class SurfaceGraphCanvasPainter(graphics: GraphicsContext, private val measurer: TextMeasurer, private val appearance: SurfaceGraphAppearance, base: TextStyle) : SurfaceGraphEngine(graphics, scaled = true) {
    private val title = base.merge(color = appearance.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    private val subtitle = base.merge(color = appearance.muted, fontSize = 11.sp)
    private val tone = appearance.selected.toArgb()
    private val dots = ShaderBrush(ImageShader(ImageBitmap(26, 26).also { image ->
        CanvasDrawScope().draw(unit, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(26f, 26f)) { drawCircle(Color(0x1F8CA0C8), 1.2f, Offset(13.5f, 13.5f)) }
    }, TileMode.Repeated, TileMode.Repeated))
    private var sketch = SurfaceGraphSketch(emptyList(), emptyList(), FloatArray(0), emptyList(), BooleanArray(0))
    private var selected: String? = null
    private var picked = BooleanArray(0)

    override val warmups = 0
    override val warmable = false
    override val canvas = appearance.canvas
    override val farZoom = Double.POSITIVE_INFINITY
    override val cardRadius = 12f

    fun sync(sketch: SurfaceGraphSketch, selected: String?) {
        this.selected = selected
        level = 1
        if (sketch === this.sketch) return
        remap(IntArray(sketch.nodes.size) { -1 })
        this.sketch = sketch; picked = BooleanArray(sketch.nodes.size)
        index(emptyList(), sketch.links, sketch.box)
    }

    fun cardAt(x: Float, y: Float) = cardIndex(x, y).takeIf { it >= 0 }?.let { sketch.nodes[it].id }

    fun draw(scope: DrawScope, camera: Viewport, viewport: Size, density: Float) = with(scope) { frame(camera, viewport, density) }

    override fun DrawScope.background(camera: Viewport) {
        drawRect(appearance.canvas, size = viewport)
        val px = density; val dx = (camera.x * px).roundToInt().mod(26) - 39f; val dy = (camera.y * px).roundToInt().mod(26) - 39f
        withTransform({ scale(1 / px, 1 / px, Offset.Zero); translate(dx, dy) }) { drawRect(dots, size = Size(viewport.width * px + 52, viewport.height * px + 52)) }
    }

    override fun pen(i: Int, pen: SurfaceGraphPen): Boolean {
        pen.rgb = tone; pen.alpha = .65f; pen.width = 1.4f; pen.corner = 5f; pen.head = 10f
        if (sketch.proposed[i]) { pen.on = 6f; pen.off = 4f }
        return true
    }

    override fun body(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, fringe: Float, tolerance: Float) {}

    override fun DrawScope.warm(step: Int) = true

    override fun DrawScope.placeholder(i: Int, slot: Int) =
        drawRoundRect(appearance.card, Offset(cardBox[i * 4], cardBox[i * 4 + 1]), Size(cardBox[i * 4 + 2] - cardBox[i * 4], cardBox[i * 4 + 3] - cardBox[i * 4 + 1]), CornerRadius(12f))

    override fun current(i: Int, slot: Int) = layers[i * 2] != null && picked[i] == (sketch.nodes[i].id == selected)

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
        picked[i] = chosen
    }
}
