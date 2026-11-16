package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphCanvasDensityTest {
    @Test
    fun readingZoomDuringADensityWriteCannotKeepTheIntermediateViewport() {
        val camera = GraphCanvasState()
        assertEquals(1.0, camera.zoom)
        val observed = ArrayList<Double>()
        val observer = Snapshot.registerGlobalWriteObserver { observed += camera.zoom }
        try {
            camera.density(2.0)
            assertEquals(1.0, camera.viewport().zoom)
            assertEquals(1.0, camera.zoom)
            assertTrue(observed.all { it == 1.0 }, "A reader cannot observe an intermediate density scale")
            camera.density(1.0)
            assertEquals(1.0, camera.viewport().zoom)
            assertEquals(1.0, camera.zoom)
        } finally {
            observer.dispose()
        }
    }
}
