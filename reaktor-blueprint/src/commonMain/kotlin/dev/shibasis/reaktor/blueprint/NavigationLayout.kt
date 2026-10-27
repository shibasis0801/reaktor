package dev.shibasis.reaktor.blueprint

import kotlin.math.abs
import kotlin.math.hypot

fun <K> BlueprintEngine.navigationLayout(key: K, groups: List<BlueprintGroup>, edges: List<BlueprintEdge>, root: String?, aspect: Double, ordering: Set<String> = edges.mapTo(hashSetOf()) { it.id }, outlets: Map<String, Pair<Double, Double>> = emptyMap()): BlueprintLayout<K> {
    fun leaves(group: BlueprintGroup): List<BlueprintNode> = group.nodes.flatMap { node -> node.inner?.let(::leaves) ?: listOf(node) }
    val ranks = linkedMapOf<String, Int>()
    val pending = ArrayDeque<String>()
    root?.let { ranks[it] = 0; pending.add(it) }
    val outgoing = edges.filter { it.id in ordering }.groupBy { it.from }
    fun visit() {
        while (pending.isNotEmpty()) {
            val from = pending.removeFirst()
            outgoing[from].orEmpty().forEach { edge -> if (edge.to !in ranks) { ranks[edge.to] = ranks.getValue(from) + 1; pending.add(edge.to) } }
        }
    }
    visit()
    groups.flatMap(::leaves).forEach { node ->
        if (node.id !in ranks) { ranks[node.id] = 1; pending.add(node.id); visit() }
    }
    val forward = edges.filter { it.id in ordering && ranks.getValue(it.to) > ranks.getValue(it.from) }
    fun rank(group: BlueprintGroup) = leaves(group).minOfOrNull { ranks.getValue(it.id) } ?: Int.MAX_VALUE
    fun ordered(group: BlueprintGroup): BlueprintGroup {
        if (group.nodes.any { it.inner != null }) return group.copy(lanes = Lanes.Free,
            nodes = group.nodes.sortedBy { it.inner?.let(::rank) ?: ranks.getValue(it.id) }
                .map { node -> node.copy(group = node.inner?.let(::ordered)) })
        var column = 0
        val nodes = group.nodes.groupBy { ranks.getValue(it.id) }.entries.sortedBy { it.key }.flatMap { (_, layer) ->
            val lanes = balancedLanes(layer.map(::cardHeight), aspect)
            layer.mapIndexed { index, node -> node.copy(lane = column + lanes[index]) }
                .also { column += (lanes.maxOrNull() ?: 0) + 1 }
        }
        return group.copy(nodes = nodes, lanes = Lanes.Columns)
    }
    val ranked = groups.sortedBy(::rank).map(::ordered)
    val owner = ranked.flatMap { group -> leaves(group).map { it.id to group.key } }.toMap()
    val inside = forward.filter { owner[it.from] == owner[it.to] }
    val laid = layout(key, ranked, inside, aspect, layouter = LayeredFrameLayouter, options = LayoutOptions(hints = 0, ordered = true))
    val links = edges.mapNotNull { edge ->
        val from = laid.cards[edge.from] ?: return@mapNotNull null
        val to = laid.cards[edge.to] ?: return@mapNotNull null
        val outlet = outlets[edge.id]
        val sy = outlet?.second?.let { from.y + HeaderHeight + (from.height - HeaderHeight) * it } ?: (from.y + HeaderHeight / 2)
        val route = navigationPath(from.x + from.width, sy, to.x, to.y + HeaderHeight / 2, laid.cards.values.toList())
        val points = if (outlet == null) route else listOf(from.x + from.width * outlet.first to sy) + route
        Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, listOf(edge.id), points, reversed = edge.reversed)
    }
    val members = HashMap<String, Set<String>>()
    fun remember(group: BlueprintGroup) {
        members[group.key] = leaves(group).mapTo(linkedSetOf()) { it.id }
        group.nodes.mapNotNull { it.inner }.forEach(::remember)
    }
    ranked.forEach(::remember)
    return laid.copy(frames = laid.frames.map { it.copy(nodes = members[it.key].orEmpty()) }, links = links)
}

private fun navigationPath(sx: Double, sy: Double, tx: Double, ty: Double, boxes: List<Card>): List<Pair<Double, Double>> {
    val gap = BlueprintEngine.CardGap / 2
    val xs = (boxes.flatMap { listOf(it.x - gap, it.x + it.width + gap) } + listOf(sx, tx)).distinct().sorted()
    val ys = (boxes.flatMap { listOf(it.y - gap, it.y + it.height + gap) } + listOf(sy, ty)).distinct().sorted()
    fun index(x: Double, y: Double) = ys.indexOf(y) * xs.size + xs.indexOf(x)
    fun clear(ax: Double, ay: Double, bx: Double, by: Double): Boolean = boxes.none { box ->
        if (ax == bx) ax > box.x + .01 && ax < box.x + box.width - .01 && maxOf(ay, by) > box.y + .01 && minOf(ay, by) < box.y + box.height - .01
        else ay > box.y + .01 && ay < box.y + box.height - .01 && maxOf(ax, bx) > box.x + .01 && minOf(ax, bx) < box.x + box.width - .01
    }
    val source = index(sx, sy); val target = index(tx, ty)
    val distance = DoubleArray(xs.size * ys.size) { Double.POSITIVE_INFINITY }
    val before = IntArray(distance.size) { -1 }
    val heap = ArrayList<Pair<Int, Double>>()
    fun push(id: Int, value: Double) {
        heap.add(id to value); var i = heap.lastIndex
        while (i > 0) { val p = (i - 1) / 2; if (heap[p].second <= value) break; heap[i] = heap[p]; i = p }; heap[i] = id to value
    }
    fun pop(): Pair<Int, Double> {
        val result = heap[0]; val last = heap.removeAt(heap.lastIndex)
        if (heap.isNotEmpty()) { var i = 0; while (i * 2 + 1 < heap.size) { var child = i * 2 + 1; if (child + 1 < heap.size && heap[child + 1].second < heap[child].second) child++; if (last.second <= heap[child].second) break; heap[i] = heap[child]; i = child }; heap[i] = last }
        return result
    }
    distance[source] = 0.0; push(source, 0.0)
    while (heap.isNotEmpty()) {
        val (at, cost) = pop()
        if (cost != distance[at]) continue
        if (at == target) break
        val x = at % xs.size; val y = at / xs.size
        val next = listOfNotNull(if (x > 0) at - 1 else null, if (x + 1 < xs.size) at + 1 else null, if (y > 0) at - xs.size else null, if (y + 1 < ys.size) at + xs.size else null)
        for (id in next) {
            val nx = id % xs.size; val ny = id / xs.size
            if (!clear(xs[x], ys[y], xs[nx], ys[ny])) continue
            val value = cost + abs(xs[x] - xs[nx]) + abs(ys[y] - ys[ny])
            if (value < distance[id]) { distance[id] = value; before[id] = at; push(id, value) }
        }
    }
    check(distance[target].isFinite()) { "No obstacle-free navigation path" }
    val path = generateSequence(target) { before[it].takeIf { parent -> parent >= 0 } }.map { xs[it % xs.size] to ys[it / xs.size] }.toList().asReversed()
    return path.filterIndexed { index, p -> index == 0 || index == path.lastIndex || (path[index - 1].first != p.first || path[index + 1].first != p.first) && (path[index - 1].second != p.second || path[index + 1].second != p.second) }
}

fun <K> BlueprintLayout<K>.navigationFocus(group: String): BlueprintLayout<K> {
    val frame = frames.firstOrNull { it.key == group } ?: return this
    fun inside(p: Pair<Double, Double>) = p.first in frame.x..(frame.x + frame.width) && p.second in frame.y..(frame.y + frame.height)
    fun stub(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val result = arrayListOf(points.first())
        for ((a, b) in points.zipWithNext()) {
            if (inside(b)) { result += b; continue }
            val end = if (a.first == b.first) a.first to (if (b.second < frame.y) frame.y - 24 else frame.y + frame.height + 24)
                else (if (b.first < frame.x) frame.x - 24 else frame.x + frame.width + 24) to a.second
            result += end
            break
        }
        return result
    }
    return copy(links = links.map { link ->
        val from = link.from in frame.nodes; val to = link.to in frame.nodes
        when {
            from && !to -> link.copy(points = stub(link.trace), curve = false)
            to && !from -> link.copy(points = stub(link.trace.asReversed()).asReversed(), curve = false)
            else -> link
        }
    })
}

fun linkDistance(link: Link, x: Double, y: Double): Double = link.trace.zipWithNext().minOfOrNull { (a, b) ->
    val dx = b.first - a.first; val dy = b.second - a.second
    val t = if (dx == 0.0 && dy == 0.0) 0.0 else (((x - a.first) * dx + (y - a.second) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    hypot(x - a.first - t * dx, y - a.second - t * dy)
} ?: Double.POSITIVE_INFINITY
