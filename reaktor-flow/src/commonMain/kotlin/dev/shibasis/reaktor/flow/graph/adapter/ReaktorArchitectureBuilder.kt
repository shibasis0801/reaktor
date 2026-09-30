package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureElement
import dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureLevel
import dev.shibasis.reaktor.flow.graph.model.ReaktorArchitecturePort
import dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureRelationship
import dev.shibasis.reaktor.flow.graph.model.ReaktorArchitectureSnapshot
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphProvenance
import dev.shibasis.reaktor.flow.graph.model.graphArchitectureLabel
import dev.shibasis.reaktor.graph.core.Graph

fun buildReaktorArchitectureSnapshot(graph: Graph): ReaktorArchitectureSnapshot {
    val flow = buildReaktorFlowGraph(graph, scopeView = null)
    val scopeElements = flow.scopes.values.map { scope ->
        ReaktorArchitectureElement(
            id = scopeElementId(scope.id),
            label = scope.label,
            kind = scope.level.name.lowercase(),
            level = scope.level,
            scopeId = scope.id,
            parentId = scope.parentId?.let(::scopeElementId),
            description = "${scope.nodeCount} direct nodes · ${scope.descendantNodeCount} total",
            attributes = mapOf(
                "depth" to scope.depth.toString(),
                "nodeCount" to scope.nodeCount.toString(),
                "descendantNodeCount" to scope.descendantNodeCount.toString(),
                "childScopeCount" to scope.childScopeCount.toString(),
            ),
            provenance = ReaktorGraphProvenance(
                origin = "runtime-scope",
                graphId = scope.id,
                graphLabel = scope.label,
                runtimeType = flow.graphs[scope.id]?.let { runtimeQualifiedName(it) },
                evidence = listOf("Graph.nodes", "ContainerNode.graphs"),
            ),
        )
    }
    val codeElements = flow.nodes.mapNotNull { node ->
        val data = node.data as? ReaktorGraphNodeData ?: return@mapNotNull null
        if (data.isScopeSummary) return@mapNotNull null
        ReaktorArchitectureElement(
            id = node.id,
            label = data.title,
            kind = data.kind.name.lowercase(),
            level = ReaktorArchitectureLevel.Code,
            scopeId = data.scopeId,
            parentId = scopeElementId(data.scopeId),
            description = data.subtitle,
            status = data.status,
            attributes = data.attributes + mapOf(
                "graphLabel" to data.graphLabel,
                "providerCount" to data.providerCount.toString(),
                "consumerCount" to data.consumerCount.toString(),
                "rootNode" to data.isRootNode.toString(),
            ),
            ports = buildList {
                data.consumerPorts.forEach { port ->
                    add(
                        ReaktorArchitecturePort(
                            key = port.handleId,
                            type = port.type,
                            direction = "consumer",
                            connected = port.connected,
                        ),
                    )
                }
                data.providerPorts.forEach { port ->
                    add(
                        ReaktorArchitecturePort(
                            key = port.handleId,
                            type = port.type,
                            direction = "provider",
                            connected = port.connected,
                        ),
                    )
                }
            },
            provenance = data.provenance,
        )
    }
    val containmentRelationships = buildList {
        flow.scopes.values.forEach { scope ->
            scope.parentId?.let { parentId ->
                add(
                    ReaktorArchitectureRelationship(
                        id = "contains:${scopeElementId(parentId)}:${scopeElementId(scope.id)}",
                        source = scopeElementId(parentId),
                        target = scopeElementId(scope.id),
                        kind = "containment",
                        label = "contains",
                        provenance = ReaktorGraphProvenance(
                            origin = "runtime-scope",
                            graphId = parentId,
                            graphLabel = flow.scopes[parentId]?.label ?: parentId,
                            evidence = listOf("ContainerNode.graphs"),
                        ),
                    ),
                )
            }
        }
        codeElements.forEach { element ->
            add(
                ReaktorArchitectureRelationship(
                    id = "contains:${scopeElementId(element.scopeId)}:${element.id}",
                    source = scopeElementId(element.scopeId),
                    target = element.id,
                    kind = "containment",
                    label = "owns",
                    provenance = element.provenance.copy(evidence = element.provenance.evidence + "Graph.nodes"),
                ),
            )
        }
    }
    val runtimeRelationships = flow.edges.mapNotNull { edge ->
        val data = edge.data as? ReaktorGraphEdgeData ?: return@mapNotNull null
        val source = codeElements.firstOrNull { it.id == edge.source } ?: return@mapNotNull null
        ReaktorArchitectureRelationship(
            id = edge.id,
            source = edge.source,
            target = edge.target,
            kind = data.kind.name.lowercase(),
            label = data.label ?: edge.label,
            sourcePort = edge.sourceHandle,
            targetPort = edge.targetHandle,
            provenance = source.provenance.copy(
                evidence = source.provenance.evidence + relationshipEvidence(data.kind),
            ),
        )
    }
    val root = flow.scopes[ReaktorFlowScopeView.RootScopeId]
    return ReaktorArchitectureSnapshot(
        id = ReaktorFlowScopeView.RootScopeId,
        label = root?.label ?: graphArchitectureLabel(graph),
        scopes = flow.scopes.values.toList(),
        elements = scopeElements + codeElements,
        relationships = containmentRelationships + runtimeRelationships,
    )
}

private fun scopeElementId(scopeId: String): String = "scope:$scopeId"

private fun relationshipEvidence(kind: ReaktorEdgeKind): String = when (kind) {
    ReaktorEdgeKind.Attachment -> "RouteNode.attachedNodes"
    ReaktorEdgeKind.Navigation -> "RouteNode.navigationTargets"
    ReaktorEdgeKind.Data -> "typed connected port"
    ReaktorEdgeKind.Containment -> "ContainerNode.graphs"
}
