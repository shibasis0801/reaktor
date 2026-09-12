package dev.shibasis.reaktor.flow.graph.layout

import dev.shibasis.reaktor.flow.graph.adapter.GraphNodeLayout
import dev.shibasis.reaktor.flow.graph.adapter.ReaktorFlowBuilder
import dev.shibasis.reaktor.flow.graph.adapter.graphLabel
import dev.shibasis.reaktor.flow.graph.adapter.regionColorForDepth
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import kotlin.math.max
import kotlin.math.min

/** Bounded typed-card packing over the original graph. No application names or fixture nodes. */
internal object TypedReaktorGraphLayoutStrategy : ReaktorGraphLayoutStrategy {
    override fun layout(
        builder: ReaktorFlowBuilder,
        graph: Graph,
        originX: Double,
        originY: Double,
        depth: Int,
        graphId: String,
    ): LayoutBounds {
        if (builder.style.layout.targetContentWidthPx > 0.0 && builder.style.layout.targetContentHeightPx > 0.0) {
            return ViewportTypedReaktorGraphLayoutStrategy.layout(builder, graph, originX, originY, depth, graphId)
        }
        builder.graphs[graphId] = graph
        val style = builder.style
        val typed = requireNotNull(style.typedNode)
        val focusedScope = builder.scopeView?.focusedScopeId?.takeIf(builder.scopeCatalog.scopes::containsKey)
            ?: ReaktorFlowScopeView.RootScopeId
        val cardWidth = style.node.minWidthPx
        val columnGap = style.layout.compactColumnGapPx
        val authoredColumns = if (graphId == focusedScope) typed.rootColumnCount else typed.scopeColumnCount
        // The canvas decides how many cards fit across. Fixed counts rendered BestBuds as a narrow
        // column down a wide window, and overflowed a narrow one.
        val columns = fittingColumns(style.layout.targetContentWidthPx, cardWidth, columnGap, authoredColumns)
            .coerceAtLeast(2)
        val contentLeft = originX + style.region.contentPaddingXPx
        val contentTop = originY + style.region.contentPaddingTopPx
        val containers = graph.nodes.filterIsInstance<ContainerNode>()
        // A route and its attached screens occupy adjacent columns. Other node families remain
        // independent blocks and wrap uniformly instead of forming unbounded parallel lanes.
        val blocks = typedLayoutBlocks(graph)
        var rowTop = contentTop
        var rowBottom = rowTop
        var column = 0
        var contentRight = contentLeft
        var contentBottom = contentTop
        fun place(node: Node, x: Double, y: Double): GraphNodeLayout {
            val placed = builder.createLayout(node, graph, x, y, widthOverride = cardWidth)
            builder.layouts[node] = placed
            builder.graphIdsByNode[node] = graphId
            contentRight = max(contentRight, placed.x + placed.width)
            contentBottom = max(contentBottom, placed.y + placed.height)
            return placed
        }
        blocks.forEach { block ->
            val span = if (block.attachments.isEmpty()) 1 else 2
            if (column > 0 && column + span > columns) {
                rowTop = rowBottom + style.layout.rowGapPx
                rowBottom = rowTop
                column = 0
            }
            val x = contentLeft + column * (cardWidth + columnGap)
            val placed = place(block.node, x, rowTop)
            rowBottom = max(rowBottom, placed.y + placed.height)
            var attachedY = rowTop
            block.attachments.forEach { attached ->
                val screen = place(attached, x + cardWidth + columnGap, attachedY)
                rowBottom = max(rowBottom, screen.y + screen.height)
                attachedY = screen.y + screen.height + style.layout.compactRowGapPx
            }
            column += span
        }

        val children = containers.flatMap(ContainerNode::graphs).distinctBy { builder.scopeCatalog.id(it) }
        if (children.isNotEmpty()) {
            val childTop = contentBottom + style.layout.rowGapPx
            val expanded = children.filter { builder.shouldExpand(requireNotNull(builder.scopeCatalog.id(it))) }
            val collapsed = children.filterNot { builder.shouldExpand(requireNotNull(builder.scopeCatalog.id(it))) }
            var expandedRight = contentLeft
            var expandedBottom = childTop
            // Expanded child regions wrap across the band. Stacking them down the left was the one
            // decision that made an expanded BestBuds 8,500px tall in a 1,800px-wide composition.
            val expandedBand = contentLeft +
                if (style.layout.targetContentWidthPx > 0.0) style.layout.targetContentWidthPx
                else (columns * cardWidth + (columns - 1) * columnGap)
            var rowLeft = contentLeft
            var rowTopExpanded = childTop
            var rowBottomExpanded = childTop
            expanded.forEach { child ->
                val layoutStart = builder.layouts.size
                val extraStart = builder.extraNodes.size
                val regionStart = builder.regions.size
                val measured = builder.layoutGraph(child,
                    style.region.boundsInsetXPx, style.region.boundsInsetTopPx,
                    depth + 1, requireNotNull(builder.scopeCatalog.id(child)))
                if (rowLeft > contentLeft && rowLeft + measured.width > expandedBand) {
                    rowLeft = contentLeft
                    rowTopExpanded = rowBottomExpanded + style.region.childRegionGapYPx
                }
                // Pack the actual compound bounds. A four-column estimate undercounted children
                // that used the full viewport and placed them outside the available band.
                val dx = rowLeft - measured.left
                val dy = rowTopExpanded - measured.top
                builder.layouts.entries.drop(layoutStart).forEach { (node, layout) ->
                    builder.layouts[node] = layout.copy(x = layout.x + dx, y = layout.y + dy)
                }
                for (index in extraStart until builder.extraNodes.size) {
                    val node = builder.extraNodes[index]
                    builder.extraNodes[index] = node.copy(position = node.position.copy(
                        x = node.position.x + dx, y = node.position.y + dy))
                }
                for (index in regionStart until builder.regions.size) {
                    val region = builder.regions[index]
                    builder.regions[index] = region.copy(x = region.x + dx, y = region.y + dy)
                }
                val bounds = LayoutBounds(rowLeft, rowTopExpanded,
                    rowLeft + measured.width, rowTopExpanded + measured.height)
                rowLeft = bounds.right + style.region.childRegionGapXPx
                rowBottomExpanded = max(rowBottomExpanded, bounds.bottom)
                expandedRight = max(expandedRight, bounds.right)
                expandedBottom = max(expandedBottom, bounds.bottom + style.region.childRegionGapYPx)
                contentRight = max(contentRight, bounds.right)
                contentBottom = max(contentBottom, bounds.bottom)
            }
            if (collapsed.isNotEmpty()) {
                // Collapsed summaries spread across the band too; two fixed columns left the
                // right third of a wide canvas empty under a long list of folded scopes.
                val summaryColumns = min(
                    fittingColumns(style.layout.targetContentWidthPx,
                        cardWidth + style.region.contentPaddingXPx + style.region.boundsInsetXPx * 2,
                        style.region.childRegionGapXPx, typed.summaryColumnCount).coerceAtLeast(1),
                    min(columns, collapsed.size),
                )
                val summaryWidth = cardWidth + style.region.contentPaddingXPx + style.region.boundsInsetXPx * 2
                val gridWidth = summaryColumns * summaryWidth + (summaryColumns - 1) * style.region.childRegionGapXPx
                // Region borders/padding consume real space too. This width budget is derived
                // from the same number of card columns, allowing expanded4 + summaries2 at root6.
                val regionColumnGap = max(columnGap,
                    style.region.contentPaddingXPx + style.region.boundsInsetXPx * 2 + style.region.childRegionGapXPx)
                val bandRight = contentLeft + columns * cardWidth + (columns - 1) * regionColumnGap
                val besideX = expandedRight + style.region.childRegionGapXPx
                val beside = expanded.isNotEmpty() && besideX + gridWidth <= bandRight
                val gridLeft = if (beside) besideX else contentLeft
                var gridTop = if (expanded.isEmpty() || beside) childTop else expandedBottom
                collapsed.chunked(summaryColumns).forEach { row ->
                    var rowRight = gridLeft
                    var bottom = gridTop
                    row.forEach { child ->
                        val bounds = builder.addScopeSummary(child,
                            requireNotNull(builder.scopeCatalog.id(child)),
                            rowRight + style.region.boundsInsetXPx,
                            gridTop + style.region.boundsInsetTopPx,
                            depth + 1)
                        rowRight = bounds.right + style.region.childRegionGapXPx
                        bottom = max(bottom, bounds.bottom)
                        contentRight = max(contentRight, bounds.right)
                        contentBottom = max(contentBottom, bounds.bottom)
                    }
                    gridTop = bottom + style.region.childRegionGapYPx
                }
            }
        }
        val bounds = LayoutBounds(
            originX - style.region.boundsInsetXPx,
            originY - style.region.boundsInsetTopPx,
            contentRight + style.region.contentPaddingXPx + style.region.boundsInsetXPx,
            contentBottom + style.region.contentPaddingBottomPx + style.region.boundsInsetBottomPx,
        )
        builder.regions += ReaktorGraphRegion(
            graphLabel(graph), graphId, bounds.left, bounds.top, bounds.width, bounds.height,
            regionColorForDepth(depth), depth,
        )
        return bounds
    }

}

internal data class TypedLayoutBlock(val node: Node, val attachments: List<Node> = emptyList())

internal fun typedLayoutBlocks(graph: Graph): List<TypedLayoutBlock> {
    val routes = graph.nodes.filterIsInstance<RouteNode<*, *>>()
    val containers = graph.nodes.filterIsInstance<ContainerNode>()
    val claimed = mutableSetOf<Node>()
    val attachments = routes.associateWith { route ->
        route.attachedNodes().filterIsInstance<Node>().filter { node ->
            node in graph.nodes && node !is ContainerNode && node !is RouteNode<*, *> && claimed.add(node)
        }
    }
    return graph.nodes.filter { it !is RouteNode<*, *> && it !is ContainerNode && it !in claimed }
        .map { TypedLayoutBlock(it) } + routes.map { TypedLayoutBlock(it, attachments.getValue(it)) } + containers.map { TypedLayoutBlock(it) }
}

/** How many cards of [cardWidth] fit across [target]; [authored] applies when no target is set. */
private fun fittingColumns(target: Double, cardWidth: Double, gap: Double, authored: Int): Int {
    if (target <= 0.0 || cardWidth <= 0.0) return authored
    return kotlin.math.floor((target + gap) / (cardWidth + gap)).toInt().coerceAtLeast(1)
}
