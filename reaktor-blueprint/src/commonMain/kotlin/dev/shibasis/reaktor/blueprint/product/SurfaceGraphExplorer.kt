package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.shibasis.composeflow.compose.interaction.*
import dev.shibasis.composeflow.model.*
import dev.shibasis.composeflow.runtime.ReactFlowState
import kotlin.math.*

/** Geometry may come from any layout engine. Rows retain their own selection identity. */
data class SurfaceGraphRect(val id: String, val x: Double, val y: Double, val width: Double, val height: Double)
data class SurfaceGraphFrame(val bounds: SurfaceGraphRect, val group: SurfaceGraphGroup, val nested: Boolean = false)
data class SurfaceGraphRoute(val link: SurfaceGraphLink, val points: List<Pair<Double, Double>>, val cross: Boolean = false, val family: String = "related", val members: Int = 1)
data class SurfaceGraphGeometry(val cards: List<SurfaceGraphRect>, val frames: List<SurfaceGraphFrame>, val routes: List<SurfaceGraphRoute>)

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
    internal var frames: List<SurfaceGraphFrame> = emptyList()
    private val history = mutableListOf<Viewport>()
    private var cursor = -1
    private var placed: List<Node>? = null
    private var restored: Viewport? = null
    fun fit(ids: Set<String> = emptySet()) {
        val targets = if (ids.isEmpty()) nodes else nodes.filter { it.id in ids }
        val bounds = if (ids.isEmpty() && frames.isNotEmpty()) frames.map { it.bounds } else targets.map { SurfaceGraphRect(it.id, it.position.x, it.position.y, it.width ?: 272.0, it.height ?: 46.0) }
        if (bounds.isEmpty() || camera.canvasSize == IntSize.Zero) return
        val left = bounds.minOf { it.x }; val top = bounds.minOf { it.y }
        val width = bounds.maxOf { it.x + it.width } - left; val height = bounds.maxOf { it.y + it.height } - top
        val zoom = minOf(camera.canvasSize.width * .94 / width.coerceAtLeast(1.0), camera.canvasSize.height * .94 / height.coerceAtLeast(1.0), if (ids.isEmpty()) 1.0 else 1.4)
        camera.setViewport(Viewport((camera.canvasSize.width - width * zoom) / 2 - left * zoom, (camera.canvasSize.height - height * zoom) / 2 - top * zoom, zoom.coerceAtLeast(.002)))
        record()
    }
    fun viewport(): Viewport = camera.viewport
    fun centre(id: String) {
        val target = nodes.firstOrNull { it.id == id } ?: return
        val zoom = camera.viewport.zoom.coerceAtLeast(.9)
        camera.setViewport(Viewport(camera.canvasSize.width / 2 - (target.position.x + (target.width ?: 272.0) / 2) * zoom,
            camera.canvasSize.height / 2 - (target.position.y + (target.height ?: 46.0) / 2) * zoom, zoom))
        record()
    }
    fun restore(x: Double, y: Double, zoom: Double) { restored = Viewport(x, y, zoom.coerceIn(.002, 3.0)); camera.setViewport(restored!!); record() }
    internal fun place(geometry: List<Node>) {
        nodes = geometry
        if (camera.canvasSize == IntSize.Zero) return
        val saved = restored; restored = null
        if (saved != null) { camera.setViewport(saved); record() } else if (placed !== geometry) fit()
        placed = geometry
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
    state: SurfaceGraphExplorerState, onSelect: (String) -> Unit, modifier: Modifier = Modifier, layout: SurfaceGraphGeometry? = null) {
    val fallback = remember(nodes, links, appearance, layout != null) {
        if (layout != null) return@remember layout
        val positions = expandedSurfaceGraphPositions(nodes, appearance.width, appearance.height)
        val cards = nodes.map { n -> val p = positions.getValue(n.id); SurfaceGraphRect(n.id, p.x, p.y, appearance.width, appearance.height) }
        val frames = nodes.groupBy { it.scope }.map { (scope, children) ->
            val rects = cards.filter { card -> children.any { it.id == card.id } }
            val x = rects.minOf { it.x } - 18; val y = rects.minOf { it.y } - 40
            SurfaceGraphFrame(SurfaceGraphRect(scope, x, y, rects.maxOf { it.x + it.width } - x + 18, rects.maxOf { it.y + it.height } - y + 18), SurfaceGraphGroup(scope, scope, color = children.first().color))
        }
        val routes = links.mapNotNull { edge -> val f = positions[edge.from]; val t = positions[edge.to]
            if (f == null || t == null) null else SurfaceGraphRoute(edge, listOf(f.x + appearance.width to f.y + 23, t.x to t.y + 23)) }
        SurfaceGraphGeometry(cards, frames, routes)
    }
    val scene = layout ?: fallback
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val cards = remember(scene, byId) { scene.cards.filter { it.id in byId } }
    val selectedCards = remember(nodes, selectedId) { nodes.filter { it.id == selectedId || it.owner == selectedId || it.rows.any { row -> row.id == selectedId || selectedId in row.aliases } }.mapTo(mutableSetOf()) { it.id } }
    val selectedOwners = remember(nodes, selectedCards) { nodes.filter { it.id in selectedCards }.mapTo(mutableSetOf()) { it.owner } }
    val geometry = remember(cards) { cards.map { Node(id = it.id, position = XYPosition(it.x, it.y), width = it.width, height = it.height) } }
    val paths = remember(scene.routes) { scene.routes.map { route ->
        val path = Path()
        route.points.forEachIndexed { index, p -> if (index == 0) path.moveTo(p.first.toFloat(), p.second.toFloat()) else path.lineTo(p.first.toFloat(), p.second.toFloat()) }
        route to path
    } }
    val interaction = rememberFlowViewportInteractionState()
    val config = remember { FlowViewportGestureConfig(minZoom = .002, maxZoom = 3.0) }
    val measurer = rememberTextMeasurer(cacheSize = 2048)
    val font = appearance.fontFamily
    val styles = remember(appearance) { listOf(
        TextStyle(color = Color.White, fontFamily = font, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        TextStyle(color = appearance.muted, fontFamily = font, fontSize = 10.5.sp),
        TextStyle(color = appearance.text, fontFamily = font, fontSize = 11.sp),
        TextStyle(color = Color.White, fontFamily = font, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, lineHeight = 50.6.sp),
        TextStyle(color = appearance.text, fontFamily = appearance.titleFontFamily, fontSize = 20.sp),
        TextStyle(color = appearance.text, fontFamily = font, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    ) }
    SideEffect { state.frames = scene.frames; state.place(geometry) }
    Canvas(modifier.clipToBounds().onSizeChanged { size -> state.camera.setCanvasSize(size); state.place(geometry) }
        .flowWheelAndTrackpadViewportGestures(state.camera, interaction, config)
        .flowPointerViewportGestures(state.camera, interaction, config, onPaneTap = { point ->
            val world = state.camera.screenToFlowPosition(point.x.toDouble(), point.y.toDouble())
            val hit = cards.asReversed().firstOrNull { world.x >= it.x && world.x <= it.x + it.width && world.y >= it.y && world.y <= it.y + it.height }
            if (hit != null) {
                val node = byId.getValue(hit.id)
                val row = if (state.viewport().zoom >= .42 && world.y >= hit.y + 46) node.rows.getOrNull(((world.y - hit.y - 46) / 18).toInt()) else null
                onSelect(row?.id ?: node.id)
            }
            state.record(); hit != null
        })) {
        val camera = state.camera.viewport
        val zoom = camera.zoom.toFloat()
        fun visible(r: SurfaceGraphRect): Boolean { val x = camera.x + r.x * camera.zoom; val y = camera.y + r.y * camera.zoom
            return x < size.width && y < size.height && x + r.width * camera.zoom > 0 && y + r.height * camera.zoom > 0 }
        fun screen(x: Double, y: Double) = Offset((camera.x + x * camera.zoom).toFloat(), (camera.y + y * camera.zoom).toFloat())
        fun text(value: String, style: TextStyle, x: Float, y: Float, width: Float, lines: Int = 1) {
            if (width < 1) return
            drawText(measurer.measure(value, style, maxLines = lines, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1))), topLeft = Offset(x, y))
        }
        drawRect(appearance.canvas)
        if (appearance.grid) {
            // The legacy atlas uses a world-anchored, adaptive engineering grid.
            var pitch = 128.0 * camera.zoom
            while (pitch < 48) pitch *= 2
            while (pitch > 160) pitch /= 2
            val minor = pitch / 8
            for ((spacing, alpha) in listOf(minor to .045f, pitch to .09f)) {
                var x = ((camera.x % spacing) + spacing) % spacing
                while (x < size.width) { drawLine(appearance.text.copy(alpha = alpha), Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height)); x += spacing }
                var y = ((camera.y % spacing) + spacing) % spacing
                while (y < size.height) { drawLine(appearance.text.copy(alpha = alpha), Offset(0f, y.toFloat()), Offset(size.width, y.toFloat())); y += spacing }
            }
        }
        withTransform({ translate(camera.x.toFloat(), camera.y.toFloat()); scale(zoom, zoom, Offset.Zero) }) {
            scene.frames.forEach { frame -> val r = frame.bounds
                if (!visible(r)) return@forEach
                val tone = frame.group.color ?: appearance.selected
                val p = Offset(r.x.toFloat(), r.y.toFloat()); val s = Size(r.width.toFloat(), r.height.toFloat())
                val radius = CornerRadius(if (frame.nested) 10f else 16f)
                drawRoundRect(tone.copy(alpha = if (frame.nested) .11f else .06f), p, s, radius)
                drawRoundRect(tone.copy(alpha = if (frame.nested) .42f else .28f), p, s, radius, style = Stroke(1f / zoom))
            }
            paths.forEach { (route, path) ->
                val bounds = path.getBounds()
                if (!visible(SurfaceGraphRect("", bounds.left.toDouble(), bounds.top.toDouble(), bounds.width.toDouble(), bounds.height.toDouble()))) return@forEach
                val active = route.link.from == selectedId || route.link.to == selectedId || route.link.from in selectedOwners || route.link.to in selectedOwners
                if (route.family == "chapter" && !active && (camera.zoom < .42 || route.cross)) return@forEach
                val color = route.link.color ?: appearance.selected
                val alpha = if (active) 1f else if (selectedId != null) .05f else if (route.family == "chapter") .24f else if (route.cross) .3f else .7f
                val dash = when (route.family) { "optional" -> floatArrayOf(6f / zoom, 4f / zoom); "related" -> floatArrayOf(2f / zoom, 4f / zoom); "draft" -> floatArrayOf(5f / zoom, 4f / zoom); else -> null }
                drawPath(path, color.copy(alpha = alpha), style = Stroke((if (active) 2.6f else if (route.family == "prerequisite") 1.8f else 1.4f) / zoom, pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))
                if (route.family == "prerequisite" && route.points.size > 1) {
                    val end = route.points.last(); val previous = route.points[route.points.lastIndex - 1]
                    val angle = atan2(end.second - previous.second, end.first - previous.first); val length = 8 / camera.zoom
                    val arrow = Path().apply { moveTo(end.first.toFloat(), end.second.toFloat()); lineTo((end.first - length * cos(angle - .5)).toFloat(), (end.second - length * sin(angle - .5)).toFloat()); lineTo((end.first - length * cos(angle + .5)).toFloat(), (end.second - length * sin(angle + .5)).toFloat()); close() }
                    drawPath(arrow, color.copy(alpha = alpha))
                }
            }
            cards.forEach { r ->
                if (!visible(r)) return@forEach
                val n = byId.getValue(r.id); val tone = n.color ?: appearance.selected
                val selected = n.id in selectedCards
                val p = Offset(r.x.toFloat(), r.y.toFloat()); val s = Size(r.width.toFloat(), r.height.toFloat())
                val look = if (camera.zoom < .18) 0 else if (camera.zoom < .42) 1 else 2
                val radius = CornerRadius(if (look == 0) 6f else 10f)
                if (selected) for ((inset, alpha) in listOf(12f to .08f, 6f to .18f, 2f to .38f)) drawRoundRect(appearance.selected.copy(alpha = alpha), p - Offset(inset / zoom, inset / zoom), Size(s.width + 2 * inset / zoom, s.height + 2 * inset / zoom), radius)
                if (look == 0) drawRoundRect(if (selected) appearance.selected else lerp(appearance.canvas, tone, .74f), p, s, radius)
                else {
                    clipRect(p.x, p.y, p.x + s.width, p.y + s.height) {
                        drawRoundRect(if (look == 1) lerp(tone, appearance.text, .5f) else appearance.card, p, s, radius)
                        if (look == 1) {
                            text(n.title, styles[3], p.x + 19, p.y + 15, s.width - 38, 3)
                        } else {
                            drawRect(Brush.horizontalGradient(listOf(lerp(tone, appearance.text, .55f), lerp(tone, appearance.text, .85f)), p.x, p.x + s.width), p, Size(s.width, 26f))
                            text("▣  ${n.title}", styles[0], p.x + 8, p.y + 4, s.width - 16)
                            text(n.subtitle, styles[1], p.x + 10, p.y + 28, s.width - 20)
                            n.rows.forEachIndexed { i, row ->
                                if (row.id == selectedId) drawRect(tone.copy(alpha = .12f), Offset(p.x, p.y + 46 + i * 18), Size(s.width, 18f))
                                text(row.title, styles[2], p.x + 14, p.y + 47 + i * 18, s.width - 28)
                            }
                        }
                    }
                    drawRoundRect(if (selected) appearance.selected else tone.copy(alpha = .45f), p, s, radius, style = Stroke(if (selected) 2f / zoom else 1f))
                }
            }
        }
        // Counter-scaled banners match the old atlas at overview and source zoom.
        scene.frames.forEach { frame -> val r = frame.bounds
            if (!visible(r) || (frame.nested && camera.zoom < .42)) return@forEach
            val p = screen(r.x, r.y); val width = (r.width * camera.zoom).toFloat()
            val domain = camera.zoom < .18 && !frame.nested
            val style = styles[if (domain) 4 else 5]
            val label = measurer.measure(frame.group.title, style, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (width - 22).toInt().coerceAtLeast(1)))
            val bannerWidth = minOf(width, label.size.width + if (domain) 22f else 200f)
            val bannerHeight = if (domain) 54f else if (frame.nested) 34f * zoom else 30f
            if (frame.nested) return@forEach
            drawRect(appearance.canvas.copy(alpha = .88f), p, Size(bannerWidth, bannerHeight))
            drawRect(frame.group.color ?: appearance.selected, p, Size(3f, bannerHeight))
            drawText(label, topLeft = p + Offset(11f, if (domain) 7f else 5f))
            if (domain) text(frame.group.subtitle, styles[1], p.x + 11, p.y + 34, bannerWidth - 22)
        }
    }
}
