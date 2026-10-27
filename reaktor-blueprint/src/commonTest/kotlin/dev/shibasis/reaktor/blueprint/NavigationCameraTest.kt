package dev.shibasis.reaktor.blueprint

import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationCameraTest {
    @Test fun selectingAfterRestoringAnOverviewSurvivesReentry() {
        val camera = GraphCanvasState()
        camera.resize(IntSize(1200, 800))
        camera.fitOnce("routes") { camera.fit(0.0, 0.0, 8000.0, 5000.0) }
        camera.restore(20.0, 30.0, .15)
        camera.centre(1800.0, 1200.0, 272.0, 560.0, readable = .75)
        val selected = camera.viewport()
        assertTrue(selected.zoom >= .75)
        camera.fitOnce("routes") { error("Reentry must keep the selected screen") }
        assertEquals(selected, camera.viewport())
    }
}
