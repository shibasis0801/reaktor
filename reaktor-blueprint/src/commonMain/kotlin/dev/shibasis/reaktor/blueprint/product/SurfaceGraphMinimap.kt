package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlin.math.max
import kotlin.math.min

private class MinimapFrame(val extent: Area) {
    var scale = 0.0
    var left = 0.0
    var top = 0.0

    fun fit(width: Float, height: Float) {
        scale = min(width / extent.width, height / extent.height)
        left = (width - extent.width * scale) / 2
        top = (height - extent.height * scale) / 2
    }

    fun at(x: Double, y: Double) = Offset(((x - extent.x) * scale + left).toFloat(), ((y - extent.y) * scale + top).toFloat())

    fun extent(width: Double, height: Double) = Size(max(width * scale, 1.0).toFloat(), max(height * scale, 1.0).toFloat())

    fun world(at: Offset) = Pair(extent.x + (at.x - left) / scale, extent.y + (at.y - top) / scale)
}

@Composable
fun SurfaceGraphMinimap(layout: BlueprintLayout<*>, state: GraphCanvasState, appearance: SurfaceGraphAppearance, modifier: Modifier = Modifier,
    tones: Map<String, Color> = emptyMap(), selected: String? = null) {
    val frame = remember(layout) {
        val boxes: List<Bounds> = layout.frames.ifEmpty { layout.cards.values.toList() }
        if (boxes.isEmpty()) null else {
            val left = boxes.minOf { it.x }; val top = boxes.minOf { it.y }
            MinimapFrame(Area(left, top, max(boxes.maxOf { it.x + it.width } - left, 1.0), max(boxes.maxOf { it.y + it.height } - top, 1.0)))
        }
    } ?: return
    val shape = RoundedCornerShape(8.dp)
    fun aim(at: Offset) {
        if (frame.scale <= 0) return
        val (x, y) = frame.world(at)
        state.look(x, y, state.zoom)
    }
    Box(modifier.size(MinimapWidth.dp, MinimapHeight.dp).clip(shape).background(appearance.card).border(1.dp, appearance.line, shape)
        .semantics { contentDescription = "Minimap" }
        .pointerInput(state, frame) { detectTapGestures { aim(it) } }
        .pointerInput(state, frame) { detectDragGestures { change, _ -> change.consume(); aim(change.position) } }
        .drawWithCache {
            frame.fit(size.width, size.height)
            val areas = layout.frames.filter { it.parent == null }
            val paths = layout.cards.values.groupBy { tones[it.id] ?: appearance.muted }.mapValues { (_, cards) ->
                Path().apply { cards.forEach { card -> addRect(Rect(frame.at(card.x, card.y), frame.extent(card.width, card.height))) } }
            }
            val chosen = selected?.let { layout.cards[it] }
            onDrawBehind {
                areas.forEach { drawRect(appearance.text.copy(alpha = .06f), frame.at(it.x, it.y), frame.extent(it.width, it.height)) }
                paths.forEach { (tone, path) -> drawPath(path, tone.copy(alpha = .75f)) }
                chosen?.let { drawRect(appearance.selected, frame.at(it.x, it.y), frame.extent(it.width, it.height).let { box -> Size(max(box.width, 4f), max(box.height, 4f)) }) }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val seen = state.seen() ?: return@Canvas
            if (frame.scale <= 0) return@Canvas
            drawRect(appearance.selected, frame.at(seen.x, seen.y), frame.extent(seen.width, seen.height), style = Stroke(1.5.dp.toPx()))
        }
    }
}

private const val MinimapWidth = 208
private const val MinimapHeight = 128
