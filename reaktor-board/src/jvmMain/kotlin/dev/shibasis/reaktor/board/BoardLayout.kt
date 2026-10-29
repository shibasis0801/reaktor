package dev.shibasis.reaktor.board

import dev.shibasis.reaktor.blueprint.Layered
import dev.shibasis.reaktor.blueprint.LayeredEdge
import dev.shibasis.reaktor.blueprint.LayeredNode
import dev.shibasis.reaktor.blueprint.LayeredSpacing

object BoardLayout {
    private val spacing = LayeredSpacing(
        node = BoardGeometry.RowPitch - BoardGeometry.CardHeight,
        layer = BoardGeometry.RankPitch - BoardGeometry.CardWidth,
        component = BoardGeometry.FrameHead,
        wrap = 4,
        wrapHeight = 4 * BoardGeometry.RowPitch,
        compact = true,
    )

    private class Frame(val width: Double, val height: Double, val places: Map<String, BoardPoint>)

    fun of(items: List<BoardItem>): List<BoardItem> {
        val known = items.mapTo(HashSet()) { it.key }
        val children = items.groupBy { it.within.takeIf { parent -> parent in known && parent != it.key } }
        val frame = frame(items, children, null, emptySet())
        return items.map { item ->
            val point = frame.places[item.key] ?: BoardPoint(0.0, 0.0)
            item.copy(at = item.at?.takeIf { children[item.key].isNullOrEmpty() }
                ?: BoardPoint(BoardGeometry.RankLeft + point.x, BoardGeometry.RankTop + point.y))
        }
    }

    private fun frame(items: List<BoardItem>, children: Map<String?, List<BoardItem>>, parent: String?, seen: Set<String>): Frame {
        val parts = children[parent].orEmpty().filter { it.key !in seen }.sortedBy { it.key }
        if (parts.isEmpty()) return Frame(0.0, 0.0, emptyMap())
        val inside = parts.associate { item -> item.key to children[item.key]?.takeIf { it.isNotEmpty() }?.let { frame(items, children, item.key, seen + item.key) } }
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
        val layout = Layered.layout(nodes, edges, spacing)
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
}
