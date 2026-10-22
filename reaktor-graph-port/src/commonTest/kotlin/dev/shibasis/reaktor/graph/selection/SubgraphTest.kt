package dev.shibasis.reaktor.graph.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubgraphTest {
    private val graph = SearchGraph("system:dev", "r1", listOf(SearchNode("chat", "Chat"), SearchNode("outbox", "Outbox"), SearchNode("feed", "Feed")), listOf(SearchEdge("send", "chat", "outbox", "calls"), SearchEdge("read", "feed", "chat", "calls")))

    @Test fun aQueryReturnsGroundedNodesAndDirectedRelationships() {
        val found = graph.query(SubgraphQuery("chat", depth = 1, direction = "downstream"))
        assertEquals(listOf("chat", "outbox"), found.nodes.map { it.id })
        assertEquals(listOf("send"), found.edges.map { it.id })
        graph.validate(found.proposal("chat-view", "Chat", "Debug sending"))
    }

    @Test fun anExplicitSelectionDoesNotGrowWhenTheGraphChanges() {
        val saved = graph.query(SubgraphQuery(seeds = listOf("chat"), depth = 0)).proposal("one", "Chat", "Only chat")
        val changed = graph.copy(revision = "r2")
        val view = changed.resolve(saved)
        assertTrue(view.stale)
        assertEquals(listOf("chat"), view.nodes.map { it.id })
        assertEquals(2, view.boundary.size)
        assertTrue(view.edges.isEmpty())
    }

    @Test fun invalidReferencesAndDanglingRelationshipsAreRefused() {
        val selection = Subgraph("id", "Chat", "", graph.id, graph.revision, listOf("chat"), listOf("send"))
        assertFailsWith<IllegalArgumentException> { graph.validate(selection) }
        assertFailsWith<IllegalArgumentException> { graph.query(SubgraphQuery(seeds = listOf("invented"))) }
        assertFailsWith<IllegalArgumentException> { graph.validate(selection.copy(graphId = "other")) }
    }

    @Test fun boundedQueriesReportTruncationAndEmptyQueriesDoNotInventResults() {
        assertTrue(graph.query(SubgraphQuery(seeds = listOf("chat"), limit = 1)).truncated)
        assertTrue(graph.query(SubgraphQuery()).nodes.isEmpty())
    }
}
