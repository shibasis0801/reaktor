package dev.shibasis.reaktor.blueprint

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

object BlueprintEngine {
    // Retain primitive coordinates rather than a Pair and two boxed Doubles for every route sample.
    private class PackedPoints(val coordinates: DoubleArray) : AbstractList<Pair<Double, Double>>() {
        override val size get() = coordinates.size / 2
        override fun get(index: Int) = coordinates[index * 2] to coordinates[index * 2 + 1]
    }

    private fun transformPoints(points: List<Pair<Double, Double>>, x: (Double) -> Double, y: (Double) -> Double): List<Pair<Double, Double>> {
        val coordinates = DoubleArray(points.size * 2)
        val packed = (points as? PackedPoints)?.coordinates
        points.indices.forEach { index ->
            val point = if (packed == null) points[index] else null
            coordinates[index * 2] = x(packed?.get(index * 2) ?: point!!.first)
            coordinates[index * 2 + 1] = y(packed?.get(index * 2 + 1) ?: point!!.second)
        }
        return PackedPoints(coordinates)
    }

    const val CardWidth = 272.0
    const val HeaderHeight = 46.0
    const val RowHeight = 18.0
    const val FooterHeight = 8.0
    const val FrameTop = 40.0
    const val FrameSide = 18.0
    const val GroupGap = 64.0
    const val LayerGap = 72.0
    const val CardGap = 18.0
    const val KitVersion = "blueprint-kit-1"
    const val ElkVersion = "0.12.0"
    private const val WrapAt = 12
    private const val ColumnGap = 40.0

    internal class Block(val group: BlueprintGroup, val layout: FrameLayout) {
        val width = layout.width + 2 * FrameSide
        val height = layout.height + FrameTop + FrameSide
    }

    fun <K> layout(
        key: K,
        groups: List<BlueprintGroup>,
        edges: List<BlueprintEdge>,
        aspect: Double,
        layouter: FrameLayouter = DefaultFrameLayouter,
        options: LayoutOptions = LayoutOptions(),
    ): BlueprintLayout<K> = BlueprintPlan(groups, edges, options).let { plan -> plan.assemble(key, plan.laid { requests -> requests.map(layouter::lay) }, aspect) }

    fun rowsOf(node: BlueprintNode): Int = node.rows ?: maxOf(rowsUsed(node.inputs), rowsUsed(node.outputs))

    fun cardHeight(rows: Int): Double = HeaderHeight + rows * RowHeight + if (rows > 0) FooterHeight else 0.0

    fun cardHeight(node: BlueprintNode): Double = cardHeight(rowsOf(node))

    fun pinY(row: Int): Double = HeaderHeight + row * RowHeight + RowHeight / 2

    fun anchor(cards: Map<String, Card>, id: String, port: String?, provides: Boolean): Pair<Double, Double> =
        anchorIn(cards.getValue(id), port, provides, HeaderHeight / 2)

    fun bezier(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> {
        val (sx, sy) = from
        val (ex, ey) = to
        val reach = maxOf(90.0, abs(ex - sx) * 0.45).let { if (ex < sx) it.coerceAtMost(320.0) else it }
        return PackedPoints(doubleArrayOf(sx, sy, sx + reach, sy, ex - reach, ey, ex, ey))
    }

    fun sample(bezier: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val (sx, sy) = bezier[0]
        val (ax, ay) = bezier[1]
        val (bx, by) = bezier[2]
        val (ex, ey) = bezier[3]
        val coordinates = DoubleArray(50)
        (0..24).forEach { step ->
            val t = step / 24.0
            val u = 1 - t
            coordinates[step * 2] = u * u * u * sx + 3 * u * u * t * ax + 3 * u * t * t * bx + t * t * t * ex
            coordinates[step * 2 + 1] = u * u * u * sy + 3 * u * u * t * ay + 3 * u * t * t * by + t * t * t * ey
        }
        return PackedPoints(coordinates)
    }

    fun curve(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> = sample(bezier(from, to))

    fun balancedLanes(heights: List<Double>, aspect: Double = 1.4): List<Int> {
        if (heights.isEmpty()) return emptyList()
        val tall = heights.map { ceil(it) + CardGap }
        val total = tall.sum()
        var best = List(heights.size) { 0 }
        var score = Double.POSITIVE_INFINITY
        for (columns in 1..tall.size) {
            var low = tall.max()
            var high = total
            while (low < high) {
                val middle = floor((low + high) / 2)
                if (columnsWithin(tall, middle).last() < columns) high = middle else low = middle + 1
            }
            val lanes = columnsWithin(tall, low)
            val filled = DoubleArray(lanes.last() + 1)
            val counts = IntArray(filled.size)
            lanes.forEachIndexed { index, lane -> filled[lane] += tall[index]; counts[lane] += 1 }
            if (counts.any { it > WrapAt } && columns < tall.size) continue
            val width = filled.size * CardWidth + (filled.size - 1) * LayerGap
            val next = abs(ln(width / maxOf(filled.max() - CardGap, 1.0) / aspect))
            if (next < score - 1e-9) {
                score = next
                best = lanes
            }
        }
        return best
    }

    private fun columnsWithin(tall: List<Double>, limit: Double): List<Int> {
        var lane = 0
        var filled = 0.0
        return tall.map { height ->
            if (filled > 0 && filled + height > limit) {
                lane += 1
                filled = 0.0
            }
            filled += height
            lane
        }
    }

    private fun rowsUsed(specs: List<PinSpec>): Int = specs.withIndex().maxOfOrNull { (index, spec) -> (spec.row ?: index) + 1 } ?: 0

    internal fun pinsOf(node: BlueprintNode): List<Pin> =
        node.inputs.mapIndexed { index, spec -> Pin(spec.key, spec.type, false, pinY(spec.row ?: index), spec.wiring) } +
            node.outputs.mapIndexed { index, spec -> Pin(spec.key, spec.type, true, pinY(spec.row ?: index), spec.wiring) }

    internal fun anchorIn(box: Bounds, port: String?, provides: Boolean, middle: Double): Pair<Double, Double> {
        val pin = port?.let { name -> (box as? Card)?.pins?.firstOrNull { it.provides == provides && it.key == name } }
        return (if (provides) box.x + box.width else box.x) to box.y + (pin?.y ?: middle)
    }

    internal fun translate(points: List<Pair<Double, Double>>, dx: Double, dy: Double) = transformPoints(points, { it + dx }, { it + dy })

    internal fun packed(flat: DoubleArray, reversed: Boolean): List<Pair<Double, Double>> {
        if (!reversed) return PackedPoints(flat)
        val count = flat.size / 2
        return PackedPoints(DoubleArray(count * 2) { at -> flat[(count - 1 - at / 2) * 2 + at % 2] })
    }

    internal fun join(edges: List<BlueprintEdge>, ends: Map<String, List<Pin>>): List<FrameEdge> {
        val joined = LinkedHashMap<String, Pair<FrameEdge, MutableList<String>>>()
        edges.forEach { edge ->
            val fromPins = ends[edge.from]
            val toPins = ends[edge.to]
            if (edge.from == edge.to || fromPins == null || toPins == null) return@forEach
            val fromPort = edge.fromPort?.takeIf { key -> fromPins.any { it.provides && it.key == key } }
            val toPort = edge.toPort?.takeIf { key -> toPins.any { !it.provides && it.key == key } }
            val id = when (edge.kind) {
                LinkKind.Route -> "route:${edge.from}>${edge.to}"
                LinkKind.Wire -> "${edge.from}>${fromPort.orEmpty()}|${edge.to}<${toPort.orEmpty()}${if (edge.family.isEmpty()) "" else "#${edge.family}"}"
            }
            joined.getOrPut(id) { FrameEdge(id, edge.from, edge.to, fromPort, toPort, edge.kind, edge.reversed, emptyList(), edge.family, edge.curved) to ArrayList() }.second += edge.id
        }
        return joined.values.map { (edge, members) -> edge.copy(members = members) }
    }

    internal fun hintPairs(curved: List<FrameEdge>, routed: List<FrameEdge>, minimum: Int): List<Pair<String, String>> {
        val linked = routed.flatMapTo(HashSet()) { listOf("${it.from}|${it.to}", "${it.to}|${it.from}") }
        class Tally(val low: String, val high: String) { var forward = 0; var backward = 0 }
        val pairs = LinkedHashMap<String, Tally>()
        curved.forEach { edge ->
            val forward = edge.from < edge.to
            val low = if (forward) edge.from else edge.to
            val high = if (forward) edge.to else edge.from
            val tally = pairs.getOrPut("$low|$high") { Tally(low, high) }
            if (forward) tally.forward += edge.members.size else tally.backward += edge.members.size
        }
        return pairs.filter { (key, tally) -> tally.forward + tally.backward >= minimum && key !in linked }
            .map { (_, tally) -> if (tally.forward >= tally.backward) tally.low to tally.high else tally.high to tally.low }
    }

    internal fun wrapLayers(flat: FrameLayout, framed: Set<String>): FrameLayout {
        if (flat.cards.isEmpty()) return flat
        val layers = flat.cards.values.groupBy { it.x.toInt() }.entries.sortedBy { it.key }.map { it.value }
        if (layers.none { it.size > WrapAt }) return flat
        val cards = LinkedHashMap(flat.cards)
        val moved = hashSetOf<String>()
        val shifts = mutableListOf<Pair<Double, Double>>()
        var shifted = 0.0
        layers.forEach { layer ->
            val span = layer.maxOf { it.width }
            val right = layer.first().x + span
            if (shifted != 0.0) layer.forEach { card -> cards[card.id] = card.copy(x = card.x + shifted) }
            if (layer.size <= WrapAt) return@forEach
            val ordered = layer.sortedBy { it.y }
            val rows = ceil(sqrt(ordered.size * 2.0)).toInt()
            val columns = (ordered.size + rows - 1) / rows
            val top = ordered.first().y
            ordered.chunked(rows).forEachIndexed { column, stack ->
                var y = top
                stack.forEach { card ->
                    cards[card.id] = card.copy(x = card.x + shifted + column * (span + ColumnGap), y = y)
                    moved += card.id
                    y += card.height + CardGap
                }
            }
            val widened = (columns - 1) * (span + ColumnGap)
            shifts += right to widened
            shifted += widened
        }
        fun shift(x: Double) = x + shifts.filter { (from, _) -> x >= from }.sumOf { it.second }
        fun anchor(id: String, port: String?, provides: Boolean) = anchorIn(cards.getValue(id), port, provides, if (id in framed) FrameTop / 2 else HeaderHeight / 2)
        val links = flat.links.map { link ->
            if (link.from in moved || link.to in moved) link.copy(points = bezier(anchor(link.from, link.fromPort, true), anchor(link.to, link.toPort, false)), curve = true)
            else link.copy(points = transformPoints(link.points, ::shift) { it })
        }
        val right = flat.width - flat.cards.values.maxOf { it.x + it.width }
        val bottom = flat.height - flat.cards.values.maxOf { it.y + it.height }
        return FrameLayout(cards, links, cards.values.maxOf { it.x + it.width } + right, cards.values.maxOf { it.y + it.height } + bottom, flat.frames, flat.failure)
    }

    internal fun pack(blocks: List<Block>, aspect: Double): List<Pair<Block, Pair<Double, Double>>> {
        val (loose, own) = blocks.partition { it.group.loose }
        fun shelves(group: List<Block>, limit: Double): List<List<Block>> = group.fold(mutableListOf<MutableList<Block>>()) { rows, block ->
            rows.firstOrNull { row -> row.sumOf { it.width } + GroupGap * row.size + block.width <= limit }?.add(block) ?: rows.add(mutableListOf(block))
            rows
        }
        fun rows(limit: Double) = shelves(own, limit) + shelves(loose, limit)
        fun width(rows: List<List<Block>>) = rows.maxOfOrNull { row -> row.sumOf { it.width } + GroupGap * (row.size - 1) } ?: 0.0
        fun height(rows: List<List<Block>>) = rows.sumOf { row -> row.maxOf { it.height } } + GroupGap * (rows.size - 1).coerceAtLeast(0)
        fun span(group: List<Block>) = group.sumOf { it.width } + GroupGap * (group.size - 1).coerceAtLeast(0)
        val widest = blocks.maxOfOrNull { it.width } ?: 0.0
        val longest = maxOf(span(own), span(loose))
        val chosen = (0..60).map { widest + (longest - widest) * it / 60.0 }.distinct().map(::rows).minByOrNull { rows ->
            abs(ln(width(rows) / height(rows).coerceAtLeast(1.0) / aspect)) + 0.015 * rows.size
        }.orEmpty()
        val placed = mutableListOf<Pair<Block, Pair<Double, Double>>>()
        var y = 0.0
        chosen.forEach { row ->
            var x = 0.0
            row.forEach { block ->
                placed += block to (x to y)
                x += block.width + GroupGap
            }
            y += row.maxOf { it.height } + GroupGap
        }
        return placed
    }
}

internal val BlueprintNode.inner: BlueprintGroup? get() = group?.takeIf { it.nodes.isNotEmpty() }

class BlueprintPlan(val groups: List<BlueprintGroup>, edges: List<BlueprintEdge>, val options: LayoutOptions = LayoutOptions()) {
    private val pins = HashMap<String, List<Pin>>()
    private val groupOf = HashMap<String, String>()
    private val rootOf = HashMap<String, String>()
    private val parentOf = HashMap<String, String>()
    private val frames = LinkedHashMap<String, BlueprintGroup>()
    private val inside = HashMap<String, MutableList<FrameEdge>>()
    private val hinted = HashMap<String, MutableList<FrameEdge>>()
    private val joined: List<FrameEdge>
    val stages: List<List<String>>

    init {
        val depth = HashMap<String, Int>()
        val nested = ArrayList<String>()
        fun walk(group: BlueprintGroup, root: String): Int {
            frames[group.key] = group
            var deepest = -1
            group.nodes.forEach { node ->
                groupOf[node.id] = group.key
                rootOf[node.id] = root
                val inner = node.inner
                if (inner == null) pins[node.id] = BlueprintEngine.pinsOf(node)
                else {
                    parentOf[node.id] = group.key
                    nested += node.id
                    deepest = maxOf(deepest, walk(inner, root))
                }
            }
            depth[group.key] = deepest + 1
            return deepest + 1
        }
        groups.forEach { walk(it, it.key) }
        joined = BlueprintEngine.join(edges, pins + nested.associateWith { emptyList() })
        fun chain(id: String) = generateSequence(groupOf[id]) { parentOf[it] }
        fun memberIn(group: String, id: String): String? {
            var cursor: String? = id
            while (cursor != null) {
                val holder = groupOf[cursor]
                if (holder == group) return cursor
                cursor = holder?.takeIf { it in parentOf }
            }
            return null
        }
        joined.forEach { edge ->
            val above = chain(edge.to).toList()
            val common = chain(edge.from).firstOrNull { it in above } ?: return@forEach
            if (groupOf[edge.from] == common && groupOf[edge.to] == common) {
                inside.getOrPut(common) { ArrayList() } += edge
                if (edge.curved) hinted.getOrPut(common) { ArrayList() } += edge
                return@forEach
            }
            val from = memberIn(common, edge.from)
            val to = memberIn(common, edge.to)
            if (from != null && to != null && from != to) hinted.getOrPut(common) { ArrayList() } += edge.copy(from = from, to = to, curved = true)
        }
        val laid = frames.values.filter { it.nodes.isNotEmpty() }
        val top = laid.maxOfOrNull { depth[it.key] ?: 0 } ?: 0
        stages = (0..top).map { stage -> laid.filter { depth[it.key] == stage }.map { it.key } }.filter { it.isNotEmpty() }
    }

    inline fun laid(lay: (List<FrameRequest>) -> List<FrameResult>): Map<String, FrameLayout> {
        val flats = LinkedHashMap<String, FrameLayout>()
        stages.forEach { stage ->
            val requests = stage.map { request(it, flats) }
            lay(requests).forEachIndexed { index, result -> flats[requests[index].key] = read(requests[index], result, flats) }
        }
        return flats
    }

    fun request(key: String, flats: Map<String, FrameLayout>): FrameRequest {
        val group = frames.getValue(key)
        val routed = inside[key].orEmpty().filter { !it.curved }
        val hints = BlueprintEngine.hintPairs(hinted[key].orEmpty(), routed, options.hints)
        val indexOf = HashMap<String, Int>(group.nodes.size * 2).also { map -> group.nodes.forEachIndexed { index, node -> map[node.id] = index } }
        val used = Array(group.nodes.size) { HashSet<String>() }
        fun port(id: String, port: String?, provides: Boolean): String {
            val index = indexOf.getValue(id)
            val pin = if (port == null) -1 else pins[id].orEmpty().indexOfFirst { it.provides == provides && it.key == port }
            return "n$index${if (provides) "e" else "w"}${if (pin >= 0) pin.toString() else ""}".also { used[index] += it }
        }
        val ends = group.mode == Lanes.Ends
        val flipped = HashSet<Int>()
        val incoming = HashSet<Int>()
        val outgoing = HashSet<Int>()
        fun line(at: Int, from: String, fromPort: String?, to: String, toPort: String?): ElkLine {
            val source = port(from, fromPort, true)
            val target = port(to, toPort, false)
            val back = ends && group.nodes[indexOf.getValue(from)].lane > group.nodes[indexOf.getValue(to)].lane
            if (back) flipped += at
            outgoing += indexOf.getValue(if (back) to else from)
            incoming += indexOf.getValue(if (back) from else to)
            return if (back) ElkLine("e$at", target, source) else ElkLine("e$at", source, target)
        }
        val lines = routed.mapIndexed { at, edge -> line(at, edge.from, edge.fromPort, edge.to, edge.toPort) } +
            hints.mapIndexed { at, (from, to) -> line(routed.size + at, from, null, to, null) }
        val first = group.nodes.minOfOrNull { it.lane }
        val last = group.nodes.maxOfOrNull { it.lane }
        val boxes = group.nodes.mapIndexed { index, node ->
            val framed = node.inner != null
            val flat = flats[node.id]
            val width = if (framed) (flat?.width ?: 0.0) + 2 * BlueprintEngine.FrameSide else BlueprintEngine.CardWidth
            val height = if (framed) (flat?.height ?: 0.0) + BlueprintEngine.FrameTop + BlueprintEngine.FrameSide else BlueprintEngine.cardHeight(node)
            val middle = if (framed) BlueprintEngine.FrameTop / 2 else BlueprintEngine.HeaderHeight / 2
            val ports = ArrayList<ElkPin>()
            fun wanted(name: String) = options.ports == Ports.All || name in used[index]
            pins[node.id].orEmpty().forEachIndexed { at, pin ->
                val name = "n$index${if (pin.provides) "e" else "w"}$at"
                if (wanted(name)) ports += ElkPin(name, if (pin.provides) width else -1.0, pin.y, pin.provides)
            }
            if (wanted("n${index}w")) ports += ElkPin("n${index}w", -1.0, middle, false)
            if (wanted("n${index}e")) ports += ElkPin("n${index}e", width, middle, true)
            val settings = linkedMapOf("elk.portConstraints" to "FIXED_POS")
            if (group.mode == Lanes.Columns) settings["elk.partitioning.partition"] = node.lane.toString()
            if (ends && first != last) when {
                node.lane == first && index !in incoming -> settings["elk.layered.layering.layerConstraint"] = "FIRST"
                node.lane == last && index !in outgoing -> settings["elk.layered.layering.layerConstraint"] = "LAST"
            }
            ElkBox("n$index", width, height, ports, settings)
        }
        val graph = linkedMapOf(
            "elk.algorithm" to "layered",
            "elk.direction" to "RIGHT",
            "elk.edgeRouting" to "ORTHOGONAL",
            "elk.spacing.nodeNode" to "18",
            "elk.layered.spacing.nodeNodeBetweenLayers" to "72",
            "elk.layered.spacing.edgeNodeBetweenLayers" to "16",
            "elk.layered.spacing.edgeEdgeBetweenLayers" to "7",
            "elk.spacing.edgeEdge" to "7",
            "elk.spacing.edgeNode" to "12",
            "elk.randomSeed" to "7",
            "elk.partitioning.activate" to (group.mode == Lanes.Columns).toString(),
            "elk.hierarchyHandling" to "SEPARATE_CHILDREN",
        )
        if (options.ordered) graph["elk.layered.considerModelOrder.strategy"] = "NODES_AND_EDGES"
        if (group.loose) graph["elk.aspectRatio"] = "1.4"
        if (key in parentOf) graph["elk.separateConnectedComponents"] = "false"
        return FrameRequest(key, group, ElkGraph(graph, boxes, lines), routed, flipped)
    }

    fun read(request: FrameRequest, result: FrameResult, flats: Map<String, FrameLayout>): FrameLayout {
        val group = request.group
        val framed = HashSet<String>()
        val items = LinkedHashMap<String, Card>(group.nodes.size * 2)
        group.nodes.forEachIndexed { index, node ->
            val x = result.nodes.getOrElse(index * 2) { 0.0 }
            val y = result.nodes.getOrElse(index * 2 + 1) { 0.0 }
            items[node.id] = if (node.inner == null) Card(node.id, x, y, BlueprintEngine.CardWidth, BlueprintEngine.cardHeight(node), pins[node.id].orEmpty(), node.folded)
            else {
                framed += node.id
                val flat = flats[node.id]
                Card(node.id, x, y, (flat?.width ?: 0.0) + 2 * BlueprintEngine.FrameSide, (flat?.height ?: 0.0) + BlueprintEngine.FrameTop + BlueprintEngine.FrameSide, emptyList(), 0)
            }
        }
        val links = request.routed.mapIndexed { index, edge ->
            Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members,
                BlueprintEngine.packed(result.edges.getOrNull(index) ?: DoubleArray(0), index in request.flipped), reversed = edge.reversed, family = edge.family)
        }
        val wrapped = BlueprintEngine.wrapLayers(FrameLayout(items, links, result.width, result.height, failure = result.failure), framed)
        if (framed.isEmpty()) return wrapped
        val cards = LinkedHashMap<String, Card>()
        val nested = ArrayList<Frame>()
        val inner = ArrayList<Link>()
        wrapped.cards.values.forEach { item ->
            if (item.id !in framed) {
                cards[item.id] = item
                return@forEach
            }
            val flat = flats[item.id]
            val child = frames.getValue(item.id)
            val ox = item.x + BlueprintEngine.FrameSide
            val oy = item.y + BlueprintEngine.FrameTop
            nested += Frame(child.key, child.label, item.x, item.y, item.width, item.height,
                child.nodes.filter { it.inner == null }.mapTo(LinkedHashSet()) { it.id }, child.detail, child.muted, group.key)
            flat?.cards?.values?.forEach { card -> cards[card.id] = card.copy(x = card.x + ox, y = card.y + oy) }
            flat?.links?.forEach { link -> inner += link.copy(points = BlueprintEngine.translate(link.points, ox, oy)) }
            flat?.frames?.forEach { frame -> nested += frame.copy(x = frame.x + ox, y = frame.y + oy) }
        }
        return FrameLayout(cards, wrapped.links + inner, wrapped.width, wrapped.height, nested, result.failure)
    }

    fun <K> assemble(key: K, flats: Map<String, FrameLayout>, aspect: Double): BlueprintLayout<K> {
        val blocks = groups.mapNotNull { group -> flats[group.key]?.takeIf { it.cards.isNotEmpty() }?.let { BlueprintEngine.Block(group, it) } }
        val cards = LinkedHashMap<String, Card>()
        val links = ArrayList<Link>()
        val roots = ArrayList<Frame>()
        val nested = ArrayList<Frame>()
        BlueprintEngine.pack(blocks, aspect).forEach { (block, origin) ->
            val (x, y) = origin
            val ox = x + BlueprintEngine.FrameSide
            val oy = y + BlueprintEngine.FrameTop
            block.layout.cards.values.forEach { card -> cards[card.id] = card.copy(x = card.x + ox, y = card.y + oy) }
            block.layout.links.forEach { link -> links += link.copy(points = BlueprintEngine.translate(link.points, ox, oy)) }
            block.layout.frames.forEach { frame -> nested += frame.copy(x = frame.x + ox, y = frame.y + oy) }
            roots += Frame(block.group.key, block.group.label, x, y, block.width, block.height,
                block.group.nodes.filter { it.inner == null && it.id in cards }.mapTo(LinkedHashSet()) { it.id }, block.group.detail, block.group.muted)
        }
        val boxes = nested.associateBy { it.key }
        val routed = inside.values.flatMapTo(HashSet()) { list -> list.filter { !it.curved }.map { it.id } }
        fun middle(id: String) = if (id in cards) BlueprintEngine.HeaderHeight / 2 else BlueprintEngine.FrameTop / 2
        joined.forEach { edge ->
            if (edge.id in routed) return@forEach
            val from: Bounds = cards[edge.from] ?: boxes[edge.from] ?: return@forEach
            val to: Bounds = cards[edge.to] ?: boxes[edge.to] ?: return@forEach
            links += Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members,
                BlueprintEngine.bezier(BlueprintEngine.anchorIn(from, edge.fromPort, true, middle(edge.from)), BlueprintEngine.anchorIn(to, edge.toPort, false, middle(edge.to))),
                cross = rootOf[edge.from] != rootOf[edge.to], reversed = edge.reversed, family = edge.family, curve = true)
        }
        return BlueprintLayout(key, cards, roots + nested, links, roots.maxOfOrNull { it.x + it.width } ?: 0.0, roots.maxOfOrNull { it.y + it.height } ?: 0.0,
            flats.values.mapNotNull { it.failure })
    }
}
