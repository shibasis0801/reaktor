package dev.shibasis.composeflow.compose.primitives

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathMeasure
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.model.XYPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.measureTime

class EdgeHitTest {
    @Test
    fun denseSelectionReportsDirectionalTimingWithoutADeviceSpecificBudget() {
        val nodes = buildMap {
            repeat(200) { index ->
                put("a$index", Node("a$index", XYPosition(0.0, index * 140.0), width = 100.0, height = 100.0,
                    handles = listOf(Handle("out", HandleType.Source, Position.Right))))
                put("b$index", Node("b$index", XYPosition(500.0, index * 140.0 + 400), width = 100.0, height = 100.0,
                    handles = listOf(Handle("in", HandleType.Target, Position.Left))))
            }
        }
        val edges = (0 until 200).map { index ->
            Edge("e$index", "a$index", "b$index", sourceHandle = "out", targetHandle = "in")
        }
        val start = anchorFor(nodes.getValue("a0"), "out", HandleType.Source, 100.0, 100.0)
        val end = anchorFor(nodes.getValue("b0"), "in", HandleType.Target, 100.0, 100.0)
        val measure = PathMeasure().apply { setPath(bezierEdgePath(start, end).path, false) }
        val point = measure.getPosition(measure.length * 0.2f)
        repeat(20) { findClosestEdge(point, edges, nodes, 100.0, 100.0) }
        val elapsed = measureTime {
            repeat(100) { assertEquals("e0", findClosestEdge(point, edges, nodes, 100.0, 100.0)?.id) }
        }
        println("edge-hit directional sample: edges=200 nodes=400 queries=100 elapsedMs=${elapsed.inWholeMilliseconds}")
    }

    @Test
    fun clickingRenderedCurveSelectsItsExactEdgeRatherThanTheStraightChord() {
        val source = Node("a", XYPosition(0.0, 0.0), width = 100.0, height = 100.0,
            handles = listOf(Handle("out", HandleType.Source, Position.Right)))
        val target = Node("b", XYPosition(500.0, 400.0), width = 100.0, height = 100.0,
            handles = listOf(Handle("in", HandleType.Target, Position.Left)))
        val edge = Edge("a-to-b", "a", "b", sourceHandle = "out", targetHandle = "in", interactionWidth = 12.0)
        val nodes = mapOf("a" to source, "b" to target)
        val start = anchorFor(source, "out", HandleType.Source, 100.0, 100.0)
        val end = anchorFor(target, "in", HandleType.Target, 100.0, 100.0)
        val measure = PathMeasure().apply { setPath(bezierEdgePath(start, end).path, false) }
        val point = measure.getPosition(measure.length * 0.2f)

        assertEquals(edge.id, findClosestEdge(point, listOf(edge), nodes, 100.0, 100.0, EdgePathStyle.Bezier)?.id)
        assertNull(findClosestEdge(point, listOf(edge), nodes, 100.0, 100.0, EdgePathStyle.Straight))
        assertNull(findClosestEdge(Offset(-500f, -500f), listOf(edge), nodes, 100.0, 100.0))
        assertNull(findClosestEdge(point, listOf(edge.copy(hidden = true)), nodes, 100.0, 100.0))
    }

    @Test
    fun orthogonalHitUsesItsRenderedElbowAndSkipsUnknownEndpoints() {
        val source = Node("a", XYPosition(0.0, 0.0), width = 100.0, height = 100.0,
            handles = listOf(Handle("out", HandleType.Source, Position.Right)))
        val target = Node("b", XYPosition(500.0, 400.0), width = 100.0, height = 100.0,
            handles = listOf(Handle("in", HandleType.Target, Position.Left)))
        val edge = Edge("orthogonal", "a", "b", sourceHandle = "out", targetHandle = "in")
        val nodes = mapOf("a" to source, "b" to target)
        val point = Offset(280f, 50f)
        assertEquals(edge, findClosestEdge(point, listOf(edge), nodes, 100.0, 100.0, EdgePathStyle.Orthogonal))
        assertNull(findClosestEdge(point, listOf(edge), emptyMap(), 100.0, 100.0, EdgePathStyle.Orthogonal))
    }
}
