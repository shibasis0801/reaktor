@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlinx.coroutines.Dispatchers
import java.lang.Runnable
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlueprintDenseOverviewTest {
    @Test fun denseOverviewPaintsEveryCardAndZoomRestoresReadableInteractiveDetail() {
        val cards = List(600) { index ->
            Card("source-$index", (index % 30) * 320.0, (index / 30) * 112.0, 272.0, 72.0, emptyList(), 0)
        }
        val layout = BlueprintLayout("dense-overview", cards.associateBy { it.id }, emptyList(), emptyList(), 9552.0, 2200.0)
        val canvas = GraphCanvasState(.001..4.0)
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun edt(block: () -> Unit) { SwingUtilities.invokeAndWait(Runnable(block)) }
        fun frames() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }; Thread.sleep(10) } }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun markers() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.filter {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("dense:") == true
        }
        edt {
            scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                BlueprintCanvas(layout, canvas, emptySet(), null,
                    cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, "Readable source ${it.id}", "Book",
                        tag = "dense:${it.id}", action = "Select ${it.id}") },
                    linkStyle = { Blueprint.link(Color.Blue, false, false) },
                    frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                    onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(), denseOverview = true)
            }
        }
        try {
            frames()
            edt {
                val reading = assertNotNull(canvas.reading())
                assertEquals(600, layout.cards.size)
                assertTrue(reading[BlueprintTier.Overview] > 550, "Overview should not measure hundreds of unreadable titles: $reading")
                assertTrue(markers().size < 30, "Overview must not compose hundreds of offscreen controls")
                val view = canvas.viewport()
                scene.render(++time * 16_666_667L).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    val background = pixels[0, 0]
                    for (card in cards) {
                        val x = (view.x + (card.x + card.width / 2) * view.zoom).roundToInt()
                        val y = (view.y + (card.y + card.height / 2) * view.zoom).roundToInt()
                        assertNotEquals(background, pixels[x, y], "Overview card ${card.id} must be painted")
                    }
                }
                canvas.restore(450.0 - cards.first().width / 2, 300.0 - cards.first().height / 2, 1.0)
            }
            frames()
            edt {
                val reading = assertNotNull(canvas.reading())
                assertTrue(reading[BlueprintTitle.Whole] > 0, "Zooming restores painted readable titles: $reading")
                assertTrue(markers().any { it.config.getOrNull(SemanticsProperties.TestTag) == "dense:source-0" })
                assertEquals(600, layout.cards.size)
                canvas.fit(0.0, 0.0, layout.width, layout.height)
            }
            frames()
            edt {
                val reading = assertNotNull(canvas.reading())
                assertEquals(600, reading[BlueprintTier.Overview], "Zooming out reports the actual mesh, despite retained detail caches: $reading")
                assertEquals(0, reading[BlueprintTier.Detail])
                assertTrue(markers().size < 30)
            }
        } finally { edt { scene.close() } }
    }
}

