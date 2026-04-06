@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.shibasis.reaktor.flow.graph

import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.flow.Edge
import dev.shibasis.reaktor.flow.EdgeMarker
import dev.shibasis.reaktor.flow.Handle
import dev.shibasis.reaktor.flow.HandleType
import dev.shibasis.reaktor.flow.MarkerType
import dev.shibasis.reaktor.flow.Node
import dev.shibasis.reaktor.flow.Position
import dev.shibasis.reaktor.flow.XYPosition
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.ui.ComposeContent
import dev.shibasis.reaktor.graph.ui.Navigable
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.math.max

private object GraphFlowMetrics {
    const val nodeMinWidth = 480.0
    const val titleHeight = 60.0
    const val rowHeight = 50.0
    const val nodePaddingY = 22.0
    const val portInset = 14.0
    const val subgraphHeader = 48.0
    const val subgraphPadding = 52.0
    const val layerGap = 80.0
    const val attachmentGap = 40.0
    const val rowGap = 18.0
    const val attachmentRowGap = 10.0
    const val childGraphGap = 18.0
    const val routeColumnGap = 44.0
    const val charWidthEstimate = 10.4
    const val pinMarginX = 10.0
    const val pinTextGap = 8.0
    const val regionInsetX = 22.0
    const val regionInsetTop = 16.0
    const val regionInsetBottom = 22.0
}

enum class ReaktorNodeKind(
    val label: String,
    val titleColor: Color,
    val bodyColor: Color,
    val borderColor: Color,
) {
    Screen("Screen", Color(0xFF2D6B3F), Color(0xFF192820), Color(0xFF3D8C55)),
    Route("Route", Color(0xFF2E4A80), Color(0xFF182040), Color(0xFF4A78CC)),
    Container("Container", Color(0xFF5E3498), Color(0xFF221838), Color(0xFF8B60C8)),
    Service("Service", Color(0xFF8B5A2B), Color(0xFF261E14), Color(0xFFCC8844)),
    Node("Node", Color(0xFF3C4A6E), Color(0xFF1C2030), Color(0xFF4A5580)),
}

enum class ReaktorEdgeKind(
    val label: String,
    val color: Color,
) {
    Attachment("Attachment", Color(0xFF7FB0FF)),
    Navigation("Navigation", Color(0xFF55A8F4)),
    Data("Data", Color(0xFF55D46E)),
    Containment("Containment", Color(0xFFC38CFF)),
}

data class ReaktorPortData(
    val handleId: String,
    val label: String,
    val type: String,
    val color: Color,
    val connected: Boolean,
)

data class ReaktorGraphNodeData(
    val nodeId: String,
    val title: String,
    val subtitle: String?,
    val graphLabel: String,
    val isRootNode: Boolean,
    val providerCount: Int,
    val consumerCount: Int,
    val providerPorts: List<ReaktorPortData>,
    val consumerPorts: List<ReaktorPortData>,
    val hiddenProviderCount: Int,
    val hiddenConsumerCount: Int,
    val kind: ReaktorNodeKind,
)

data class ReaktorGraphEdgeData(
    val kind: ReaktorEdgeKind,
    val label: String? = null,
)

data class ReaktorGraphRegion(
    val label: String,
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val color: Color,
    val depth: Int,
)

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

data class ReaktorFlowGraph(
    val nodes: List<Node>,
    val edges: List<Edge>,
    val regions: List<ReaktorGraphRegion>,
    val graphNodes: Map<String, GraphNode>,
    val flowIdsByNode: Map<GraphNode, String>,
    val graphIdsByNode: Map<GraphNode, String>,
    val graphs: Map<String, Graph>,
)

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
        val serviceColumnGap = 28.0
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
        val visibleProviderPorts = allProviderPorts
        val visibleConsumerPorts = allConsumerPorts
        val width = widthOverride ?: measureWidth(node)
        val rowCount = max(visibleProviderPorts.size, visibleConsumerPorts.size).coerceAtLeast(1)
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
            providerPorts = visibleProviderPorts,
            consumerPorts = visibleConsumerPorts,
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
        edges.putIfAbsent(
            id,
            Edge(
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
            ),
        )
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
        val consumers = node.consumerPorts.flattenedValues().toList()
        val providers = node.providerPorts.flattenedValues().toList()
        val titleLength = nodeTitle(node).length
        val maxLeftLabel = consumers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0
        val maxRightLabel = providers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0
        val pinsWidth =
            (maxLeftLabel + maxRightLabel + 12) * GraphFlowMetrics.charWidthEstimate +
                GraphFlowMetrics.pinMarginX * 2 +
                GraphFlowMetrics.pinTextGap * 2
        val titleWidth = titleLength * GraphFlowMetrics.charWidthEstimate + 80.0
        return max(GraphFlowMetrics.nodeMinWidth, max(pinsWidth, titleWidth))
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

fun reaktorNodeKind(node: GraphNode): ReaktorNodeKind = when {
    node is ComposeContent -> ReaktorNodeKind.Screen
    node is ContainerNode -> ReaktorNodeKind.Container
    node is RouteNode<*, *> -> ReaktorNodeKind.Route
    node is ControllerNode<*> -> ReaktorNodeKind.Screen
    node is BasicNode -> ReaktorNodeKind.Service
    else -> ReaktorNodeKind.Node
}

private fun nodeTitle(node: GraphNode): String = when (node) {
    is RouteNode<*, *> -> {
        val pattern = node.pattern.original.trim()
        when {
            pattern.isNotEmpty() -> pattern
            !node.label.isNullOrBlank() && !looksLikeUuid(node.label) -> node.label
            else -> "Root Route"
        }
    }
    else -> {
        val label = node.label.trim()
        if (label.isBlank() || looksLikeUuid(label)) {
            node::class.simpleName ?: "Node"
        } else {
            label
        }
    }
}

private fun nodeSubtitle(node: GraphNode): String? = when (node) {
    is RouteNode<*, *> -> node::class.simpleName
    is ContainerNode -> "${node.graphs.size} child graphs"
    else -> null
}

private fun pinLabel(key: String, typeName: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty() || looksLikeUuid(trimmed)) {
        return shortType(typeName)
    }
    return trimmed
}

private fun shortType(type: String): String {
    var name = type.substringAfterLast('.')
    val genericIndex = name.indexOf('<')
    if (genericIndex > 0) {
        name = name.substring(0, genericIndex)
    }
    return name
}

private fun looksLikeUuid(value: String): Boolean {
    val parts = value.split('-')
    if (parts.size != 5) {
        return false
    }
    val lengths = intArrayOf(8, 4, 4, 4, 12)
    return parts.withIndex().all { (index, part) ->
        part.length == lengths[index] && part.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

private fun portColor(
    type: String,
    connected: Boolean,
): Color {
    if (!connected) {
        return Color(0xFF666C80)
    }
    return if ("NavBinding" in type || "RouteBinding" in type) {
        Color(0xFF55A8F4)
    } else {
        Color(0xFF55D46E)
    }
}

private fun isInternalPort(key: String): Boolean =
    key.isBlank() ||
        key == "routeBinding" ||
        key == "navBinding" ||
        key == "controller" ||
        key == "controllerBinding"

private fun shouldDisplayPort(key: String): Boolean =
    key.isNotBlank() &&
        key != "controller" &&
        key != "controllerBinding"

private fun graphLabel(graph: Graph): String =
    (graph as? Navigable)?.label?.ifBlank { graph.label } ?: graph.label.ifBlank { "Root" }

private fun regionColor(depth: Int): Color = when (depth) {
    0 -> Color(0xFF4A5878)
    1 -> Color(0xFF4A7858)
    else -> Color(0xFF6A5A7E)
}
