package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.editor.GraphPart
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.graph.core.GraphShape
import kotlin.math.hypot

class CapturePlacement(
    private val layout: BlueprintLayout<*>,
    shape: GraphShape,
    private val placement: Placement = Placement(),
    represented: Map<String, String> = layout.cardOf,
    private val rows: Map<Pair<String, String>, Spot> = emptyMap(),
) {
    enum class State { World, Attached, Folded, Unattached }

    data class Attachment(val at: Spot, val state: State, val label: String, val container: String? = null) {
        val caption: String? get() = when (state) {
            State.World, State.Attached -> null
            State.Folded -> "In ${container ?: label}"
            State.Unattached -> "Unattached · $label"
        }
    }

    private val nodes = shape.nodes.associateBy { it.id }
    private val scopes = shape.scopes.associateBy { it.id }
    private val frames = layout.frames.associateBy { it.key }
    private val notes = placement.notes.filter { it.id !in placement.removedNotes }.associateBy { it.id }
    private val captures = placement.captures.filterIsInstance<Capture.Frame>().associateBy { it.id }
    private val owners = represented
    private val attached = shape.nodes.mapNotNull { node -> node.attachedTo?.let { it to node.id } }.toMap()
    private val wires = shape.wires.associate { it.id to (it.provider to it.consumer) } + shape.routes.associate { it.id to (it.from to it.to) }
    private val routes = buildMap {
        for (link in layout.links) {
            for (member in link.members) if (member !in this) put(member, link)
            put(link.id, link)
        }
    }

    fun resolve(anchor: CaptureAnchor): Spot = attachment(anchor).at

    fun caption(anchor: CaptureAnchor): String? = attachment(anchor).caption

    fun attachment(anchor: CaptureAnchor): Attachment = locate(anchor, emptySet())

    fun remember(): GraphPart? {
        val changed = placement.captures.mapNotNull { value ->
            var next = value.anchored(remember(value.anchor))
            next = when (next) {
                is Capture.Arrow -> next.copy(end = remember(next.end))
                is Capture.Line -> next.copy(end = remember(next.end))
                else -> next
            }
            next.takeIf { it != value }
        }
        return changed.takeIf { it.isNotEmpty() }?.let { GraphPart(changes = emptyList(), layout = Placement(capture = it)) }
    }

    private fun remember(anchor: CaptureAnchor): CaptureAnchor {
        val target = attachment(anchor)
        if (target.state == State.Unattached || target.state == State.World) return anchor
        return when (anchor) {
            is CaptureAnchor.World -> anchor
            is CaptureAnchor.Node -> anchor.copy(fallback = target.at, label = target.label)
            is CaptureAnchor.Row -> anchor.copy(fallback = target.at, label = target.label)
            is CaptureAnchor.Port -> anchor.copy(fallback = target.at, label = target.label)
            is CaptureAnchor.Wire -> anchor.copy(fallback = target.at, label = target.label)
            is CaptureAnchor.Frame -> anchor.copy(fallback = target.at, label = target.label)
        }
    }

    private fun locate(anchor: CaptureAnchor, visiting: Set<String>): Attachment {
        fun missing(label: String) = Attachment(anchor.fallbackSpot(), State.Unattached, label)
        return when (anchor) {
            is CaptureAnchor.World -> Attachment(anchor.at, State.World, "")
            is CaptureAnchor.Node -> {
                val node = nodes[anchor.node] ?: return missing(anchor.label)
                nodeTarget(node.id, node.label, anchor.offset) ?: missing(anchor.label)
            }
            is CaptureAnchor.Row -> {
                val node = nodes[anchor.node] ?: return missing(anchor.label)
                val card = visible(node.id)
                val pin = card?.pins?.firstOrNull { it.id == anchor.row || it.key == anchor.row || anchor.row in it.aliases }
                val at = rows[node.id to anchor.row]
                if (anchor.row !in node.attributes && node.ports.none { it.key == anchor.row } && pin == null && at == null) return missing(anchor.label)
                val label = "${node.label} · ${anchor.row}"
                when {
                    card == null || card.id != node.id -> nodeTarget(node.id, label, anchor.offset) ?: missing(anchor.label)
                    at != null -> Attachment(at + anchor.offset, State.Attached, label)
                    pin != null -> Attachment(Spot(card.x, card.y + pin.y) + anchor.offset, State.Attached, label)
                    else -> Attachment(Spot(card.x, card.y) + anchor.offset, State.Folded, label, node.label)
                }
            }
            is CaptureAnchor.Port -> {
                val node = nodes[anchor.node] ?: return missing(anchor.label)
                if (node.ports.none { it.key == anchor.port || it.definitionId.isNotBlank() && it.definitionId == anchor.port }) return missing(anchor.label)
                val label = "${node.label} · ${anchor.port}"
                val card = visible(node.id)
                if (card == null || card.id != node.id) nodeTarget(node.id, label, anchor.offset) ?: missing(anchor.label)
                else {
                    val pins = card.pins.filter { it.id == anchor.port || it.key == anchor.port || anchor.port in it.aliases }
                    val pin = pins.singleOrNull() ?: return Attachment(Spot(card.x, card.y) + anchor.offset, State.Folded, label, node.label)
                    Attachment(Spot(card.x + if (pin.provides) card.width else 0.0, card.y + pin.y) + anchor.offset, State.Attached, label)
                }
            }
            is CaptureAnchor.Wire -> {
                val ends = wires[anchor.wire] ?: return missing(anchor.label)
                val label = "${nodes[ends.first]?.label ?: ends.first} → ${nodes[ends.second]?.label ?: ends.second}"
                val route = routes[anchor.wire]
                val middle = route?.trace?.let(::middle)
                if (route != null && middle != null) Attachment(middle + anchor.offset, if (route.id == anchor.wire && route.members.size <= 1) State.Attached else State.Folded, label,
                    if (route.members.size > 1) "${route.members.size} links" else null)
                else {
                    val from = visible(ends.first)
                    val to = visible(ends.second)
                    if (from != null && from.id == to?.id && (from.id != ends.first || from.id != ends.second)) {
                        Attachment(Spot(from.x, from.y + BlueprintEngine.HeaderHeight / 2) + anchor.offset, State.Folded, label, container(from.id))
                    } else missing(anchor.label)
                }
            }
            is CaptureAnchor.Frame -> {
                if (anchor.frame !in scopes && anchor.frame !in notes && anchor.frame !in captures) return missing(anchor.label)
                frames[anchor.frame]?.let { frame -> return Attachment(Spot(frame.x, frame.y) + anchor.offset, State.Attached,
                    scopes[anchor.frame]?.label ?: frame.label) }
                val capture = captures[anchor.frame]
                if (capture != null) {
                    if (capture.id in visiting) return missing(anchor.label)
                    val parent = locate(capture.anchor, visiting + capture.id)
                    if (parent.state == State.Unattached) return missing(anchor.label)
                    return parent.copy(at = parent.at + anchor.offset, state = if (parent.state == State.World) State.Attached else parent.state, label = capture.title)
                }
                val scope = scopes[anchor.frame]
                val note = notes[anchor.frame]
                if (scope == null && note == null) return missing(anchor.label)
                val card = layout.cards[anchor.frame] ?: layout.cards["summary:${anchor.frame}"] ?: scope?.let {
                    nodes.values.asSequence().filter { node -> within(node.scope, scope.id) }.mapNotNull { node -> visible(node.id) }.distinctBy { it.id }.singleOrNull()
                }
                if (card != null) Attachment(Spot(card.x, card.y) + anchor.offset, State.Folded, scope?.label ?: note!!.text, container(card.id))
                else missing(anchor.label)
            }
        }
    }

    private fun nodeTarget(id: String, label: String, offset: Spot): Attachment? {
        frames[id]?.let { return Attachment(Spot(it.x, it.y) + offset, State.Attached, label) }
        val card = visible(id) ?: return null
        val folded = card.id != id
        return Attachment(Spot(card.x, card.y) + offset, if (folded) State.Folded else State.Attached, label, if (folded) container(card.id) else null)
    }

    private fun visible(id: String): Card? = layout.cards[id] ?: owners[id]?.let(layout.cards::get)
        ?: attached[id]?.let { layout.cards[it] ?: owners[it]?.let(layout.cards::get) }

    private fun container(id: String): String = scopes[id]?.label ?: notes[id]?.text ?: captures[id]?.title ?: nodes[id]?.label
        ?: frames.values.firstOrNull { "summary:${it.key}" == id }?.label
        ?: scopes.values.firstOrNull { "summary:${it.id}" == id }?.label ?: id

    private fun within(scope: String, ancestor: String): Boolean {
        var next: String? = scope
        val seen = hashSetOf<String>()
        while (next != null && seen.add(next)) {
            if (next == ancestor) return true
            next = scopes[next]?.parent
        }
        return false
    }

    private fun middle(points: List<Pair<Double, Double>>): Spot? {
        if (points.isEmpty()) return null
        val lengths = points.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }
        var left = lengths.sum() / 2
        for (i in lengths.indices) {
            val length = lengths[i]
            if (left <= length && length > 0) {
                val t = left / length
                return Spot(points[i].first + (points[i + 1].first - points[i].first) * t,
                    points[i].second + (points[i + 1].second - points[i].second) * t)
            }
            left -= length
        }
        return points.last().let { Spot(it.first, it.second) }
    }

    private operator fun Spot.plus(other: Spot) = Spot(x + other.x, y + other.y)
}
