package dev.shibasis.reaktor.flow.graph.model

import dev.shibasis.composeflow.model.Node

/** Orthogonal, reusable graph-visibility controls used by Desktop and future tooling clients. */
data class ReaktorGraphLens(
    val query: String = "",
    val relationshipKinds: Set<ReaktorEdgeKind> = ReaktorEdgeKind.entries.toSet(),
    val nodeKinds: Set<ReaktorNodeKind> = ReaktorNodeKind.entries.toSet(),
    val includeNeighbors: Boolean = true,
) {
    val isDefault: Boolean
        get() = query.isBlank() &&
            relationshipKinds.size == ReaktorEdgeKind.entries.size &&
            nodeKinds.size == ReaktorNodeKind.entries.size

    fun toggle(kind: ReaktorEdgeKind): ReaktorGraphLens = copy(
        relationshipKinds = relationshipKinds.toMutableSet().apply {
            if (!add(kind)) remove(kind)
        },
    )
}

data class ReaktorGraphLensResult(
    val flow: ReaktorFlowGraph,
    val matchingNodeIds: Set<String>,
    val visibleNodeIds: Set<String>,
    val visibleRelationshipCount: Int,
)

fun ReaktorFlowGraph.applyLens(lens: ReaktorGraphLens): ReaktorGraphLensResult {
    val query = lens.query.trim().lowercase()
    val nodeData = nodes.associate { node -> node.id to (node.data as? ReaktorGraphNodeData) }
    val allowedByKind = nodes.filterTo(mutableSetOf()) { node ->
        val kind = nodeData[node.id]?.kind ?: ReaktorNodeKind.Node
        kind in lens.nodeKinds
    }.mapTo(mutableSetOf(), Node::id)
    val matching = if (query.isEmpty()) {
        allowedByKind
    } else {
        nodes.filterTo(mutableSetOf()) { node ->
            if (node.id !in allowedByKind) return@filterTo false
            val data = nodeData[node.id]
            listOfNotNull(
                node.id,
                data?.title,
                data?.subtitle,
                data?.graphLabel,
                data?.kind?.label,
                data?.provenance?.runtimeType,
                data?.provenance?.origin,
            ).any { query in it.lowercase() } ||
                data?.providerPorts.orEmpty().any { query in it.label.lowercase() || query in it.type.lowercase() } ||
                data?.consumerPorts.orEmpty().any { query in it.label.lowercase() || query in it.type.lowercase() }
        }.mapTo(mutableSetOf(), Node::id)
    }
    val allowedEdges = edges.filter { edge ->
        val kind = (edge.data as? ReaktorGraphEdgeData)?.kind ?: return@filter false
        kind in lens.relationshipKinds
    }
    val visibleIds = when {
        query.isEmpty() -> allowedByKind
        !lens.includeNeighbors -> matching
        else -> matching + allowedEdges
            .filter { it.source in matching || it.target in matching }
            .flatMap { listOf(it.source, it.target) }
    }
    val visibleEdges = allowedEdges.filter { it.source in visibleIds && it.target in visibleIds }
    val visibleNodes = nodes.filter { it.id in visibleIds }
    val visibleScopeIds = visibleNodes.mapNotNullTo(mutableSetOf()) {
        (it.data as? ReaktorGraphNodeData)?.scopeId
    }
    val visibleGraphNodes = graphNodes.filterKeys(visibleIds::contains)
    val filtered = copy(
        nodes = visibleNodes,
        edges = visibleEdges,
        regions = regions.filter { region ->
            region.id == focusedScopeId || visibleScopeIds.any { scopeId ->
                ReaktorFlowScopeView.isDescendantOrSelf(scopeId, region.id)
            }
        },
        graphNodes = visibleGraphNodes,
        flowIdsByNode = flowIdsByNode.filterValues(visibleIds::contains),
        graphIdsByNode = graphIdsByNode.filterKeys(visibleGraphNodes.values::contains),
    )
    return ReaktorGraphLensResult(
        flow = filtered,
        matchingNodeIds = matching,
        visibleNodeIds = visibleIds,
        visibleRelationshipCount = visibleEdges.size,
    )
}
