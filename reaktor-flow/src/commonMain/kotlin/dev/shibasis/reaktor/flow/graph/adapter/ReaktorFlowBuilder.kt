@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.shibasis.reaktor.flow.graph.adapter

import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.MarkerType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.layout.GraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.math.max

private data class GraphNodeLayout(
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

private data class LayoutBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

fun buildReaktorFlowGraph(graph: Graph): ReaktorFlowGraph =
    ReaktorFlowBuilder().build(graph)

private class ReaktorFlowBuilder {
    private val layouts = linkedMapOf<GraphNode, GraphNodeLayout>()
    private val flowIdsByNode = linkedMapOf<GraphNode, String>()
    private val graphIdsByNode = linkedMapOf<GraphNode, String>()
    private val graphNodes = linkedMapOf<String, GraphNode>()
    private val edges = linkedMapOf<String, Edge>()
    private val regions = mutableListOf<ReaktorGraphRegion>()
    private val graphs = linkedMapOf<String, Graph>()

    fun build(graph: Graph): ReaktorFlowGraph {
        layoutGraph(graph, 80.0, 80.0, 0, "root")
        resolveEdges(graph)
        return ReaktorFlowGraph(
            nodes = layouts.values.map(::toFlowNode),
            edges = edges.values.toList(),
            regions = regions.toList(),
            graphNodes = graphNodes.toMap(),
            flowIdsByNode = flowIdsByNode.toMap(),
            graphIdsByNode = graphIdsByNode.toMap(),
            graphs = graphs.toMap(),
        )
    }

    private fun layoutGraph(
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

        val startY = originY + GraphFlowMetrics.subgraphHeader + GraphFlowMetrics.subgraphPadding
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

        var maxRight = originX + GraphFlowMetrics.subgraphPadding
        var maxBottom = startY

        val servicesWidth = services.maxOfOrNull(::measureWidth) ?: GraphFlowMetrics.nodeMinWidth
        val serviceColumns = preferredServiceColumns(services.size)
        val serviceColumnGap = GraphFlowMetrics.serviceColumnGap
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
                            x = originX + GraphFlowMetrics.subgraphPadding + column * (servicesWidth + serviceColumnGap),
                            y = serviceColumnBottom,
                            widthOverride = servicesWidth,
                        )
                        place(layout)
                        rowBottom = max(rowBottom, layout.y + layout.height)
                        maxRight = max(maxRight, layout.x + layout.width)
                        maxBottom = max(maxBottom, layout.y + layout.height)
                    }
                    serviceColumnBottom = rowBottom + GraphFlowMetrics.rowGap
                }
        }

        val routeColumnX = originX + GraphFlowMetrics.subgraphPadding +
            if (services.isNotEmpty()) serviceAreaWidth + GraphFlowMetrics.layerGap else 0.0

        val routeWidth = routes.maxOfOrNull(::measureWidth) ?: GraphFlowMetrics.nodeMinWidth
        val screenWidth = (routeAttachedNodes + standaloneScreens)
            .maxOfOrNull(::measureWidth)
            ?: GraphFlowMetrics.nodeMinWidth
        val screenColumnX = routeColumnX + routeWidth + GraphFlowMetrics.attachmentGap
        if (services.isNotEmpty()) {
            maxRight = max(maxRight, routeColumnX)
        }

        val routeColumns = preferredRouteColumns(routes.size)
        val routeBlockWidth = routeWidth + if (routeAttachedNodes.isNotEmpty() || standaloneScreens.isNotEmpty()) {
            GraphFlowMetrics.attachmentGap + screenWidth
        } else {
            0.0
        }
        var routeLaneBottom = startY
        routes.chunked(routeColumns).forEach { routeRow ->
            var rowBottom = routeLaneBottom
            routeRow.forEachIndexed { column, route ->
                val routeX = routeColumnX + column * (routeBlockWidth + GraphFlowMetrics.routeColumnGap)
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
                        x = routeX + routeWidth + GraphFlowMetrics.attachmentGap,
                        y = attachedY,
                        widthOverride = screenWidth,
                    )
                    place(attachedLayout)
                    attachedY = attachedLayout.y + attachedLayout.height + GraphFlowMetrics.attachmentRowGap
                    laneBottom = max(laneBottom, attachedLayout.y + attachedLayout.height)
                    laneRight = max(laneRight, attachedLayout.x + attachedLayout.width)
                }

                rowBottom = max(rowBottom, laneBottom)
                maxRight = max(maxRight, laneRight)
                maxBottom = max(maxBottom, laneBottom)
            }
            routeLaneBottom = rowBottom + GraphFlowMetrics.rowGap
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
                        x = screenColumnX + column * (screenWidth + GraphFlowMetrics.routeColumnGap),
                        y = standaloneTop,
                        widthOverride = screenWidth,
                    )
                    place(layout)
                    rowBottom = max(rowBottom, layout.y + layout.height)
                    maxRight = max(maxRight, layout.x + layout.width)
                    maxBottom = max(maxBottom, layout.y + layout.height)
                }
                standaloneTop = rowBottom + GraphFlowMetrics.rowGap
            }
        }

        var childGraphRight = maxRight
        var childGraphBottom = maxBottom
        if (containers.isNotEmpty()) {
            var currentY = max(maxBottom, serviceColumnBottom).let {
                if (localLayouts.isEmpty()) startY else it + GraphFlowMetrics.childGraphGap
            }
            for (containerNode in containers) {
                val layout = createLayout(
                    node = containerNode,
                    graph = graph,
                    x = originX + GraphFlowMetrics.subgraphPadding,
                    y = currentY,
                    widthOverride = max(servicesWidth, measureWidth(containerNode)),
                )
                place(layout)
                maxRight = max(maxRight, layout.x + layout.width)
                maxBottom = max(maxBottom, layout.y + layout.height)

                if (containerNode is ContainerNode && containerNode.graphs.isNotEmpty()) {
                    val childStartX = layout.x + layout.width + GraphFlowMetrics.layerGap
                    var childX = childStartX
                    var childY = currentY
                    var rowBottom = currentY
                    val childGraphsPerRow = preferredChildGraphsPerRow(containerNode.graphs.size)
                    containerNode.graphs.forEachIndexed { index, childGraph ->
                        if (index > 0 && index % childGraphsPerRow == 0) {
                            childX = childStartX
                            childY = rowBottom + GraphFlowMetrics.childGraphGap
                        }
                        val childBounds = layoutGraph(
                            graph = childGraph,
                            originX = childX,
                            originY = childY,
                            depth = depth + 1,
                            graphId = "$graphId/$index",
                        )
                        childX = childBounds.right + GraphFlowMetrics.subgraphPadding
                        rowBottom = max(rowBottom, childBounds.bottom)
                        childGraphRight = max(childGraphRight, childBounds.right)
                        childGraphBottom = max(childGraphBottom, childBounds.bottom)
                    }
                    currentY = max(layout.y + layout.height, rowBottom) + GraphFlowMetrics.childGraphGap
                } else {
                    currentY += layout.height + GraphFlowMetrics.rowGap
                }
                maxBottom = max(maxBottom, currentY)
            }
        }

        val contentRight = max(
            childGraphRight,
            localLayouts.maxOfOrNull { it.x + it.width } ?: originX + GraphFlowMetrics.subgraphPadding,
        )
        val contentBottom = max(
            childGraphBottom,
            localLayouts.maxOfOrNull { it.y + it.height } ?: startY,
        )
        val bounds = LayoutBounds(
            left = originX - GraphFlowMetrics.regionInsetX,
            top = originY - GraphFlowMetrics.regionInsetTop,
            right = contentRight + GraphFlowMetrics.subgraphPadding + GraphFlowMetrics.regionInsetX,
            bottom = contentBottom + GraphFlowMetrics.subgraphPadding + GraphFlowMetrics.regionInsetBottom,
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

    private fun createLayout(
        node: GraphNode,
        graph: Graph,
        x: Double,
        y: Double,
        widthOverride: Double? = null,
    ): GraphNodeLayout {
        val allProviderPorts = visiblePorts(node.providerPorts.flattenedValues().toList())
        val allConsumerPorts = visiblePorts(node.consumerPorts.flattenedValues().toList())
        val width = widthOverride ?: measureWidth(node)
        val rowCount = max(allProviderPorts.size, allConsumerPorts.size).coerceAtLeast(1)
        val height = GraphFlowMetrics.titleHeight + rowCount * GraphFlowMetrics.rowHeight + GraphFlowMetrics.nodePaddingY * 2
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

    private fun resolveEdges(graph: Graph) {
        graph.nodes.filterIsInstance<RouteNode<*, *>>().forEach { route ->
            val routeLayout = layouts[route] ?: return@forEach

            route.attachedNodes().forEach { attached ->
                val attachedNode = attached as? GraphNode ?: return@forEach
                val targetLayout = layouts[attachedNode] ?: return@forEach
                addEdge(
                    source = routeLayout.flowId,
                    target = targetLayout.flowId,
                    kind = ReaktorEdgeKind.Attachment,
                    label = route.pattern.original,
                    sourceHandle = "routeBinding",
                    targetHandle = "routeBinding",
                )
            }

            route.navigationTargets().forEach { targetRoute ->
                val targetLayout = layouts[targetRoute] ?: return@forEach
                addEdge(
                    source = routeLayout.flowId,
                    target = targetLayout.flowId,
                    kind = ReaktorEdgeKind.Navigation,
                    sourceHandle = "__nav__",
                    targetHandle = "navBinding",
                )
            }
        }

        graph.nodes.forEach { node ->
            val sourceLayout = layouts[node] ?: return@forEach

            node.consumerPorts.flattenedValues()
                .filter { it.isConnected() }
                .forEach { consumer ->
                    if (isInternalPort(consumer.key.key)) {
                        return@forEach
                    }

                    val edge = consumer.edge ?: return@forEach
                    val providerNode = edge.destination as? GraphNode ?: return@forEach
                    if (providerNode is RouteNode<*, *>) {
                        return@forEach
                    }

                    val targetLayout = layouts[providerNode] ?: return@forEach
                    addEdge(
                        source = targetLayout.flowId,
                        target = sourceLayout.flowId,
                        kind = ReaktorEdgeKind.Data,
                        label = pinLabel(consumer.key.key, consumer.type.type),
                        sourceHandle = edge.provider.key.key.ifBlank { edge.provider.type.type },
                        targetHandle = consumer.key.key.ifBlank { consumer.type.type },
                    )
                }

            if (node is ContainerNode) {
                node.graphs.forEach { child ->
                    graphRootRoute(child)?.let { rootRoute ->
                        val rootLayout = layouts[rootRoute] ?: return@let
                        addEdge(
                            source = sourceLayout.flowId,
                            target = rootLayout.flowId,
                            kind = ReaktorEdgeKind.Containment,
                            label = graphLabel(child),
                            sourceHandle = "__contains__",
                            targetHandle = "routeBinding",
                        )
                    }
                    resolveEdges(child)
                }
            }
        }
    }

    private fun graphRootRoute(graph: Graph): RouteNode<*, *>? =
        (graph.backStack.entries.value.firstOrNull()?.edge?.end as? RouteNode<*, *>)
            ?: graph.nodes.filterIsInstance<RouteNode<*, *>>().firstOrNull()

    private fun addEdge(
        source: String,
        target: String,
        kind: ReaktorEdgeKind,
        label: String? = null,
        sourceHandle: String? = null,
        targetHandle: String? = null,
    ) {
        val id = listOf(source, sourceHandle.orEmpty(), target, targetHandle.orEmpty(), kind.name, label.orEmpty()).joinToString("->")
        if (edges[id] == null) {
            edges[id] = Edge(
                id = id,
                source = source,
                target = target,
                sourceHandle = sourceHandle,
                targetHandle = targetHandle,
                data = ReaktorGraphEdgeData(kind = kind, label = label),
                label = label,
                markerEnd = EdgeMarker(type = MarkerType.ArrowClosed),
                zIndex = when (kind) {
                    ReaktorEdgeKind.Containment -> 0
                    ReaktorEdgeKind.Data -> 1
                    ReaktorEdgeKind.Attachment -> 2
                    ReaktorEdgeKind.Navigation -> 3
                },
            )
        }
    }

    private fun toFlowNode(layout: GraphNodeLayout): Node {
        val graphNode = layout.graphNode
        return Node(
            id = layout.flowId,
            position = XYPosition(layout.x, layout.y),
            data = ReaktorGraphNodeData(
                nodeId = graphNode.id.toString(),
                title = nodeTitle(graphNode),
                subtitle = nodeSubtitle(graphNode),
                graphLabel = graphLabel(layout.graph),
                isRootNode = graphRootRoute(layout.graph) == graphNode,
                providerCount = layout.providerCount,
                consumerCount = layout.consumerCount,
                providerPorts = layout.providerPorts,
                consumerPorts = layout.consumerPorts,
                hiddenProviderCount = layout.hiddenProviderCount,
                hiddenConsumerCount = layout.hiddenConsumerCount,
                kind = reaktorNodeKind(graphNode),
            ),
            type = "graph",
            width = layout.width,
            height = layout.height,
            handles = buildHandles(layout),
            sourcePosition = Position.Right,
            targetPosition = Position.Left,
            showDefaultHandles = false,
        )
    }

    private fun measureWidth(node: GraphNode): Double {
        // Match layout width to the visual contract of the Compose node renderer. React Flow also
        // treats node measurement as editor-space data; if the estimate is too small, titles and
        // ports become unreadable even when the graph topology itself is correct.
        val consumers = node.consumerPorts.flattenedValues().toList()
        val providers = node.providerPorts.flattenedValues().toList()
        val titleLength = nodeTitle(node).length
        val maxLeftLabel = consumers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0
        val maxRightLabel = providers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0

        val leftColumnWidth = if (maxLeftLabel == 0) {
            0.0
        } else {
            maxLeftLabel * GraphFlowMetrics.portCharWidthEstimate +
                GraphFlowMetrics.portDotSize +
                GraphFlowMetrics.portGap
        }
        val rightColumnWidth = if (maxRightLabel == 0) {
            0.0
        } else {
            maxRightLabel * GraphFlowMetrics.portCharWidthEstimate +
                GraphFlowMetrics.portDotSize +
                GraphFlowMetrics.portGap
        }
        val bodyWidth =
            when {
                leftColumnWidth > 0.0 && rightColumnWidth > 0.0 ->
                    leftColumnWidth + GraphFlowMetrics.columnGap + rightColumnWidth
                else -> max(leftColumnWidth, rightColumnWidth)
            } + GraphFlowMetrics.bodyHorizontalPadding * 2.0

        val badgeAllowance = if (graphRootRoute(node.graph) == node) {
            (4 * GraphFlowMetrics.titleCharWidthEstimate) +
                GraphFlowMetrics.rootBadgeHorizontalPadding * 2.0 +
                GraphFlowMetrics.titleToBadgeGap
        } else {
            0.0
        }
        val titleWidth =
            titleLength * GraphFlowMetrics.titleCharWidthEstimate +
                GraphFlowMetrics.titleHorizontalPadding * 2.0 +
                badgeAllowance
        // React Flow effectively works from measured DOM widths; this builder has to provide the
        // same safety margin up front, otherwise the Compose renderer receives a width that is
        // already too small for its real title/body sublayout.
        val widthFactor = when (node) {
            is RouteNode<*, *> -> 1.24
            is ControllerNode<*> -> 1.18
            is ContainerNode -> 1.16
            is BasicNode -> 1.10
            else -> 1.12
        }
        val columnFactor = if (leftColumnWidth > 0.0 && rightColumnWidth > 0.0) 1.10 else 1.0
        val baseWidth = max(bodyWidth * columnFactor, titleWidth)
        return max(GraphFlowMetrics.nodeMinWidth, baseWidth * widthFactor)
    }

    private fun visiblePorts(ports: List<Port<*>>): List<ReaktorPortData> =
        ports
            .filter { shouldDisplayPort(it.key.key) }
            .map { port ->
                ReaktorPortData(
                    handleId = port.key.key.ifBlank { port.type.type },
                    label = pinLabel(port.key.key, port.type.type),
                    type = shortType(port.type.type),
                    color = portColor(port.type.type, port.isConnected()),
                    connected = port.isConnected(),
                )
            }
            .distinctBy(ReaktorPortData::handleId)

    private fun buildHandles(layout: GraphNodeLayout): List<Handle> {
        val rowCount = max(layout.consumerPorts.size, layout.providerPorts.size).coerceAtLeast(1)
        val handles = buildList {
            layout.consumerPorts.forEachIndexed { index, port ->
                add(
                    Handle(
                        id = port.handleId,
                        type = HandleType.Target,
                        position = Position.Left,
                        offset = handleOffset(index, rowCount),
                        inset = GraphFlowMetrics.portInset,
                    )
                )
            }
            layout.providerPorts.forEachIndexed { index, port ->
                add(
                    Handle(
                        id = port.handleId,
                        type = HandleType.Source,
                        position = Position.Right,
                        offset = handleOffset(index, rowCount),
                        inset = GraphFlowMetrics.portInset,
                    )
                )
            }
            if (layout.graphNode is RouteNode<*, *> && layout.graphNode.navigationTargets().isNotEmpty()) {
                add(Handle(id = "__nav__", type = HandleType.Source, position = Position.Bottom, offset = 0.5))
                add(Handle(id = "navBinding", type = HandleType.Target, position = Position.Top, offset = 0.5))
            }
            if (layout.graphNode is ContainerNode && layout.graphNode.graphs.isNotEmpty()) {
                add(Handle(id = "__contains__", type = HandleType.Source, position = Position.Bottom, offset = 0.84))
            }
        }
        return handles.distinctBy { it.type to it.id }
    }

    private fun handleOffset(index: Int, count: Int): Double =
        when {
            count <= 0 -> 0.5
            else -> {
                val totalHeight =
                    GraphFlowMetrics.titleHeight +
                        count * GraphFlowMetrics.rowHeight +
                        GraphFlowMetrics.nodePaddingY * 2.0
                val rowCenter =
                    GraphFlowMetrics.titleHeight +
                        GraphFlowMetrics.nodePaddingY +
                        index * GraphFlowMetrics.rowHeight +
                        GraphFlowMetrics.rowHeight / 2.0
                rowCenter / totalHeight
            }
        }
}

private fun regionColor(depth: Int): Color = when (depth) {
    0 -> Color(0xFF4A5878)
    1 -> Color(0xFF4A7858)
    else -> Color(0xFF6A5A7E)
}
