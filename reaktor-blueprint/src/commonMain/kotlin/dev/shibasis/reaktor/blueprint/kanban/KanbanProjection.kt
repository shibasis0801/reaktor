package dev.shibasis.reaktor.blueprint.kanban

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape

data class KanbanLane(val id: String, val title: String)

data class KanbanColumn(
    val lane: KanbanLane,
    val items: List<NodeShape>,
    val total: Int,
    val scopedTotal: Int,
) {
    val shownTotal: Int get() = items.size
    val filteredTotal: Int get() = scopedTotal - shownTotal
}

class KanbanProjection private constructor(
    val columns: List<KanbanColumn>,
    val availableThreads: Set<String>,
    val closingReasons: Map<String, String>,
) {
    val total: Int get() = columns.sumOf { it.total }
    val scopedTotal: Int get() = columns.sumOf { it.scopedTotal }
    val shownTotal: Int get() = columns.sumOf { it.shownTotal }
    val filteredTotal: Int get() = scopedTotal - shownTotal

    companion object {
        fun from(
            shape: GraphShape,
            lanes: List<KanbanLane>,
            laneOf: (NodeShape) -> String?,
            threadOf: (NodeShape) -> String = { it.scope },
            closingReasonOf: (NodeShape) -> String? = { it.attributes["resolution"] },
            threads: Set<String>? = null,
            visible: (NodeShape) -> Boolean = { true },
        ): KanbanProjection {
            require(lanes.all { it.id.isNotBlank() && it.title.isNotBlank() }) { "Lanes need an identity and title" }
            require(lanes.map { it.id }.toSet().size == lanes.size) { "Lane identities must be unique" }
            val indices = lanes.mapIndexed { index, lane -> lane.id to index }.toMap()
            val totals = IntArray(lanes.size)
            val scoped = IntArray(lanes.size)
            val items = List(lanes.size) { mutableListOf<NodeShape>() }
            val available = linkedSetOf<String>()
            val reasons = linkedMapOf<String, String>()
            val seen = hashSetOf<String>()
            for (node in shape.nodes) {
                require(seen.add(node.id)) { "Node identities must be unique" }
                val lane = laneOf(node) ?: continue
                val index = requireNotNull(indices[lane]) { "No supplied lane for status $lane" }
                totals[index]++
                val thread = threadOf(node)
                available.add(thread)
                if (threads != null && thread !in threads) continue
                scoped[index]++
                if (!visible(node)) continue
                items[index].add(node)
                closingReasonOf(node)?.takeIf { it.isNotBlank() }?.let { reasons[node.id] = it }
            }
            return KanbanProjection(lanes.mapIndexed { index, lane ->
                KanbanColumn(lane, items[index].toList(), totals[index], scoped[index])
            }, available.toSet(), reasons.toMap())
        }
    }
}
