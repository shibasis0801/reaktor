package dev.shibasis.reaktor.blueprint.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shibasis.reaktor.blueprint.editor.GraphPart
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Template
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Serializable
data class CaptureStyle(val stroke: Long? = null, val fill: Long? = null, val weight: Weight = Weight.Normal) {
    init { require(listOfNotNull(stroke, fill).all { it in Int.MIN_VALUE.toLong()..0xFFFFFFFFL }) }
    @Serializable enum class Weight { Fine, Normal, Bold }
}

@Serializable
data class CaptureCrop(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 })
        require(left < right && top < bottom)
    }
}

enum class CaptureTool { Select, Box, Diamond, Ellipse, Text, Note, Line, Arrow, Frame, Pencil, Eraser, Image, Comment }

object CaptureCatalogue {
    val templates: List<Template> = listOf(
        CaptureTool.Box to "Rectangle",
        CaptureTool.Diamond to "Diamond",
        CaptureTool.Ellipse to "Ellipse",
        CaptureTool.Text to "Text",
        CaptureTool.Note to "Note",
        CaptureTool.Line to "Line",
        CaptureTool.Arrow to "Arrow",
        CaptureTool.Frame to "Frame",
        CaptureTool.Pencil to "Freehand",
        CaptureTool.Image to "Image",
        CaptureTool.Comment to "Comment",
    ).map { (tool, label) ->
        Template(label, NodeKind.Node, "capture:${tool.name.lowercase()}", emptyList(), category = "Capture", captureTool = tool)
    }
}

data class CaptureMetrics(
    val textWidth: Double = MachineSignal.Editor.searchControlWidth.value.toDouble(),
    val lineHeight: Double = MachineSignal.Space.s5.value.toDouble(),
    val characterWidth: Double = MachineSignal.Space.s2.value.toDouble(),
    val padding: Double = MachineSignal.Space.s2.value.toDouble(),
    val defaultWidth: Double = MachineSignal.Editor.navigatorWidth.value.toDouble(),
    val defaultHeight: Double = MachineSignal.Space.s6.value.toDouble() * 3,
    val hitTolerance: Double = MachineSignal.Space.s1_5.value.toDouble(),
    val handleSize: Double = MachineSignal.Dot.large.value.toDouble(),
) {
    init { require(listOf(textWidth, lineHeight, characterWidth, padding, defaultWidth, defaultHeight, hitTolerance, handleSize).all { it.isFinite() && it > 0 }) }
    fun textHeight(text: String): Double = padding * 2 + text.lines().sumOf { max(1.0, ceil(it.length * characterWidth / (textWidth - padding * 2).coerceAtLeast(characterWidth))) } * lineHeight
}

class CaptureTools(
    initial: Placement,
    private val nextId: () -> String,
    val author: CaptureAuthor? = null,
    val metrics: CaptureMetrics = CaptureMetrics(),
    private val resolve: (CaptureAnchor) -> Spot = CaptureAnchor::fallbackSpot,
) {
    var tool by mutableStateOf(CaptureTool.Select)
        private set
    var locked by mutableStateOf(false)
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    var preview by mutableStateOf<List<Capture>>(initial.captures)
        private set
    var previewAssets by mutableStateOf(initial.assets)
        private set
    private var base = initial
    private val changed = linkedMapOf<String, Capture>()
    private val removed = linkedSetOf<String>()
    private val assets = linkedMapOf<String, CaptureAsset>()
    private var gesture: Gesture? = null
    private data class Gesture(val start: Spot, val original: Capture?, val hit: CaptureHit.Result?, val created: Boolean)

    fun sync(placement: Placement) {
        base = placement
        clearDraft()
        if (selected !in preview.map { it.id }) selected = null
    }

    fun select(tool: CaptureTool, lock: Boolean = locked) {
        cancel()
        this.tool = tool
        locked = lock
    }

    fun lock(value: Boolean) { locked = value }

    fun begin(at: Spot, anchor: CaptureAnchor = CaptureAnchor.World(at), hit: CaptureHit.Result? = null): Boolean {
        require(at.finite())
        check(gesture == null) { "Finish or cancel the current capture first" }
        if (tool == CaptureTool.Image) return false
        if (tool == CaptureTool.Select) {
            selected = hit?.id
            val original = preview.firstOrNull { it.id == selected } ?: return false
            gesture = Gesture(at, original, hit, false)
            return true
        }
        if (tool == CaptureTool.Eraser) {
            gesture = Gesture(at, null, null, false)
            hit?.let { erase(it.id) }
            return true
        }
        val id = nextId().also { require(it.isNotBlank() && base.captures.none { old -> old.id == it } && changed[it] == null) { "A capture needs a new identity" } }
        val value: Capture = when (tool) {
            CaptureTool.Box -> Capture.Box(id, 0.0, 0.0, anchor, author)
            CaptureTool.Diamond -> Capture.Diamond(id, 0.0, 0.0, anchor, author)
            CaptureTool.Ellipse -> Capture.Ellipse(id, 0.0, 0.0, anchor, author)
            CaptureTool.Note -> Capture.Note(id, "", metrics.defaultWidth, metrics.defaultHeight, anchor, author)
            CaptureTool.Text -> Capture.Text(id, "", anchor, author)
            CaptureTool.Comment -> Capture.Comment(id, "", anchor, author)
            CaptureTool.Frame -> Capture.Frame(id, "", 0.0, 0.0, anchor, author)
            CaptureTool.Line -> Capture.Line(id, anchor, anchor, author = author)
            CaptureTool.Arrow -> Capture.Arrow(id, anchor, anchor, author = author)
            CaptureTool.Pencil -> Capture.Freehand(id, listOf(Spot(0.0, 0.0)), anchor, author)
            else -> error("Choose a capture tool")
        }
        selected = id
        changed[id] = value
        gesture = Gesture(at, value, null, true)
        refresh()
        return true
    }

    fun update(at: Spot, anchor: CaptureAnchor = CaptureAnchor.World(at), hit: CaptureHit.Result? = null) {
        require(at.finite())
        val g = gesture ?: return
        if (tool == CaptureTool.Eraser) { hit?.let { erase(it.id) }; return }
        val original = g.original ?: return
        val value = if (!g.created) {
            when (g.hit?.handle) {
                CaptureHit.Handle.Start -> when (original) {
                    is Capture.Line -> original.copy(anchor = anchor)
                    is Capture.Arrow -> original.copy(anchor = anchor)
                    else -> original
                }
                CaptureHit.Handle.End -> when (original) {
                    is Capture.Line -> original.copy(end = anchor)
                    is Capture.Arrow -> original.copy(end = anchor)
                    else -> original
                }
                CaptureHit.Handle.NorthWest, CaptureHit.Handle.NorthEast, CaptureHit.Handle.SouthWest, CaptureHit.Handle.SouthEast -> resize(original, g.hit.handle, at)
                else -> original.translated(at - g.start)
            }
        } else when (original) {
            is Capture.Line -> original.copy(end = anchor)
            is Capture.Arrow -> original.copy(end = anchor)
            is Capture.Freehand -> (changed[original.id] as Capture.Freehand).let { current ->
                val relative = at - resolve(original.anchor)
                if (current.points.last() == relative) current else current.copy(points = current.points + relative)
            }
            is Capture.Text, is Capture.Comment -> original
            is Capture.Note -> if (at == g.start) original else original.sized(abs(at.x - g.start.x), abs(at.y - g.start.y))
                .anchored(original.anchor.shifted(Spot(min(at.x, g.start.x) - g.start.x, min(at.y, g.start.y) - g.start.y)))
            else -> original.sized(abs(at.x - g.start.x), abs(at.y - g.start.y))
                .anchored(original.anchor.shifted(Spot(min(at.x, g.start.x) - g.start.x, min(at.y, g.start.y) - g.start.y)))
        }
        changed[value.id] = value
        refresh()
    }

    fun setText(id: String, text: String): Boolean = edit(id) {
        when (it) {
            is Capture.Text -> it.copy(text = text)
            is Capture.Note -> it.copy(text = text)
            is Capture.Comment -> it.copy(text = text)
            is Capture.Frame -> it.copy(title = text)
            is Capture.Image -> it.copy(alt = text)
            else -> it
        }
    }

    fun insertImage(asset: CaptureAsset, at: Spot, width: Double, height: Double, anchor: CaptureAnchor = CaptureAnchor.World(at), alt: String = ""): String {
        require(at.finite() && width.isFinite() && height.isFinite() && width > 0 && height > 0)
        require(asset.id.isNotBlank() && asset.reference.isNotBlank() && asset.mediaType.startsWith("image/"))
        val existing = assets[asset.id] ?: base.assets[asset.id]
        require(existing == null || existing == asset) { "An image asset identity already refers to different bytes" }
        val id = nextId().also { require(it.isNotBlank() && base.captures.none { old -> old.id == it } && changed[it] == null) }
        assets[asset.id] = asset
        changed[id] = Capture.Image(id, asset.id, width, height, anchor, author, alt)
        selected = id
        refresh()
        return id
    }

    fun crop(id: String, crop: CaptureCrop?): Boolean = edit(id) { if (it is Capture.Image) it.copy(crop = crop) else it }

    fun reply(commentId: String, text: String): String {
        require(text.isNotBlank())
        val parent = preview.filterIsInstance<Capture.Comment>().firstOrNull { it.id == commentId }
            ?: throw IllegalArgumentException("Choose a comment to reply to")
        val thread = linkedSetOf(parent.id)
        do {
            val size = thread.size
            preview.filterIsInstance<Capture.Comment>().filter { it.replyTo in thread }.forEach { thread += it.id }
        } while (thread.size > size)
        val bottom = preview.filter { it.id in thread }.maxOf { CaptureHit.geometry(it, resolve, metrics).bounds.bottom }
        val origin = resolve(parent.anchor)
        val id = nextId().also { require(it.isNotBlank() && base.captures.none { old -> old.id == it } && changed[it] == null) }
        changed[id] = Capture.Comment(id, text, parent.anchor.shifted(Spot(metrics.padding * 2, bottom - origin.y + metrics.lineHeight * 2)), author, parent.id)
        selected = id
        refresh()
        return id
    }

    fun style(id: String, style: CaptureStyle): Boolean = edit(id) { it.styled(style) }

    fun erase(id: String): Boolean {
        if (preview.none { it.id == id }) return false
        changed.remove(id)
        if (base.captures.any { it.id == id }) removed += id
        if (selected == id) selected = null
        refresh()
        return true
    }

    fun finish(): GraphPart? {
        val delta = changed.values.filter { it != base.captures.firstOrNull { old -> old.id == it.id } && it.drawable() }
        val usedAssets = delta.filterIsInstance<Capture.Image>().mapTo(hashSetOf()) { it.asset }
        val layout = Placement(capture = delta, removedCaptures = removed.toSet(), assets = assets.filterKeys { it in usedAssets })
        val result = if (delta.isEmpty() && removed.isEmpty()) null else GraphPart(changes = emptyList(), layout = layout)
        clearDraft()
        if (!locked && tool !in setOf(CaptureTool.Select, CaptureTool.Eraser)) tool = CaptureTool.Select
        return result
    }

    fun cancel() { clearDraft() }

    private fun clearDraft() {
        gesture = null
        changed.clear()
        removed.clear()
        assets.clear()
        refresh()
    }

    private fun refresh() {
        val originals = base.captures.filter { it.id !in removed }.map { changed[it.id] ?: it }
        val ids = originals.mapTo(hashSetOf()) { it.id }
        preview = originals + changed.values.filter { it.id !in ids && it.id !in removed }
        previewAssets = base.assets + assets
    }

    private fun edit(id: String, transform: (Capture) -> Capture): Boolean {
        val old = preview.firstOrNull { it.id == id } ?: return false
        val value = transform(old)
        if (value == old) return false
        changed[id] = value
        refresh()
        return true
    }

    private fun resize(value: Capture, handle: CaptureHit.Handle, at: Spot): Capture {
        val shape = CaptureHit.geometry(value, resolve, metrics)
        val west = handle == CaptureHit.Handle.NorthWest || handle == CaptureHit.Handle.SouthWest
        val north = handle == CaptureHit.Handle.NorthWest || handle == CaptureHit.Handle.NorthEast
        val opposite = Spot(if (west) shape.bounds.right else shape.bounds.left, if (north) shape.bounds.bottom else shape.bounds.top)
        val fixed = opposite.rotated(shape.center, shape.angle)
        val local = at.rotated(fixed, -shape.angle) - fixed
        val width = abs(local.x)
        val height = abs(local.y)
        val center = (fixed + Spot(local.x / 2, local.y / 2)).rotated(fixed, shape.angle)
        val origin = center - Spot(width / 2, height / 2)
        return value.sized(width, height).anchored(value.anchor.shifted(origin - shape.origin))
    }
}

fun CaptureAnchor.fallbackSpot(): Spot = when (this) {
    is CaptureAnchor.World -> at
    is CaptureAnchor.Node -> fallback
    is CaptureAnchor.Row -> fallback
    is CaptureAnchor.Port -> fallback
    is CaptureAnchor.Wire -> fallback
    is CaptureAnchor.Frame -> fallback
}

internal fun CaptureAnchor.shifted(delta: Spot): CaptureAnchor = when (this) {
    is CaptureAnchor.World -> copy(at = at + delta)
    is CaptureAnchor.Node -> copy(offset = offset + delta, fallback = fallback + delta)
    is CaptureAnchor.Row -> copy(offset = offset + delta, fallback = fallback + delta)
    is CaptureAnchor.Port -> copy(offset = offset + delta, fallback = fallback + delta)
    is CaptureAnchor.Wire -> copy(offset = offset + delta, fallback = fallback + delta)
    is CaptureAnchor.Frame -> copy(offset = offset + delta, fallback = fallback + delta)
}

internal operator fun Spot.plus(other: Spot) = Spot(x + other.x, y + other.y)
internal operator fun Spot.minus(other: Spot) = Spot(x - other.x, y - other.y)
internal fun Spot.finite() = x.isFinite() && y.isFinite()
internal fun Spot.rotated(center: Spot, radians: Double): Spot = Spot(center.x + (x - center.x) * cos(radians) - (y - center.y) * sin(radians), center.y + (x - center.x) * sin(radians) + (y - center.y) * cos(radians))

internal fun Capture.anchored(value: CaptureAnchor): Capture = when (this) {
    is Capture.Box -> copy(anchor = value)
    is Capture.Diamond -> copy(anchor = value)
    is Capture.Ellipse -> copy(anchor = value)
    is Capture.Frame -> copy(anchor = value)
    is Capture.Note -> copy(anchor = value)
    is Capture.Image -> copy(anchor = value)
    is Capture.Text -> copy(anchor = value)
    is Capture.Comment -> copy(anchor = value)
    is Capture.Freehand -> copy(anchor = value)
    is Capture.Line -> copy(anchor = value)
    is Capture.Arrow -> copy(anchor = value)
}

internal fun Capture.translated(delta: Spot): Capture = when (this) {
    is Capture.Line -> copy(anchor = anchor.shifted(delta), end = end.shifted(delta), bends = bends.map { it + delta })
    is Capture.Arrow -> copy(anchor = anchor.shifted(delta), end = end.shifted(delta), bends = bends.map { it + delta })
    else -> anchored(anchor.shifted(delta))
}

internal fun Capture.sized(width: Double, height: Double): Capture = when (this) {
    is Capture.Box -> copy(width = width, height = height)
    is Capture.Diamond -> copy(width = width, height = height)
    is Capture.Ellipse -> copy(width = width, height = height)
    is Capture.Frame -> copy(width = width, height = height)
    is Capture.Note -> copy(width = width, height = height)
    is Capture.Image -> copy(width = width, height = height)
    else -> this
}

internal fun Capture.styled(style: CaptureStyle): Capture = when (this) {
    is Capture.Box -> copy(style = style)
    is Capture.Diamond -> copy(style = style)
    is Capture.Ellipse -> copy(style = style)
    is Capture.Frame -> copy(style = style)
    is Capture.Note -> copy(style = style)
    is Capture.Image -> copy(style = style)
    is Capture.Text -> copy(style = style)
    is Capture.Comment -> copy(style = style)
    is Capture.Freehand -> copy(style = style)
    is Capture.Line -> copy(style = style)
    is Capture.Arrow -> copy(style = style)
}

internal fun Capture.drawable(): Boolean = when (this) {
    is Capture.Box -> width > 0 && height > 0
    is Capture.Diamond -> width > 0 && height > 0
    is Capture.Ellipse -> width > 0 && height > 0
    is Capture.Frame -> width > 0 && height > 0
    is Capture.Image -> width > 0 && height > 0
    is Capture.Note -> text.isNotBlank() && width > 0 && height > 0
    is Capture.Text -> text.isNotBlank()
    is Capture.Comment -> text.isNotBlank()
    is Capture.Freehand -> points.distinct().size > 1
    is Capture.Line -> anchor != end || bends.isNotEmpty()
    is Capture.Arrow -> anchor != end || bends.isNotEmpty()
}
