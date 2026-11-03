package dev.shibasis.reaktor.blueprint

import kotlin.math.roundToInt

object BlueprintRegions {
    internal fun <K> layout(key: K, groups: List<BlueprintGroup>, edges: List<BlueprintEdge>,
                            cardOf: Map<String, String>, opened: Set<String>, layouter: FrameLayouter, aspect: Double = 16.0 / 9.0, options: LayoutOptions = LayoutOptions()): BlueprintLayout<K> {
        val cards = LinkedHashMap<String, Card>()
        val frames = ArrayList<Frame>()
        fun visit(group: BlueprintGroup, parent: String?, lane: Int) {
            val direct = group.nodes.filter { it.group == null }
            frames += Frame(group.key, group.label, 0.0, 0.0, 0.0, 0.0, direct.mapTo(LinkedHashSet()) { it.id },
                group.detail, group.muted, parent, group.mode, group.loose, lane)
            group.nodes.forEach { node ->
                val child = node.group
                if (child != null) visit(child, group.key, node.lane)
                else cards[node.id] = Card(node.id, 0.0, 0.0, BlueprintEngine.CardWidth, BlueprintEngine.cardHeight(node),
                    BlueprintEngine.pinsOf(node), node.folded, node.lane)
            }
        }
        groups.forEach { visit(it, null, 0) }
        val links = edges.map { Link(it.id, it.from, it.to, it.fromPort, it.toPort, it.kind, listOf(it.id), emptyList(),
            reversed = it.reversed, family = it.family, curve = it.curved) }
        val logical = BlueprintLayout(key, cards, frames, links, 0.0, 0.0, cardOf = cardOf)
        val projected = overview(logical, key, opened, layouter, aspect, options)
        return if (projected.width == 0.0) BlueprintEngine.layout(key, groups, edges, aspect, layouter, options).copy(cardOf = cardOf)
        else projected
    }

    fun <K> overview(scene: BlueprintLayout<*>, key: K, opened: Set<String> = emptySet(),
                     layouter: FrameLayouter = DefaultFrameLayouter, aspect: Double = 16.0 / 9.0, options: LayoutOptions = LayoutOptions()): BlueprintLayout<K> {
        val children = scene.frames.groupBy { it.parent }
        val regions = LinkedHashMap<String, String>()
        val members = LinkedHashMap<String, String>()
        fun visit(frame: Frame, root: Boolean) {
            val direct = frame.nodes.count { it in scene.cards }
            val nested = children[frame.key].orEmpty()
            if (!root && frame.key !in opened && (direct > 0 || nested.size != 1)) {
                regions[frame.key] = "summary:${frame.key}"
                return
            }
            if (direct > 8 && frame.key !in opened) members[frame.key] = "summary:members:${frame.key}"
            nested.forEach { visit(it, root && direct == 0 && nested.size == 1) }
        }
        children[null].orEmpty().forEach { visit(it, true) }
        return of(scene, key, regions, layouter = layouter, members = members, aspect = aspect, options = options)
    }

    fun <K> of(scene: BlueprintLayout<*>, key: K, regions: Map<String, String>, cardOf: Map<String, String> = scene.cardOf,
               layouter: FrameLayouter = DefaultFrameLayouter, members: Map<String, String> = emptyMap(), aspect: Double = 16.0 / 9.0, options: LayoutOptions = LayoutOptions(), bodies: Map<String, BlueprintBody> = emptyMap()): BlueprintLayout<K> {
        val frames = scene.frames.associateBy { it.key }
        require(frames.size == scene.frames.size) { "Duplicate frame identity" }
        fun path(frame: Frame): List<Frame> {
            val seen = HashSet<String>()
            return generateSequence(frame) { it.parent?.let(frames::get) }.onEach {
                require(seen.add(it.key)) { "Cyclic frame ownership" }
            }.toList().asReversed()
        }
        val paths = scene.frames.associate { it.key to path(it) }
        val selected = scene.frames.filter { it.key in regions && paths.getValue(it.key).none { parent -> parent.key != it.key && parent.key in regions } }
        if (selected.isEmpty() && members.isEmpty() && bodies.isEmpty()) return BlueprintLayout(key, scene.cards, scene.frames, scene.links, scene.width, scene.height, scene.layoutFailures, cardOf, scene.foldedLinks)
        val summaries = selected.associate { it.key to regions.getValue(it.key) }
        val direct = members.filterKeys { frame -> frame in frames && paths.getValue(frame).none { it.key in summaries } }
        val summaryIds = (summaries.values + direct.values).toHashSet()
        require(summaryIds.size == summaries.size + direct.size) { "Duplicate summary identity" }
        val frameOwner = paths.mapValues { (_, parents) -> parents.firstNotNullOfOrNull { summaries[it.key] } }
        val parentOf = HashMap<String, String>()
        val owner = HashMap<String, String>()
        scene.frames.forEach { frame -> frame.nodes.forEach { id ->
            if (id in scene.cards) {
                require(parentOf.put(id, frame.key) == null) { "Card belongs to more than one frame" }
                (frameOwner[frame.key] ?: direct[frame.key])?.let { owner[id] = it }
            }
        } }
        summaryIds.forEach { id -> require(id !in scene.cards || owner[id] == id) { "Summary identity belongs to another card" } }
        val projected = cardOf.mapNotNull { (node, card) ->
            (owner[card] ?: card).takeIf { it in scene.cards || it in summaryIds }?.let { node to it }
        }.toMap()
        val totals = projected.values.groupingBy { it }.eachCount()
        val children = scene.frames.groupBy { it.parent?.takeIf(frames::containsKey) }
        fun card(id: String) = scene.cards.getValue(id).let { original ->
            fun pin(pin: Pin) = PinSpec(pin.key, pin.type, pin.wiring,
                ((pin.y - original.pinHeader - BlueprintEngine.RowHeight / 2) / BlueprintEngine.RowHeight).roundToInt())
            BlueprintNode(id, original.lane, original.pins.filterNot { it.provides }.map(::pin), original.pins.filter { it.provides }.map(::pin), original.folded,
                body = bodies[id] ?: BlueprintBody(original.width, (original.height - original.pinHeader).coerceAtLeast(0.0)), pinHeader = original.pinHeader)
        }
        fun group(frame: Frame): BlueprintGroup = BlueprintGroup(frame.key, frame.label,
            listOfNotNull(direct[frame.key]?.let { id -> BlueprintNode(id, 0, folded = totals[id] ?: 0, rows = BlueprintFace.ProseRows, body = bodies[id]) }) +
            frame.nodes.filter { it in scene.cards && it !in owner }.map(::card) + children[frame.key].orEmpty().map { child ->
                summaries[child.key]?.let { id -> BlueprintNode(id, child.lane, folded = totals[id] ?: 0, rows = BlueprintFace.ProseRows, body = bodies[id]) }
                    ?: group(child).let { BlueprintNode(it.key, child.lane, group = it) }
            }, frame.detail, frame.muted, frame.loose, frame.lanes)
        val groups = children[null].orEmpty().map { frame ->
            summaries[frame.key]?.let { id -> BlueprintGroup(frame.key, frame.label,
                listOf(BlueprintNode(id, 0, folded = totals[id] ?: 0, rows = BlueprintFace.ProseRows, body = bodies[id])), frame.detail, frame.muted, frame.loose, frame.lanes) }
                ?: group(frame)
        }.toMutableList()
        val loose = scene.cards.keys.filter { it !in parentOf && it !in owner }
        val looseKey = generateSequence("projection:loose") { "$it:" }.first { it !in frames && it !in scene.cards }
        if (loose.isNotEmpty()) groups += BlueprintGroup(looseKey, "", loose.map(::card), loose = true)
        val sourceLinks = scene.links.associateBy { it.id }
        require(sourceLinks.size == scene.links.size) { "Duplicate link identity" }
        val edges = scene.links.map { link ->
            val from = owner[link.from] ?: frameOwner[link.from] ?: link.from
            val to = owner[link.to] ?: frameOwner[link.to] ?: link.to
            BlueprintEdge(link.id, from, to, link.fromPort.takeIf { from == link.from }, link.toPort.takeIf { to == link.to },
                link.kind, link.reversed, link.family, link.curve)
        }
        val layout = BlueprintEngine.layout(key, groups, edges, aspect, layouter, options)
        val cards = layout.cards.mapValues { (id, placed) -> scene.cards[id]?.takeIf { id !in summaryIds && id !in bodies }
            ?.copy(x = placed.x, y = placed.y) ?: placed }
        val links = layout.links.mapNotNull { link ->
            val members = link.members.flatMap { sourceLinks.getValue(it).members }
            if (link.from == link.to && link.from in summaryIds) null else link.copy(members = members)
        }
        val shown = links.flatMapTo(HashSet()) { it.members }
        val hidden = scene.foldedLinks.mapValues { (_, card) -> owner[card] ?: card }.toMutableMap()
        scene.links.forEach { link ->
            val from = owner[link.from] ?: frameOwner[link.from] ?: link.from
            val to = owner[link.to] ?: frameOwner[link.to] ?: link.to
            if (from == to && from in summaryIds) link.members.filterNot(shown::contains).forEach { hidden[it] = from }
        }
        return layout.copy(cards = cards, frames = layout.frames.filter { it.key !in summaries && it.key != looseKey }, links = links,
            layoutFailures = scene.layoutFailures + layout.layoutFailures, cardOf = projected, foldedLinks = hidden)
    }
}

