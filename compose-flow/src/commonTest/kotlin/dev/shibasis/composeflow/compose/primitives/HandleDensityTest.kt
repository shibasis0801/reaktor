package dev.shibasis.composeflow.compose.primitives

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.model.XYPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class HandleDensityTest {
    @Test
    fun hitTargetCentersMeetActualEdgeAnchorsAcrossAllSidesAndDisplayDensities() {
        val style = HandleRenderStyle(size = 8.dp)
        for (scale in listOf(1f, 1.5f, 2f)) {
            val density = Density(scale)
            for (position in Position.entries) {
                val handle = Handle("port", HandleType.Source, position, offset = 0.37, inset = 14.0 * scale)
                val node = Node("node", XYPosition(100.0 * scale, 30.0 * scale),
                    width = 260.0 * scale, height = 142.0 * scale, handles = listOf(handle))
                val anchor = anchorFor(node, handle.id, handle.type, 0.0, 0.0)
                val hitTarget = handleTopLeft(handle, 260.dp, 142.dp, style, density)
                val centerX = node.position.x + with(density) { (hitTarget.x + style.size / 2).toPx() }
                val centerY = node.position.y + with(density) { (hitTarget.y + style.size / 2).toPx() }
                assertEquals(anchor.point.x.toDouble(), centerX, 0.0001, "$position at density $scale")
                assertEquals(anchor.point.y.toDouble(), centerY, 0.0001, "$position at density $scale")
            }
        }
    }

    @Test
    fun unspecifiedDensityRetainsExistingOneToOnePlacement() {
        val handle = Handle("input", HandleType.Target, Position.Left, offset = 0.5, inset = 14.0)
        val style = HandleRenderStyle(size = 8.dp)
        assertEquals(handleTopLeft(handle, 260.dp, 142.dp, style, Density(1f)),
            handleTopLeft(handle, 260.dp, 142.dp, style))
        assertEquals(10.dp, handleTopLeft(handle, 260.dp, 142.dp, style).x)
    }
}
