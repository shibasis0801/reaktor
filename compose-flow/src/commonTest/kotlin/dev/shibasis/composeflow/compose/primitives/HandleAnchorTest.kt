package dev.shibasis.composeflow.compose.primitives

import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.model.XYPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandleAnchorTest {
    private val defaultWidth = 200.0
    private val defaultHeight = 100.0

    private fun node(
        x: Double = 0.0,
        y: Double = 0.0,
        w: Double? = null,
        h: Double? = null,
        handles: List<Handle> = emptyList(),
    ) = Node(
        id = "test",
        position = XYPosition(x, y),
        width = w,
        height = h,
        handles = handles,
    )

    @Test
    fun bottomHandleAnchorsAtBottomCenter() {
        val n = node(x = 10.0, y = 20.0, w = 200.0, h = 100.0)
        val anchor = anchorFor(n, null, HandleType.Source, defaultWidth, defaultHeight)
        assertEquals(Position.Bottom, anchor.position)
        assertEquals(110f, anchor.point.x, 1f)
        assertEquals(120f, anchor.point.y, 1f)
    }

    @Test
    fun topHandleAnchorsAtTopCenter() {
        val n = node(x = 10.0, y = 20.0, w = 200.0, h = 100.0)
        val anchor = anchorFor(n, null, HandleType.Target, defaultWidth, defaultHeight)
        assertEquals(Position.Top, anchor.position)
        assertEquals(110f, anchor.point.x, 1f)
        assertEquals(20f, anchor.point.y, 1f)
    }

    @Test
    fun leftHandleAnchorsAtLeftCenter() {
        val handle = Handle(id = "left", type = HandleType.Target, position = Position.Left)
        val n = node(x = 0.0, y = 0.0, w = 200.0, h = 100.0, handles = listOf(handle))
        val anchor = anchorFor(n, "left", HandleType.Target, defaultWidth, defaultHeight)
        assertEquals(Position.Left, anchor.position)
        assertEquals(0f, anchor.point.x, 1f)
        assertEquals(50f, anchor.point.y, 1f)
    }

    @Test
    fun rightHandleAnchorsAtRightCenter() {
        val handle = Handle(id = "right", type = HandleType.Source, position = Position.Right)
        val n = node(x = 0.0, y = 0.0, w = 200.0, h = 100.0, handles = listOf(handle))
        val anchor = anchorFor(n, "right", HandleType.Source, defaultWidth, defaultHeight)
        assertEquals(Position.Right, anchor.position)
        assertEquals(200f, anchor.point.x, 1f)
        assertEquals(50f, anchor.point.y, 1f)
    }

    @Test
    fun handleWithCustomOffsetPositionsCorrectly() {
        val handle = Handle(id = "h", type = HandleType.Source, position = Position.Bottom, offset = 0.25)
        val n = node(x = 0.0, y = 0.0, w = 200.0, h = 100.0, handles = listOf(handle))
        val anchor = anchorFor(n, "h", HandleType.Source, defaultWidth, defaultHeight)
        assertEquals(50f, anchor.point.x, 1f)
        assertEquals(100f, anchor.point.y, 1f)
    }

    @Test
    fun handleWithInsetMovesAwayFromEdge() {
        val handle = Handle(id = "h", type = HandleType.Target, position = Position.Top, inset = 10.0)
        val n = node(x = 0.0, y = 0.0, w = 200.0, h = 100.0, handles = listOf(handle))
        val anchor = anchorFor(n, "h", HandleType.Target, defaultWidth, defaultHeight)
        assertEquals(10f, anchor.point.y, 1f)
    }

    @Test
    fun defaultHandleFallsBackWhenNoExplicitHandle() {
        val n = node(x = 50.0, y = 50.0, w = 100.0, h = 80.0)
        val sourceAnchor = anchorFor(n, "nonexistent", HandleType.Source, defaultWidth, defaultHeight)
        assertEquals(Position.Bottom, sourceAnchor.position)
        assertTrue(sourceAnchor.point.y >= 120f, "source should be near bottom")
    }

    @Test
    fun resolvedHandlesGeneratesDefaultsWhenEmpty() {
        val n = node()
        val handles = resolvedHandles(n)
        assertEquals(2, handles.size)
        assertTrue(handles.any { it.type == HandleType.Source })
        assertTrue(handles.any { it.type == HandleType.Target })
    }

    @Test
    fun resolvedHandlesReturnsEmptyWhenDefaultsDisabled() {
        val n = node().copy(showDefaultHandles = false)
        val handles = resolvedHandles(n)
        assertTrue(handles.isEmpty())
    }

    @Test
    fun resolvedHandlesReturnsExplicitHandlesWhenProvided() {
        val explicit = listOf(
            Handle(id = "a", type = HandleType.Source, position = Position.Right),
            Handle(id = "b", type = HandleType.Target, position = Position.Left),
        )
        val n = node(handles = explicit)
        val handles = resolvedHandles(n)
        assertEquals(2, handles.size)
        assertEquals("a", handles[0].id)
        assertEquals("b", handles[1].id)
    }
}
