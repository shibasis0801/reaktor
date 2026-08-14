package dev.shibasis.reaktor.flow.graph.model

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.ui.Navigable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The four zoom levels used by Reaktor's C4-style architecture projection. */
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

/**
 * Product/tooling supplied operational facts layered over the source architecture.
 *
 * [ReaktorArchitectureElement.kind] is intentionally open. Control planes use `task`, `resource`,
 * `run`, `provider`, and `deployment`; framework consumers can add kinds without a schema change.
 */
@Serializable
data class ReaktorArchitectureOverlay(
    val id: String,
    val label: String,
    val elements: List<ReaktorArchitectureElement> = emptyList(),
    val relationships: List<ReaktorArchitectureRelationship> = emptyList(),
)

/**
 * Primitive-only architecture read model shared by Desktop, exported tooling, and MCP.
 *
 * Scope elements use ids prefixed with `scope:`; runtime code elements keep the stable flow ids
 * produced by [dev.shibasis.reaktor.flow.graph.adapter.buildReaktorFlowGraph]. Containment is
 * explicit, so clients can render a C4 tree without reverse-engineering path-encoded ids.
 */
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

internal data class ReaktorRuntimeScopeCatalog(
    val scopes: LinkedHashMap<String, ReaktorArchitectureScope>,
    val graphs: LinkedHashMap<String, Graph>,
    val idsByGraph: Map<Graph, String>,
) {
    fun graph(scopeId: String): Graph? = graphs[scopeId]

    fun id(graph: Graph): String? = idsByGraph[graph]

    fun path(scopeId: String): List<ReaktorArchitectureScope> {
        val result = ArrayDeque<ReaktorArchitectureScope>()
        var cursor: String? = scopeId
        while (cursor != null) {
            val scope = scopes[cursor] ?: break
            result.addFirst(scope)
            cursor = scope.parentId
        }
        return result.toList()
    }
}

/**
 * One traversal owns scope identity for layout, navigation, breadcrumbs, and exported read models.
 * Child ordinals are assigned across every container in a graph, in node order, matching layout.
 */
internal fun buildReaktorRuntimeScopeCatalog(root: Graph): ReaktorRuntimeScopeCatalog {
    val scopes = linkedMapOf<String, ReaktorArchitectureScope>()
    val graphs = linkedMapOf<String, Graph>()
    val idsByGraph = linkedMapOf<Graph, String>()

    fun walk(graph: Graph, id: String, parentId: String?, depth: Int): Int {
        graphs[id] = graph
        idsByGraph[graph] = id
        val childGraphs = graph.nodes
            .filterIsInstance<ContainerNode>()
            .flatMap(ContainerNode::graphs)
        var descendantNodeCount = graph.nodes.size
        childGraphs.forEachIndexed { index, child ->
            descendantNodeCount += walk(
                graph = child,
                id = ReaktorFlowScopeView.childScopeId(id, index),
                parentId = id,
                depth = depth + 1,
            )
        }
        scopes[id] = ReaktorArchitectureScope(
            id = id,
            label = graphArchitectureLabel(graph),
            parentId = parentId,
            depth = depth,
            nodeCount = graph.nodes.size,
            descendantNodeCount = descendantNodeCount,
            childScopeCount = childGraphs.size,
            level = when (depth) {
                0 -> ReaktorArchitectureLevel.System
                1 -> ReaktorArchitectureLevel.Container
                else -> ReaktorArchitectureLevel.Component
            },
        )
        return descendantNodeCount
    }

    walk(root, ReaktorFlowScopeView.RootScopeId, parentId = null, depth = 0)
    val orderedScopes = linkedMapOf<String, ReaktorArchitectureScope>()
    graphs.keys.forEach { id -> orderedScopes[id] = scopes.getValue(id) }
    return ReaktorRuntimeScopeCatalog(orderedScopes, graphs, idsByGraph)
}

internal fun graphArchitectureLabel(graph: Graph): String =
    (graph as? Navigable)?.label?.takeIf(String::isNotBlank)
        ?: graph.label.takeIf(String::isNotBlank)
        ?: "Graph"
