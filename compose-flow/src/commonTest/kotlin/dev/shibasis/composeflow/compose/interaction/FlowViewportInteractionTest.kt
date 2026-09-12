package dev.shibasis.composeflow.compose.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.runtime.ReactFlowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowViewportInteractionTest {
    @Test
    fun nativeAnchorUsesDisplayDensityAndRejectsClippedCanvasAndChrome() {
        val interaction = FlowViewportInteractionState()
        for (density in listOf(1f, 2f)) {
            interaction.updateCanvasGeometry(Offset(100f, 60f) * density,
                Rect(120f * density, 80f * density, 400f * density, 300f * density), density)
            assertEquals(Offset(80f, 60f) * density, interaction.canvasPositionFromWindow(Offset(180f, 120f)))
            assertNull(interaction.canvasPositionFromWindow(Offset(110f, 100f)), "Clipped part of canvas")
            assertNull(interaction.canvasPositionFromWindow(Offset(180f, 50f)), "Toolbar")
            assertNull(interaction.canvasPositionFromWindow(Offset(401f, 120f)), "Inspector")
        }
    }

    @Test
    fun zoomAfterFitIsContinuousAndKeepsThePointUnderThePointer() {
        val state = ReactFlowState(Viewport(30.0, -90.0, 0.01))
        val anchor = Offset(400f, 230f)
        val before = state.screenToFlowPosition(anchor.x.toDouble(), anchor.y.toDouble())
        state.zoomBy(1.1, anchor.x.toDouble(), anchor.y.toDouble(), minZoom = 0.1)
        assertEquals(0.011, state.viewport.zoom, 1e-12)
        val after = state.screenToFlowPosition(anchor.x.toDouble(), anchor.y.toDouble())
        assertEquals(before.x, after.x, 1e-7)
        assertEquals(before.y, after.y, 1e-7)
        val camera = state.viewport
        state.zoomBy(0.9, anchor.x.toDouble(), anchor.y.toDouble(), minZoom = 0.1)
        assertEquals(camera, state.viewport, "Zoom-out below the floor must not jump IN to that floor")
        state.zoomBy(1_000.0, anchor.x.toDouble(), anchor.y.toDouble(), maxZoom = 2.0)
        assertEquals(2.0, state.viewport.zoom)
    }
}
