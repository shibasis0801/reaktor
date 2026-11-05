@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.colors
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlueprintCardActionPaintTest {
    private val actions = listOf("open", "inspect").map { BlueprintCardAction(it, it, SurfaceGraphSymbol) {} }

    @Test fun actionsStayExposedAtEveryZoomWithoutMovingCards() {
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) for (face in listOf(BlueprintFace.Code, BlueprintFace.Prose)) {
            val card = Card("source", 0.0, 0.0, 420.0, 220.0, emptyList(), 0)
            val layout = BlueprintLayout("actions", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
            val canvas = GraphCanvasState(.01..4.0)
            val look = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Source sheet and linked units", "", face = face, actions = actions)
            lateinit var scene: ImageComposeScene
            var time = 0L
            fun frames() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }; Thread.sleep(10) } }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            edt {
                scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        BlueprintCanvas(layout, canvas, emptySet(), null, cardLook = { look.copy(tone = MachineSignal.colors.controlAccent) },
                            linkStyle = { Blueprint.link(Color.Blue, false, false) }, frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                frames()
                for (zoom in listOf(.04, .55, 1.6)) {
                    edt { canvas.restore(40.0, 70.0, zoom) }; frames()
                    edt {
                        val reading = assertNotNull(canvas.reading())
                        assertEquals(1, reading[BlueprintTier.Detail], "$mode $face $zoom: $reading")
                        assertEquals(1, reading[BlueprintTitle.Whole])
                        val hits = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.filter {
                            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("blueprint-card-action:") == true
                        }
                        assertEquals(actions.size, hits.size, "$mode $face $zoom")
                        val ordered = hits.sortedBy { it.boundsInRoot.left }
                        assertTrue(ordered.first().boundsInRoot.right <= ordered.last().boundsInRoot.left)
                        assertTrue(ordered.first().boundsInRoot.left >= 40)
                        assertTrue(ordered.last().boundsInRoot.right <= 40 + card.width * zoom)
                        assertEquals(card, layout.cards.getValue(card.id))
                        val folder = File("build/reports/blueprint-card-actions").apply { mkdirs() }
                        scene.render(++time * 16_666_667L).use { image -> image.encodeToData()!!.use { data ->
                            File(folder, "${mode.name.lowercase()}-${face.name.lowercase()}-$zoom.png").writeBytes(data.bytes)
                        } }
                    }
                }
            } finally { edt { scene.close() } }
        }
    }

    @Test fun measuredBodyReservesTheExactTrailingActionStrip() {
        val node = BlueprintNode("source", 0)
        lateinit var scene: ImageComposeScene
        val measured = mutableListOf<Triple<BlueprintBody, BlueprintBody, Float>>()
        edt {
            scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    for (face in listOf(BlueprintFace.Code, BlueprintFace.Prose)) {
                        val look = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "SourceSheetWithANameLongEnoughToMeasureEveryAction", "", face = face)
                        val plain = measureBlueprintBody(node, look)
                        val withActions = measureBlueprintBody(node, look.copy(actions = actions))
                        measured += Triple(plain, withActions, MachineSignal.Editor.controlHeight.value * actions.size + MachineSignal.Editor.controlGap.value)
                    }
                }
            }
            repeat(8) { Snapshot.sendApplyNotifications(); scene.render(it * 16_666_667L).close() }
        }
        try {
            assertTrue(measured.isNotEmpty())
            for ((plain, withActions, strip) in measured) {
                assertEquals(strip.toDouble(), withActions.width - plain.width, .001)
                assertEquals(plain.height, withActions.height)
            }
        } finally { edt { scene.close() } }
    }

    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
}
