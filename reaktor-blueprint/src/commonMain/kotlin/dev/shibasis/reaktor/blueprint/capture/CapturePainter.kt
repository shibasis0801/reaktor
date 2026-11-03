package dev.shibasis.reaktor.blueprint.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class CapturePainter(
    private val measurer: TextMeasurer,
    private val colors: MachineSignalColors,
    private val textStyle: TextStyle = MachineSignal.Type.base,
    val metrics: CaptureMetrics = CaptureMetrics(),
) {
    enum class Level { Overview, Summary, Detail }

    fun DrawScope.paint(
        captures: List<Capture>,
        zoom: Double,
        level: Level,
        selected: String? = null,
        viewport: CaptureHit.Bounds? = null,
        resolve: (CaptureAnchor) -> Spot = CaptureAnchor::fallbackSpot,
        image: (String) -> ImageBitmap? = { null },
        portrait: (CaptureAuthor) -> ImageBitmap? = { null },
        placement: CapturePlacement? = null,
    ) {
        require(zoom.isFinite() && zoom > 0)
        val position = placement?.let { value -> { anchor: CaptureAnchor -> value.resolve(anchor) } } ?: resolve
        val comments = captures.filterIsInstance<Capture.Comment>().associateBy { it.id }
        val replies = comments.values.groupingBy { it.replyTo }.eachCount()
        for (value in captures) {
            val g = CaptureHit.geometry(value, position, metrics)
            val extent = g.extent
            if (viewport != null && (extent.right < viewport.left || extent.left > viewport.right || extent.bottom < viewport.top || extent.top - metrics.lineHeight / zoom > viewport.bottom)) continue
            val bitmap = if (value is Capture.Image && level != Level.Overview) image(value.asset) else null
            val style = value.style
            val tone = style.stroke?.let { Color(it) } ?: colors.controlAccent
            val weight = when (style.weight) {
                CaptureStyle.Weight.Fine -> MachineSignal.Stroke.hairline.value
                CaptureStyle.Weight.Normal -> MachineSignal.Stroke.bar.value
                CaptureStyle.Weight.Bold -> MachineSignal.Stroke.bar.value + MachineSignal.Stroke.hairline.value
            } / zoom.toFloat()
            val dash = PathEffect.dashPathEffect(floatArrayOf((metrics.padding / zoom).toFloat(), (metrics.padding / 2 / zoom).toFloat()))
            val outline = Stroke(weight, pathEffect = dash)
            val parent = (value as? Capture.Comment)?.replyTo?.let(comments::get)
            if (level == Level.Detail && parent != null) {
                val from = CaptureHit.geometry(parent, position, metrics).bounds
                drawPath(path(listOf(Spot(from.left + metrics.padding, from.bottom),
                    Spot(from.left + metrics.padding, g.bounds.top + metrics.padding),
                    Spot(g.bounds.left, g.bounds.top + metrics.padding))), tone, style = outline)
            }
            val b = g.bounds
            val top = Offset(b.left.toFloat(), b.top.toFloat())
            val size = Size(b.width.toFloat().coerceAtLeast(0f), b.height.toFloat().coerceAtLeast(0f))
            if (g.points.isNotEmpty()) {
                val path = path(g.points)
                drawPath(path, tone, style = outline)
                if (value is Capture.Arrow && g.points.size > 1) {
                    val end = g.points.last()
                    val previous = g.points.dropLast(1).lastOrNull { it != end }
                    if (previous != null) {
                        val angle = atan2(end.y - previous.y, end.x - previous.x)
                        val length = metrics.handleSize / zoom
                        val wing = PI / 6
                        val arrow = path(listOf(Spot(end.x - cos(angle - wing) * length, end.y - sin(angle - wing) * length), end,
                            Spot(end.x - cos(angle + wing) * length, end.y - sin(angle + wing) * length)))
                        drawPath(arrow, tone, style = Stroke(weight))
                    }
                }
            } else rotate((g.angle * 180 / PI).toFloat(), g.center.offset()) {
                val fill = style.fill?.let { Color(it) } ?: when (value) {
                    is Capture.Note -> if (value.tint != 0L) Color(value.tint) else colors.surfaceAlt
                    is Capture.Frame -> if (value.tint != 0L) Color(value.tint) else Color.Transparent
                    is Capture.Comment -> colors.surfaceAlt
                    else -> Color.Transparent
                }
                when (value) {
                    is Capture.Ellipse -> { drawOval(fill, top, size); drawOval(tone, top, size, style = outline) }
                    is Capture.Diamond -> {
                        val diamond = path(listOf(Spot(g.center.x, b.top), Spot(b.right, g.center.y), Spot(g.center.x, b.bottom), Spot(b.left, g.center.y)), true)
                        drawPath(diamond, fill); drawPath(diamond, tone, style = outline)
                    }
                    else -> {
                        drawRect(fill, top, size)
                        if (value is Capture.Image && level != Level.Overview) drawCaptureImage(value, bitmap, top, size)
                        drawRect(tone, top, size, style = outline)
                    }
                }
                if (level != Level.Overview) {
                    val content = when (value) {
                        is Capture.Note -> value.text
                        is Capture.Text -> value.text
                        is Capture.Comment -> value.text
                        is Capture.Frame -> value.title
                        is Capture.Image -> if (bitmap == null) listOf("Image unavailable", value.alt).filter { it.isNotBlank() }.joinToString(" · ") else ""
                        else -> ""
                    }
                    if (content.isNotEmpty()) clipRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()) {
                        val reply = (value as? Capture.Comment)?.replyTo
                        val header = if (reply != null && level == Level.Detail) "Reply to ${parent?.author?.name ?: "unavailable comment"}" else null
                        if (header != null) label(header, Offset(top.x + metrics.padding.toFloat(), top.y + metrics.padding.toFloat()),
                            (b.width - metrics.padding * 2).toFloat(), true, true, colors.textMuted)
                        label(content, Offset(top.x + metrics.padding.toFloat(), top.y + metrics.padding.toFloat() + if (header == null) 0f else metrics.lineHeight.toFloat()),
                            (b.width - metrics.padding * 2).toFloat(), level == Level.Summary, false, colors.textStrong)
                    }
                }
            }
            val metaHeight = (metrics.lineHeight / zoom).toFloat()
            val badge = Offset(top.x, top.y - metaHeight)
            val author = value.author
            val face = if (level == Level.Detail && author != null) portrait(author) else null
            var textAt = badge
            if (face != null) {
                drawImage(face, dstOffset = IntOffset(badge.x.roundToInt(), badge.y.roundToInt()), dstSize = IntSize(metaHeight.roundToInt().coerceAtLeast(1), metaHeight.roundToInt().coerceAtLeast(1)))
                textAt = Offset(badge.x + metaHeight + (metrics.padding / zoom).toFloat(), badge.y)
            }
            val thread = when {
                value is Capture.Comment && value.replyTo != null -> " · reply"
                (replies[value.id] ?: 0) > 0 -> " · ${replies.getValue(value.id)} ${if (replies[value.id] == 1) "reply" else "replies"}"
                else -> ""
            }
            val end = when (value) { is Capture.Arrow -> value.end; is Capture.Line -> value.end; else -> null }
            val location = listOfNotNull(placement?.caption(value.anchor), end?.let { placement?.caption(it) }).distinct().joinToString(" / ")
            val caption = (if (location.isEmpty()) "" else "$location · ") + "capture$thread" + if (level == Level.Detail && author != null) " · ${author.name}" else ""
            label(caption, textAt, ((b.width * zoom).coerceAtLeast(metrics.textWidth) / zoom).toFloat() - (textAt.x - badge.x), true, true, colors.controlAccent, zoom)
            if (selected == value.id) for (point in CaptureHit.handles(value, position, metrics).values) {
                val side = (metrics.handleSize / zoom).toFloat()
                val start = Offset(point.x.toFloat() - side / 2, point.y.toFloat() - side / 2)
                drawRect(colors.surface, start, Size(side, side))
                drawRect(colors.controlAccent, start, Size(side, side), style = Stroke(MachineSignal.Stroke.hairline.value / zoom.toFloat()))
            }
        }
    }

    private fun DrawScope.label(value: String, at: Offset, width: Float, oneLine: Boolean, meta: Boolean, color: Color, zoom: Double = 1.0) {
        if (width <= 0) return
        val font = if (meta) MachineSignal.Type.label / zoom.toFloat() else MachineSignal.Type.body
        val measured = measurer.measure(value, textStyle.copy(fontSize = font, lineHeight = if (meta) MachineSignal.Type.body / zoom.toFloat() else MachineSignal.Type.body * (metrics.lineHeight / MachineSignal.Type.body.value).toFloat(), color = color),
            overflow = if (oneLine) TextOverflow.Ellipsis else TextOverflow.Clip, maxLines = if (oneLine) 1 else Int.MAX_VALUE,
            constraints = Constraints(maxWidth = if (width.isFinite()) width.toInt().coerceIn(1, 32760) else Constraints.Infinity), density = Density(1f))
        drawText(measured, topLeft = at)
    }

    private fun DrawScope.drawCaptureImage(value: Capture.Image, bitmap: ImageBitmap?, at: Offset, size: Size) {
        if (bitmap == null || size.width <= 0 || size.height <= 0) return
        val crop = value.crop
        val left = ((crop?.left ?: 0.0) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((crop?.top ?: 0.0) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = ((crop?.right ?: 1.0) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((crop?.bottom ?: 1.0) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        drawImage(bitmap, srcOffset = IntOffset(left, top), srcSize = IntSize(right - left, bottom - top), dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()), dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)))
    }

    private fun path(points: List<Spot>, close: Boolean = false) = Path().apply {
        points.firstOrNull()?.let { moveTo(it.x.toFloat(), it.y.toFloat()) }
        points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        if (close) close()
    }

    private fun Spot.offset() = Offset(x.toFloat(), y.toFloat())
}
