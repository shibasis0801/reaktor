package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.flow.graph.model.*
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import kotlin.test.*

class ReaktorArchitectureFlowLayoutTest {
    private val provenance = ReaktorGraphProvenance("test", "compute", "Compute")
    private fun element(id: String, scope: String) = ReaktorArchitectureElement(id, id, "worker",
        ReaktorArchitectureLevel.Container, scope, null, provenance = provenance)

    @Test fun largeTopologyUsesBothAxesWithoutOverlappingGroups() {
        val overlay = ReaktorArchitectureOverlay("compute", "Compute", listOf(21, 20, 1, 9).flatMapIndexed { group, count ->
            (0 until count).map { element("$group:$it", "group-$group") }
        })
        val graph = overlay.toGroupedFlowGraph(ReaktorGraphStyle())
        assertEquals(51, graph.nodes.size)
        assertTrue(graph.regions.map { it.y }.distinct().size > 1, "All groups must not be squeezed into a single strip")
        graph.regions.forEachIndexed { index, a -> graph.regions.drop(index + 1).forEach { b ->
            assertFalse(a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y,
                "${a.id} overlaps ${b.id}")
        } }
        val width = graph.regions.maxOf { it.x + it.width } - graph.regions.minOf { it.x }
        val height = graph.regions.maxOf { it.y + it.height } - graph.regions.minOf { it.y }
        assertTrue(width / height in 1.0..3.5, "Topology aspect ratio ${width / height} wastes the desktop viewport")
    }

    @Test fun dependencyFocusRetainsPeerEdgesAndIsolatedSelections() {
        val elements = listOf("a", "b", "c", "d").map { element(it, "group") }
        fun edge(a: String, b: String) = ReaktorArchitectureRelationship("$a-$b", a, b, "service", provenance = provenance)
        val overlay = ReaktorArchitectureOverlay("compute", "Compute", elements, listOf(edge("a", "b"), edge("a", "c"), edge("b", "c")))
        val focused = overlay.toGroupedFlowGraph(ReaktorGraphStyle(), "a", true)
        assertEquals(setOf("a", "b", "c"), focused.nodes.map { it.id }.toSet())
        assertEquals(3, focused.edges.size)
        assertEquals(listOf("d"), overlay.toGroupedFlowGraph(ReaktorGraphStyle(), "d", true).nodes.map { it.id })
    }

    @Test fun largeSingleGroupAdaptsItsColumnsToTheActualCanvas() {
        val overlay = ReaktorArchitectureOverlay("records", "Returned records", (0 until 100).map { element("$it", "nodes") })
        val laptop = overlay.toGroupedFlowGraph(ReaktorGraphStyle(), viewportAspectRatio = 1.5)
        val wide = overlay.toGroupedFlowGraph(ReaktorGraphStyle(), viewportAspectRatio = 4.0)
        assertTrue(wide.nodes.map { it.position.x }.distinct().size > laptop.nodes.map { it.position.x }.distinct().size)
        val region = wide.regions.single()
        assertTrue(region.width / region.height > 2.5, "A returned page must not become a four-column vertical strip")
        wide.nodes.forEachIndexed { index, a -> wide.nodes.drop(index + 1).forEach { b ->
            assertFalse(a.position.x < b.position.x + requireNotNull(b.width) && a.position.x + requireNotNull(a.width) > b.position.x &&
                a.position.y < b.position.y + requireNotNull(b.height) && a.position.y + requireNotNull(a.height) > b.position.y)
        } }
    }
}
