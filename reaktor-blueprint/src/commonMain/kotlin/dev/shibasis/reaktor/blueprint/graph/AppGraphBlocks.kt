package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.blueprint.BlueprintEdge
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintNode
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.FrameEdge
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Lanes
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.graph.core.Digraph
import kotlin.math.abs
import kotlin.math.ln

internal class AppGraphBlocks(private val order: Digraph, private val aspect: Double) {
    class Entry(val key: String?, val label: String, val nodes: List<BlueprintNode>, val muted: Boolean = false)

    private val before = order.edges.groupBy({ it.second }, { it.first })

    private class Grid(val columns: List<List<BlueprintNode>>) {
        val widths = columns.map { column -> column.maxOf { BlueprintEngine.cardWidth(it) } }
        val heights = (0 until (columns.maxOfOrNull { it.size } ?: 0)).map { row ->
            columns.mapNotNull { it.getOrNull(row) }.maxOf { BlueprintEngine.cardHeight(it) }
        }
        val width = maxOf(BlueprintEngine.CardWidth, span(widths, Column)) + 2 * Inset
        val height = Head + span(heights, Row) + Inset
    }

    private inner class Block(val entry: Entry) {
        val framed = entry.key != null
        val grid = Grid(if (framed) wrapped(entry.nodes) else listOf(entry.nodes))
        val columns get() = grid.columns
        val width = if (framed) grid.width else grid.widths.single()
        val height = if (framed) grid.height else grid.heights.single()
        var x = 0.0
        var y = 0.0

        private fun left(column: Int) = x + Inset + grid.widths.take(column).sumOf { it + Column }
        private fun top(row: Int) = y + Head + grid.heights.take(row).sumOf { it + Row }

        val across: List<Street> by lazy {
            (0..columns.size).map { column ->
                when (column) {
                    0 -> Street(true, x + Inset / 2, Inset)
                    columns.size -> Street(true, x + width - Inset / 2, Inset)
                    else -> Street(true, left(column) - Column / 2, Column)
                }
            }
        }
        val along: List<Street> by lazy {
            (0..grid.heights.size).map { row ->
                when (row) {
                    0 -> Street(false, y + (BlueprintEngine.FrameTop + Head) / 2, Head - BlueprintEngine.FrameTop)
                    grid.heights.size -> Street(false, y + height - Inset / 2, Inset)
                    else -> Street(false, top(row) - Row / 2, Row)
                }
            }
        }

        fun cards(): List<Card> = columns.flatMapIndexed { column, nodes ->
            nodes.mapIndexed { row, node ->
                Card(node.id, if (framed) left(column) else x, if (framed) top(row) else y, BlueprintEngine.cardWidth(node),
                    BlueprintEngine.cardHeight(node), BlueprintEngine.pinsOf(node), node.folded, node.lane, node.pinHeader)
            }
        }

        fun frame() = Frame(checkNotNull(entry.key), entry.label, x, y, width, height, entry.nodes.mapTo(LinkedHashSet()) { it.id },
            emptyList(), entry.muted, lanes = Lanes.Ranks)
    }

    private class Spot(val band: Int, val shelf: Int, val block: Block, val column: Int)

    fun layout(request: LayoutRequest, bands: List<List<Entry>>, edges: List<BlueprintEdge>, bundles: Boolean): GraphLayout {
        var top = Gap
        val placed = bands.filter { it.isNotEmpty() }.map { entries ->
            val shelves = shelves(entries.map { Block(it) })
            var left = Gap
            shelves.forEach { shelf ->
                var y = top
                shelf.forEach { block -> block.x = left; block.y = y; y += block.height + Gap }
                left += shelf.maxOf { it.width } + Gap
            }
            top += depth(shelves) + 2 * Gap
            shelves
        }
        val blocks = placed.flatten().flatten()
        val cards = LinkedHashMap<String, Card>().apply { blocks.forEach { block -> block.cards().forEach { put(it.id, it) } } }
        val spots = HashMap<String, Spot>()
        placed.forEachIndexed { band, shelves ->
            shelves.forEachIndexed { at, shelf ->
                shelf.forEach { block -> block.columns.forEachIndexed { column, nodes -> nodes.forEach { spots[it.id] = Spot(band, at, block, column) } } }
            }
        }
        val joined = BlueprintEngine.join(edges, cards.mapValues { it.value.pins }, bundles)
        val traces = joined.associateWith { edge -> route(edge, cards, spots, placed) }
        separate(traces.values.filterNotNull())
        val links = joined.map { edge ->
            val trace = traces[edge]
            val start = BlueprintEngine.anchor(cards, edge.from, edge.fromPort, true)
            val points = trace?.points() ?: if (edge.from == edge.to) listOf(start) else BlueprintEngine.bezier(start, BlueprintEngine.anchor(cards, edge.to, edge.toPort, false))
            Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members, points,
                reversed = edge.reversed, family = edge.family, curve = trace == null && edge.from != edge.to)
        }
        return GraphLayout(request, cards, blocks.filter { it.framed }.map { it.frame() }, links,
            (blocks.maxOfOrNull { it.x + it.width } ?: 0.0) + Gap, (blocks.maxOfOrNull { it.y + it.height } ?: 0.0) + Gap)
    }

    private fun route(edge: FrameEdge, cards: Map<String, Card>, spots: Map<String, Spot>, bands: List<List<List<Block>>>): Trace? {
        val from = spots[edge.from] ?: return null
        val to = spots[edge.to] ?: return null
        if (edge.from == edge.to || edge.curved || from.band != to.band) return null
        val start = BlueprintEngine.anchor(cards, edge.from, edge.fromPort, true)
        val end = BlueprintEngine.anchor(cards, edge.to, edge.toPort, false)
        val trace = Trace(start)
        fun nearest(streets: List<Street>, a: Double, b: Double) = streets.minBy { abs(a - it.at) + abs(it.at - b) }
        if (from.block === to.block) {
            val leave = from.block.across[from.column + 1]
            val arrive = from.block.across[to.column]
            trace.across(leave.at)
            if (leave != arrive) {
                val street = nearest(from.block.along, start.second, end.second)
                trace.along(street.at, leave)
                trace.across(arrive.at, street)
            }
            trace.along(end.second, arrive)
            trace.across(end.first)
            return trace
        }
        val shelves = bands[from.band]
        fun gate(shelf: Int) = if (shelf < shelves.size) Street(true, shelves[shelf].first().x - Gap / 2, Gap)
            else Street(true, shelves.last().let { last -> last.first().x + last.maxOf { it.width } } + Gap / 2, Gap)
        fun passages(shelf: List<Block>) = listOf(Street(false, shelf.first().y - Gap / 2, Gap)) +
            shelf.zipWithNext { a, b -> Street(false, (a.y + a.height + b.y) / 2, b.y - a.y - a.height) } +
            shelf.last().let { Street(false, it.y + it.height + Gap / 2, Gap) }
        val exit = gate(from.shelf + 1)
        val entrance = gate(to.shelf)
        var y = if (!from.block.framed || from.column == from.block.columns.lastIndex) start.second.also { trace.across(exit.at) } else {
            val leave = from.block.across[from.column + 1]
            val street = nearest(from.block.along, start.second, end.second)
            trace.across(leave.at)
            trace.along(street.at, leave)
            trace.across(exit.at, street)
            street.at
        }
        val inside = if (!to.block.framed || to.column == 0) null else nearest(to.block.along, y, end.second)
        val arriveAt = inside?.at ?: end.second
        fun cross(shelf: Int, west: Boolean) {
            val passage = nearest(passages(shelves[shelf]), y, arriveAt)
            trace.along(passage.at, if (west) gate(shelf + 1) else gate(shelf))
            trace.across((if (west) gate(shelf) else gate(shelf + 1)).at, passage)
            y = passage.at
        }
        if (to.shelf > from.shelf) (from.shelf + 1 until to.shelf).forEach { cross(it, false) }
        else (from.shelf downTo to.shelf).forEach { cross(it, true) }
        trace.along(arriveAt, entrance)
        if (inside != null) {
            val arrive = to.block.across[to.column]
            trace.across(arrive.at, inside)
            trace.along(end.second, arrive)
        }
        trace.across(end.first)
        return trace
    }

    private fun separate(traces: List<Trace>) {
        val held = LinkedHashMap<Street, MutableList<Pair<Trace, Int>>>()
        traces.forEach { trace -> trace.streets.forEachIndexed { at, street -> if (street != null) held.getOrPut(street) { ArrayList() } += trace to at } }
        held.forEach { (street, segments) ->
            val spans = segments.map { (trace, at) ->
                val (a, b) = if (street.vertical) trace.ys[at] to trace.ys[at + 1] else trace.xs[at] to trace.xs[at + 1]
                minOf(a, b) to maxOf(a, b)
            }
            val ends = ArrayList<Double>()
            val lanes = IntArray(segments.size)
            segments.indices.sortedWith(compareBy({ spans[it].first }, { spans[it].second })).forEach { index ->
                val free = ends.indexOfFirst { it + Lane < spans[index].first }
                lanes[index] = if (free >= 0) free.also { ends[it] = spans[index].second } else ends.size.also { ends += spans[index].second }
            }
            if (ends.size < 2) return@forEach
            val pitch = minOf(Lane, (street.room - Lane) / (ends.size - 1))
            segments.forEachIndexed { index, (trace, at) ->
                val offset = (lanes[index] - (ends.size - 1) / 2.0) * pitch
                if (street.vertical) { trace.xs[at] += offset; trace.xs[at + 1] += offset }
                else { trace.ys[at] += offset; trace.ys[at + 1] += offset }
            }
        }
    }

    private fun wrapped(nodes: List<BlueprintNode>): List<List<BlueprintNode>> {
        if (nodes.isEmpty()) return emptyList()
        val ids = nodes.mapTo(HashSet()) { it.id }
        val tied = order.edges.filter { (from, to) -> from != to && from in ids && to in ids }.flatMapTo(HashSet()) { listOf(it.first, it.second) }
        val (linked, loose) = nodes.partition { it.id in tied }
        val ranked = if (linked.isEmpty()) emptyList() else ordered(linked, order.columns(linked.map { listOf(it.id) })) { listOf(it.id) }
        return (1..nodes.size).map { rows -> Grid(filled(ranked.flatMap { it.chunked(rows) }, loose, rows)) }
            .minWith(compareBy({ it.width * it.height * stretch(it.width / it.height) }, { skew(it.width, it.height, FrameAspect) })).columns
    }

    private fun filled(columns: List<List<BlueprintNode>>, loose: List<BlueprintNode>, rows: Int): List<List<BlueprintNode>> {
        val queue = ArrayDeque(loose)
        return columns.map { column -> column + List(minOf(rows - column.size, queue.size)) { queue.removeFirst() } } + queue.chunked(rows)
    }

    private fun stretch(aspect: Double) = maxOf(1.0, aspect / WidestFrame).let { it * it } * maxOf(1.0, NarrowestFrame / aspect).let { it * it }

    private fun shelves(blocks: List<Block>): List<List<Block>> {
        val ranked = ordered(blocks, order.columns(blocks.map { block -> block.entry.nodes.map { it.id } })) { block -> block.entry.nodes.map { it.id } }
        val tallest = blocks.maxOf { it.height }
        val total = blocks.sumOf { it.height + Gap }
        return (generateSequence(tallest) { it * 1.15 }.takeWhile { it < total } + total).map { limit -> stacked(ranked, limit) }
            .minWith(compareBy({ skew(spread(it), depth(it), aspect) }, { spread(it) * depth(it) }))
    }

    private fun stacked(ranked: List<List<Block>>, limit: Double): List<List<Block>> = buildList {
        ranked.forEach { rank ->
            var shelf = ArrayList<Block>()
            var height = 0.0
            rank.forEach { block ->
                if (shelf.isNotEmpty() && height + Gap + block.height > limit) { add(shelf); shelf = ArrayList(); height = 0.0 }
                height += (if (shelf.isEmpty()) 0.0 else Gap) + block.height
                shelf += block
            }
            if (shelf.isNotEmpty()) add(shelf)
        }
    }

    private fun spread(shelves: List<List<Block>>) = span(shelves.map { shelf -> shelf.maxOf { it.width } }, Gap)

    private fun depth(shelves: List<List<Block>>) = shelves.maxOf { shelf -> span(shelf.map { it.height }, Gap) }

    private fun <T> ordered(items: List<T>, ranks: List<Int>, leaves: (T) -> List<String>): List<List<T>> {
        val position = HashMap<String, Double>()
        return items.indices.groupBy { ranks[it] }.entries.sortedBy { it.key }.map { (_, indices) ->
            val placed = indices.sortedWith(compareBy({ barycenter(leaves(items[it]), position) }, { it }))
            placed.forEachIndexed { at, index -> leaves(items[index]).forEach { position[it] = (at + .5) / placed.size } }
            placed.map(items::get)
        }
    }

    private fun barycenter(ids: List<String>, position: Map<String, Double>): Double =
        ids.flatMap { before[it].orEmpty() }.mapNotNull(position::get).takeIf { it.isNotEmpty() }?.average() ?: Double.MAX_VALUE

    private data class Street(val vertical: Boolean, val at: Double, val room: Double)

    private class Trace(start: Pair<Double, Double>) {
        val xs = arrayListOf(start.first)
        val ys = arrayListOf(start.second)
        val streets = ArrayList<Street?>()

        fun across(x: Double, street: Street? = null) = to(x, ys.last(), street)

        fun along(y: Double, street: Street? = null) = to(xs.last(), y, street)

        private fun to(x: Double, y: Double, street: Street?) {
            if (x == xs.last() && y == ys.last()) return
            val last = streets.lastIndex
            if (last >= 0 && (xs[last] == xs[last + 1]) == (x == xs.last())) {
                xs[last + 1] = x
                ys[last + 1] = y
                if (streets[last] != street) streets[last] = null
                return
            }
            xs += x
            ys += y
            streets += street
        }

        fun points(): List<Pair<Double, Double>> = xs.indices.map { xs[it] to ys[it] }
    }

    companion object {
        const val Column = BlueprintEngine.LayerGap
        const val Row = 28.0
        const val Inset = 24.0
        const val Head = BlueprintEngine.FrameTop + 16.0
        const val Gap = BlueprintEngine.GroupGap
        const val Lane = 6.0
        const val FrameAspect = 1.6
        const val WidestFrame = 6.0
        const val NarrowestFrame = .5
    }
}

private fun span(values: List<Double>, gap: Double) = values.sum() + gap * (values.size - 1).coerceAtLeast(0)

private fun skew(width: Double, height: Double, target: Double) = abs(ln(width / height.coerceAtLeast(1.0)) - ln(target))
