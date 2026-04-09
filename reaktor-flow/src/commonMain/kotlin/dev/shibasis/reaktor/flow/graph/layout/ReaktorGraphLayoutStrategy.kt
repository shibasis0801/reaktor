@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.shibasis.reaktor.flow.graph.layout

import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.flow.graph.adapter.GraphNodeLayout
import dev.shibasis.reaktor.flow.graph.adapter.ReaktorFlowBuilder
import dev.shibasis.reaktor.flow.graph.adapter.graphLabel
import dev.shibasis.reaktor.flow.graph.adapter.measureNodeWidth
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphPalette
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import kotlin.math.max

/**
 * Layout strategies own graph-space placement only.
 *
 * The default implementation is blueprint-style:
 * - semantic lanes for services / routes / screens / containers
 * - deterministic packing
 * - compound region boxes for nested graphs
 *
 * This keeps layout policy understandable and replaceable without mixing it into rendering or
 * generic canvas behavior.
 */
internal interface ReaktorGraphLayoutStrategy {
    fun layout(
        builder: ReaktorFlowBuilder,
        graph: Graph,
        originX: Double,
        originY: Double,
        depth: Int,
        graphId: String,
    ): LayoutBounds
}

internal data class LayoutBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

internal object BlueprintReaktorGraphLayoutStrategy : ReaktorGraphLayoutStrategy {
    override fun layout(
        builder: ReaktorFlowBuilder,
        graph: Graph,
        originX: Double,
        originY: Double,
        depth: Int,
        graphId: String,
    ): LayoutBounds {
        builder.graphs[graphId] = graph
        val services = mutableListOf<GraphNode>()
        val routes = mutableListOf<RouteNode<*, *>>()
        val screens = mutableListOf<GraphNode>()
        val containers = mutableListOf<GraphNode>()

        for (node in graph.nodes) when (node) {
            is ContainerNode -> containers += node
            is RouteNode<*, *> -> routes += node
            is ControllerNode<*> -> screens += node
            is BasicNode -> services += node
            else -> services += node
        }

        val style = builder.style
        val startY = originY + style.region.contentPaddingTopPx
        val localLayouts = mutableListOf<GraphNodeLayout>()

        fun place(layout: GraphNodeLayout) {
            builder.layouts[layout.graphNode] = layout
            builder.graphIdsByNode[layout.graphNode] = graphId
            localLayouts += layout
        }

        val routeAttachments = routes.associateWith { route ->
            route.attachedNodes()
                .mapNotNull { it as? GraphNode }
                .filter { attached -> attached in graph.nodes && attached !is ContainerNode }
        }
        val attachedScreens = routeAttachments.values.flatten().toSet()
        val standaloneScreens = screens.filterNot(attachedScreens::contains)
        val routeAttachedNodes: List<GraphNode> = routeAttachments.values.flatten()

        var maxRight = originX + style.region.contentPaddingXPx
        var maxBottom = startY

        val servicesWidth = services.maxOfOrNull { measureNodeWidth(it, style) } ?: style.node.minWidthPx
        val serviceColumns = preferredServiceColumns(services.size)
        val serviceColumnGap = style.layout.compactColumnGapPx
        val serviceAreaWidth = if (services.isEmpty()) {
            0.0
        } else {
            servicesWidth * serviceColumns + serviceColumnGap * (serviceColumns - 1)
        }
        var serviceColumnBottom = startY
        if (services.isNotEmpty()) {
            services.chunked(serviceColumns).forEach { row ->
                var rowBottom = serviceColumnBottom
                row.forEachIndexed { column, node ->
                    val layout = builder.createLayout(
                        node = node,
                        graph = graph,
                        x = originX + style.region.contentPaddingXPx + column * (servicesWidth + serviceColumnGap),
                        y = serviceColumnBottom,
                        widthOverride = servicesWidth,
                    )
                    place(layout)
                    rowBottom = max(rowBottom, layout.y + layout.height)
                    maxRight = max(maxRight, layout.x + layout.width)
                    maxBottom = max(maxBottom, layout.y + layout.height)
                }
                serviceColumnBottom = rowBottom + style.layout.rowGapPx
            }
        }

        val routeColumnX = originX + style.region.contentPaddingXPx +
            if (services.isNotEmpty()) serviceAreaWidth + style.layout.groupColumnGapPx else 0.0
        val routeWidth = routes.maxOfOrNull { measureNodeWidth(it, style) } ?: style.node.minWidthPx
        val screenWidth = (routeAttachedNodes + standaloneScreens)
            .maxOfOrNull { measureNodeWidth(it, style) }
            ?: style.node.minWidthPx
        val screenColumnX = routeColumnX + routeWidth + style.layout.columnGapPx
        if (services.isNotEmpty()) {
            maxRight = max(maxRight, routeColumnX)
        }

        val routeColumns = preferredRouteColumns(routes.size)
        val routeBlockWidth = routeWidth + if (routeAttachedNodes.isNotEmpty() || standaloneScreens.isNotEmpty()) {
            style.layout.columnGapPx + screenWidth
        } else {
            0.0
        }
        var routeLaneBottom = startY
        routes.chunked(routeColumns).forEach { routeRow ->
            var rowBottom = routeLaneBottom
            routeRow.forEachIndexed { column, route ->
                val routeX = routeColumnX + column * (routeBlockWidth + style.layout.compactColumnGapPx)
                val routeY = routeLaneBottom
                val routeLayout = builder.createLayout(
                    node = route,
                    graph = graph,
                    x = routeX,
                    y = routeY,
                    widthOverride = routeWidth,
                )
                place(routeLayout)
                var laneBottom = routeLayout.y + routeLayout.height
                var laneRight = routeLayout.x + routeLayout.width

                var attachedY = routeY
                routeAttachments.getValue(route).forEach { attached ->
                    val attachedLayout = builder.createLayout(
                        node = attached,
                        graph = graph,
                        x = routeX + routeWidth + style.layout.columnGapPx,
                        y = attachedY,
                        widthOverride = screenWidth,
                    )
                    place(attachedLayout)
                    attachedY = attachedLayout.y + attachedLayout.height + style.layout.compactRowGapPx
                    laneBottom = max(laneBottom, attachedLayout.y + attachedLayout.height)
                    laneRight = max(laneRight, attachedLayout.x + attachedLayout.width)
                }

                rowBottom = max(rowBottom, laneBottom)
                maxRight = max(maxRight, laneRight)
                maxBottom = max(maxBottom, laneBottom)
            }
            routeLaneBottom = rowBottom + style.layout.rowGapPx
        }

        if (standaloneScreens.isNotEmpty()) {
            val standaloneColumns = preferredStandaloneColumns(standaloneScreens.size)
            var standaloneTop = max(startY, routeLaneBottom)
            standaloneScreens.chunked(standaloneColumns).forEach { screenRow ->
                var rowBottom = standaloneTop
                screenRow.forEachIndexed { column, screen ->
                    val layout = builder.createLayout(
                        node = screen,
                        graph = graph,
                        x = screenColumnX + column * (screenWidth + style.layout.compactColumnGapPx),
                        y = standaloneTop,
                        widthOverride = screenWidth,
                    )
                    place(layout)
                    rowBottom = max(rowBottom, layout.y + layout.height)
                    maxRight = max(maxRight, layout.x + layout.width)
                    maxBottom = max(maxBottom, layout.y + layout.height)
                }
                standaloneTop = rowBottom + style.layout.rowGapPx
            }
        }

        var childGraphRight = maxRight
        var childGraphBottom = maxBottom
        if (containers.isNotEmpty()) {
            var currentY = max(maxBottom, serviceColumnBottom).let {
                if (localLayouts.isEmpty()) startY else it + style.layout.compactRowGapPx
            }
            for (containerNode in containers) {
                val layout = builder.createLayout(
                    node = containerNode,
                    graph = graph,
                    x = originX + style.region.contentPaddingXPx,
                    y = currentY,
                    widthOverride = max(servicesWidth, measureNodeWidth(containerNode, style)),
                )
                place(layout)
                maxRight = max(maxRight, layout.x + layout.width)
                maxBottom = max(maxBottom, layout.y + layout.height)

                if (containerNode is ContainerNode && containerNode.graphs.isNotEmpty()) {
                    val childStartX = layout.x + layout.width + style.layout.groupColumnGapPx
                    var childX = childStartX
                    var childY = currentY
                    var rowBottom = currentY
                    val childGraphsPerRow = preferredChildGraphsPerRow(containerNode.graphs.size)
                    containerNode.graphs.forEachIndexed { index, childGraph ->
                        if (index > 0 && index % childGraphsPerRow == 0) {
                            childX = childStartX
                            childY = rowBottom + style.region.childRegionGapYPx
                        }
                        val childBounds = builder.layoutGraph(
                            graph = childGraph,
                            originX = childX,
                            originY = childY,
                            depth = depth + 1,
                            graphId = "$graphId/$index",
                        )
                        childX = childBounds.right + style.region.childRegionGapXPx
                        rowBottom = max(rowBottom, childBounds.bottom)
                        childGraphRight = max(childGraphRight, childBounds.right)
                        childGraphBottom = max(childGraphBottom, childBounds.bottom)
                    }
                    currentY = max(layout.y + layout.height, rowBottom) + style.layout.compactRowGapPx
                } else {
                    currentY += layout.height + style.layout.rowGapPx
                }
                maxBottom = max(maxBottom, currentY)
            }
        }

        val contentRight = max(
            childGraphRight,
            localLayouts.maxOfOrNull { it.x + it.width } ?: originX + style.region.contentPaddingXPx,
        )
        val contentBottom = max(
            childGraphBottom,
            localLayouts.maxOfOrNull { it.y + it.height } ?: startY,
        )
        val bounds = LayoutBounds(
            left = originX - style.region.boundsInsetXPx,
            top = originY - style.region.boundsInsetTopPx,
            right = contentRight + style.region.contentPaddingXPx + style.region.boundsInsetXPx,
            bottom = contentBottom + style.region.contentPaddingBottomPx + style.region.boundsInsetBottomPx,
        )
        builder.regions += ReaktorGraphRegion(
            label = graphLabel(graph),
            id = graphId,
            x = bounds.left,
            y = bounds.top,
            width = bounds.width,
            height = bounds.height,
            color = regionColor(depth),
            depth = depth,
        )
        return bounds
    }

    private fun preferredChildGraphsPerRow(childGraphCount: Int): Int = when {
        childGraphCount <= 2 -> childGraphCount
        childGraphCount <= 9 -> 3
        else -> 4
    }

    private fun preferredServiceColumns(serviceCount: Int): Int = when {
        serviceCount <= 2 -> 1
        serviceCount <= 8 -> 2
        else -> 3
    }

    private fun preferredRouteColumns(routeCount: Int): Int = when {
        routeCount <= 2 -> 1
        routeCount <= 16 -> 2
        routeCount <= 28 -> 3
        else -> 4
    }

    private fun preferredStandaloneColumns(screenCount: Int): Int = when {
        screenCount <= 2 -> 1
        screenCount <= 12 -> 2
        screenCount <= 20 -> 3
        else -> 4
    }

    private fun regionColor(depth: Int): Color = when (depth) {
        0 -> ReaktorGraphPalette.rootRegion
        1 -> ReaktorGraphPalette.nestedRegion
        else -> ReaktorGraphPalette.deepRegion
    }
}
