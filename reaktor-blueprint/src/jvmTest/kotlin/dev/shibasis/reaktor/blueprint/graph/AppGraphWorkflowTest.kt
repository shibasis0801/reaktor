package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.graph.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppGraphWorkflowTest {
    private val graph = AppGraph(GraphShape(
        listOf(ScopeShape("root", "Worker"), ScopeShape("flow", "Matching", "root", "matching"),
            ScopeShape("nested", "Planner", "flow", "planner")),
        listOf(
            NodeShape("matching", "root", "Matching", "Workflow", NodeKind.Workflow, attributes = mapOf("workflow" to "matching")),
            NodeShape("candidates", "flow", "Candidates", "Activity", NodeKind.Node),
            NodeShape("planner", "flow", "Planner", "Workflow", NodeKind.Workflow, attributes = mapOf("workflow" to "planner")),
            NodeShape("plan", "nested", "Plan", "Activity", NodeKind.Node),
            NodeShape("store", "root", "Postgres", "Store", NodeKind.Store),
        ),
        listOf(WireShape("candidates", "read", "store", "rows", "Candidates"),
            WireShape("plan", "candidates", "candidates", "result", "Candidates")), emptyList()), GraphSource.System)

    @Test fun `whole graph folds steps and preserves external wire identities`() {
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard))
        assertEquals(setOf("matching", "store"), layout.cards.keys)
        assertEquals(layout.cardOf["matching"], layout.cardOf["plan"])
        assertTrue(layout.links.any { it.from == "store" && it.to == "matching" && "store.rows>candidates.read" in it.members })
    }

    @Test fun `opening a workflow reveals its steps while nested workflows stay folded`() {
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard, expandedFlows = setOf("matching")))
        assertTrue("candidates" in layout.cards && "planner" in layout.cards)
        assertFalse("plan" in layout.cards)
        assertEquals(layout.cardOf["planner"], layout.cardOf["plan"])
        val expanded = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard, expandedFlows = setOf("matching", "planner")))
        assertTrue("plan" in expanded.cards)
    }
}
