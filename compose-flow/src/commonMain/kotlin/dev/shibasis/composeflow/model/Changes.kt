package dev.shibasis.composeflow.model

fun addEdge(
    connection: Connection,
    edges: List<Edge>,
    id: String = defaultEdgeId(connection),
): List<Edge> {
    val source = connection.source?.takeIf(String::isNotBlank) ?: return edges
    val target = connection.target?.takeIf(String::isNotBlank) ?: return edges

    val alreadyConnected = edges.any { edge ->
        edge.source == source &&
            edge.target == target &&
            edge.sourceHandle == connection.sourceHandle &&
            edge.targetHandle == connection.targetHandle
    }
    if (alreadyConnected) {
        return edges
    }

    return edges + Edge(
        id = id,
        source = source,
        target = target,
        sourceHandle = connection.sourceHandle,
        targetHandle = connection.targetHandle,
        markerEnd = EdgeMarker(),
    )
}

fun applyNodeChanges(
    changes: List<NodeChange>,
    nodes: List<Node>,
): List<Node> {
    var current = nodes
    changes.forEach { change ->
        current = when (change) {
            is NodeAddChange -> {
                val index = change.index ?: current.size
                current.toMutableList().apply {
                    add(index.coerceIn(0, size), change.item)
                }
            }
            is NodeDimensionChange -> current.map { node ->
                if (node.id == change.id) {
                    node.copy(measured = change.dimensions)
                } else {
                    node
                }
            }
            is NodePositionChange -> current.map { node ->
                if (node.id == change.id) {
                    node.copy(position = change.position, dragging = change.dragging)
                } else {
                    node
                }
            }
            is NodeRemoveChange -> current.filterNot { node -> node.id == change.id }
            is NodeReplaceChange -> current.map { node -> if (node.id == change.id) change.item else node }
            is NodeSelectionChange -> current.map { node ->
                if (node.id == change.id) {
                    node.copy(selected = change.selected)
                } else {
                    node
                }
            }
        }
    }
    return current
}

fun applyEdgeChanges(
    changes: List<EdgeChange>,
    edges: List<Edge>,
): List<Edge> {
    var current = edges
    changes.forEach { change ->
        current = when (change) {
            is EdgeAddChange -> {
                val index = change.index ?: current.size
                current.toMutableList().apply {
                    add(index.coerceIn(0, size), change.item)
                }
            }
            is EdgeRemoveChange -> current.filterNot { edge -> edge.id == change.id }
            is EdgeReplaceChange -> current.map { edge -> if (edge.id == change.id) change.item else edge }
            is EdgeSelectionChange -> current.map { edge ->
                if (edge.id == change.id) {
                    edge.copy(selected = change.selected)
                } else {
                    edge
                }
            }
        }
    }
    return current
}

private fun defaultEdgeId(connection: Connection): String = buildString {
    append(connection.source ?: "source")
    append("->")
    append(connection.target ?: "target")
    connection.sourceHandle?.let {
        append(':')
        append(it)
    }
    connection.targetHandle?.let {
        append(':')
        append(it)
    }
}
