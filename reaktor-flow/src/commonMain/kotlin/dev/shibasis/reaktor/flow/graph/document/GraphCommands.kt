package dev.shibasis.reaktor.flow.graph.document

/**
 * The editing vocabulary of the unified graph-editor: every mutation of a
 * [GraphDocument] is a [GraphCommand] applied through a
 * [GraphEditorSession]. Commands are pure `(document) -> document` functions
 * with validation; the session supplies undo/redo, dirty tracking, and
 * change notification. Renderers (Compose desktop, React web) translate
 * gestures — drag, connect, palette pick — into commands and never touch the
 * document directly, so every editing surface gets identical semantics.
 *
 * Invalid commands throw [GraphCommandException]; [GraphEditorSession.execute]
 * surfaces that as a failed [Result] without mutating state.
 */
sealed interface GraphCommand {
    /** Human-readable label for undo menus ("Undo Add node"). */
    val label: String

    fun apply(document: GraphDocument): GraphDocument
}

class GraphCommandException(message: String) : IllegalArgumentException(message)

private fun require(condition: Boolean, message: () -> String) {
    if (!condition) throw GraphCommandException(message())
}

// ── Nodes ───────────────────────────────────────────────────────────

data class AddNode(val node: DocNode) : GraphCommand {
    override val label = "Add node"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.node(node.id) == null) { "Node already exists: ${node.id}" }
        require(node.scopeId == ROOT_DOC_SCOPE || document.scope(node.scopeId) != null) {
            "Unknown scope: ${node.scopeId}"
        }
        return document.copy(nodes = document.nodes + node)
    }
}

data class UpdateNodeData(val nodeId: String, val data: DocNodeData) : GraphCommand {
    override val label = "Edit node"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.node(nodeId) != null) { "Unknown node: $nodeId" }
        return document.copy(nodes = document.nodes.map { if (it.id == nodeId) it.copy(data = data) else it })
    }
}

/** `position = null` releases the node back to auto-layout. */
data class MoveNode(val nodeId: String, val position: DocPoint?) : GraphCommand {
    override val label = "Move node"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.node(nodeId) != null) { "Unknown node: $nodeId" }
        return document.copy(nodes = document.nodes.map { if (it.id == nodeId) it.copy(position = position) else it })
    }
}

data class SetNodeScope(val nodeId: String, val scopeId: String) : GraphCommand {
    override val label = "Move node into scope"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.node(nodeId) != null) { "Unknown node: $nodeId" }
        require(scopeId == ROOT_DOC_SCOPE || document.scope(scopeId) != null) { "Unknown scope: $scopeId" }
        return document.copy(nodes = document.nodes.map { if (it.id == nodeId) it.copy(scopeId = scopeId) else it })
    }
}

/** Removes the node and every edge touching it. */
data class RemoveNode(val nodeId: String) : GraphCommand {
    override val label = "Delete node"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.node(nodeId) != null) { "Unknown node: $nodeId" }
        return document.copy(
            nodes = document.nodes.filterNot { it.id == nodeId },
            edges = document.edges.filterNot { it.from == nodeId || it.to == nodeId },
        )
    }
}

// ── Edges ───────────────────────────────────────────────────────────

data class AddEdge(val edge: DocEdge) : GraphCommand {
    override val label = "Connect"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.edge(edge.id) == null) { "Edge already exists: ${edge.id}" }
        val from = document.node(edge.from) ?: throw GraphCommandException("Unknown source node: ${edge.from}")
        val to = document.node(edge.to) ?: throw GraphCommandException("Unknown target node: ${edge.to}")
        edge.fromPort?.let { port ->
            require(from.ports.any { it.key == port && it.role == DocPortRole.Provider }) {
                "Node ${from.id} has no provider port '$port'"
            }
        }
        edge.toPort?.let { port ->
            require(to.ports.any { it.key == port && it.role == DocPortRole.Consumer }) {
                "Node ${to.id} has no consumer port '$port'"
            }
        }
        return document.copy(edges = document.edges + edge)
    }
}

data class RemoveEdge(val edgeId: String) : GraphCommand {
    override val label = "Disconnect"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.edge(edgeId) != null) { "Unknown edge: $edgeId" }
        return document.copy(edges = document.edges.filterNot { it.id == edgeId })
    }
}

data class UpdateEdge(val edgeId: String, val kind: String? = null, val label_: String? = null) : GraphCommand {
    override val label = "Edit connection"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.edge(edgeId) != null) { "Unknown edge: $edgeId" }
        return document.copy(edges = document.edges.map { edge ->
            if (edge.id == edgeId) edge.copy(kind = kind ?: edge.kind, label = label_ ?: edge.label) else edge
        })
    }
}

// ── Scopes (nest arbitrarily; path-encoded ids) ─────────────────────

data class AddScope(val scope: DocScope) : GraphCommand {
    override val label = "Add group"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.scope(scope.id) == null) { "Scope already exists: ${scope.id}" }
        val parent = scope.id.substringBeforeLast('/', missingDelimiterValue = "")
        require(parent == ROOT_DOC_SCOPE || (parent.isNotEmpty() && document.scope(parent) != null)) {
            "Unknown parent scope for: ${scope.id}"
        }
        return document.copy(scopes = document.scopes + scope)
    }
}

/**
 * Removes the scope and everything nested beneath it; member nodes survive by
 * reparenting to the removed scope's parent (Blueprints' "expand node" — a
 * group is a boundary, not a cage).
 */
data class RemoveScope(val scopeId: String) : GraphCommand {
    override val label = "Ungroup"
    override fun apply(document: GraphDocument): GraphDocument {
        require(document.scope(scopeId) != null) { "Unknown scope: $scopeId" }
        val parent = scopeId.substringBeforeLast('/', missingDelimiterValue = ROOT_DOC_SCOPE)
        val prefix = "$scopeId/"
        val removed = { id: String -> id == scopeId || id.startsWith(prefix) }
        return document.copy(
            scopes = document.scopes.filterNot { removed(it.id) },
            nodes = document.nodes.map { if (removed(it.scopeId)) it.copy(scopeId = parent) else it },
        )
    }
}

// ── Document ────────────────────────────────────────────────────────

data class RenameDocument(val name: String) : GraphCommand {
    override val label = "Rename"
    override fun apply(document: GraphDocument): GraphDocument {
        require(name.isNotBlank()) { "Document name cannot be blank" }
        return document.copy(name = name)
    }
}
