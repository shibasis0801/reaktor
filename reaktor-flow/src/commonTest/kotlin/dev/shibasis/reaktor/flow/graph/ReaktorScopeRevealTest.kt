package dev.shibasis.reaktor.flow.graph

import kotlin.test.*

class ReaktorScopeRevealTest {
    @Test fun revealingOneOwnerPreservesFoldedSiblingsAndExistingExpandedBranches() {
        val result = ReaktorFlowScopeView().expand("root/1").reveal(listOf("root/0/2"))
        assertEquals(setOf("root", "root/1", "root/0", "root/0/2"), result.expandedScopeIds)
        assertFalse(result.isExpanded("root/0/1"))
        assertEquals("root", result.focusedScopeId)
    }

    @Test fun aConnectionCrossingTheFocusedBoundaryWidensOnlyToItsCommonAncestor() {
        val view = ReaktorFlowScopeView().focus("root/0/1")
        assertEquals("root/0", view.reveal(listOf("root/0/1/0", "root/0/2")).focusedScopeId)
        assertEquals("root/0/1", view.reveal(listOf("root/0/1/0")).focusedScopeId)
        assertEquals(view, view.reveal(emptyList()))
    }
}
