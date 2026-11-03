package dev.shibasis.reaktor.board

import dev.shibasis.reaktor.blueprint.Layered
import dev.shibasis.reaktor.blueprint.LayeredAlign
import dev.shibasis.reaktor.blueprint.LayeredEdge
import dev.shibasis.reaktor.blueprint.LayeredNode
import dev.shibasis.reaktor.blueprint.LayeredResult
import dev.shibasis.reaktor.blueprint.LayeredSpacing
import kotlin.math.sqrt

object BoardLayout {
    const val Aspect = 1.6
    private const val FrameGap = 48.0
    private const val Stretch = 2.0
    private const val RowsFrom = 4

    private val cards = LayeredSpacing(
        node = BoardGeometry.RowPitch - BoardGeometry.CardHeight,
        layer = BoardGeometry.RankPitch - BoardGeometry.CardWidth,
        component = BoardGeometry.FrameHead,
        wrap = 4,
        wrapHeight = 4 * BoardGeometry.RowPitch,
        compact = true,
    )

    private class Frame(val width: Double, val height: Double, val places: Map<String, BoardPoint>)

    private class Block(val members: List<Int>, val width: Double, val height: Double, val rank: Int)

    fun of(items: List<BoardItem>, aspect: Double = Aspect): List<BoardItem> {
        val known = items.mapTo(HashSet()) { it.key }
        val children = items.groupBy { it.within.takeIf { parent -> parent in known && parent != it.key } }
        val frame = frame(items, children, null, emptySet(), aspect)
        return items.map { item ->
            val point = frame.places[item.key] ?: BoardPoint(0.0, 0.0)
            item.copy(at = item.at?.takeIf { children[item.key].isNullOrEmpty() }
                ?: BoardPoint(BoardGeometry.RankLeft + point.x, BoardGeometry.RankTop + point.y))
        }
    }

    private fun frame(items: List<BoardItem>, children: Map<String?, List<BoardItem>>, parent: String?, seen: Set<String>, aspect: Double): Frame {
        val parts = children[parent].orEmpty().filter { it.key !in seen }.sortedBy { it.key }
        if (parts.isEmpty()) return Frame(0.0, 0.0, emptyMap())
        val inside = parts.associate { item -> item.key to children[item.key]?.takeIf { it.isNotEmpty() }?.let { frame(items, children, item.key, seen + item.key, aspect) } }
        val owner = HashMap<String, String>()
        parts.forEach { part ->
            owner[part.key] = part.key
            inside[part.key]?.places?.keys?.forEach { owner[it] = part.key }
        }
        val pairs = LinkedHashSet<Pair<String, String>>()
        fun edge(from: String, to: String) {
            val source = owner[from] ?: return
            val target = owner[to] ?: return
            if (source != target) pairs += source to target
        }
        items.forEach { item ->
            item.after.forEach { edge(it, item.key) }
            item.relations.forEach { edge(item.key, it.to) }
        }
        val nodes = parts.map { item ->
            val nested = inside[item.key]
            LayeredNode(item.key,
                nested?.let { it.width + 2 * BoardGeometry.FramePad } ?: BoardGeometry.CardWidth,
                nested?.let { it.height + BoardGeometry.FrameHead + BoardGeometry.FramePad } ?: BoardGeometry.CardHeight)
        }
        val edges = pairs.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .mapIndexed { index, (from, to) -> LayeredEdge("e$index", from, to) }
        val framed = parts.indices.filterTo(LinkedHashSet()) { inside[parts[it].key] != null }
        val layout = if (framed.isEmpty()) Layered.layout(nodes, edges, cards) else arranged(nodes, edges, framed, pairs, aspect)
        val places = HashMap<String, BoardPoint>()
        parts.forEachIndexed { index, part ->
            val x = layout.x[index]
            val y = layout.y[index]
            places[part.key] = BoardPoint(x, y)
            inside[part.key]?.places?.forEach { (key, at) ->
                places[key] = BoardPoint(x + BoardGeometry.FramePad + at.x, y + BoardGeometry.FrameHead + at.y)
            }
        }
        return Frame(layout.width, layout.height, places)
    }

    private fun arranged(nodes: List<LayeredNode>, edges: List<LayeredEdge>, framed: Set<Int>, order: Set<Pair<String, String>>, aspect: Double): LayeredResult {
        val area = nodes.sumOf { (it.width + FrameGap) * (it.height + FrameGap) }
        val spacing = LayeredSpacing(node = FrameGap, layer = FrameGap * 1.5, component = FrameGap, aspect = aspect,
            wrapHeight = maxOf(2 * sqrt(area / aspect), nodes.maxOf { it.height }), align = LayeredAlign.West, compact = true)
        if (nodes.size >= RowsFrom) return packed(nodes, edges, framed, order, aspect)
        val flow = Layered.layout(nodes, edges, spacing)
        val shape = flow.width / flow.height.coerceAtLeast(1.0)
        return if (shape > aspect * Stretch * Stretch || shape < aspect / Stretch / Stretch) packed(nodes, edges, framed, order, aspect) else flow
    }

    private fun packed(nodes: List<LayeredNode>, edges: List<LayeredEdge>, framed: Set<Int>, order: Set<Pair<String, String>>, aspect: Double): LayeredResult {
        val rank = ranks(nodes, order)
        val loose = nodes.indices.filter { it !in framed }
        val ids = loose.mapTo(HashSet()) { nodes[it].id }
        val cardLayout = loose.takeIf { it.isNotEmpty() }?.let { Layered.layout(it.map(nodes::get), edges.filter { edge -> edge.from in ids && edge.to in ids }, cards) }
        val blocks = (framed.map { Block(listOf(it), nodes[it].width, nodes[it].height, rank[it]) } +
            listOfNotNull(cardLayout?.let { Block(loose, it.width, it.height, loose.minOf(rank::get)) }))
            .sortedWith(compareBy<Block> { it.rank }.thenByDescending { it.height }.thenBy { it.members.first() })
        val origins = Layered.shelves(blocks.map { it.width to it.height }, FrameGap, aspect)
        val x = DoubleArray(nodes.size)
        val y = DoubleArray(nodes.size)
        blocks.forEachIndexed { slot, block ->
            val (left, top) = origins[slot]
            block.members.forEachIndexed { local, index ->
                x[index] = left + if (index in framed) 0.0 else cardLayout!!.x[local]
                y[index] = top + if (index in framed) 0.0 else cardLayout!!.y[local]
            }
        }
        return LayeredResult(x, y, emptyList(), blocks.indices.maxOf { origins[it].first + blocks[it].width }, blocks.indices.maxOf { origins[it].second + blocks[it].height })
    }

    private fun ranks(nodes: List<LayeredNode>, order: Set<Pair<String, String>>): IntArray {
        val index = HashMap<String, Int>().also { map -> nodes.forEachIndexed { i, node -> map[node.id] = i } }
        val arcs = order.mapNotNull { (from, to) -> index[from]?.let { a -> index[to]?.let { b -> a to b } } }
        val rank = IntArray(nodes.size)
        repeat(nodes.size) {
            var moved = false
            arcs.forEach { (a, b) -> if (rank[b] <= rank[a] && rank[a] + 1 < nodes.size) { rank[b] = rank[a] + 1; moved = true } }
            if (!moved) return rank
        }
        return rank
    }
}
