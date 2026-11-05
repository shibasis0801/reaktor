@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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

class GraphMarkerMotionTest {
    @Test fun semanticMarkersWaitForTheCameraToSettleWhileDrawnControlsFollowEveryFrame() {
        val plain = Card("plain", 0.0, 0.0, 200.0, 120.0, emptyList(), 0)
        val chosen = Card("chosen", 300.0, 0.0, 200.0, 120.0, emptyList(), 0)
        val layout = BlueprintLayout("motion", mapOf(plain.id to plain, chosen.id to chosen), emptyList(), emptyList(), 500.0, 120.0)
        val canvas = GraphCanvasState(.002..16.0)
        lateinit var scene: ImageComposeScene
        var tick = 0L
        fun frame() = edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
        fun settle() { repeat(40) { frame(); Thread.sleep(8) } }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun left(tag: String) = edt { scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.boundsInRoot.left }
        edt {
            scene = ImageComposeScene(1100, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    val colors = MachineSignal.colors
                    BlueprintCanvas(layout, canvas, emptySet(), null, { Blueprint.link(colors.source, false, false) },
                        { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                        { card -> BlueprintCardLook(colors.source, SurfaceGraphSymbol, card.id, "Recorded fields", tag = card.id,
                            choice = if (card === chosen) BlueprintChoice(false, "chosen-choice") {} else null) },
                        {}, {}, {}, Modifier.fillMaxSize())
                }
            }
        }
        try {
            edt { canvas.restore(30.0, 60.0, 1.0) }; settle()
            val still = left("plain")
            val drawn = left("chosen-choice")
            for (step in 1..5) {
                edt { canvas.restore(30.0 + 34 * step, 60.0, 1.0) }; frame()
                assertEquals(still, left("plain"), "A semantic marker moved during the gesture")
                assertEquals(drawn + 34f * step, left("chosen-choice"), .5f, "A drawn control lagged the camera")
            }
            repeat(5) { frame() }
            assertEquals(still + 170f, left("plain"), .5f, "A semantic marker stayed behind after the camera came to rest")
            assertEquals(drawn + 170f, left("chosen-choice"), .5f)
        } finally { edt { scene.close() } }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
