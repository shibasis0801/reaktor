package dev.shibasis.reaktor.flow.graph.model

import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** One scope in a layered band: which scope, what to call it, and the tint of its region. */
data class ReaktorLayoutBandScope(val scopeId: String, val label: String, val color: Color)

/**
 * Lays an architecture overlay out as a dependency flow, read left to right.
 *
 * [toGroupedFlowGraph] packs each scope into a roughly square block and shelves the blocks. That is
 * the right answer when nothing is known about an overlay's shape, and the wrong answer whenever
 * the overlay *is* a flow: it produces a block of near-identical consumer cards beside a block of
 * provider cards, with every relationship crossing the gap in both directions, and nothing in the
 * picture says which way a relationship points.
 *
 * This lays the same overlay out by causality instead:
 *
 * - **Columns are causal.** An element nothing points at sits leftmost; an element others point at
 *   sits to their right, at the depth of the longest chain reaching it. Every relationship
 *   therefore runs rightwards, and a column index is a dependency depth.
 * - **Rows are ordered to straighten the wires.** Within a column, elements are placed at the mean
 *   row of what they connect to, swept in both directions — the standard barycentre heuristic,
 *   which turns a crossing mesh into readable bundles.
 * - **Deep columns wrap into lanes.** A column of twenty-one cards is a column nobody can read:
 *   framed to fit, it drops the whole graph to a zoom where no label survives. Lanes are sized so
 *   the band lands near the viewport's own proportions, and the barycentre order carries over.
 * - **Bands stack.** Each entry in [bands] is a row of scopes laid out under the previous one, for
 *   parts of an overlay that share no relationship and should not be interleaved.
 *
 * Scope order inside a band is a left-to-right priority: an element never sits left of an element
 * whose scope precedes it, whatever the relationships would allow. That keeps each scope one
 * contiguous, labelled rectangle.
 */
fun ReaktorArchitectureOverlay.toLayeredFlowGraph(
    style: ReaktorGraphStyle,
    bands: List<List<ReaktorLayoutBandScope>>,
    viewportAspectRatio: Double = 2.0,
): ReaktorFlowGraph {
    val empty = ReaktorFlowGraph(
        emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), style,
    )
    val projection = empty.withArchitectureOverlay(this)
    if (elements.isEmpty()) return projection

    val nodeWidth = style.defaultNodeWidth() * 1.08
    val nodeHeight = style.defaultNodeHeight()
    val columnGap = style.layout.columnGapPx * 1.6
    val rowGap = style.layout.rowGapPx * 0.7
    val bandGap = style.layout.groupColumnGapPx * 1.4
    val origin = style.layout.rootOriginPx
    val regionPadding = style.region.contentPaddingTopPx

    val scopeOf = elements.associate { it.id to it.scopeId }
    fun edgesWithin(ids: Set<String>) = relationships.filter { it.source in ids && it.target in ids }

    // Longest-path layering with back edges dropped: a relationship cycle must not be allowed to
    // make the picture unreadable as well as being a defect.
    fun layers(ids: List<String>): MutableMap<String, Int> {
        val outgoing = edgesWithin(ids.toSet()).groupBy({ it.source }, { it.target })
        val layer = ids.associateWith { 0 }.toMutableMap()
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun walk(id: String): Int {
            if (id in done) return layer.getValue(id)
            if (!visiting.add(id)) return layer.getValue(id)
            val depth = outgoing[id].orEmpty().distinct().filter { it != id }
                .maxOfOrNull { max(layer.getValue(it), walk(it)) + 1 } ?: 0
            visiting.remove(id)
            done.add(id)
            layer[id] = depth
            return depth
        }
        ids.forEach { walk(it) }
        // A relationship points consumer -> provider and the walk measures how deep a consumer
        // reaches, so invert it: providers stand right of everything that points at them.
        val deepest = layer.values.maxOrNull() ?: 0
        return layer.mapValues { (_, depth) -> deepest - depth }.toMutableMap()
    }

    val positions = mutableMapOf<String, XYPosition>()
    val regions = mutableListOf<ReaktorGraphRegion>()
    var top = origin

    bands.forEach { band ->
        val scopeIds = band.map { it.scopeId }
        val ids = elements.filter { it.scopeId in scopeIds }.map { it.id }
        if (ids.isEmpty()) return@forEach
        val layer = layers(ids)
        // Scope order is a hard left-to-right priority, applied after the causal layering so a
        // provider scope cannot be pulled left among the scopes that consume it.
        val leadScope = band.first().scopeId
        val deepestLead = ids.filter { scopeOf[it] == leadScope }.maxOfOrNull { layer.getValue(it) } ?: 0
        ids.forEach { id ->
            val rank = scopeIds.indexOf(scopeOf[id])
            if (rank > 0) layer[id] = deepestLead + rank
        }

        val columns = ids.groupBy { layer.getValue(it) }.entries.sortedBy { it.key }.associate { it.toPair() }
            .mapValues { (_, members) ->
                members.sortedBy { id -> elements.first { it.id == id }.label.lowercase() }.toMutableList()
            }
        val neighbours = buildMap<String, MutableList<String>> {
            edgesWithin(ids.toSet()).forEach { relation ->
                getOrPut(relation.source) { mutableListOf() }.add(relation.target)
                getOrPut(relation.target) { mutableListOf() }.add(relation.source)
            }
        }
        val order = mutableMapOf<String, Double>()
        columns.forEach { (_, members) -> members.forEachIndexed { index, id -> order[id] = index.toDouble() } }
        repeat(6) { pass ->
            val keys = if (pass % 2 == 0) columns.keys.toList() else columns.keys.reversed()
            keys.forEach { column ->
                val members = columns.getValue(column)
                val scored = members.map { id ->
                    val peers = neighbours[id].orEmpty().mapNotNull(order::get)
                    id to (if (peers.isEmpty()) order.getValue(id) else peers.average())
                }.sortedBy { it.second }
                members.clear()
                members.addAll(scored.map { it.first })
                members.forEachIndexed { index, id -> order[id] = index.toDouble() }
            }
        }

        val aspect = viewportAspectRatio.coerceIn(0.6, 4.0)
        val maxRows = ceil(sqrt(ids.size * (nodeWidth + columnGap) / ((nodeHeight + rowGap) * aspect)))
            .toInt().coerceIn(4, 14)
        val lanes = columns.values.flatMap { members -> members.chunked(maxRows) }
        val tallest = lanes.maxOf { it.size }
        val bandHeight = tallest * (nodeHeight + rowGap) - rowGap
        var x = origin
        lanes.forEach { members ->
            // Short lanes are centred against the tallest, so a lane of two does not read as a lane
            // of two that happens to start at the top.
            val offset = (bandHeight - (members.size * (nodeHeight + rowGap) - rowGap)) / 2
            members.forEachIndexed { index, id ->
                positions[id] = XYPosition(x, top + regionPadding + offset + index * (nodeHeight + rowGap))
            }
            x += nodeWidth + columnGap
        }
        band.forEach { scope ->
            val members = ids.filter { scopeOf[it] == scope.scopeId }.mapNotNull(positions::get)
            if (members.isEmpty()) return@forEach
            val left = members.minOf { it.x }
            val right = members.maxOf { it.x } + nodeWidth
            val topEdge = members.minOf { it.y }
            val bottom = members.maxOf { it.y } + nodeHeight
            regions += ReaktorGraphRegion(
                scope.label, scope.scopeId,
                left - columnGap / 3, topEdge - regionPadding,
                right - left + columnGap * 2 / 3, bottom - topEdge + regionPadding + rowGap,
                scope.color, 0,
            )
        }
        top += regionPadding + bandHeight + rowGap + bandGap
    }

    return projection.copy(
        nodes = projection.nodes.mapNotNull { node ->
            positions[node.id]?.let { node.copy(position = it, width = nodeWidth, height = nodeHeight) }
        },
        regions = regions,
    )
}
