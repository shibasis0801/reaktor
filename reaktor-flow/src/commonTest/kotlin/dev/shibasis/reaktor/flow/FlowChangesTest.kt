package dev.shibasis.reaktor.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowChangesTest {
    @Test
    fun addEdgeDeduplicatesConnections() {
        val connection = Connection(source = "a", target = "b", sourceHandle = "out", targetHandle = "in")
        val first = addEdge(connection, emptyList())
        val second = addEdge(connection, first)

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals("a", first.first().source)
        assertEquals("b", first.first().target)
    }

    @Test
    fun applyNodeChangesUpdatesSelectionAndPosition() {
        val node = Node(id = "n1", position = XYPosition(10.0, 20.0), selected = false)
        val updated = applyNodeChanges(
            changes = listOf(
                NodeSelectionChange(id = "n1", selected = true),
                NodePositionChange(id = "n1", position = XYPosition(30.0, 40.0), dragging = true),
            ),
            nodes = listOf(node),
        )

        assertTrue(updated.first().selected)
        assertEquals(30.0, updated.first().position.x)
        assertEquals(40.0, updated.first().position.y)
        assertTrue(updated.first().dragging)
    }

    @Test
    fun applyEdgeChangesRemovesAndSelects() {
        val keep = Edge(id = "keep", source = "a", target = "b")
        val remove = Edge(id = "remove", source = "b", target = "c")

        val updated = applyEdgeChanges(
            changes = listOf(
                EdgeSelectionChange(id = "keep", selected = true),
                EdgeRemoveChange(id = "remove"),
            ),
            edges = listOf(keep, remove),
        )

        assertEquals(1, updated.size)
        assertEquals("keep", updated.first().id)
        assertTrue(updated.first().selected)
        assertFalse(updated.any { it.id == "remove" })
    }
}
