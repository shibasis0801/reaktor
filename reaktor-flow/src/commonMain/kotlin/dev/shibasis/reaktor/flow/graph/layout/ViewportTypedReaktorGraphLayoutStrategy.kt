package dev.shibasis.reaktor.flow.graph.layout

import dev.shibasis.reaktor.flow.graph.adapter.GraphNodeLayout
import dev.shibasis.reaktor.flow.graph.adapter.ReaktorFlowBuilder
import dev.shibasis.reaktor.flow.graph.adapter.graphLabel
import dev.shibasis.reaktor.flow.graph.adapter.regionColorForDepth
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import kotlin.math.max
import kotlin.math.sqrt

/** Pack measured subtrees along the viewport's long axis, balancing dense scopes by their area. */
internal object ViewportTypedReaktorGraphLayoutStrategy : ReaktorGraphLayoutStrategy {
    override fun layout(builder: ReaktorFlowBuilder, graph: Graph, originX: Double, originY: Double,
        depth: Int, graphId: String): LayoutBounds {
        builder.graphs[graphId] = graph
        val style = builder.style
        val horizontal = style.layout.targetContentWidthPx >= style.layout.targetContentHeightPx
        val contentLeft = originX + style.region.contentPaddingXPx
        val contentTop = originY + style.region.contentPaddingTopPx
        val availableMinor = if (horizontal) {
            style.layout.targetContentHeightPx - style.region.contentPaddingTopPx - style.region.contentPaddingBottomPx -
                style.region.boundsInsetTopPx - style.region.boundsInsetBottomPx
        } else {
            style.layout.targetContentWidthPx - style.region.contentPaddingXPx * 2 - style.region.boundsInsetXPx * 2
        }
        val blocks = typedLayoutBlocks(graph).map { block ->
            val card = builder.createLayout(block.node, graph, 0.0, 0.0, style.node.minWidthPx)
            val attachments = block.attachments.map { builder.createLayout(it, graph, 0.0, 0.0, style.node.minWidthPx) }
            MeasuredBlock(card, attachments,
                if (attachments.isEmpty()) card.width else card.width * 2 + style.layout.compactColumnGapPx,
                max(card.height, attachments.sumOf { it.height } +
                    (attachments.size - 1).coerceAtLeast(0) * style.layout.compactRowGapPx))
        }
        val children = graph.nodes.filterIsInstance<ContainerNode>().flatMap(ContainerNode::graphs)
            .distinctBy { builder.scopeCatalog.id(it) }.map { child ->
                val childId = requireNotNull(builder.scopeCatalog.id(child))
                val layoutsStart = builder.layouts.size
                val extrasStart = builder.extraNodes.size
                val regionsStart = builder.regions.size
                val bounds = if (builder.shouldExpand(childId)) {
                    builder.layoutGraph(child, style.region.boundsInsetXPx, style.region.boundsInsetTopPx, depth + 1, childId)
                } else {
                    builder.addScopeSummary(child, childId, style.region.boundsInsetXPx, style.region.boundsInsetTopPx, depth + 1)
                }
                MeasuredSubtree(bounds, builder.layouts.keys.drop(layoutsStart),
                    extrasStart until builder.extraNodes.size, regionsStart until builder.regions.size)
            }
        // A literal viewport-height cap turns a large expanded graph into an enormous strip.
        // Let dense scopes grow on the short axis as well, proportional to measured content area.
        val area = blocks.sumOf { (it.width + style.layout.compactColumnGapPx) * (it.height + style.layout.rowGapPx) } +
            children.sumOf { (it.bounds.width + style.region.childRegionGapXPx) * (it.bounds.height + style.region.childRegionGapYPx) }
        val aspect = if (horizontal) style.layout.targetContentWidthPx / style.layout.targetContentHeightPx
            else style.layout.targetContentHeightPx / style.layout.targetContentWidthPx
        val minorExtent = max(availableMinor, sqrt(area / aspect))
        val packing = ViewportPacking(horizontal, minorExtent, style.layout.compactColumnGapPx, style.layout.rowGapPx)
        var right = contentLeft
        var bottom = contentTop
        fun place(measured: GraphNodeLayout, x: Double, y: Double) {
            val placed = measured.copy(x = x, y = y)
            builder.layouts[placed.graphNode] = placed
            builder.graphIdsByNode[placed.graphNode] = graphId
            right = max(right, x + placed.width)
            bottom = max(bottom, y + placed.height)
        }
        blocks.forEach { block ->
            val (x, y) = packing.place(block.width, block.height)
            place(block.card, contentLeft + x, contentTop + y)
            var attachedY = contentTop + y
            block.attachments.forEach { attached ->
                place(attached, contentLeft + x + block.card.width + style.layout.compactColumnGapPx, attachedY)
                attachedY += attached.height + style.layout.compactRowGapPx
            }
        }
        val childLeft = if (horizontal) right + style.region.childRegionGapXPx else contentLeft
        val childTop = if (horizontal) contentTop else bottom + style.region.childRegionGapYPx
        val childPacking = ViewportPacking(horizontal, minorExtent, style.region.childRegionGapXPx, style.region.childRegionGapYPx)
        children.forEach { child ->
            val measured = child.bounds
            val (x, y) = childPacking.place(measured.width, measured.height)
            val dx = childLeft + x - measured.left
            val dy = childTop + y - measured.top
            // Move the whole measured subtree, including its folded summaries and nested regions.
            child.nodes.forEach { node ->
                val layout = builder.layouts.getValue(node)
                builder.layouts[node] = layout.copy(x = layout.x + dx, y = layout.y + dy)
            }
            for (index in child.extras) {
                val node = builder.extraNodes[index]
                builder.extraNodes[index] = node.copy(position = node.position.copy(x = node.position.x + dx, y = node.position.y + dy))
            }
            for (index in child.regions) {
                val region = builder.regions[index]
                builder.regions[index] = region.copy(x = region.x + dx, y = region.y + dy)
            }
            right = max(right, childLeft + x + measured.width)
            bottom = max(bottom, childTop + y + measured.height)
        }
        val bounds = LayoutBounds(originX - style.region.boundsInsetXPx, originY - style.region.boundsInsetTopPx,
            right + style.region.contentPaddingXPx + style.region.boundsInsetXPx,
            bottom + style.region.contentPaddingBottomPx + style.region.boundsInsetBottomPx)
        builder.regions += ReaktorGraphRegion(graphLabel(graph), graphId, bounds.left, bounds.top,
            bounds.width, bounds.height, regionColorForDepth(depth), depth)
        return bounds
    }
}

/** Rectangle packing independent of graph identities, node families and the UI toolkit. */
private class ViewportPacking(val horizontal: Boolean, val minorExtent: Double, val gapX: Double, val gapY: Double) {
    private var major = 0.0
    private var minor = 0.0
    private var bandSize = 0.0

    fun place(width: Double, height: Double): Pair<Double, Double> {
        val nextMinor = if (horizontal) height else width
        val nextMajor = if (horizontal) width else height
        if (minor > 0.0 && minor + nextMinor > minorExtent) {
            major += bandSize + if (horizontal) gapX else gapY
            minor = 0.0
            bandSize = 0.0
        }
        val position = if (horizontal) major to minor else minor to major
        minor += nextMinor + if (horizontal) gapY else gapX
        bandSize = max(bandSize, nextMajor)
        return position
    }
}

private data class MeasuredBlock(val card: GraphNodeLayout, val attachments: List<GraphNodeLayout>, val width: Double, val height: Double)
private data class MeasuredSubtree(val bounds: LayoutBounds, val nodes: List<dev.shibasis.reaktor.graph.core.node.Node>,
    val extras: IntRange, val regions: IntRange)
