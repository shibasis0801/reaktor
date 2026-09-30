package dev.shibasis.reaktor.flow.graph.model

import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/** A wide grid of source groups, using the shared interactive graph renderer. No runtime edges are invented. */
fun ReaktorArchitectureOverlay.toGroupedFlowGraph(style: ReaktorGraphStyle, focusId: String? = null, neighborsOnly: Boolean = false, viewportAspectRatio: Double = 2.0): ReaktorFlowGraph {
    val ids = if (neighborsOnly && focusId != null) relationships.filter { it.source == focusId || it.target == focusId }
        .flatMap { listOf(it.source, it.target) }.toSet() + focusId else elements.mapTo(hashSetOf()) { it.id }
    val visible = copy(elements = elements.filter { it.id in ids }, relationships = relationships.filter { it.source in ids && it.target in ids })
    val empty = ReaktorFlowGraph(emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), style)
    val projection = empty.withArchitectureOverlay(visible)
    val nodeWidth = style.defaultNodeWidth() * 1.08
    val nodeHeight = style.defaultNodeHeight()
    val gapX = style.layout.columnGapPx
    val gapY = style.layout.rowGapPx
    val positions = mutableMapOf<String, XYPosition>()
    val regions = mutableListOf<ReaktorGraphRegion>()
    data class Group(val id: String, val members: List<ReaktorArchitectureElement>, val columns: Int) {
        val width = columns * (nodeWidth + gapX) - gapX
        val height = ceil(members.size.toDouble() / columns) * (nodeHeight + gapY) + style.region.contentPaddingTopPx
    }
    val groups = visible.elements.groupBy { it.scopeId }.map { (id, members) ->
        // A single large group must also use horizontal space. A fixed four-column cap
        // produced tall node/record pages even when the whole canvas was ultrawide.
        val aspect = viewportAspectRatio.coerceIn(0.5, 4.0)
        val columns = ceil(sqrt(members.size * aspect * (nodeHeight + gapY) / (nodeWidth + gapX)))
            .toInt().coerceIn(1, members.size)
        Group(id, members, columns)
    }
    if (groups.isEmpty()) return projection
    val separation = gapX * 3
    fun pack(limit: Double): List<XYPosition> {
        var x = 0.0
        var y = 0.0
        var rowHeight = 0.0
        return groups.map { group ->
            if (x > 0 && x + group.width > limit) { x = 0.0; y += rowHeight + gapY * 2; rowHeight = 0.0 }
            XYPosition(x, y).also { x += group.width + separation; rowHeight = maxOf(rowHeight, group.height) }
        }
    }
    // Choose a shelf packing near the canvas aspect ratio. One unbroken row makes a large
    // workspace illegible when framed, while forcing every group below the previous wastes width.
    val minWidth = groups.maxOf { it.width }
    val maxWidth = groups.sumOf { it.width } + separation * (groups.size - 1)
    val area = groups.sumOf { it.width * it.height }
    val origins = (0..32).map { pack(minWidth + (maxWidth - minWidth) * it / 32) }.minBy { candidate ->
        val w = groups.indices.maxOf { candidate[it].x + groups[it].width }
        val h = groups.indices.maxOf { candidate[it].y + groups[it].height }
        abs(ln((w / h) / viewportAspectRatio.coerceIn(0.5, 4.0))) + (w * h / area - 1) * 0.2
    }
    groups.forEachIndexed { groupIndex, group ->
        val x = style.layout.rootOriginPx + origins[groupIndex].x
        val top = style.layout.rootOriginPx + origins[groupIndex].y + style.region.contentPaddingTopPx
        group.members.forEachIndexed { index, node -> positions[node.id] = XYPosition(x + index % group.columns * (nodeWidth + gapX), top + index / group.columns * (nodeHeight + gapY)) }
        val groupWidth = group.width
        regions += ReaktorGraphRegion(group.id.replaceFirstChar(Char::uppercase), group.id,
            x - gapX / 3, top - style.region.contentPaddingTopPx, groupWidth + gapX * 2 / 3,
            group.height,
            ReaktorNodeKind.Container.borderColor, 0)
    }
    return projection.copy(nodes = projection.nodes.map { it.copy(position = positions.getValue(it.id), width = nodeWidth, height = nodeHeight) }, regions = regions)
}
