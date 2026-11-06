@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import java.lang.Runnable
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlueprintAreaBannerTest {
    private val areas = listOf("Mathematics", "Physics", "Biology")
    private val tone = Color(0xFF4C7DD9)
    private fun left(area: Int) = 18.0 + area * 3400
    private val top = 40.0
    private val cards = areas.indices.flatMap { area ->
        List(200) { index -> Card("a$area-$index", left(area) + 40 + index % 10 * 320.0, top + 80 + index / 10 * 112.0, 272.0, 72.0, emptyList(), 0) }
    }

    private fun layout(named: Boolean) = BlueprintLayout("areas", cards.associateBy { it.id },
        listOf(Frame("atlas", "Atlas", 0.0, 0.0, 10068.0, 2378.0, emptySet(), emptyList(), false)) + areas.mapIndexed { area, name ->
            Frame("area-$area", if (named) name else "", left(area), top, 3232.0, 2320.0,
                cards.filter { it.id.startsWith("a$area-") }.mapTo(HashSet()) { it.id }, emptyList(), false, parent = "atlas")
        }, emptyList(), 10068.0, 2378.0)

    @Test fun zoomedOutAreasNameThemselvesAndEveryCardStandsOffItsArea() {
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val named = shot(layout(named = true), mode)
            val bare = shot(layout(named = false), mode)
            val view = named.view
            assertEquals(0, named.reading.level, "$mode: 600 cards at fit take the dense overview: ${named.reading}")
            for ((area, name) in areas.withIndex()) {
                val x = (view.x + left(area) * view.zoom).roundToInt()
                val y = (view.y + top * view.zoom).roundToInt()
                assertTrue(changed(named, bare, x + 6, y) > 94 * 36 / 4, "$mode: $name must carry its name over the zoomed-out map, not its wrapper")
            }
            for (card in cards.filter { it.y > 600 && it.id.substringAfter('-').toInt() % 7 == 0 }) {
                val y = (view.y + (card.y + card.height / 2) * view.zoom).roundToInt()
                val face = named.pixels[(view.x + (card.x + card.width / 2) * view.zoom).roundToInt(), y]
                val gap = named.pixels[(view.x + (card.x + card.width + 24) * view.zoom).roundToInt(), y]
                assertTrue(apart(face, gap) > .15f, "$mode: ${card.id} must stand off its area at fit; face $face, area $gap")
            }
        }
    }

    @Test fun anAreaWhoseTopScrolledAwayKeepsItsNamePinnedInView() {
        val zoom = .14
        val at = Viewport(450 - (left(1) + 600) * zoom, 300 - 2240 * zoom, zoom)
        val named = shot(layout(named = true), MachineSignalMode.Light, at)
        val bare = shot(layout(named = false), MachineSignalMode.Light, at)
        assertEquals(0, named.reading.level, "${named.reading}")
        assertTrue(named.view.y + top * zoom < 0, "Physics starts above the view: ${named.view}")
        val x = (named.view.x + left(1) * zoom).roundToInt()
        assertTrue(changed(named, bare, x + 6, 48 + 12) > 94 * 36 / 4, "Physics keeps its name just below the map's top inset")
    }

    private fun changed(named: Shot, bare: Shot, x: Int, y: Int): Int {
        var changed = 0
        for (px in x until x + 94) for (py in y until y + 36) if (apart(named.pixels[px, py], bare.pixels[px, py]) > .2f) changed++
        return changed
    }

    private fun apart(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private class Shot(val pixels: PixelMap, val view: Viewport, val reading: BlueprintReading)

    private fun shot(layout: BlueprintLayout<String>, mode: MachineSignalMode, at: Viewport? = null): Shot {
        val canvas = GraphCanvasState(.001..4.0)
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun edt(block: () -> Unit) { SwingUtilities.invokeAndWait(Runnable(block)) }
        fun frames() = repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }; Thread.sleep(10) }
        edt {
            scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        cardLook = { BlueprintCardLook(tone, BlueprintBench.icon, "Readable ${it.id}", "Book") },
                        linkStyle = { Blueprint.link(tone, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "200 sources") },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(), denseOverview = true)
                }
            }
        }
        try {
            frames()
            if (at != null) { edt { canvas.restore(at.x, at.y, at.zoom) }; frames() }
            lateinit var shot: Shot
            edt {
                scene.render(++time * 16_666_667L).use { image ->
                    shot = Shot(image.toComposeImageBitmap().toPixelMap(), canvas.viewport(), assertNotNull(canvas.reading()))
                }
            }
            return shot
        } finally { edt { scene.close() } }
    }
}
