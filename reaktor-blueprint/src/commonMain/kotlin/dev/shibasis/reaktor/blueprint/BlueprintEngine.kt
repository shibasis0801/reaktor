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
    const val DoorSize = 4.0
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

    internal class Wrapped(val layout: FrameLayout, val moved: Set<String> = emptySet(), val shift: (Double) -> Double = { it })

    internal fun wrapLayers(flat: FrameLayout, framed: Set<String>): Wrapped {
        if (flat.cards.isEmpty()) return Wrapped(flat)
        val layers = flat.cards.values.groupBy { it.x.toInt() }.entries.sortedBy { it.key }.map { it.value }
        if (layers.none { it.size > WrapAt }) return Wrapped(flat)
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
        return Wrapped(FrameLayout(cards, links, cards.values.maxOf { it.x + it.width } + right, cards.values.maxOf { it.y + it.height } + bottom, flat.frames, flat.failure), moved, ::shift)
    }

    internal fun shifted(points: List<Pair<Double, Double>>, shift: (Double) -> Double) = transformPoints(points, shift) { it }

    internal fun gather(request: FrameRequest, result: FrameResult): FrameResult {
        val boxes = request.graph.boxes
        if (request.doors.isEmpty() || result.nodes.size < boxes.size * 2) return result
        val owner = HashMap<String, Int>(boxes.size * 4)
        boxes.forEachIndexed { at, box -> box.pins.forEach { owner[it.id] = at } }
        val joined = IntArray(boxes.size) { it }
        fun root(v: Int): Int { var r = v; while (joined[r] != r) r = joined[r]; return r }
        val sources = request.graph.lines.map { owner[it.source] }
        request.graph.lines.forEachIndexed { at, line ->
            val a = sources[at] ?: return@forEachIndexed
            val b = owner[line.target] ?: return@forEachIndexed
            val ra = root(a); val rb = root(b)
            if (ra != rb) joined[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val anchored = request.doors.mapTo(HashSet()) { root(it.box) }
        val pieces = boxes.indices.groupBy(::root).filterKeys { it !in anchored }.values.sortedByDescending { it.size }
        if (pieces.isEmpty()) return result
        fun x(at: Int) = result.nodes[at * 2]
        fun y(at: Int) = result.nodes[at * 2 + 1]
        val core = boxes.indices.filter { root(it) in anchored }
        val left = boxes.indices.minOf { x(it) }
        val top = boxes.indices.minOf { y(it) }
        val coreLeft = core.minOf { x(it) }
        val coreTop = core.minOf { y(it) }
        val coreWidth = core.maxOf { x(it) + boxes[it].width } - coreLeft
        val coreBottom = top + core.maxOf { y(it) + boxes[it].height } - coreTop
        val bounds = pieces.map { piece -> doubleArrayOf(piece.minOf { x(it) }, piece.minOf { y(it) }, piece.maxOf { x(it) + boxes[it].width }, piece.maxOf { y(it) + boxes[it].height }) }
        val area = bounds.sumOf { (it[2] - it[0] + LooseGap) * (it[3] - it[1] + LooseGap) }
        val spots = Layered.shelves(bounds.map { (it[2] - it[0]) to (it[3] - it[1]) }, LooseGap, (coreWidth * coreWidth / area).coerceIn(1.0, 8.0))
        val dx = DoubleArray(boxes.size)
        val dy = DoubleArray(boxes.size)
        core.forEach { dx[it] = left - coreLeft; dy[it] = top - coreTop }
        pieces.forEachIndexed { at, piece -> piece.forEach { dx[it] = left + spots[at].first - bounds[at][0]; dy[it] = coreBottom + LooseGap + spots[at].second - bounds[at][1] } }
        val nodes = DoubleArray(result.nodes.size) { result.nodes[it] + if (it / 2 < boxes.size) (if (it % 2 == 0) dx[it / 2] else dy[it / 2]) else 0.0 }
        val edges = result.edges.mapIndexed { at, flat -> val box = sources.getOrNull(at) ?: return@mapIndexed flat; DoubleArray(flat.size) { flat[it] + if (it % 2 == 0) dx[box] else dy[box] } }
        val padRight = (result.width - boxes.indices.maxOf { x(it) + boxes[it].width }).coerceAtLeast(0.0)
        val padBottom = (result.height - boxes.indices.maxOf { y(it) + boxes[it].height }).coerceAtLeast(0.0)
        var right = boxes.indices.maxOf { nodes[it * 2] + boxes[it].width }
        var bottom = boxes.indices.maxOf { nodes[it * 2 + 1] + boxes[it].height }
        edges.forEach { flat -> for (at in flat.indices step 2) { right = maxOf(right, flat[at]); bottom = maxOf(bottom, flat[at + 1]) } }
        return FrameResult(right + padRight, bottom + padBottom, nodes, edges, result.failure)
    }

    private const val LooseGap = 36.0

    internal fun stitch(pieces: List<List<Pair<Double, Double>>>): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>(pieces.sumOf { it.size })
        fun add(point: Pair<Double, Double>) {
            if (points.isNotEmpty() && abs(points.last().first - point.first) < 1e-6 && abs(points.last().second - point.second) < 1e-6) return
            if (points.size >= 2) {
                val a = points[points.size - 2]; val b = points.last()
                if ((abs(a.first - b.first) < 1e-6 && abs(b.first - point.first) < 1e-6) || (abs(a.second - b.second) < 1e-6 && abs(b.second - point.second) < 1e-6)) { points[points.size - 1] = point; return }
            }
            points += point
        }
        pieces.forEach { piece -> piece.forEach(::add) }
        return points
    }

    internal fun pinned(points: List<Pair<Double, Double>>, point: Pair<Double, Double>, atEnd: Boolean): List<Pair<Double, Double>> {
        if (points.size < 2) return points
        val tip = if (atEnd) points.size - 1 else 0
        val near = if (atEnd) points.size - 2 else 1
        if (abs(points[tip].first - point.first) > 2.0 || abs(points[tip].second - point.second) > 2.0) return points
        val moved = points.toMutableList()
        if (points.size > 2 && abs(points[near].second - points[tip].second) < 1e-6) moved[near] = points[near].first to point.second
        moved[tip] = point
        return moved
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
    private val leaving = HashMap<String, LinkedHashMap<String, MutableList<FrameEdge>>>()
    private val arriving = HashMap<String, LinkedHashMap<String, MutableList<FrameEdge>>>()
    private val joined: List<FrameEdge>
    private val overlays: Set<String>
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
        val merged = BlueprintEngine.join(edges, pins + nested.associateWith { emptyList() })
        fun crosses(edge: FrameEdge) = rootOf[edge.from] != rootOf[edge.to]
        overlays = if (merged.none { it.kind == LinkKind.Wire && crosses(it) }) emptySet()
            else merged.filter { it.kind == LinkKind.Route && crosses(it) }.mapTo(HashSet()) { it.id }
        joined = merged.map { edge -> if (edge.id in overlays) edge.copy(curved = true) else edge }
        fun chain(id: String) = generateSequence(groupOf[id]) { parentOf[it] }
        joined.forEach { edge ->
            val source = rootOf[edge.from] ?: return@forEach
            val target = rootOf[edge.to] ?: return@forEach
            if (edge.curved || source == target) return@forEach
            chain(edge.from).forEach { leaving.getOrPut(it) { LinkedHashMap() }.getOrPut(target) { ArrayList() } += edge }
            chain(edge.to).forEach { arriving.getOrPut(it) { LinkedHashMap() }.getOrPut(source) { ArrayList() } += edge }
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

    private fun memberIn(group: String, id: String): String? {
        var cursor: String? = id
        while (cursor != null) {
            val holder = groupOf[cursor]
            if (holder == group) return cursor
            cursor = holder?.takeIf { it in parentOf }
        }
        return null
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
        val lines = ArrayList<ElkLine>()
        routed.forEachIndexed { at, edge -> lines += line(at, edge.from, edge.fromPort, edge.to, edge.toPort) }
        hints.forEachIndexed { at, (from, to) -> lines += line(routed.size + at, from, null, to, null) }
        val doors = ArrayList<FrameDoor>()
        val passes = ArrayList<FramePass>()
        val gates = ArrayList<ElkBox>()
        fun doorway(member: String, root: String, exit: Boolean): String {
            val index = indexOf.getValue(member)
            val inner = flats[member]
            val keys = (if (exit) inner?.exits else inner?.entries)?.keys?.toList().orEmpty()
            return "n$index${if (exit) "o" else "i"}${keys.indexOf(root)}".also { used[index] += it }
        }
        fun open(root: String, exit: Boolean, edges: List<FrameEdge>) {
            val name = "d${doors.size}"
            val pin = name + if (exit) "w" else "e"
            doors += FrameDoor(root, exit, group.nodes.size + doors.size)
            gates += ElkBox(name, BlueprintEngine.DoorSize, BlueprintEngine.DoorSize, listOf(ElkPin(pin, if (exit) -1.0 else BlueprintEngine.DoorSize, BlueprintEngine.DoorSize / 2, !exit)),
                linkedMapOf("elk.portConstraints" to "FIXED_POS", "elk.layered.layering.layerConstraint" to if (exit) "LAST_SEPARATE" else "FIRST_SEPARATE"))
            val grouped = LinkedHashMap<Triple<String, String?, Boolean>, MutableList<String>>()
            edges.forEach { edge ->
                val end = if (exit) edge.from else edge.to
                val member = memberIn(key, end) ?: return@forEach
                val inner = member != end
                grouped.getOrPut(Triple(member, if (inner) null else if (exit) edge.fromPort else edge.toPort, inner)) { ArrayList() } += edge.id
            }
            grouped.forEach { (at, ids) ->
                val (member, pinKey, inner) = at
                val end = if (inner) doorway(member, root, exit) else port(member, pinKey, exit)
                passes += FramePass(lines.size, doors.size - 1, member, pinKey, inner, ids)
                lines += if (exit) ElkLine("p${lines.size}", end, pin) else ElkLine("p${lines.size}", pin, end)
            }
        }
        arriving[key]?.forEach { (root, edges) -> open(root, false, edges) }
        leaving[key]?.forEach { (root, edges) -> open(root, true, edges) }
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
            flat?.entries?.values?.forEachIndexed { at, (_, y) -> if (wanted("n${index}i$at")) ports += ElkPin("n${index}i$at", -1.0, BlueprintEngine.FrameTop + y, false) }
            flat?.exits?.values?.forEachIndexed { at, (_, y) -> if (wanted("n${index}o$at")) ports += ElkPin("n${index}o$at", width, BlueprintEngine.FrameTop + y, true) }
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
        if (key in parentOf || doors.isNotEmpty()) graph["elk.separateConnectedComponents"] = "false"
        return FrameRequest(key, group, ElkGraph(graph, boxes + gates, lines), routed, flipped, doors, passes)
    }

    fun read(request: FrameRequest, laid: FrameResult, flats: Map<String, FrameLayout>): FrameLayout {
        val result = BlueprintEngine.gather(request, laid)
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
        val wrap = BlueprintEngine.wrapLayers(FrameLayout(items, links, result.width, result.height, failure = result.failure), framed)
        val wrapped = wrap.layout
        val gates = request.doors.map { door ->
            val x = wrap.shift(result.nodes.getOrElse(door.box * 2) { 0.0 })
            val y = result.nodes.getOrElse(door.box * 2 + 1) { 0.0 } + BlueprintEngine.DoorSize / 2
            (if (door.exit) x else x + BlueprintEngine.DoorSize) to y
        }
        val exits = LinkedHashMap<String, Pair<Double, Double>>()
        val entries = LinkedHashMap<String, Pair<Double, Double>>()
        request.doors.forEachIndexed { at, door -> (if (door.exit) exits else entries)[door.root] = gates[at] }
        val departures = HashMap<String, List<Pair<Double, Double>>>()
        val arrivals = HashMap<String, List<Pair<Double, Double>>>()
        request.passes.forEach { pass ->
            val door = request.doors[pass.door]
            val gate = gates[pass.door]
            val box = wrapped.cards[pass.member] ?: return@forEach
            val child = flats[pass.member]
            val end = if (pass.inner) {
                val at = (if (door.exit) child?.exits else child?.entries)?.get(door.root) ?: return@forEach
                (if (door.exit) box.x + box.width else box.x) to box.y + BlueprintEngine.FrameTop + at.second
            } else BlueprintEngine.anchorIn(box, pass.port, door.exit, if (pass.member in framed) BlueprintEngine.FrameTop / 2 else BlueprintEngine.HeaderHeight / 2)
            val raw = result.edges.getOrNull(pass.line)
            val route = if (pass.member in wrap.moved || raw == null || raw.size < 4) BlueprintEngine.sample(if (door.exit) BlueprintEngine.bezier(end, gate) else BlueprintEngine.bezier(gate, end))
            else BlueprintEngine.pinned(BlueprintEngine.pinned(BlueprintEngine.shifted(BlueprintEngine.packed(raw, false), wrap.shift), gate, door.exit), end, !door.exit)
            val ox = box.x + BlueprintEngine.FrameSide
            val oy = box.y + BlueprintEngine.FrameTop
            pass.edges.forEach { id ->
                if (door.exit) {
                    val inner = if (pass.inner) child?.departures?.get(id) ?: return@forEach else null
                    departures[id] = if (inner == null) route else BlueprintEngine.stitch(listOf(BlueprintEngine.translate(inner, ox, oy), route))
                } else {
                    val inner = if (pass.inner) child?.arrivals?.get(id) ?: return@forEach else null
                    arrivals[id] = if (inner == null) route else BlueprintEngine.stitch(listOf(route, BlueprintEngine.translate(inner, ox, oy)))
                }
            }
        }
        if (framed.isEmpty()) return FrameLayout(wrapped.cards, wrapped.links, wrapped.width, wrapped.height, wrapped.frames, wrapped.failure, exits, entries, departures, arrivals)
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
        return FrameLayout(cards, wrapped.links + inner, wrapped.width, wrapped.height, nested, result.failure, exits, entries, departures, arrivals)
    }

    fun <K> assemble(key: K, flats: Map<String, FrameLayout>, aspect: Double): BlueprintLayout<K> {
        val blocks = groups.mapNotNull { group -> flats[group.key]?.takeIf { it.cards.isNotEmpty() }?.let { BlueprintEngine.Block(group, it) } }
        val cards = LinkedHashMap<String, Card>()
        val links = ArrayList<Link>()
        val roots = ArrayList<Frame>()
        val nested = ArrayList<Frame>()
        val routed = inside.values.flatMapTo(HashSet()) { list -> list.filter { !it.curved }.map { it.id } }
        val keyed = blocks.associateBy { it.group.key }
        val crossing = joined.filter { it.id !in routed && it.id !in overlays && rootOf[it.from] != rootOf[it.to] && rootOf[it.from] in keyed && rootOf[it.to] in keyed }
        val flow = if (crossing.isEmpty()) null else flow(blocks, keyed, crossing, aspect)
        val placement = flow?.let { blocks.map { block -> block to it.origins.getValue(block.group.key) } } ?: BlueprintEngine.pack(blocks, aspect)
        val origins = placement.associate { (block, origin) -> block.group.key to origin }
        placement.forEach { (block, origin) ->
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
        fun middle(id: String) = if (id in cards) BlueprintEngine.HeaderHeight / 2 else BlueprintEngine.FrameTop / 2
        joined.forEach { edge ->
            if (edge.id in routed) return@forEach
            val from: Bounds = cards[edge.from] ?: boxes[edge.from] ?: return@forEach
            val to: Bounds = cards[edge.to] ?: boxes[edge.to] ?: return@forEach
            val start = BlueprintEngine.anchorIn(from, edge.fromPort, true, middle(edge.from))
            val end = BlueprintEngine.anchorIn(to, edge.toPort, false, middle(edge.to))
            val source = rootOf[edge.from]
            val target = rootOf[edge.to]
            val trunk = flow?.trunks?.get(source to target)?.takeIf { !edge.curved && it.size >= 2 }
            val departure = trunk?.let { leg(keyed, origins, source, edge.id, true) }
            val arrival = trunk?.let { leg(keyed, origins, target, edge.id, false) }
            links += if (trunk != null && departure != null && arrival != null) Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members,
                BlueprintEngine.stitch(listOf(departure, trunk, arrival)), cross = true, reversed = edge.reversed, family = edge.family)
            else Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members, BlueprintEngine.bezier(start, end),
                cross = source != target, reversed = edge.reversed, family = edge.family, curve = true)
        }
        val width = maxOf(roots.maxOfOrNull { it.x + it.width } ?: 0.0, flow?.width ?: 0.0)
        val height = maxOf(roots.maxOfOrNull { it.y + it.height } ?: 0.0, flow?.height ?: 0.0)
        return BlueprintLayout(key, cards, roots + nested, links, width, height, flats.values.mapNotNull { it.failure })
    }

    private companion object {
        const val Stretch = 2.0
        const val RowGap = 96.0
        const val RowCost = 0.08
        const val CutShare = 1.0
        const val Tries = 6
        const val Spur = 16.0
    }

    private class Flow(val origins: Map<String, Pair<Double, Double>>, val trunks: Map<Pair<String?, String?>, List<Pair<Double, Double>>>, val width: Double, val height: Double)

    private fun leg(keyed: Map<String, BlueprintEngine.Block>, origins: Map<String, Pair<Double, Double>>, root: String?, id: String, departure: Boolean): List<Pair<Double, Double>>? {
        val layout = keyed[root]?.layout ?: return null
        val (x, y) = origins[root] ?: return null
        val points = (if (departure) layout.departures else layout.arrivals)[id] ?: return null
        return BlueprintEngine.translate(points, x + BlueprintEngine.FrameSide, y + BlueprintEngine.FrameTop)
    }

    private fun anchorWithin(block: BlueprintEngine.Block, id: String, port: String?, provides: Boolean): Double? {
        block.layout.cards[id]?.let { return BlueprintEngine.FrameTop + BlueprintEngine.anchorIn(it, port, provides, BlueprintEngine.HeaderHeight / 2).second }
        return block.layout.frames.firstOrNull { it.key == id }?.let { BlueprintEngine.FrameTop + it.y + BlueprintEngine.FrameTop / 2 }
    }

    private fun flow(blocks: List<BlueprintEngine.Block>, keyed: Map<String, BlueprintEngine.Block>, crossing: List<FrameEdge>, aspect: Double): Flow {
        val pairs = LinkedHashMap<Pair<String, String>, MutableList<FrameEdge>>()
        crossing.forEach { edge -> pairs.getOrPut(rootOf.getValue(edge.from) to rootOf.getValue(edge.to)) { ArrayList() } += edge }
        val ports = HashMap<String, MutableList<LayeredPort>>()
        val edges = ArrayList<LayeredEdge>()
        pairs.entries.forEachIndexed { i, (pair, members) ->
            val (a, b) = pair
            val out = keyed.getValue(a).layout.exits[b]?.let { BlueprintEngine.FrameTop + it.second }
                ?: members.mapNotNull { anchorWithin(keyed.getValue(a), it.from, it.fromPort, true) }.takeIf { it.isNotEmpty() }?.average() ?: (keyed.getValue(a).height / 2)
            val into = keyed.getValue(b).layout.entries[a]?.let { BlueprintEngine.FrameTop + it.second }
                ?: members.mapNotNull { anchorWithin(keyed.getValue(b), it.to, it.toPort, false) }.takeIf { it.isNotEmpty() }?.average() ?: (keyed.getValue(b).height / 2)
            ports.getOrPut(a) { ArrayList() } += LayeredPort("o$i", true, out)
            ports.getOrPut(b) { ArrayList() } += LayeredPort("i$i", false, into)
            edges += LayeredEdge("f$i", a, b, "o$i", "i$i", members.sumOf { it.members.size.coerceAtLeast(1) })
        }
        val nodes = blocks.map { LayeredNode(it.group.key, it.width, it.height, ports[it.group.key].orEmpty()) }
        val area = blocks.sumOf { (it.width + BlueprintEngine.GroupGap) * (it.height + BlueprintEngine.GroupGap) }
        val spacing = LayeredSpacing(node = BlueprintEngine.GroupGap, layer = BlueprintEngine.GroupGap * 1.5, edge = 10.0,
            component = BlueprintEngine.GroupGap, aspect = aspect, wrapHeight = maxOf(2 * sqrt(area / aspect), blocks.maxOf { it.height }), align = LayeredAlign.West)
        val whole = Layered.layout(nodes, edges, spacing)
        val trunks = HashMap<Pair<String?, String?>, List<Pair<Double, Double>>>()
        val snake = wrapped(whole, nodes, edges, spacing, aspect)
        if (snake != null) {
            pairs.keys.forEachIndexed { i, pair -> trunks[pair] = snake.trunks.getValue(i) }
            return Flow(blocks.indices.associate { blocks[it].group.key to snake.origins[it] }, trunks, snake.width, snake.height)
        }
        pairs.keys.forEachIndexed { i, pair -> trunks[pair] = BlueprintEngine.packed(whole.routes[i], false) }
        return Flow(blocks.indices.associate { blocks[it].group.key to (whole.x[it] to whole.y[it]) }, trunks, whole.width, whole.height)
    }

    private fun wrapped(whole: LayeredResult, nodes: List<LayeredNode>, edges: List<LayeredEdge>, spacing: LayeredSpacing, aspect: Double): Snake? {
        fun misfit(width: Double, height: Double) = abs(ln(width / height.coerceAtLeast(1.0) / aspect))
        val single = misfit(whole.width, whole.height)
        return rows(whole, nodes, edges, aspect).take(Tries)
            .map { (rows, penalty) -> snake(rows, nodes, edges, spacing).let { it to misfit(it.width, it.height) + penalty } }
            .filter { it.second < single }
            .minByOrNull { it.second }?.first
    }

    private fun rows(whole: LayeredResult, nodes: List<LayeredNode>, edges: List<LayeredEdge>, aspect: Double): List<Pair<List<List<List<Int>>>, Double>> {
        val wide = whole.width / whole.height.coerceAtLeast(1.0)
        if (wide < aspect * Stretch) return emptyList()
        val bands = ArrayList<MutableList<Int>>()
        var reach = Double.NEGATIVE_INFINITY
        nodes.indices.sortedBy { whole.x[it] }.forEach { i ->
            if (bands.isEmpty() || whole.x[i] >= reach) bands += mutableListOf(i) else bands.last() += i
            reach = maxOf(reach, whole.x[i] + nodes[i].width)
        }
        if (bands.size < 4) return emptyList()
        val left = bands.map { band -> band.minOf { whole.x[it] } }
        val right = bands.map { band -> band.maxOf { whole.x[it] + nodes[it].width } }
        val top = bands.map { band -> band.minOf { whole.y[it] } }
        val bottom = bands.map { band -> band.maxOf { whole.y[it] + nodes[it].height } }
        fun split(limit: Double): List<IntRange> {
            val chunks = ArrayList<IntRange>()
            var start = 0
            for (next in 1..bands.size) if (next == bands.size || right[next] - left[start] > limit) { chunks += start until next; start = next }
            return chunks
        }
        val index = HashMap<String, Int>().also { map -> nodes.forEachIndexed { i, node -> map[node.id] = i } }
        val bandOf = IntArray(nodes.size).also { of -> bands.forEachIndexed { b, band -> band.forEach { of[it] = b } } }
        val mass = edges.sumOf { it.weight }.coerceAtLeast(1).toDouble()
        return bands.indices.flatMap { i -> (i until bands.size).map { j -> right[j] - left[i] } }.distinct().map(::split).distinctBy { chunks -> chunks.map { it.first } }
            .filter { chunks -> chunks.size >= 2 && chunks.none { it.first == it.last } }
            .map { chunks ->
                val chunkOf = IntArray(bands.size).also { of -> chunks.forEachIndexed { c, chunk -> chunk.forEach { of[it] = c } } }
                val cuts = CutShare * edges.sumOf { edge ->
                    val from = chunkOf[bandOf[index.getValue(edge.from)]]
                    val to = chunkOf[bandOf[index.getValue(edge.to)]]
                    edge.weight * if (to >= from) (to - from).toDouble() else 2.0 * (from - to)
                } / mass
                val width = chunks.maxOf { right[it.last] - left[it.first] }
                val height = chunks.sumOf { chunk -> chunk.maxOf { bottom[it] } - chunk.minOf { top[it] } } + (chunks.size - 1) * RowGap
                val penalty = RowCost * (chunks.size - 1) + cuts
                Triple(chunks.map { chunk -> chunk.map { bands[it] } }, penalty, abs(ln(width / height / aspect)) + penalty)
            }
            .sortedBy { it.third }
            .map { it.first to it.second }
    }

    private class Snake(val origins: List<Pair<Double, Double>>, val trunks: Map<Int, List<Pair<Double, Double>>>, val width: Double, val height: Double)

    private class Row(val nodes: List<LayeredNode>, val edges: List<LayeredEdge>, val layout: LayeredResult) {
        val bands: List<DoubleArray> = ArrayList<DoubleArray>().also { merged ->
            nodes.indices.map { doubleArrayOf(layout.x[it], layout.x[it] + nodes[it].width) }.sortedBy { it[0] }.forEach { span ->
                val last = merged.lastOrNull()
                if (last != null && span[0] <= last[1]) last[1] = maxOf(last[1], span[1]) else merged += span
            }
        }
        fun at(id: String) = nodes.indexOfFirst { it.id == id }
        fun band(x: Double) = bands.indexOfFirst { x >= it[0] - 1e-6 && x <= it[1] + 1e-6 }.coerceAtLeast(0)
        fun route(id: String, dx: Double, dy: Double) = BlueprintEngine.translate(BlueprintEngine.packed(layout.routes[edges.indexOfFirst { it.id == id }], false), dx, dy)
    }

    private fun snake(rows: List<List<List<Int>>>, nodes: List<LayeredNode>, edges: List<LayeredEdge>, spacing: LayeredSpacing): Snake {
        val index = HashMap<String, Int>().also { map -> nodes.forEachIndexed { i, node -> map[node.id] = i } }
        val rowOf = IntArray(nodes.size).also { of -> rows.forEachIndexed { r, row -> row.flatten().forEach { of[it] = r } } }
        fun source(e: Int) = rowOf[index.getValue(edges[e].from)]
        fun target(e: Int) = rowOf[index.getValue(edges[e].to)]
        val laid = rows.mapIndexed { r, row ->
            val members = row.flatMapIndexed { band, blocks -> blocks.map { val node = nodes[it]; LayeredNode(node.id, node.width, node.height, node.ports, node.rank, band, node.anchor) } }
            val links = edges.indices.filter { source(it) == r && target(it) == r }.map { edges[it] }
            Row(members, links, Layered.layout(members, links, spacing))
        }
        fun x(i: Int) = laid[rowOf[i]].let { row -> row.layout.x[row.at(nodes[i].id)] }
        fun y(i: Int) = laid[rowOf[i]].let { row -> row.layout.y[row.at(nodes[i].id)] }
        fun offset(i: Int, port: String?) = nodes[i].ports.firstOrNull { it.id == port }?.offset ?: nodes[i].anchor
        class Hop(val e: Int) {
            val from = index.getValue(edges[e].from)
            val to = index.getValue(edges[e].to)
            val r = rowOf[from]
            val s = rowOf[to]
            val sx = x(from) + nodes[from].width
            val sy = y(from) + offset(from, edges[e].fromPort)
            val tx = x(to)
            val ty = y(to) + offset(to, edges[e].toPort)
            val down = s > r
            val drop = laid[r].band(x(from))
            val rise = laid[s].band(tx) - 1
            val near = if (down) r + 1 else r
            val far = if (down) s else s + 1
            var dropX = 0.0
            var riseX = 0.0
            var marginX = 0.0
            var nearY = 0.0
            var farY = 0.0
        }
        val hops = edges.indices.filter { source(it) != target(it) }.map(::Hop)
        val edge = spacing.edge
        val columns = HashMap<Pair<Int, Int>, Pair<MutableList<Hop>, MutableList<Hop>>>()
        hops.forEach { hop ->
            columns.getOrPut(hop.r to hop.drop) { ArrayList<Hop>() to ArrayList() }.first += hop
            columns.getOrPut(hop.s to hop.rise) { ArrayList<Hop>() to ArrayList() }.second += hop
        }
        columns.forEach { (key, lists) ->
            val bands = laid[key.first].bands
            val gap = key.second
            val drops = lists.first.sortedWith(compareBy<Hop> { if (it.down) 0 else 1 }.thenBy { if (it.down) -it.sy else it.sy })
            val rises = lists.second.sortedWith(compareBy<Hop> { if (it.down) 0 else 1 }.thenBy { if (it.down) -it.ty else it.ty })
            when {
                gap < 0 -> rises.forEachIndexed { k, hop -> hop.riseX = bands.first()[0] - Spur - (rises.size - 1 - k) * edge }
                gap >= bands.size - 1 -> drops.forEachIndexed { k, hop -> hop.dropX = bands.last()[1] + Spur + k * edge }
                else -> {
                    val left = bands[gap][1]
                    val right = bands[gap + 1][0]
                    val margin = minOf(Spur, (right - left) / 3)
                    val count = drops.size + rises.size
                    val step = if (count > 1) minOf(edge, (right - left - 2 * margin) / (count - 1)) else 0.0
                    drops.forEachIndexed { k, hop -> hop.dropX = left + margin + k * step }
                    rises.forEachIndexed { k, hop -> hop.riseX = right - margin - (rises.size - 1 - k) * step }
                }
            }
        }
        val leftmost = minOf(0.0, hops.minOfOrNull { it.riseX } ?: 0.0)
        hops.filter { abs(it.s - it.r) > 1 }.forEachIndexed { k, hop -> hop.marginX = leftmost - Spur - k * edge }
        val inset = -minOf(leftmost, hops.minOfOrNull { if (abs(it.s - it.r) > 1) it.marginX else 0.0 } ?: 0.0) + if (hops.any { it.riseX < 0 || abs(it.s - it.r) > 1 }) edge else 0.0
        class Run(val hop: Hop, val start: Double, val end: Double, val near: Boolean) {
            val low = minOf(start, end)
            val high = maxOf(start, end)
            val rising = if (hop.down) start else end
            val falling = if (hop.down) end else start
            var level = 0
        }
        val runs = HashMap<Int, MutableList<Run>>()
        hops.forEach { hop ->
            if (abs(hop.s - hop.r) == 1) runs.getOrPut(hop.near) { ArrayList() } += Run(hop, hop.dropX, hop.riseX, true)
            else {
                runs.getOrPut(hop.near) { ArrayList() } += Run(hop, hop.dropX, hop.marginX, true)
                runs.getOrPut(hop.far) { ArrayList() } += Run(hop, hop.marginX, hop.riseX, false)
            }
        }
        val levels = IntArray(rows.size + 1)
        runs.forEach { (g, list) ->
            fun inside(x: Double, run: Run) = x >= run.low - 0.5 && x <= run.high + 0.5
            val clearance = 3 * edge
            val slot = slots(list.size,
                overlaps = { a, b -> list[a].low < list[b].high + clearance && list[b].low < list[a].high + clearance },
                crossings = { above, below -> (if (inside(list[below].rising, list[above])) 1 else 0) + (if (inside(list[above].falling, list[below])) 1 else 0) },
                key = { -list[it].start })
            list.forEachIndexed { k, run -> run.level = slot[k] }
            levels[g] = slot.max() + 1
        }
        val gaps = DoubleArray(rows.size + 1) { g -> if (g == 0 || g == rows.size) 0.0 else maxOf(RowGap, 2 * Spur + levels[g] * edge) }
        val tops = DoubleArray(rows.size)
        rows.indices.forEach { r -> tops[r] = (if (r == 0) 0.0 else tops[r - 1] + laid[r - 1].layout.height) + gaps[r] }
        runs.forEach { (g, list) ->
            val top = tops[g - 1] + laid[g - 1].layout.height
            list.forEach { run -> if (run.near) run.hop.nearY = top + Spur + run.level * edge else run.hop.farY = top + Spur + run.level * edge }
        }
        val trunks = HashMap<Int, List<Pair<Double, Double>>>()
        edges.indices.forEach { e -> if (source(e) == target(e)) trunks[e] = laid[source(e)].route(edges[e].id, inset, tops[source(e)]) }
        hops.forEach { hop ->
            val start = hop.sx + inset to tops[hop.r] + hop.sy
            val end = hop.tx + inset to tops[hop.s] + hop.ty
            val dropX = hop.dropX + inset
            val riseX = hop.riseX + inset
            trunks[hop.e] = BlueprintEngine.stitch(listOf(
                if (abs(hop.s - hop.r) == 1) listOf(start, dropX to start.second, dropX to hop.nearY, riseX to hop.nearY, riseX to end.second, end)
                else listOf(start, dropX to start.second, dropX to hop.nearY, hop.marginX + inset to hop.nearY, hop.marginX + inset to hop.farY, riseX to hop.farY, riseX to end.second, end),
            ))
        }
        val origins = nodes.indices.map { i -> (inset + x(i)) to (tops[rowOf[i]] + y(i)) }
        val right = maxOf(laid.maxOf { it.layout.width }, hops.maxOfOrNull { it.dropX + Spur } ?: 0.0) + inset
        return Snake(origins, trunks, right, tops.last() + laid.last().layout.height)
    }
}
