package dev.shibasis.reaktor.blueprint

import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationCameraTest {
    @Test fun pendingRestorePreservesControllerGesturesAcrossProjectedLayoutChangesAtBothDensities() {
        for (density in listOf(1.0, 2.0)) for (mounted in listOf(false, true)) for (motion in listOf("pan", "zoom")) {
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            camera.resize(IntSize((1100 * density).toInt(), (800 * density).toInt()))
            if (mounted) camera.fitOnce("opening", "scene") { camera.fit(0.0, 0.0, 8000.0, 5000.0) }
            camera.restore(-170.0, 230.0, .6)
            val restored = camera.viewport()
            if (motion == "pan") camera.flow.panBy(6.0 * density, 3.0 * density)
            else camera.flow.zoomBy(1.025, 693.0 * density, 410.0 * density, .002 * density, 16.0 * density)
            val requested = camera.viewport()
            assertTrue(requested != restored)
            if (motion == "pan") {
                assertEquals(restored.x + 6.0, requested.x, .000001)
                assertEquals(restored.y + 3.0, requested.y, .000001)
                assertEquals(restored.zoom, requested.zoom, .000001)
            } else {
                assertEquals(.615, requested.zoom, .000001)
                val worldX = (693.0 - restored.x) / restored.zoom
                val worldY = (410.0 - restored.y) / restored.zoom
                assertEquals(693.0, requested.x + worldX * requested.zoom, .000001)
                assertEquals(410.0, requested.y + worldY * requested.zoom, .000001)
            }
            camera.fitOnce("projected-detail", "scene") { error("A pending restore cannot replace a controller gesture with Fit") }
            assertEquals(requested, camera.viewport(), "Projected detail must retain the latest controller viewport")
            camera.fitOnce("projected-detail-again", "scene") { error("Consuming a stale restore cannot cause a later Fit") }
            assertEquals(requested, camera.viewport())
        }
    }

    @Test fun firstMeasuredFitRetainsExplicitCameraAuthorityAndLaterSceneFitAtBothDensities() {
        for (density in listOf(1.0, 2.0)) for (opening in listOf("fresh", "pan", "focus", "restored")) {
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            camera.insetStart = 80.0; camera.insetEnd = 240.0
            camera.insetTop = 32.0; camera.insetBottom = 64.0
            camera.resize(IntSize((1100 * density).toInt(), (800 * density).toInt()))
            when (opening) {
                "pan" -> { camera.pan(37.0, -19.0); camera.zoomAt(1.25, 217.0, 193.0) }
                "focus" -> camera.centre(3900.0, 2600.0, 304.0, 180.0, readable = 1.2)
                "restored" -> camera.restore(-170.0, 230.0, 1.17)
            }
            val requested = camera.viewport()
            var fits = 0
            camera.fitOnce("opening", "scene") { fits++; camera.fit(0.0, 0.0, 10000.0, 6000.0) }
            if (opening == "fresh") {
                assertEquals(1, fits)
                assertTrue(camera.untouched)
                assertTrue(camera.zoom < requested.zoom)
            } else {
                assertEquals(0, fits)
                assertEquals(requested, camera.viewport())
            }
            val shown = camera.viewport()
            camera.fitOnce("facts-refreshed", "scene") { fits++; camera.fit(0.0, 0.0, 10000.0, 6000.0) }
            assertEquals(if (opening == "fresh") 2 else 0, fits)
            assertEquals(shown, camera.viewport())
            camera.fitOnce("next", "next-scene") { fits++; camera.fit(20000.0, 18000.0, 4000.0, 3000.0) }
            assertEquals(if (opening == "fresh") 3 else 1, fits)
            assertTrue(camera.viewport() != shown)
            assertTrue(camera.untouched)
        }
    }

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

    @Test fun resizingPreservesTheWorldCentreAtBothDensitiesWithInsets() {
        for (density in listOf(1.0, 2.0)) {
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            camera.insetStart = 80.0; camera.insetEnd = 240.0
            camera.insetTop = 32.0; camera.insetBottom = 64.0
            camera.resize(IntSize((1100 * density).toInt(), (800 * density).toInt()), window = IntSize((1100 * density).toInt(), (800 * density).toInt()))
            camera.restore(-170.0, 230.0, .75)
            val before = camera.seen()!!
            val zoom = camera.zoom
            val untouched = camera.untouched
            for ((width, height) in listOf(1512 to 900, 900 to 700, 1100 to 800)) {
                camera.resize(IntSize((width * density).toInt(), (height * density).toInt()), window = IntSize((width * density).toInt(), (height * density).toInt()))
                val after = camera.seen()!!
                assertEquals(before.x + before.width / 2, after.x + after.width / 2, 1e-8)
                assertEquals(before.y + before.height / 2, after.y + after.height / 2, 1e-8)
                assertEquals(zoom, camera.zoom)
                assertEquals(untouched, camera.untouched)
            }
        }
    }

    @Test fun resizingRetainsTheFitCohortWithoutAddingAnotherFit() {
        val camera = GraphCanvasState(.002..16.0)
        camera.resize(IntSize(1100, 800), window = IntSize(1100, 800))
        var fits = 0
        camera.fitOnce("host", "version") { fits++; camera.fit(0.0, 0.0, 8000.0, 5000.0) }
        assertTrue(camera.untouched)
        camera.resize(IntSize(1512, 900), window = IntSize(1512, 900))
        assertTrue(camera.untouched)
        val resized = camera.viewport()
        camera.fitOnce("host", "version") { fits++; camera.fit(0.0, 0.0, 8000.0, 5000.0) }
        assertEquals(1, fits)
        assertEquals(resized, camera.viewport())
    }

    @Test fun initialSameAndUnavailableSizesDoNotTranslateTheCamera() {
        val camera = GraphCanvasState()
        camera.restore(20.0, 30.0, .75)
        val before = camera.viewport()
        camera.resize(IntSize(1100, 800))
        assertEquals(before, camera.viewport())
        camera.resize(IntSize(1100, 800))
        assertEquals(before, camera.viewport())
        camera.resize(IntSize.Zero)
        assertEquals(before, camera.viewport())
    }

    @Test fun fullLeafRevealMeetsPreparedAncestorEntryAndRepeatedRevealKeepsItsCamera() {
        for (density in listOf(1.0, 2.0)) for (initial in listOf(.120042896, 2.0)) {
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            camera.insetStart = 80.0; camera.insetEnd = 240.0
            camera.insetTop = 32.0; camera.insetBottom = 64.0
            camera.resize(IntSize((1100 * density).toInt(), (800 * density).toInt()))
            camera.restore(20.0, 30.0, initial)
            val ancestorEntry = listOf(.2625641, .1898808, .4292216, .896).max()
            camera.centre(1800.0, 1200.0, 272.0, 144.0, readable = ancestorEntry, fallback = 1.0)
            val revealed = camera.viewport()
            assertTrue(revealed.zoom >= ancestorEntry)
            if (initial >= ancestorEntry) assertEquals(initial, revealed.zoom)
            val seen = camera.seen()!!
            assertEquals(1800.0 + 272.0 / 2, seen.x + seen.width / 2, 1e-8)
            assertEquals(1200.0 + 144.0 / 2, seen.y + seen.height / 2, 1e-8)
            camera.centre(1800.0, 1200.0, 272.0, 144.0, readable = ancestorEntry, fallback = 1.0)
            assertEquals(revealed, camera.viewport())
        }
    }

    @Test fun repeatedFitWithStablePaintedBoundsIgnoresThePreviousCamera() {
        for (density in listOf(1.0, 2.0)) {
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            camera.insetStart = 80.0; camera.insetEnd = 240.0
            camera.insetTop = 32.0; camera.insetBottom = 64.0
            camera.resize(IntSize((1100 * density).toInt(), (800 * density).toInt()))
            camera.paintedBounds = { box, zoom ->
                listOf(Area(box.x - 80.0 / zoom, box.y - 28.0 / zoom,
                    box.width + 200.0 / zoom, box.height + 56.0 / zoom))
            }
            camera.fit(0.0, 0.0, 54000.0, 57000.0, minZoom = 0.0)
            val fit = camera.viewport()
            for (zoom in listOf(.1, 4.0, 16.0)) {
                camera.restore(20.0, 30.0, zoom)
                camera.fit(0.0, 0.0, 54000.0, 57000.0, minZoom = 0.0)
                assertEquals(fit, camera.viewport())
            }
        }
    }
    @Test fun resizingAfterADensityRebaseDoesNotTranslateTheLogicalCameraTwice() {
        val camera = GraphCanvasState(.002..16.0)
        camera.insetStart = 80.0; camera.insetEnd = 240.0
        camera.insetTop = 32.0; camera.insetBottom = 64.0
        camera.resize(IntSize(1100, 800), window = IntSize(1100, 800))
        camera.restore(200.0, 170.0, .8)
        val centre = camera.seen()!!
        val before = camera.viewport()
        for (density in listOf(2.0, 1.0)) {
            camera.density(density)
            camera.resize(IntSize((1100 * density).toInt(), (800 * density).toInt()), window = IntSize((1100 * density).toInt(), (800 * density).toInt()))
            val after = camera.seen()!!
            assertEquals(centre.x + centre.width / 2, after.x + after.width / 2, 1e-8)
            assertEquals(centre.y + centre.height / 2, after.y + after.height / 2, 1e-8)
            assertEquals(before, camera.viewport())
        }
    }

    @Test fun unavailableRootBreaksResizeAuthorityUntilTheNextValidPair() {
        for (density in listOf(1.0, 2.0)) for (unavailable in listOf<IntSize?>(null, IntSize.Zero, IntSize(-1, 800))) {
            fun size(width: Int, height: Int) = IntSize((width * density).toInt(), (height * density).toInt())
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            camera.resize(size(1100, 800), window = size(1100, 800))
            camera.restore(20.0, 30.0, .75)
            val before = camera.viewport()
            camera.resize(size(1300, 840), window = unavailable)
            assertEquals(before, camera.viewport())
            camera.resize(size(1412, 880), window = size(1412, 880))
            assertEquals(before, camera.viewport())
            camera.resize(size(1512, 920), window = size(1512, 920))
            assertEquals(before.copy(x = before.x + 50.0, y = before.y + 20.0), camera.viewport())
        }
    }

    @Test fun sameRootPanelResizePreservesBothDirectionsOfCameraHistory() {
        for (density in listOf(1.0, 2.0)) {
            fun size(width: Int, height: Int) = IntSize((width * density).toInt(), (height * density).toInt())
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            val root = size(1512, 982)
            camera.resize(root, window = root)
            camera.restore(20.0, 30.0, .75)
            val first = camera.viewport()
            camera.pan(80.0, -40.0)
            val second = camera.viewport()
            camera.back()
            assertEquals(first, camera.viewport())
            camera.resize(size(1192, 982), window = root)
            assertEquals(first, camera.viewport())
            camera.forward()
            assertEquals(second, camera.viewport())
            camera.resize(root, window = root)
            assertEquals(second, camera.viewport())
            camera.back()
            assertEquals(first, camera.viewport())
        }
    }

    @Test fun actualRootResizeRebasesEveryCameraHistoryEntryWithInsets() {
        for (density in listOf(1.0, 2.0)) {
            fun size(width: Int, height: Int) = IntSize((width * density).toInt(), (height * density).toInt())
            val camera = GraphCanvasState(.002..16.0)
            camera.density(density)
            camera.insetStart = 80.0; camera.insetEnd = 240.0
            camera.insetTop = 32.0; camera.insetBottom = 64.0
            camera.resize(size(1100, 800), window = size(1100, 800))
            camera.restore(20.0, 30.0, .75)
            val first = camera.viewport()
            camera.pan(80.0, -40.0)
            val second = camera.viewport()
            camera.pan(40.0, 60.0)
            val third = camera.viewport()
            camera.back()
            val centre = camera.seen()!!
            camera.resize(size(1512, 900), window = size(1512, 900))
            val resized = camera.seen()!!
            assertEquals(centre.x + centre.width / 2, resized.x + resized.width / 2, 1e-8)
            assertEquals(centre.y + centre.height / 2, resized.y + resized.height / 2, 1e-8)
            assertEquals(second.copy(x = second.x + 206.0, y = second.y + 50.0), camera.viewport())
            camera.forward()
            assertEquals(third.copy(x = third.x + 206.0, y = third.y + 50.0), camera.viewport())
            camera.back()
            assertEquals(second.copy(x = second.x + 206.0, y = second.y + 50.0), camera.viewport())
            camera.back()
            assertEquals(first.copy(x = first.x + 206.0, y = first.y + 50.0), camera.viewport())
            camera.forward()
            assertEquals(second.copy(x = second.x + 206.0, y = second.y + 50.0), camera.viewport())
        }
    }

    @Test fun resizedRestoreWinsFirstFitAndSurvivesCoherentDensityRebasing() {
        val camera = GraphCanvasState(.002..16.0)
        camera.resize(IntSize(1100, 800), window = IntSize(1100, 800))
        camera.restore(20.0, 30.0, .75)
        val before = camera.viewport()
        camera.resize(IntSize(1512, 900), window = IntSize(1512, 900))
        val expected = before.copy(x = before.x + 206.0, y = before.y + 50.0)
        assertEquals(expected, camera.viewport())
        camera.fitOnce("restored", "same-full-layout") { error("A saved camera must win first Fit") }
        assertEquals(expected, camera.viewport())
        for (density in listOf(2.0, 1.0)) {
            camera.density(density)
            camera.resize(IntSize((1512 * density).toInt(), (900 * density).toInt()), window = IntSize((1512 * density).toInt(), (900 * density).toInt()))
            assertEquals(expected, camera.viewport())
            camera.fitOnce("restored", "same-full-layout") { error("Density alone must not Fit") }
            assertEquals(expected, camera.viewport())
        }
    }


    @Test fun absoluteAndFallbackZoomKeepTheInsetDefinedWorldAnchor() {
        for (density in listOf(1.0, 2.0)) for (width in listOf(900, 1100, 1512, 1920)) {
            for (insets in listOf(listOf(0.0, 0.0, 0.0, 0.0), listOf(80.0, 240.0, 32.0, 64.0))) {
                val camera = GraphCanvasState(.002..16.0)
                camera.density(density)
                camera.insetStart = insets[0]; camera.insetEnd = insets[1]
                camera.insetTop = insets[2]; camera.insetBottom = insets[3]
                camera.resize(IntSize((width * density).toInt(), (800 * density).toInt()))
                camera.restore(-170.0, 230.0, .75)
                val anchorX = insets[0] + (width - insets[0] - insets[1]) / 2
                val anchorY = insets[2] + (800 - insets[2] - insets[3]) / 2
                val before = camera.viewport()
                val worldX = (anchorX - before.x) / before.zoom
                val worldY = (anchorY - before.y) / before.zoom
                fun unchangedAnchor() {
                    val view = camera.viewport()
                    assertTrue(kotlin.math.hypot((worldX * view.zoom + view.x - anchorX) * density,
                        (worldY * view.zoom + view.y - anchorY) * density) <= 1.0)
                }
                for (zoom in listOf(.25, .5, 1.0, 2.0)) {
                    camera.zoomTo(zoom)
                    assertEquals(zoom, camera.zoom, 1e-8)
                    unchangedAnchor()
                }
                camera.zoomBy(.8)
                assertEquals(1.6, camera.zoom, 1e-8)
                unchangedAnchor()
                val at = camera.viewport()
                val pointerX = width * .63
                val pointerY = 410.0
                val pointerWorldX = (pointerX - at.x) / at.zoom
                val pointerWorldY = (pointerY - at.y) / at.zoom
                camera.zoomAt(1.25, pointerX, pointerY)
                val after = camera.viewport()
                assertEquals(2.0, after.zoom, 1e-8)
                assertTrue(kotlin.math.hypot((pointerWorldX * after.zoom + after.x - pointerX) * density,
                    (pointerWorldY * after.zoom + after.y - pointerY) * density) <= 1.0)
            }
        }
    }

}
