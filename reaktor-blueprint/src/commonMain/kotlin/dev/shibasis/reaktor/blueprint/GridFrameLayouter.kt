package dev.shibasis.reaktor.blueprint

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

object GridFrameLayouter : FrameLayouter {
    private const val LooseAspect = 1.4

    override fun lay(group: BlueprintGroup, pins: Map<String, List<Pin>>, edges: List<FrameEdge>): FrameLayout {
        if (group.nodes.isEmpty()) return FrameLayout(emptyMap(), emptyList(), 0.0, 0.0)
        val columns = if (group.loose) looseColumns(group.nodes) else group.nodes.groupBy { it.lane }.entries.sortedBy { it.key }.map { it.value }
        val cards = linkedMapOf<String, Card>()
        columns.forEachIndexed { index, column ->
            var y = 0.0
            val x = index * (BlueprintEngine.CardWidth + BlueprintEngine.LayerGap)
            column.forEach { node ->
                val height = BlueprintEngine.cardHeight(node)
                cards[node.id] = Card(node.id, x, y, BlueprintEngine.CardWidth, height, pins.getValue(node.id), node.folded)
                y += height + BlueprintEngine.CardGap
            }
        }
        val links = edges.filter { it.from in cards && it.to in cards }.map { edge ->
            Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members,
                BlueprintEngine.curve(BlueprintEngine.anchor(cards, edge.from, edge.fromPort, true), BlueprintEngine.anchor(cards, edge.to, edge.toPort, false)),
                reversed = edge.reversed)
        }
        return FrameLayout(cards, links, cards.values.maxOf { it.x + it.width }, cards.values.maxOf { it.y + it.height })
    }

    private fun looseColumns(nodes: List<BlueprintNode>): List<List<BlueprintNode>> {
        val averageHeight = nodes.sumOf { BlueprintEngine.cardHeight(it) } / nodes.size + BlueprintEngine.CardGap
        val pitch = BlueprintEngine.CardWidth + BlueprintEngine.LayerGap
        val count = sqrt(LooseAspect * nodes.size * averageHeight / pitch).roundToInt().coerceIn(1, nodes.size)
        val perColumn = ceil(nodes.size / count.toDouble()).toInt()
        return nodes.chunked(perColumn)
    }
}
