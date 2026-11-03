package dev.shibasis.reaktor.blueprint.canvas

import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintMapLevel
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.PinRef
import dev.shibasis.reaktor.blueprint.editor.Selection

internal class ProjectedMapInteraction(
    private val full: BlueprintLayout<*>,
    private val level: BlueprintMapLevel<*>,
) {
    data class Resolved(val selection: Selection, val pin: Pair<Card, Pin>? = null)

    private val groups = level.frames.values.mapNotNull { group ->
        group.summary?.id?.let { it to group.frame.key }
    }.toMap()
    private val frames = full.frames.associateBy { it.key }
    private val wireIds = full.links.flatMapTo(HashSet()) { it.members } + full.foldedLinks.keys

    fun card(id: String): Resolved? = when {
        id in full.cards -> Resolved(Selection(nodes = setOf(id)))
        id in frames -> Resolved(Selection(notes = setOf(id)))
        id in groups && groups.getValue(id) in frames -> Resolved(Selection(notes = setOf(groups.getValue(id))))
        else -> null
    }

    fun pin(ref: PinRef): Resolved? {
        val forwarded = level.forwardedPorts[ref]
        if (forwarded == null) {
            val original = original(ref) ?: return null
            return Resolved(Selection(nodes = setOf(original.first.id)), original)
        }
        val boundary = level.layout.cards[ref.card]?.pins?.singleOrNull { ref.matches(ref.card, it) } ?: return null
        val originals = forwarded.distinct().map { at ->
            val original = original(at) ?: return null
            if (original.second.type != boundary.type || original.second.provides != boundary.provides) return null
            original
        }
        if (originals.isEmpty()) return null
        if (originals.size == 1) return Resolved(Selection(nodes = setOf(originals.single().first.id)), originals.single())
        val members = level.layout.links.filter(ref::carries).flatMapTo(LinkedHashSet()) { it.members }
        return members.takeIf { it.isNotEmpty() && wireIds.containsAll(it) }?.let { Resolved(Selection(wires = it)) }
    }

    fun wire(link: Link): Resolved? {
        val known = (level.layout.links.asSequence() + full.links.asSequence()).any { candidate ->
            candidate.id == link.id && candidate.from == link.from && candidate.to == link.to &&
                candidate.fromPort == link.fromPort && candidate.toPort == link.toPort && candidate.members == link.members
        }
        val members = link.members.toSet()
        return members.takeIf { known && it.isNotEmpty() && wireIds.containsAll(it) }?.let { Resolved(Selection(wires = it)) }
    }

    fun projectedCard(id: String) = id !in full.cards && id in groups

    fun projectedPin(ref: PinRef) = ref in level.forwardedPorts

    fun projectedWire(link: Link): Boolean = full.links.none { original ->
        original.id == link.id && original.from == link.from && original.to == link.to &&
            original.fromPort == link.fromPort && original.toPort == link.toPort && original.members == link.members
    } || link.members.size > 1

    fun targetBounds(id: String): Bounds? = full.cards[id] ?: frames[id] ?: groups[id]?.let(frames::get)
        ?: full.cardOf[id]?.let(full.cards::get) ?: full.foldedLinks[id]?.let(full.cards::get)

    private fun original(ref: PinRef): Pair<Card, Pin>? {
        val card = full.cards[ref.card] ?: return null
        val pin = card.pins.singleOrNull { ref.matches(card.id, it) } ?: return null
        return card to pin
    }
}
