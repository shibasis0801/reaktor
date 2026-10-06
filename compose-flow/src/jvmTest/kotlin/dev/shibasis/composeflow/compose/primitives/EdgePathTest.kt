package dev.shibasis.composeflow.compose.primitives

import androidx.compose.ui.geometry.Offset
import dev.shibasis.composeflow.model.Position
import kotlin.test.Test
import kotlin.test.assertTrue

class EdgePathTest {
    private fun anchor(x: Float, y: Float, position: Position) =
        FlowAnchor(Offset(x, y), position)

    @Test
    fun bezierPathConnectsStartToEnd() {
        val start = anchor(0f, 100f, Position.Bottom)
        val end = anchor(200f, 0f, Position.Top)
        val path = bezierEdgePath(start, end)
        assertTrue(path.path.getBounds().left <= 0f, "path should include start x")
        assertTrue(path.path.getBounds().right >= 200f, "path should include end x")
    }

    @Test
    fun orthogonalPathConnectsStartToEnd() {
        val start = anchor(0f, 50f, Position.Right)
        val end = anchor(200f, 150f, Position.Left)
        val path = orthogonalEdgePath(start, end)
        val bounds = path.path.getBounds()
        assertTrue(bounds.left <= 0f)
        assertTrue(bounds.right >= 200f)
    }

    @Test
    fun straightPathIsDirectLine() {
        val start = anchor(10f, 20f, Position.Bottom)
        val end = anchor(100f, 200f, Position.Top)
        val path = straightEdgePath(start, end)
        val bounds = path.path.getBounds()
        assertTrue(bounds.left >= 10f - 1f)
        assertTrue(bounds.right <= 100f + 1f)
        assertTrue(bounds.top >= 20f - 1f)
        assertTrue(bounds.bottom <= 200f + 1f)
    }

    @Test
    fun smoothStepPathConnectsStartToEnd() {
        val start = anchor(0f, 50f, Position.Right)
        val end = anchor(300f, 200f, Position.Left)
        val path = smoothStepEdgePath(start, end)
        val bounds = path.path.getBounds()
        assertTrue(bounds.left <= 0f + 1f)
        assertTrue(bounds.right >= 300f - 1f)
    }

    @Test
    fun simpleBezierPathConnectsStartToEnd() {
        val start = anchor(0f, 0f, Position.Bottom)
        val end = anchor(200f, 200f, Position.Top)
        val path = simpleBezierEdgePath(start, end)
        val bounds = path.path.getBounds()
        assertTrue(bounds.left <= 0f + 1f)
        assertTrue(bounds.right >= 200f - 1f)
    }

    @Test
    fun bezierHandlesVerticalCollinearCase() {
        val start = anchor(100f, 0f, Position.Bottom)
        val end = anchor(102f, 300f, Position.Top)
        val path = bezierEdgePath(start, end)
        val bounds = path.path.getBounds()
        assertTrue(bounds.height >= 290f, "path should span most of the vertical distance")
    }

    @Test
    fun allPathTypesProduceNonEmptyPaths() {
        val start = anchor(50f, 50f, Position.Bottom)
        val end = anchor(250f, 250f, Position.Top)
        val types = listOf(
            bezierEdgePath(start, end),
            orthogonalEdgePath(start, end),
            straightEdgePath(start, end),
            smoothStepEdgePath(start, end),
            simpleBezierEdgePath(start, end),
        )
        types.forEach { pathData ->
            assertTrue(!pathData.path.isEmpty, "path should not be empty")
        }
    }
}
