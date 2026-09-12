package dev.shibasis.reaktor.flow.graph.model

import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.MarkerType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth

/** Project operational architecture facts onto the live source graph without mutating either. */
fun ReaktorFlowGraph.withArchitectureOverlay(overlay: ReaktorArchitectureOverlay): ReaktorFlowGraph {
    val sourceById = nodes.associateBy(Node::id)
    val sourceAliases = buildMap<String, Node> {
        putAll(sourceById)
        nodes
            .groupBy { node -> (node.data as? ReaktorGraphNodeData)?.scopeId }
            .forEach { (scopeId, scopeNodes) ->
                if (scopeId == null) return@forEach
                val representative = scopeNodes.firstOrNull { node ->
                    (node.data as? ReaktorGraphNodeData)?.isScopeSummary == true
                } ?: scopeNodes.firstOrNull { node ->
                    (node.data as? ReaktorGraphNodeData)?.isRootNode == true
                } ?: scopeNodes.first()
                put("scope:$scopeId", representative)
            }
    }
    val overlayNodes = mutableListOf<Node>()
    val overlayById = linkedMapOf<String, Node>()
    val siblingsByAnchor = mutableMapOf<String, Int>()
    val fallbackTop = nodes.maxOfOrNull { it.position.y + (it.height ?: style.defaultNodeHeight()) }
        ?.plus(style.layout.groupColumnGapPx)
        ?: style.layout.rootOriginPx

    overlay.elements.forEachIndexed { index, element ->
        if (element.id in sourceById || element.id in overlayById) return@forEachIndexed
        val anchor = element.parentId?.let { sourceAliases[it] ?: overlayById[it] }
            ?: nodes.firstOrNull { node ->
                (node.data as? ReaktorGraphNodeData)?.scopeId == element.scopeId
            }
        val anchorKey = anchor?.id ?: element.scopeId
        val sibling = siblingsByAnchor.getOrElse(anchorKey) { 0 }
        siblingsByAnchor[anchorKey] = sibling + 1
        val width = style.node.minWidthPx * 1.08
        val height = style.defaultNodeHeight()
        val position = if (anchor != null) {
            XYPosition(
                x = anchor.position.x + (anchor.width ?: style.defaultNodeWidth()) + style.layout.compactColumnGapPx,
                y = anchor.position.y + sibling * (height + style.layout.compactRowGapPx),
            )
        } else {
            XYPosition(
                x = style.layout.rootOriginPx + (index % 4) * (width + style.layout.compactColumnGapPx),
                y = fallbackTop + (index / 4) * (height + style.layout.compactRowGapPx),
            )
        }
        val kind = operationalNodeKind(element.kind)
        val providerPorts = element.ports
            .filter { it.direction.equals("provider", ignoreCase = true) }
            .map { it.asGraphPort(kind) }
        val consumerPorts = element.ports
            .filterNot { it.direction.equals("provider", ignoreCase = true) }
            .map { it.asGraphPort(kind) }
        val node = Node(
            id = element.id,
            position = position,
            data = ReaktorGraphNodeData(
                nodeId = element.id,
                title = element.label,
                subtitle = listOfNotNull(element.status, element.description).joinToString(" · ").ifBlank { null },
                graphLabel = element.provenance.graphLabel,
                isRootNode = false,
                providerCount = providerPorts.size,
                consumerCount = consumerPorts.size,
                providerPorts = providerPorts,
                consumerPorts = consumerPorts,
                hiddenProviderCount = 0,
                hiddenConsumerCount = 0,
                kind = kind,
                scopeId = element.scopeId,
                scopePath = scopePath(element.scopeId),
                architectureLevel = element.level,
                status = element.status,
                attributes = element.attributes + ("architectureKind" to element.kind),
                provenance = element.provenance,
            ),
            type = "graph",
            width = width,
            height = height,
            handles = buildList {
                consumerPorts.forEachIndexed { portIndex, port ->
                    add(Handle(port.handleId, HandleType.Target, Position.Left, overlayHandleOffset(portIndex, consumerPorts.size)))
                }
                providerPorts.forEachIndexed { portIndex, port ->
                    add(Handle(port.handleId, HandleType.Source, Position.Right, overlayHandleOffset(portIndex, providerPorts.size)))
                }
            },
            sourcePosition = Position.Right,
            targetPosition = Position.Left,
            showDefaultHandles = providerPorts.isEmpty() && consumerPorts.isEmpty(),
        )
        overlayNodes += node
        overlayById[node.id] = node
    }

    fun endpoint(id: String): String? = overlayById[id]?.id ?: sourceAliases[id]?.id
    val explicitRelationships = overlay.relationships.mapNotNull { relationship ->
        val sourceId = endpoint(relationship.source) ?: return@mapNotNull null
        val targetId = endpoint(relationship.target) ?: return@mapNotNull null
        relationship.copy(source = sourceId, target = targetId).asFlowEdge()
    }
    val explicitIds = explicitRelationships.mapTo(mutableSetOf()) { it.source to it.target }
    val implicitRelationships = overlay.elements.mapNotNull { element ->
        val parentId = element.parentId ?: return@mapNotNull null
        val resolvedParentId = endpoint(parentId) ?: return@mapNotNull null
        val resolvedElementId = endpoint(element.id) ?: return@mapNotNull null
        if (resolvedParentId to resolvedElementId in explicitIds) return@mapNotNull null
        ReaktorArchitectureRelationship(
            id = "overlay-parent:$resolvedParentId:$resolvedElementId",
            source = resolvedParentId,
            target = resolvedElementId,
            kind = "operates",
            label = element.kind,
            status = element.status,
            provenance = element.provenance,
        ).asFlowEdge()
    }
    return copy(
        nodes = nodes + overlayNodes,
        edges = edges + (explicitRelationships + implicitRelationships).distinctBy(Edge::id),
    )
}

private fun ReaktorFlowGraph.scopePath(scopeId: String): List<String> = buildList {
    var cursor: String? = scopeId
    while (cursor != null) {
        add(0, cursor)
        cursor = scopes[cursor]?.parentId
    }
}

private fun ReaktorArchitecturePort.asGraphPort(kind: ReaktorNodeKind): ReaktorPortData =
    ReaktorPortData(
        handleId = key,
        label = key,
        type = type,
        color = kind.borderColor,
        connected = connected,
    )

private fun ReaktorArchitectureRelationship.asFlowEdge(): Edge {
    val edgeKind = operationalEdgeKind(kind)
    return Edge(
        id = id,
        source = source,
        target = target,
        sourceHandle = sourcePort,
        targetHandle = targetPort,
        data = ReaktorGraphEdgeData(kind = edgeKind, label = label ?: kind),
        label = label,
        markerEnd = EdgeMarker(type = MarkerType.ArrowClosed),
        animated = status.equals("running", ignoreCase = true),
        zIndex = 4,
    )
}

private fun operationalNodeKind(kind: String): ReaktorNodeKind = when (kind.lowercase()) {
    "task" -> ReaktorNodeKind.Action
    "resource" -> ReaktorNodeKind.Infra
    "run" -> ReaktorNodeKind.Telemetry
    "provider" -> ReaktorNodeKind.Service
    "deployment" -> ReaktorNodeKind.Release
    "test" -> ReaktorNodeKind.Test
    "database", "store", "database-record" -> ReaktorNodeKind.Data
    else -> ReaktorNodeKind.Node
}

private fun operationalEdgeKind(kind: String): ReaktorEdgeKind = when (kind.lowercase()) {
    "contains", "containment", "owns" -> ReaktorEdgeKind.Containment
    "deploys", "deployment", "targets", "runs" -> ReaktorEdgeKind.Attachment
    "navigation", "routes" -> ReaktorEdgeKind.Navigation
    else -> ReaktorEdgeKind.Data
}

private fun overlayHandleOffset(index: Int, count: Int): Double =
    if (count <= 0) 0.5 else (index + 1).toDouble() / (count + 1).toDouble()
