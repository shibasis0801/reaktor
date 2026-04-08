package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.MarkerType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.math.max

// References:
// - React Flow keeps graph extraction and edge assembly separate from the renderer/store. That
//   keeps the editor surface swappable while node/edge semantics remain stable.
// - Compose guidance pushes measurement and semantic model preparation ahead of composition. The
//   editor renderer should consume prepared node/edge data, not discover graph semantics itself.

internal fun ReaktorFlowBuilder.resolveEdges(graph: Graph) {
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

internal fun ReaktorFlowBuilder.addEdge(
    source: String,
    target: String,
    kind: ReaktorEdgeKind,
    label: String? = null,
    sourceHandle: String? = null,
    targetHandle: String? = null,
) {
    val id = listOf(
        source,
        sourceHandle.orEmpty(),
        target,
        targetHandle.orEmpty(),
        kind.name,
        label.orEmpty(),
    ).joinToString("->")
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

internal fun ReaktorFlowBuilder.toFlowNode(layout: GraphNodeLayout): Node {
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

internal fun ReaktorFlowBuilder.buildHandles(layout: GraphNodeLayout): List<Handle> {
    val rowCount = max(layout.consumerPorts.size, layout.providerPorts.size).coerceAtLeast(1)
    val handles = buildList {
        layout.consumerPorts.forEachIndexed { index, port ->
            add(
                Handle(
                    id = port.handleId,
                    type = HandleType.Target,
                    position = Position.Left,
                    offset = handleOffset(index, rowCount, style),
                    inset = style.port.insetPx,
                )
            )
        }
        layout.providerPorts.forEachIndexed { index, port ->
            add(
                Handle(
                    id = port.handleId,
                    type = HandleType.Source,
                    position = Position.Right,
                    offset = handleOffset(index, rowCount, style),
                    inset = style.port.insetPx,
                )
            )
        }
        if (layout.graphNode is RouteNode<*, *> && layout.graphNode.navigationTargets().isNotEmpty()) {
            add(
                Handle(
                    id = "__nav__",
                    type = HandleType.Source,
                    position = Position.Bottom,
                    offset = style.port.navigationHandleOffset,
                )
            )
            add(
                Handle(
                    id = "navBinding",
                    type = HandleType.Target,
                    position = Position.Top,
                    offset = style.port.navigationHandleOffset,
                )
            )
        }
        if (layout.graphNode is ContainerNode && layout.graphNode.graphs.isNotEmpty()) {
            add(
                Handle(
                    id = "__contains__",
                    type = HandleType.Source,
                    position = Position.Bottom,
                    offset = style.port.containmentHandleOffset,
                )
            )
        }
    }
    return handles.distinctBy { it.type to it.id }
}
