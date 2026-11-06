package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphCanvasReadableFitTest {
    @Test fun readableSmallSceneFitsAllBoundsWhileLargeSceneFocusesAtMeasuredZoomAtBothDensities() {
        for (density in listOf(1.0, 2.0)) {
            val focus = Area(100.0, 50.0, 180.0, 80.0)
            fun state() = GraphCanvasState(.01..4.0).apply {
                density(density)
                flow.setCanvasSize(IntSize((1000 * density).toInt(), (600 * density).toInt()))
                readableZoom = { _, _, _, _ -> 1.2 }
            }
            val small = state()
            small.fitOnce("small") { small.fitReadable(listOf(Area(0.0, 0.0, 500.0, 200.0)), focus) }
            assertEquals(1.2, small.zoom, .00001)
            assertEquals(500.0, small.viewport().x + 250 * small.zoom, .00001)
            assertEquals(300.0, small.viewport().y + 100 * small.zoom, .00001)
            val large = state()
            large.fitOnce("large") { large.fitReadable(listOf(Area(0.0, 0.0, 10_000.0, 10_000.0)), focus) }
            assertEquals(1.2, large.zoom, .00001)
            assertEquals(500.0, large.viewport().x + (focus.x + focus.width / 2) * large.zoom, .00001)
            assertEquals(300.0, large.viewport().y + (focus.y + focus.height / 2) * large.zoom, .00001)
        }
    }

    @Test fun unknownReadabilityAndMissingFocusKeepOrdinaryFit() {
        val boxes = listOf(Area(600.0, 300.0, 6000.0, 4000.0))
        fun state() = GraphCanvasState(.01..4.0).apply { flow.setCanvasSize(IntSize(1000, 600)) }
        val expected = state()
        expected.fitOnce("plain") { expected.fit(boxes) }
        for (minimum in listOf<Double?>(null, Double.NaN, -1.0)) {
            val actual = state().apply { readableZoom = { _, _, _, _ -> minimum } }
            actual.fitOnce("readable") { actual.fitReadable(boxes, boxes.single()) }
            assertEquals(expected.viewport(), actual.viewport())
            assertTrue(actual.zoom < 1)
        }
        val noFocus = state().apply { readableZoom = { _, _, _, _ -> 1.2 } }
        noFocus.fitOnce("no-focus") { noFocus.fitReadable(boxes, null) }
        assertEquals(expected.viewport(), noFocus.viewport())
    }
}
