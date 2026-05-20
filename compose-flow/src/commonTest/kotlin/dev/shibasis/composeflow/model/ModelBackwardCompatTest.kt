package dev.shibasis.composeflow.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelBackwardCompatTest {
    @Test
    fun nodeDefaultsPreserveBackwardCompatibility() {
        val node = Node(id = "n1", position = XYPosition(0.0, 0.0))
        assertTrue(node.draggable)
        assertTrue(node.selectable)
        assertTrue(node.connectable)
        assertTrue(node.deletable)
        assertNull(node.extent)
    }

    @Test
    fun edgeDefaultsPreserveBackwardCompatibility() {
        val edge = Edge(id = "e1", source = "a", target = "b")
        assertTrue(edge.selectable)
        assertTrue(edge.deletable)
        assertEquals(false, edge.reconnectable)
        assertEquals(20.0, edge.interactionWidth)
        assertNull(edge.markerStart)
    }

    @Test
    fun nodeCanOverrideFlags() {
        val node = Node(
            id = "n1",
            position = XYPosition(0.0, 0.0),
            draggable = false,
            selectable = false,
            connectable = false,
            deletable = false,
        )
        assertEquals(false, node.draggable)
        assertEquals(false, node.selectable)
        assertEquals(false, node.connectable)
        assertEquals(false, node.deletable)
    }

    @Test
    fun edgeCanOverrideFlags() {
        val edge = Edge(
            id = "e1",
            source = "a",
            target = "b",
            selectable = false,
            deletable = false,
            reconnectable = true,
            interactionWidth = 30.0,
        )
        assertEquals(false, edge.selectable)
        assertEquals(false, edge.deletable)
        assertTrue(edge.reconnectable)
        assertEquals(30.0, edge.interactionWidth)
    }
}
