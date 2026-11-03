package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlueprintZoomPolicyTest {
    @Test
    fun defaultAbsoluteAndRelativeZoomReachSixteenWithoutChangingTheLowerBound() {
        for (density in listOf(1.0, 2.0)) {
            val camera = prepared(density)
            assertEquals(0.05..16.0, camera.zoomRange)
            camera.restore(-120.25, 64.5, 1.0)
            camera.zoomTo(16.0)
            assertEquals(16.0, camera.viewport().zoom, 1e-10)
            camera.zoomTo(64.0)
            assertEquals(16.0, camera.viewport().zoom, 1e-10)
            camera.restore(-120.25, 64.5, 1.0)
            for (expected in listOf(2.0, 4.0, 8.0, 16.0)) {
                camera.zoomBy(2.0)
                assertEquals(expected, camera.viewport().zoom, 1e-10)
            }
            camera.zoomBy(2.0)
            assertEquals(16.0, camera.viewport().zoom, 1e-10)
            camera.zoomTo(0.0001)
            assertEquals(0.05, camera.viewport().zoom, 1e-10)
        }
    }

    @Test
    fun twentyAnchoredZoomStepsReachSixteenAtBothDensities() {
        for (density in listOf(1.0, 2.0)) {
            val camera = prepared(density)
            camera.restore(-120.25, 64.5, 1.0)
            val rootX = 73.25
            val rootY = 48.5
            val pointerX = rootX + 645.0 * density
            val pointerY = rootY + 311.0 * density
            val x = (pointerX - rootX) / density
            val y = (pointerY - rootY) / density
            val initial = camera.viewport()
            val worldX = (x - initial.x) / initial.zoom
            val worldY = (y - initial.y) / initial.zoom
            val factor = 16.0.pow(1.0 / 20.0)
            repeat(20) {
                val previous = camera.viewport()
                camera.zoomAt(factor, x, y)
                val observed = camera.viewport()
                assertEquals(min(previous.zoom * factor, 16.0), observed.zoom, 1e-10)
                val projectedX = rootX + (observed.x + worldX * observed.zoom) * density
                val projectedY = rootY + (observed.y + worldY * observed.zoom) * density
                assertTrue(hypot(projectedX - pointerX, projectedY - pointerY) <= 1.0)
            }
            assertEquals(16.0, camera.viewport().zoom, 1e-10)
            camera.zoomAt(100.0, x, y)
            val clamped = camera.viewport()
            assertEquals(16.0, clamped.zoom, 1e-10)
            assertTrue(hypot(
                rootX + (clamped.x + worldX * clamped.zoom) * density - pointerX,
                rootY + (clamped.y + worldY * clamped.zoom) * density - pointerY
            ) <= 1.0)
        }
    }

    @Test
    fun publicRestoreRetainsSixteenAndItsOriginalHistoryAndLowerFloor() {
        for (density in listOf(1.0, 2.0)) {
            val camera = prepared(density)
            camera.restore(-182.25, 73.5, 16.0)
            val first = camera.viewport()
            assertEquals(16.0, first.zoom, 1e-10)
            camera.restore(91.5, -46.25, 64.0)
            val second = camera.viewport()
            assertEquals(16.0, second.zoom, 1e-10)
            camera.back()
            assertEquals(first, camera.viewport())
            camera.forward()
            assertEquals(second, camera.viewport())
            camera.restore(0.0, 0.0, 0.0001)
            assertEquals(0.002, camera.viewport().zoom, 1e-10)
            assertEquals(0.05, camera.zoomRange.start, 1e-10)
        }
    }

    @Test
    fun explicitCustomRangesAndLevelsKeepTheirOwnLimits() {
        for (density in listOf(1.0, 2.0)) {
            for (range in listOf(0.08..1.75, 0.002..8.0)) {
                val levels = GraphLevels(0.25, 0.18, 0.6, 0.5)
                val camera = prepared(density, GraphCanvasState(zoomRange = range, levels = levels))
                assertEquals(range, camera.zoomRange)
                assertSame(levels, camera.levels)
                camera.zoomTo(16.0)
                assertEquals(range.endInclusive, camera.viewport().zoom, 1e-10)
                camera.restore(-20.5, 31.25, 1.0)
                camera.zoomBy(32.0)
                assertEquals(range.endInclusive, camera.viewport().zoom, 1e-10)
                camera.restore(-20.5, 31.25, 1.0)
                val before = camera.viewport()
                val x = 645.0
                val y = 311.0
                val worldX = (x - before.x) / before.zoom
                val worldY = (y - before.y) / before.zoom
                camera.zoomAt(32.0, x, y)
                val after = camera.viewport()
                assertEquals(range.endInclusive, after.zoom, 1e-10)
                assertTrue(hypot(
                    (after.x + worldX * after.zoom - x) * density,
                    (after.y + worldY * after.zoom - y) * density
                ) <= 1.0)
                camera.restore(9.0, -7.0, 32.0)
                assertEquals(range.endInclusive, camera.viewport().zoom, 1e-10)
                camera.zoomTo(0.0001)
                assertEquals(range.start, camera.viewport().zoom, 1e-10)
                camera.restore(0.0, 0.0, 0.0001)
                assertEquals(min(0.002, range.start), camera.viewport().zoom, 1e-10)
            }
        }
    }

    private fun prepared(density: Double, camera: GraphCanvasState = GraphCanvasState()): GraphCanvasState {
        camera.density(density)
        val size = IntSize((1100 * density).toInt(), (800 * density).toInt())
        camera.resize(size, size)
        camera.insetStart = 80.0
        camera.insetEnd = 240.0
        camera.insetTop = 32.0
        camera.insetBottom = 64.0
        return camera
    }
}
