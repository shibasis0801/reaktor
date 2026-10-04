package dev.shibasis.reaktor.graph.architecture

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.shape
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ReaktorArchitectureLevel(
    val label: String,
    val summary: String,
) {
    @SerialName("system")
    System("System", "System context and its immediate boundaries"),

    @SerialName("container")
    Container("Container", "Runtime containers and collapsed child graphs"),

    @SerialName("component")
    Component("Component", "Immediate child graphs expanded into components"),

    @SerialName("code")
    Code("Code", "Complete runtime graph, nodes, ports, and relationships"),
}

@Serializable
data class ReaktorGraphProvenance(
    val origin: String,
    val graphId: String,
    val graphLabel: String,
    val runtimeType: String? = null,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class ReaktorArchitectureScope(
    val id: String,
    val label: String,
    val parentId: String?,
    val depth: Int,
    val nodeCount: Int,
    val descendantNodeCount: Int,
    val childScopeCount: Int,
    val level: ReaktorArchitectureLevel,
)

@Serializable
data class ReaktorArchitecturePort(
    val key: String,
    val type: String,
    val direction: String,
    val connected: Boolean,
)

@Serializable
data class ReaktorArchitectureElement(
    val id: String,
    val label: String,
    val kind: String,
    val level: ReaktorArchitectureLevel,
    val scopeId: String,
    val parentId: String?,
    val description: String? = null,
    val status: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val ports: List<ReaktorArchitecturePort> = emptyList(),
    val provenance: ReaktorGraphProvenance,
)

@Serializable
data class ReaktorArchitectureRelationship(
    val id: String,
    val source: String,
    val target: String,
    val kind: String,
    val label: String? = null,
    val sourcePort: String? = null,
    val targetPort: String? = null,
    val status: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val provenance: ReaktorGraphProvenance,
)

@Serializable
data class ReaktorArchitectureOverlay(
    val id: String,
    val label: String,
    val elements: List<ReaktorArchitectureElement> = emptyList(),
    val relationships: List<ReaktorArchitectureRelationship> = emptyList(),
)

@Serializable
data class ReaktorArchitectureSnapshot(
    val id: String,
    val label: String,
    val scopes: List<ReaktorArchitectureScope>,
    val elements: List<ReaktorArchitectureElement>,
    val relationships: List<ReaktorArchitectureRelationship>,
) {
    fun withOverlay(overlay: ReaktorArchitectureOverlay): ReaktorArchitectureSnapshot = copy(
        elements = (elements + overlay.elements).distinctBy(ReaktorArchitectureElement::id),
        relationships = (relationships + overlay.relationships)
            .distinctBy(ReaktorArchitectureRelationship::id),
    )

    fun withOverlays(overlays: Iterable<ReaktorArchitectureOverlay>): ReaktorArchitectureSnapshot =
        overlays.fold(this) { snapshot, overlay -> snapshot.withOverlay(overlay) }
}

val ReaktorArchitectureJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    prettyPrint = true
}

fun ReaktorArchitectureSnapshot.encode(): String =
    ReaktorArchitectureJson.encodeToString(ReaktorArchitectureSnapshot.serializer(), this)

fun Graph.architecture(): ReaktorArchitectureSnapshot = shape().architecture()

fun GraphShape.architecture(): ReaktorArchitectureSnapshot {
    val byId = scopes.associateBy(ScopeShape::id)
    val children = scopes.filter { it.parent != null }.groupBy { it.parent!! }
    val members = nodes.groupBy { it.scope }
    fun depth(scope: ScopeShape): Int = generateSequence(scope) { current -> current.parent?.let(byId::get) }.count() - 1
    fun descendants(scope: String): Int = members[scope].orEmpty().size + children[scope].orEmpty().sumOf { descendants(it.id) }
    val architectureScopes = scopes.map { scope ->
        val depth = depth(scope)
        ReaktorArchitectureScope(
            id = scope.id,
            label = scope.label,
            parentId = scope.parent,
            depth = depth,
            nodeCount = members[scope.id].orEmpty().size,
            descendantNodeCount = descendants(scope.id),
            childScopeCount = children[scope.id].orEmpty().size,
            level = when (depth) {
                0 -> ReaktorArchitectureLevel.System
                1 -> ReaktorArchitectureLevel.Container
                else -> ReaktorArchitectureLevel.Component
            },
        )
    }
    val scopeElements = architectureScopes.map { scope ->
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
            provenance = ReaktorGraphProvenance(origin = "runtime-scope", graphId = scope.id, graphLabel = scope.label,
                evidence = listOf("Graph.nodes", "ContainerNode.graphs")),
        )
    }
    val codeElements = nodes.map { node ->
        val scopeLabel = byId[node.scope]?.label ?: node.scope
        val ports = node.ports.filterNot { it.internal }
        ReaktorArchitectureElement(
            id = node.id,
            label = node.label,
            kind = node.kind.name.lowercase(),
            level = ReaktorArchitectureLevel.Code,
            scopeId = node.scope,
            parentId = scopeElementId(node.scope),
            description = node.type,
            status = node.lifecycle,
            attributes = node.attributes + buildMap {
                put("graphLabel", scopeLabel)
                put("providerCount", ports.count { it.provides }.toString())
                put("consumerCount", ports.count { !it.provides }.toString())
                node.route?.let { put("route", it) }
            },
            ports = ports.map { port ->
                ReaktorArchitecturePort(port.key, port.type, if (port.provides) "provider" else "consumer",
                    connected = port.connections > 0 || port.wiring == PortWiring.Connected || port.wiring == PortWiring.Injected)
            },
            provenance = ReaktorGraphProvenance(origin = "runtime-node", graphId = node.scope, graphLabel = scopeLabel,
                runtimeType = node.type, evidence = listOf("Graph.nodes")),
        )
    }
    val provenanceOf = codeElements.associate { it.id to it.provenance }
    val containment = architectureScopes.mapNotNull { scope ->
        scope.parentId?.let { parent ->
            ReaktorArchitectureRelationship(
                id = "contains:${scopeElementId(parent)}:${scopeElementId(scope.id)}",
                source = scopeElementId(parent), target = scopeElementId(scope.id), kind = "containment", label = "contains",
                provenance = ReaktorGraphProvenance(origin = "runtime-scope", graphId = parent, graphLabel = byId[parent]?.label ?: parent,
                    evidence = listOf("ContainerNode.graphs")),
            )
        }
    } + codeElements.map { element ->
        ReaktorArchitectureRelationship(
            id = "contains:${scopeElementId(element.scopeId)}:${element.id}",
            source = scopeElementId(element.scopeId), target = element.id, kind = "containment", label = "owns",
            provenance = element.provenance.copy(evidence = element.provenance.evidence + "Graph.nodes"),
        )
    }
    fun evidenced(source: String, evidence: String) = provenanceOf.getValue(source).let { it.copy(evidence = it.evidence + evidence) }
    val data = wires.filter { it.provider in provenanceOf && it.consumer in provenanceOf }.map { wire ->
        ReaktorArchitectureRelationship(
            id = "${wire.provider}.${wire.providerPort}>${wire.consumer}.${wire.consumerPort}",
            source = wire.provider, target = wire.consumer, kind = "data", label = wire.type,
            sourcePort = wire.providerPort, targetPort = wire.consumerPort,
            provenance = evidenced(wire.provider, "typed connected port"),
        )
    }
    val navigation = routes.filter { it.from in provenanceOf && it.to in provenanceOf }.map { route ->
        ReaktorArchitectureRelationship(
            id = "navigation:${route.from}>${route.to}", source = route.from, target = route.to, kind = "navigation",
            provenance = evidenced(route.from, "RouteNode.navigationTargets"),
        )
    }
    val attachment = nodes.mapNotNull { node ->
        val route = node.attachedTo?.takeIf { it in provenanceOf } ?: return@mapNotNull null
        ReaktorArchitectureRelationship(
            id = "attachment:$route>${node.id}", source = route, target = node.id, kind = "attachment",
            provenance = evidenced(route, "RouteNode.attachedNodes"),
        )
    }
    val root = scopes.firstOrNull { it.parent == null }
    return ReaktorArchitectureSnapshot(
        id = root?.id ?: "root",
        label = root?.label ?: "Graph",
        scopes = architectureScopes,
        elements = scopeElements + codeElements,
        relationships = containment + data + navigation + attachment,
    )
}

private fun scopeElementId(scopeId: String): String = "scope:$scopeId"
