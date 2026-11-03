@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.*

class BlueprintPaintedPinsTest {
    @Test fun committedRowsKeepTheirExactHitsAtBothDensitiesAndWithALaggedMarkerCamera() {
        for (density in listOf(1f, 2f)) {
            val pin = Pin("KNOWS", "Person", true, 96.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected)
            val card = Card("record", 0.0, 0.0, 420.0, 260.0, listOf(pin), 0)
            val layout = BlueprintLayout("ports", mapOf(card.id to card), emptyList(), emptyList(), card.width, card.height)
            val canvas = GraphCanvasState(.002..16.0)
            var clicks = 0
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun settle() { repeat(40) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(8) } }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            edt {
                scene = ImageComposeScene(1100, 700, Density(density), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                        val colors = MachineSignal.colors
                        val look = BlueprintCardLook(colors.source, SurfaceGraphSymbol, "Relationship", "Recorded fields", tag = "record",
                            pins = listOf(BlueprintPinLook(colors.source, "KNOWS: Person", colors.textStrong, true)))
                        BlueprintCanvas(layout, canvas, emptySet(), null, { Blueprint.link(colors.source, false, false) },
                            { BlueprintFrameLook(SurfaceGraphSymbol, "") }, { look }, {}, {}, {}, Modifier.fillMaxSize(),
                            onPinClick = { c, p -> assertEquals(card.id, c.id); assertEquals(pin.key, p.key); clicks++ })
                    }
                }
            }
            try {
                settle()
                for (zoom in listOf(.2, 1.05, 1.6, 4.0, 16.0, 1.05)) {
                    edt { canvas.restore(30.0, 60.0, zoom) }; settle()
                    edt {
                        val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                        val rows = painter.reading()[BlueprintTier.Detail] == 1
                        assertEquals(rows, painter.rows(card.id, .05), "Paint/hit mismatch at $zoom / $density")
                        val hits = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                            .filter { it.config.getOrNull(SemanticsProperties.TestTag) == "record-provides-KNOWS" }
                        assertEquals(if (rows) 1 else 0, hits.size, "Missing painted pin at $zoom / $density")
                        if (rows) {
                            assertEquals(pin, painter.pinAt(2f, pin.y.toFloat())?.second)
                            val action = assertNotNull(hits.single().config.getOrNull(SemanticsActions.OnClick)?.action)
                            assertTrue(action())
                        }
                    }
                }
                assertTrue(clicks >= 4)
            } finally { edt { scene.close() } }
        }
    }
    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
}
