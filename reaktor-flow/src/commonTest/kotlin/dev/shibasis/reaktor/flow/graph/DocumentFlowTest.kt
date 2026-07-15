package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.flow.graph.document.DocEdge
import dev.shibasis.reaktor.flow.graph.document.DocNode
import dev.shibasis.reaktor.flow.graph.document.DocNodeData
import dev.shibasis.reaktor.flow.graph.document.DocPoint
import dev.shibasis.reaktor.flow.graph.document.DocScope
import dev.shibasis.reaktor.flow.graph.document.GraphDocument
import dev.shibasis.reaktor.flow.graph.document.buildDocumentFlowGraph
import dev.shibasis.reaktor.flow.graph.document.documentNodeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentFlowTest {

    private fun doc() = GraphDocument(
        id = "d", tenantId = "t", name = "Doc",
        nodes = listOf(
            DocNode(id = "pinned", kind = "topic", position = DocPoint(400.0, 250.0), data = DocNodeData(title = "Pinned")),
            DocNode(id = "a", kind = "task", data = DocNodeData(title = "A")),
            DocNode(id = "b", kind = "unknown-kind", data = DocNodeData(title = "B")),
            DocNode(id = "inner", kind = "topic", scopeId = "root/p1", data = DocNodeData(title = "Inner")),
        ),
        edges = listOf(
            DocEdge(id = "e1", from = "a", to = "pinned", kind = "prereq", label = "before"),
            DocEdge(id = "e2", from = "a", to = "inner", kind = "related"),
        ),
        scopes = listOf(DocScope(id = "root/p1", label = "Phase 1")),
    )

    @Test
    fun projectsNodesEdgesAndRegions() {
        val flow = buildDocumentFlowGraph(doc())
        assertEquals(4, flow.nodes.size)
        assertEquals(2, flow.edges.size)
        assertEquals(1, flow.regions.size)
        assertEquals("Phase 1", flow.regions.single().label)
    }

    @Test
    fun pinnedPositionsAreExactAndUnpinnedAreDeterministic() {
        val first = buildDocumentFlowGraph(doc())
        val second = buildDocumentFlowGraph(doc())
        val pinned = first.nodes.first { it.id == "pinned" }
        assertEquals(400.0, pinned.position.x)
        assertEquals(250.0, pinned.position.y)
        // Deterministic: identical documents lay out identically.
        assertEquals(
            first.nodes.associate { it.id to it.position },
            second.nodes.associate { it.id to it.position },
        )
        // No two unpinned nodes share a position.
        val positions = first.nodes.map { it.position.x to it.position.y }
        assertEquals(positions.size, positions.toSet().size)
    }

    @Test
    fun collapsedScopeBecomesSummaryAndReRoutesEdges() {
        val flow = buildDocumentFlowGraph(doc(), scopeView = ReaktorFlowScopeView(emptySet()))
        val ids = flow.nodes.map { it.id }
        assertTrue("doc-scope:root/p1" in ids, "collapsed scope should render a summary node")
        assertTrue("inner" !in ids, "collapsed members are hidden")
        val rerouted = flow.edges.first { it.id == "doc-edge:e2" }
        assertEquals("doc-scope:root/p1", rerouted.target)
    }

    @Test
    fun expandedScopeViewShowsMembers() {
        val flow = buildDocumentFlowGraph(doc(), scopeView = ReaktorFlowScopeView(setOf("root/p1")))
        assertTrue(flow.nodes.any { it.id == "inner" })
        assertTrue(flow.nodes.none { it.id.startsWith("doc-scope:") })
    }

    @Test
    fun kindMappingCoversConceptCodeAndUnknown() {
        assertEquals(ReaktorNodeKind.Action, documentNodeKind("task"))
        assertEquals(ReaktorNodeKind.Data, documentNodeKind("book"))
        assertEquals(ReaktorNodeKind.Service, documentNodeKind("service"))
        assertEquals(ReaktorNodeKind.Node, documentNodeKind("anything-else"))
        val flow = buildDocumentFlowGraph(doc())
        val generic = flow.nodes.first { it.id == "b" }.data as ReaktorGraphNodeData
        assertEquals(ReaktorNodeKind.Node, generic.kind)
    }
}
