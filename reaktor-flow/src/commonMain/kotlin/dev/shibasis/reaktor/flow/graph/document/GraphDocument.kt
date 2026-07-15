package dev.shibasis.reaktor.flow.graph.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The graph-editor's persistent document model — the one serializable format
 * shared by every target (desktop Compose, web React, Manna) and every graph
 * family (concept roadmaps, knowledge maps, n8n-style executable flows).
 *
 * This schema is the **frozen inter-phase contract** of the graph-editor
 * unification (see `graph-editor-unification.md`): the TS twin in Manna and
 * the Memgraph `graphdoc.*` gateway ops read/write exactly this JSON. Evolve
 * it additively only — new optional fields, never renames — and extend
 * [GraphDocumentSchemaTest] when you do.
 *
 * Design notes:
 * - **Kinds are open.** [DocNode.kind] is a string resolved against the node
 *   template registry (concept kinds like `topic`/`resource`/`milestone`, the
 *   18 semantic [dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind]s, or
 *   anything a future family defines). Unknown kinds render as generic cards.
 * - **Positions are optional.** A node without [DocNode.position] is placed by
 *   the deterministic blueprint layout; a node with one is user-pinned. Mixed
 *   documents are the normal case (curated skeleton + freeform additions).
 * - **Scopes nest arbitrarily** via path-encoded ids (`root/a/b/...`), the same
 *   encoding as [dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView] —
 *   R4 levels, collapse boundaries, and breadcrumbs come for free.
 */
@Serializable
data class GraphDocument(
    val id: String,
    val tenantId: String,
    val name: String,
    val kind: GraphDocumentKind = GraphDocumentKind.Concept,
    val nodes: List<DocNode> = emptyList(),
    val edges: List<DocEdge> = emptyList(),
    val scopes: List<DocScope> = emptyList(),
    /** Monotonic save counter, bumped by persistence, not by edits. */
    val version: Int = 1,
    /** ISO-8601; stamped by the persistence layer on save. */
    val updatedAt: String? = null,
) {
    fun node(id: String): DocNode? = nodes.firstOrNull { it.id == id }
    fun edge(id: String): DocEdge? = edges.firstOrNull { it.id == id }
    fun scope(id: String): DocScope? = scopes.firstOrNull { it.id == id }
}

@Serializable
enum class GraphDocumentKind {
    /** Learning/execution roadmap — ordered, prerequisite-driven. */
    @SerialName("roadmap") Roadmap,
    /** Freeform concept map. */
    @SerialName("concept") Concept,
    /** Executable dataflow (typed ports, n8n-style). */
    @SerialName("flow") Flow,
}

@Serializable
data class DocNode(
    val id: String,
    /** Open kind resolved against the node template registry. */
    val kind: String,
    /** Owning scope (path-encoded, `root` = top level). */
    val scopeId: String = ROOT_DOC_SCOPE,
    /** Absent = auto-layout; present = user-pinned. */
    val position: DocPoint? = null,
    val data: DocNodeData = DocNodeData(),
    /** Typed ports — only meaningful for flow-family kinds. */
    val ports: List<DocPort> = emptyList(),
)

@Serializable
data class DocNodeData(
    val title: String = "",
    val content: String? = null,
    /** Open extension bag (progress, priority, chapter lists, template params…). */
    val meta: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DocPort(
    val key: String,
    val type: String,
    val role: DocPortRole,
)

@Serializable
enum class DocPortRole {
    @SerialName("provider") Provider,
    @SerialName("consumer") Consumer,
}

@Serializable
data class DocEdge(
    val id: String,
    val from: String,
    val to: String,
    /** Domain kind (prereq/optional/related/dataflow/…): styled by the renderer. */
    val kind: String,
    /** Port bindings — flow-family edges connect specific ports. */
    val fromPort: String? = null,
    val toPort: String? = null,
    val label: String? = null,
)

@Serializable
data class DocScope(
    /** Path-encoded id below `root`, e.g. `root/phase-1`. */
    val id: String,
    val label: String,
    val color: String? = null,
)

@Serializable
data class DocPoint(val x: Double, val y: Double)

const val ROOT_DOC_SCOPE: String = "root"

/**
 * The document wire format. `ignoreUnknownKeys` + `explicitNulls = false` give
 * forward compatibility: newer writers may add optional fields without breaking
 * older readers, and absent optionals stay absent on the wire.
 */
val GraphDocumentJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

fun GraphDocument.encode(): String = GraphDocumentJson.encodeToString(GraphDocument.serializer(), this)

fun decodeGraphDocument(text: String): GraphDocument =
    GraphDocumentJson.decodeFromString(GraphDocument.serializer(), text)
