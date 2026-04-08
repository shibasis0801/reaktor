package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.composeflow.model.Edge
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.math.max

internal data class GraphNodeLayout(
    val flowId: String,
    val graphNode: GraphNode,
    val graph: Graph,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val providerCount: Int,
    val consumerCount: Int,
    val providerPorts: List<ReaktorPortData>,
    val consumerPorts: List<ReaktorPortData>,
    val hiddenProviderCount: Int,
    val hiddenConsumerCount: Int,
)

internal data class LayoutBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

fun buildReaktorFlowGraph(
    graph: Graph,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): ReaktorFlowGraph = ReaktorFlowBuilder(style).build(graph)

// Build-state owns traversal/layout only. Edge assembly and render-facing node construction are
// kept in sibling files so semantic graph extraction stays separate from rendering concerns.
internal class ReaktorFlowBuilder(
    internal val style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
) {
    internal val layouts = linkedMapOf<GraphNode, GraphNodeLayout>()
    internal val flowIdsByNode = linkedMapOf<GraphNode, String>()
    internal val graphIdsByNode = linkedMapOf<GraphNode, String>()
    internal val graphNodes = linkedMapOf<String, GraphNode>()
    internal val edges = linkedMapOf<String, Edge>()
    internal val regions = mutableListOf<ReaktorGraphRegion>()
    internal val graphs = linkedMapOf<String, Graph>()

    internal fun build(graph: Graph): ReaktorFlowGraph {
        layoutGraph(graph, style.layout.rootOriginPx, style.layout.rootOriginPx, 0, "root")
        resolveEdges(graph)
        return ReaktorFlowGraph(
            nodes = layouts.values.map(::toFlowNode),
            edges = edges.values.toList(),
            regions = regions.toList(),
            graphNodes = graphNodes.toMap(),
            flowIdsByNode = flowIdsByNode.toMap(),
            graphIdsByNode = graphIdsByNode.toMap(),
            graphs = graphs.toMap(),
            style = style,
        )
    }

    internal fun layoutGraph(
        graph: Graph,
        originX: Double,
        originY: Double,
        depth: Int,
        graphId: String,
    ): LayoutBounds {
        graphs[graphId] = graph
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

        val startY = originY + style.region.graphInsetPx * 2.0
        val localLayouts = mutableListOf<GraphNodeLayout>()

        fun place(layout: GraphNodeLayout) {
            layouts[layout.graphNode] = layout
            graphIdsByNode[layout.graphNode] = graphId
            localLayouts += layout
        }

        val routeAttachments: Map<RouteNode<*, *>, List<GraphNode>> = routes.associateWith { route ->
            route.attachedNodes()
                .mapNotNull { it as? GraphNode }
                .filter { attached -> attached in graph.nodes && attached !is ContainerNode }
        }
        val attachedScreens = routeAttachments.values.flatten().toSet()
        val standaloneScreens = screens.filterNot(attachedScreens::contains)
        val routeAttachedNodes: List<GraphNode> = routeAttachments.values.flatten()

        var maxRight = originX + style.region.graphInsetPx
        var maxBottom = startY

        val servicesWidth = services.maxOfOrNull { measureNodeWidth(it, style) } ?: style.node.minWidthPx
        val serviceColumns = preferredServiceColumns(services.size)
        val serviceColumnGap = style.layout.compactGapPx
        val serviceAreaWidth = if (services.isEmpty()) {
            0.0
        } else {
            servicesWidth * serviceColumns + serviceColumnGap * (serviceColumns - 1)
        }
        var serviceColumnBottom = startY
        if (services.isNotEmpty()) {
            services
                .chunked(serviceColumns)
                .forEach { row ->
                    var rowBottom = serviceColumnBottom
                    row.forEachIndexed { column, node ->
                        val layout = createLayout(
                            node = node,
                            graph = graph,
                            x = originX + style.region.graphInsetPx + column * (servicesWidth + serviceColumnGap),
                            y = serviceColumnBottom,
                            widthOverride = servicesWidth,
                        )
                        place(layout)
                        rowBottom = max(rowBottom, layout.y + layout.height)
                        maxRight = max(maxRight, layout.x + layout.width)
                        maxBottom = max(maxBottom, layout.y + layout.height)
                    }
                    serviceColumnBottom = rowBottom + style.layout.gapPx
                }
        }

        val routeColumnX = originX + style.region.graphInsetPx +
            if (services.isNotEmpty()) serviceAreaWidth + style.layout.majorGapPx else 0.0

        val routeWidth = routes.maxOfOrNull { measureNodeWidth(it, style) } ?: style.node.minWidthPx
        val screenWidth = (routeAttachedNodes + standaloneScreens)
            .maxOfOrNull { measureNodeWidth(it, style) }
            ?: style.node.minWidthPx
        val screenColumnX = routeColumnX + routeWidth + style.layout.gapPx
        if (services.isNotEmpty()) {
            maxRight = max(maxRight, routeColumnX)
        }

        val routeColumns = preferredRouteColumns(routes.size)
        val routeBlockWidth = routeWidth + if (routeAttachedNodes.isNotEmpty() || standaloneScreens.isNotEmpty()) {
            style.layout.gapPx + screenWidth
        } else {
            0.0
        }
        var routeLaneBottom = startY
        routes.chunked(routeColumns).forEach { routeRow ->
            var rowBottom = routeLaneBottom
            routeRow.forEachIndexed { column, route ->
                val routeX = routeColumnX + column * (routeBlockWidth + style.layout.compactGapPx)
                val routeY = routeLaneBottom
                val routeLayout = createLayout(
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
                routeAttachments.getValue(route).forEach { attached: GraphNode ->
                    val attachedLayout = createLayout(
                        node = attached,
                        graph = graph,
                        x = routeX + routeWidth + style.layout.gapPx,
                        y = attachedY,
                        widthOverride = screenWidth,
                    )
                    place(attachedLayout)
                    attachedY = attachedLayout.y + attachedLayout.height + style.layout.compactGapPx
                    laneBottom = max(laneBottom, attachedLayout.y + attachedLayout.height)
                    laneRight = max(laneRight, attachedLayout.x + attachedLayout.width)
                }

                rowBottom = max(rowBottom, laneBottom)
                maxRight = max(maxRight, laneRight)
                maxBottom = max(maxBottom, laneBottom)
            }
            routeLaneBottom = rowBottom + style.layout.gapPx
        }

        if (standaloneScreens.isNotEmpty()) {
            val standaloneColumns = preferredStandaloneColumns(standaloneScreens.size)
            var standaloneTop = max(startY, routeLaneBottom)
            standaloneScreens.chunked(standaloneColumns).forEach { screenRow ->
                var rowBottom = standaloneTop
                screenRow.forEachIndexed { column, screen ->
                    val layout = createLayout(
                        node = screen,
                        graph = graph,
                        x = screenColumnX + column * (screenWidth + style.layout.compactGapPx),
                        y = standaloneTop,
                        widthOverride = screenWidth,
                    )
                    place(layout)
                    rowBottom = max(rowBottom, layout.y + layout.height)
                    maxRight = max(maxRight, layout.x + layout.width)
                    maxBottom = max(maxBottom, layout.y + layout.height)
                }
                standaloneTop = rowBottom + style.layout.gapPx
            }
        }

        var childGraphRight = maxRight
        var childGraphBottom = maxBottom
        if (containers.isNotEmpty()) {
            var currentY = max(maxBottom, serviceColumnBottom).let {
                if (localLayouts.isEmpty()) startY else it + style.layout.compactGapPx
            }
            for (containerNode in containers) {
                val layout = createLayout(
                    node = containerNode,
                    graph = graph,
                    x = originX + style.region.graphInsetPx,
                    y = currentY,
                    widthOverride = max(servicesWidth, measureNodeWidth(containerNode, style)),
                )
                place(layout)
                maxRight = max(maxRight, layout.x + layout.width)
                maxBottom = max(maxBottom, layout.y + layout.height)

                if (containerNode is ContainerNode && containerNode.graphs.isNotEmpty()) {
                    val childStartX = layout.x + layout.width + style.layout.majorGapPx
                    var childX = childStartX
                    var childY = currentY
                    var rowBottom = currentY
                    val childGraphsPerRow = preferredChildGraphsPerRow(containerNode.graphs.size)
                    containerNode.graphs.forEachIndexed { index, childGraph ->
                        if (index > 0 && index % childGraphsPerRow == 0) {
                            childX = childStartX
                            childY = rowBottom + style.layout.compactGapPx
                        }
                        val childBounds = layoutGraph(
                            graph = childGraph,
                            originX = childX,
                            originY = childY,
                            depth = depth + 1,
                            graphId = "$graphId/$index",
                        )
                        childX = childBounds.right + style.region.graphInsetPx
                        rowBottom = max(rowBottom, childBounds.bottom)
                        childGraphRight = max(childGraphRight, childBounds.right)
                        childGraphBottom = max(childGraphBottom, childBounds.bottom)
                    }
                    currentY = max(layout.y + layout.height, rowBottom) + style.layout.compactGapPx
                } else {
                    currentY += layout.height + style.layout.gapPx
                }
                maxBottom = max(maxBottom, currentY)
            }
        }

        val contentRight = max(
            childGraphRight,
            localLayouts.maxOfOrNull { it.x + it.width } ?: originX + style.region.graphInsetPx,
        )
        val contentBottom = max(
            childGraphBottom,
            localLayouts.maxOfOrNull { it.y + it.height } ?: startY,
        )
        val bounds = LayoutBounds(
            left = originX - style.region.insetXPx,
            top = originY - style.region.insetTopPx,
            right = contentRight + style.region.graphInsetPx + style.region.insetXPx,
            bottom = contentBottom + style.region.graphInsetPx + style.region.insetBottomPx,
        )
        regions += ReaktorGraphRegion(
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

    internal fun createLayout(
        node: GraphNode,
        graph: Graph,
        x: Double,
        y: Double,
        widthOverride: Double? = null,
    ): GraphNodeLayout {
        val allProviderPorts = visiblePorts(node.providerPorts.flattenedValues().toList())
        val allConsumerPorts = visiblePorts(node.consumerPorts.flattenedValues().toList())
        val width = widthOverride ?: measureNodeWidth(node, style)
        val height = measureNodeHeight(
            providerCount = allProviderPorts.size,
            consumerCount = allConsumerPorts.size,
            style = style,
        )
        val flowId = flowIdsByNode.getOrPut(node) { "${graphLabel(graph)}::${node.id}" }
        graphNodes[flowId] = node

        return GraphNodeLayout(
            flowId = flowId,
            graphNode = node,
            graph = graph,
            x = x,
            y = y,
            width = width,
            height = height,
            providerCount = allProviderPorts.size,
            consumerCount = allConsumerPorts.size,
            providerPorts = allProviderPorts,
            consumerPorts = allConsumerPorts,
            hiddenProviderCount = 0,
            hiddenConsumerCount = 0,
        )
    }

}
