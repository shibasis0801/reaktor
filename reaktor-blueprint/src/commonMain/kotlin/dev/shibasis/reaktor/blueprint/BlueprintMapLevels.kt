package dev.shibasis.reaktor.blueprint

import kotlin.math.abs

data class BlueprintFrameLevel(
    val frame: Frame,
    val summary: Card?,
    val members: Set<String>,
    val enterZoom: Double,
    val leaveZoom: Double,
    val expanded: Boolean,
    val blend: Double,
)

data class BlueprintMapLevel<K>(
    val layout: BlueprintLayout<K>,
    val frames: Map<String, BlueprintFrameLevel>,
    val expanded: Set<String>,
    val zoom: Double,
    val forwardedPorts: Map<PinRef, List<PinRef>> = emptyMap(),
    val bundles: Boolean = true,
    val memberBlends: Map<String, Double> = emptyMap(),
)

class BlueprintMapLevels<K> private constructor(
    val full: BlueprintLayout<K>,
    private val prepared: Map<String, BlueprintFrameLevel>,
    private val children: Map<String?, List<Frame>>,
    private val parentOf: Map<String, String>,
) {
    fun targetBounds(id: String): Bounds? = full.cards[id] ?: prepared[id]?.frame
        ?: prepared.values.firstOrNull { it.summary?.id == id }?.frame
        ?: full.cardOf[id]?.let(full.cards::get)
        ?: full.foldedLinks[id]?.let(full.cards::get)

    fun at(zoom: Double, previousExpanded: Set<String> = emptySet(), bundles: Boolean = true): BlueprintMapLevel<K> {
        require(zoom.isFinite() && zoom > 0)
        val levels = prepared.mapValues { (key, value) ->
            val expanded = value.summary == null || zoom >= if (key in previousExpanded) value.leaveZoom else value.enterZoom
            val span = value.enterZoom - value.leaveZoom
            value.copy(expanded = expanded, blend = if (value.summary == null) 1.0 else if (span <= 0) {
                if (expanded) 1.0 else 0.0
            } else ((zoom - value.leaveZoom) / span).coerceIn(0.0, 1.0))
        }
        val owner = HashMap<String, String>()
        val frameOwner = HashMap<String, String>()
        val visible = LinkedHashMap<String, Card>()
        val visibleFrames = ArrayList<Frame>()
        fun visit(frame: Frame, folded: String?) {
            val level = levels.getValue(frame.key)
            val summary = folded ?: level.summary?.takeUnless { level.expanded }?.id
            if (summary != null) {
                frameOwner[frame.key] = summary
                frame.nodes.filter(full.cards::containsKey).forEach { owner[it] = summary }
                if (folded == null) visible[summary] = level.summary!!
            } else {
                visibleFrames += frame
                frame.nodes.forEach { id -> full.cards[id]?.let { visible[id] = it } }
            }
            children[frame.key].orEmpty().forEach { visit(it, summary) }
        }
        children[null].orEmpty().forEach { visit(it, null) }
        full.cards.forEach { (id, card) -> if (id !in parentOf) visible[id] = card }
        val cardOf = full.cardOf.mapValues { (_, id) -> owner[id] ?: id }
        val hidden = full.foldedLinks.mapValues { (_, id) -> owner[id] ?: id }.toMutableMap()
        val summaries = levels.values.mapNotNull { it.summary }.associateBy { it.id }
        fun endpoint(id: String) = owner[id] ?: frameOwner[id] ?: id
        fun type(id: String, key: String?, provides: Boolean) = full.cards[id]?.pins
            ?.firstOrNull { it.key == key && it.provides == provides }?.type
        data class Corridor(val from: String, val to: String, val family: String, val kind: LinkKind, val reversed: Boolean,
                            val fromType: String?, val toType: String?)
        val corridors = LinkedHashMap<Corridor, MutableList<Link>>()
        val links = ArrayList<Link>()
        full.links.forEach { link ->
            val from = endpoint(link.from)
            val to = endpoint(link.to)
            if (from == to && from in summaries) link.members.forEach { hidden[it] = from }
            else {
                var points = link.trace
                summaries[from]?.takeIf { it.id in visible }?.let { points = outside(points, it) }
                summaries[to]?.takeIf { it.id in visible }?.let { points = outside(points.asReversed(), it).asReversed() }
                val projected = link.copy(from = from, to = to, fromPort = link.fromPort.takeIf { from == link.from },
                    toPort = link.toPort.takeIf { to == link.to }, points = points, curve = link.curve && from == link.from && to == link.to)
                if (from != link.from || to != link.to) corridors.getOrPut(Corridor(from, to, link.family, link.kind, link.reversed,
                    type(link.from, link.fromPort, true), type(link.to, link.toPort, false))) { ArrayList() } += projected
                else links += link
            }
        }
        val originals = full.links.associateBy { it.id }
        val forwarded = LinkedHashMap<PinRef, List<PinRef>>()
        fun boundary(id: String, neighbour: String, group: List<Link>, provides: Boolean, point: Pair<Double, Double>?, wire: String? = null): String? {
            val summary = visible[id]?.takeIf { it.id in summaries } ?: return null
            val refs = group.mapNotNull { projected -> originals[projected.id]?.let { original ->
                val card = if (provides) original.from else original.to
                val key = (if (provides) original.fromPort else original.toPort) ?: return@let null
                full.cards[card]?.pins?.firstOrNull { it.key == key && it.provides == provides }?.let { PinRef(card, key, provides) }
            } }.distinct().sortedWith(compareBy({ it.card }, { it.key }))
            if (refs.isEmpty()) return null
            val types = refs.mapNotNull { ref -> full.cards[ref.card]?.pins?.firstOrNull { ref.matches(ref.card, it) }?.type }.distinct()
            if (types.size != 1) return null
            val family = group.first().family
            val original = originals.getValue(group.first().id)
            val typed = listOf(type(original.from, original.fromPort, true), type(original.to, original.toPort, false))
                .joinToString("") { it?.let { "${it.length}:$it" } ?: "-:" }
            val key = "boundary:${neighbour.length}:$neighbour:${family.length}:$family:types:$typed" + wire?.let { ":wire:${it.length}:$it" }.orEmpty()
            val ref = PinRef(id, key, provides)
            val pin = Pin(key, types.single(), provides, (point?.second ?: summary.y) - summary.y,
                dev.shibasis.reaktor.graph.core.PortWiring.Connected)
            visible[id] = summary.copy(pins = (summary.pins + pin).distinctBy { it.key to it.provides })
            forwarded[ref] = (forwarded[ref].orEmpty() + refs).distinct()
            return key
        }
        val projected = if (bundles) corridors.values.toList() else corridors.values.flatMap { group -> group.map(::listOf) }
        projected.forEach { group ->
            val first = group.minBy { it.id }
            val wire = first.id.takeUnless { bundles }
            val from = boundary(first.from, first.to, group, true, first.points.firstOrNull(), wire) ?: first.fromPort
            val to = boundary(first.to, first.from, group, false, first.points.lastOrNull(), wire) ?: first.toPort
            links += first.copy(fromPort = from, toPort = to,
                members = if (bundles) group.flatMap { it.members }.distinct().sorted() else first.members)
        }
        val layout = if (levels.values.all { it.expanded }) full else full.copy(cards = visible, frames = visibleFrames, links = links,
            cardOf = cardOf, foldedLinks = hidden)
        return BlueprintMapLevel(layout, levels.mapValues { (_, frame) -> frame.copy(summary = frame.summary?.let { visible[it.id] ?: it }) },
            levels.filterValues { it.expanded }.keys, zoom, forwarded, bundles)
    }

    companion object {
        fun <K> prepare(full: BlueprintLayout<K>, titleZoom: (Card) -> Double,
                        summary: (Frame) -> String? = { "summary:${it.key}" }, hysteresis: Double = .12): BlueprintMapLevels<K> {
            require(hysteresis.isFinite() && hysteresis > 0 && hysteresis < 1)
            val frames = full.frames.associateBy { it.key }
            require(frames.size == full.frames.size) { "Duplicate frame identity" }
            val children = full.frames.groupBy { it.parent?.takeIf(frames::containsKey) }
            val parentOf = HashMap<String, String>()
            full.frames.forEach { frame -> frame.nodes.filter(full.cards::containsKey).forEach { id ->
                require(parentOf.put(id, frame.key) == null) { "Card belongs to more than one frame" }
            } }
            val summaries = full.frames.associate { frame -> frame.key to summary(frame)?.let { id ->
                require(id !in full.cards) { "Summary identity belongs to another card" }
                Card(id, frame.x, frame.y, frame.width, frame.height, emptyList(), 0, frame.lane)
            } }
            require(summaries.values.filterNotNull().map { it.id }.distinct().size == summaries.values.count { it != null }) { "Duplicate summary identity" }
            val members = HashMap<String, Set<String>>()
            val pending = HashSet<String>()
            fun descendants(frame: Frame): Set<String> = members.getOrPut(frame.key) {
                require(pending.add(frame.key)) { "Cyclic frame ownership" }
                (frame.nodes.filter(full.cards::containsKey) + children[frame.key].orEmpty().flatMap { descendants(it) }).toSet()
                    .also { pending.remove(frame.key) }
            }
            full.frames.forEach(::descendants)
            val summaryCards = summaries.mapValues { (key, card) -> card?.copy(folded = full.cardOf.values.count { it in members.getValue(key) }) }
            fun minimum(card: Card) = titleZoom(card).also { require(it.isFinite() && it >= 0) }
            val minimums = (full.cards.values + summaryCards.values.filterNotNull()).associate { it.id to minimum(it) }
            fun childZoom(frame: Frame): Double {
                val direct = frame.nodes.mapNotNull(minimums::get)
                val nested = children[frame.key].orEmpty().map { child ->
                    summaryCards[child.key]?.let { minimums.getValue(it.id) } ?: childZoom(child)
                }
                return (direct + nested).maxOrNull() ?: 0.0
            }
            val prepared = full.frames.associate { frame ->
                val cards = members.getValue(frame.key)
                val summaryCard = summaryCards[frame.key]
                val minimum = childZoom(frame)
                frame.key to BlueprintFrameLevel(frame, summaryCard, cards, minimum * (1 + hysteresis), minimum, false, 0.0)
            }
            return BlueprintMapLevels(full, prepared, children, parentOf)
        }

        private fun outside(points: List<Pair<Double, Double>>, box: Bounds): List<Pair<Double, Double>> {
            fun inside(p: Pair<Double, Double>) = p.first >= box.x && p.first <= box.x + box.width && p.second >= box.y && p.second <= box.y + box.height
            if (points.isEmpty() || !inside(points.first())) return points
            for (index in 1 until points.size) {
                val end = points[index]
                if (inside(end)) continue
                val start = points[index - 1]
                val dx = end.first - start.first
                val dy = end.second - start.second
                val candidates = ArrayList<Double>()
                if (abs(dx) > 1e-9) {
                    candidates += (box.x - start.first) / dx
                    candidates += (box.x + box.width - start.first) / dx
                }
                if (abs(dy) > 1e-9) {
                    candidates += (box.y - start.second) / dy
                    candidates += (box.y + box.height - start.second) / dy
                }
                val t = candidates.filter { it >= 0 && it <= 1 }.filter { t ->
                    val x = start.first + dx * t
                    val y = start.second + dy * t
                    x >= box.x - 1e-7 && x <= box.x + box.width + 1e-7 && y >= box.y - 1e-7 && y <= box.y + box.height + 1e-7
                }.maxOrNull() ?: return points.drop(index)
                return listOf((start.first + dx * t) to (start.second + dy * t)) + points.drop(index)
            }
            return emptyList()
        }
    }
}
