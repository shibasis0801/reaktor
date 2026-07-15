package dev.shibasis.reaktor.flow.graph.document

import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeMarker
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth

/**
 * L3 of the graph-editor unification: project a user-authored [GraphDocument]
 * onto the same [ReaktorFlowGraph] the desktop canvas already renders — so
 * documents (roadmaps, concept maps, flows) appear on `ReaktorGraphCanvas` /
 * `ReaktorGraphEditor` with zero changes to the canvas itself.
 *
 * Layout contract mirrors the document semantics: a node with a pinned
 * [DocNode.position] renders exactly there; unpinned nodes fall into a
 * deterministic per-scope grid (stable across rebuilds, no physics). Scopes
 * project as dashed regions; with a [ReaktorFlowScopeView], collapsed scopes
 * render as boundary summary nodes and their members disappear — identical
 * semantics to the derived-graph projection and the web canvas.
 */
fun buildDocumentFlowGraph(
    document: GraphDocument,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    scopeView: ReaktorFlowScopeView? = null,
): ReaktorFlowGraph {
    val builder = DocumentFlowBuilder(document, style, scopeView)
    return builder.build()
}

/** Kind names that map onto the semantic palette; anything else renders generic. */
private val conceptKinds: Map<String, ReaktorNodeKind> = mapOf(
    "topic" to ReaktorNodeKind.Node,
    "resource" to ReaktorNodeKind.Data,
    "book" to ReaktorNodeKind.Data,
    "course" to ReaktorNodeKind.Data,
    "chapter" to ReaktorNodeKind.Ui,
    "milestone" to ReaktorNodeKind.Release,
    "task" to ReaktorNodeKind.Action,
    "goal" to ReaktorNodeKind.Agent,
)

fun documentNodeKind(kind: String): ReaktorNodeKind {
    conceptKinds[kind.lowercase()]?.let { return it }
    return ReaktorNodeKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
        ?: ReaktorNodeKind.Node
}

fun documentEdgeKind(kind: String): ReaktorEdgeKind = when (kind.lowercase()) {
    "navigation" -> ReaktorEdgeKind.Navigation
    "containment" -> ReaktorEdgeKind.Containment
    "attachment" -> ReaktorEdgeKind.Attachment
    else -> ReaktorEdgeKind.Data // prereq / optional / related / dataflow / custom
}

private class DocumentFlowBuilder(
    private val document: GraphDocument,
    private val style: ReaktorGraphStyle,
    private val scopeView: ReaktorFlowScopeView?,
) {
    private val nodeWidth = style.defaultNodeWidth()
    private val nodeHeight = style.defaultNodeHeight()
    private val gapX = style.layout.columnGapPx
    private val gapY = style.layout.rowGapPx
    private val pad = style.region.contentPaddingXPx

    private val nodesByScope: Map<String, List<DocNode>> = document.nodes.groupBy { it.scopeId }
    private val scopeIds: Set<String> = document.scopes.map { it.id }.toSet()

    /** A scope is open when the view is absent or it and its ancestors are expanded. */
    private fun scopeOpen(scopeId: String): Boolean {
        if (scopeId == ROOT_DOC_SCOPE) return true
        val view = scopeView ?: return true
        var acc: String? = null
        for (segment in scopeId.split('/')) {
            acc = if (acc == null) segment else "$acc/$segment"
            if (acc != ROOT_DOC_SCOPE && acc in scopeIds && !view.isExpanded(acc)) return false
        }
        return true
    }

    private fun collapsedAncestor(scopeId: String): String? {
        if (scopeView == null) return null
        var acc: String? = null
        for (segment in scopeId.split('/')) {
            acc = if (acc == null) segment else "$acc/$segment"
            if (acc != ROOT_DOC_SCOPE && acc in scopeIds && !scopeView.isExpanded(acc)) return acc
        }
        return null
    }

    fun build(): ReaktorFlowGraph {
        val flowNodes = mutableListOf<Node>()
        val regions = mutableListOf<ReaktorGraphRegion>()
        val placed = mutableMapOf<String, Pair<Double, Double>>() // node id → absolute position

        // Deterministic scope origins: root content first, then each open scope
        // block stacked below, summaries for collapsed ones.
        var cursorY = style.layout.rootOriginPx
        val orderedScopes = listOf(ROOT_DOC_SCOPE) + document.scopes.map { it.id }

        for (scopeId in orderedScopes) {
            if (scopeId != ROOT_DOC_SCOPE && collapsedAncestor(scopeId) != null && collapsedAncestor(scopeId) != scopeId) continue
            val members = nodesByScope[scopeId].orEmpty()
            val scope = document.scopes.firstOrNull { it.id == scopeId }

            if (scopeId != ROOT_DOC_SCOPE && scopeView != null && !scopeView.isExpanded(scopeId)) {
                // Collapsed boundary summary — one card standing in for the scope.
                val summaryId = "doc-scope:$scopeId"
                flowNodes += summaryNode(summaryId, scope?.label ?: scopeId, membersInside(scopeId), style.layout.rootOriginPx, cursorY)
                placed[summaryId] = style.layout.rootOriginPx to cursorY
                cursorY += nodeHeight + gapY * 2
                continue
            }
            if (members.isEmpty() && scopeId != ROOT_DOC_SCOPE && scope == null) continue

            // Grid for unpinned members; pinned members keep their coordinates.
            val unpinned = members.filter { it.position == null }
            val columns = maxOf(1, kotlin.math.ceil(kotlin.math.sqrt(unpinned.size.toDouble())).toInt())
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE

            members.forEachIndexed { _, member -> }
            var gridIndex = 0
            for (member in members) {
                val position = member.position
                val (x, y) = if (position != null) {
                    position.x to position.y
                } else {
                    val col = gridIndex % columns
                    val row = gridIndex / columns
                    gridIndex += 1
                    (style.layout.rootOriginPx + col * (nodeWidth + gapX)) to (cursorY + row * (nodeHeight + gapY))
                }
                placed[member.id] = x to y
                flowNodes += documentNode(member, x, y)
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x + nodeWidth); maxY = maxOf(maxY, y + nodeHeight)
            }

            if (scope != null && members.isNotEmpty()) {
                regions += ReaktorGraphRegion(
                    label = scope.label,
                    id = scope.id,
                    x = minX - pad,
                    y = minY - style.region.contentPaddingTopPx,
                    width = (maxX - minX) + pad * 2,
                    height = (maxY - minY) + style.region.contentPaddingTopPx + style.region.contentPaddingBottomPx,
                    color = ReaktorNodeKind.Container.borderColor,
                    depth = scope.id.count { it == '/' },
                )
            }
            if (members.isNotEmpty()) {
                cursorY = maxOf(cursorY, maxY) + gapY * 2
            }
        }

        // Edges re-route to boundary summaries exactly like the web projection.
        val flowEdges = document.edges.mapNotNull { edge ->
            val source = visibleEndpoint(edge.from) ?: return@mapNotNull null
            val target = visibleEndpoint(edge.to) ?: return@mapNotNull null
            if (source == target) return@mapNotNull null
            val kind = documentEdgeKind(edge.kind)
            Edge(
                id = "doc-edge:${edge.id}",
                source = source,
                target = target,
                data = dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData(kind = kind, label = edge.label ?: edge.kind),
                label = edge.label,
                markerEnd = EdgeMarker(color = null),
            )
        }

        return ReaktorFlowGraph(
            nodes = flowNodes,
            edges = flowEdges,
            regions = regions,
            graphNodes = emptyMap(),
            flowIdsByNode = emptyMap(),
            graphIdsByNode = emptyMap(),
            graphs = emptyMap(),
            style = style,
        )
    }

    private fun membersInside(scopeId: String): Int =
        document.nodes.count { it.scopeId == scopeId || it.scopeId.startsWith("$scopeId/") }

    private fun visibleEndpoint(nodeId: String): String? {
        val node = document.nodes.firstOrNull { it.id == nodeId } ?: return null
        val collapsed = collapsedAncestor(node.scopeId) ?: return nodeId
        return "doc-scope:$collapsed"
    }

    private fun documentNode(member: DocNode, x: Double, y: Double): Node {
        val kind = documentNodeKind(member.kind)
        val providers = member.ports.filter { it.role == DocPortRole.Provider }
        val consumers = member.ports.filter { it.role == DocPortRole.Consumer }
        return Node(
            id = member.id,
            position = XYPosition(x, y),
            width = nodeWidth,
            height = nodeHeight,
            data = ReaktorGraphNodeData(
                nodeId = member.id,
                title = member.data.title.ifBlank { member.id },
                subtitle = member.data.content?.take(80),
                graphLabel = document.name,
                isRootNode = false,
                providerCount = providers.size,
                consumerCount = consumers.size,
                providerPorts = emptyList(),
                consumerPorts = emptyList(),
                hiddenProviderCount = providers.size,
                hiddenConsumerCount = consumers.size,
                kind = kind,
            ),
            type = "graph",
        )
    }

    private fun summaryNode(id: String, label: String, count: Int, x: Double, y: Double): Node =
        Node(
            id = id,
            position = XYPosition(x, y),
            width = nodeWidth,
            height = nodeHeight,
            data = ReaktorGraphNodeData(
                nodeId = id,
                title = label,
                subtitle = "$count nodes · collapsed",
                graphLabel = document.name,
                isRootNode = false,
                providerCount = 0,
                consumerCount = 0,
                providerPorts = emptyList(),
                consumerPorts = emptyList(),
                hiddenProviderCount = 0,
                hiddenConsumerCount = 0,
                kind = ReaktorNodeKind.Container,
            ),
            type = "graph",
        )
}
