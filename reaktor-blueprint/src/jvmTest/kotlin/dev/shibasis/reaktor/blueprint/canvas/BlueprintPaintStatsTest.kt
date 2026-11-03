@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.Blueprint
import dev.shibasis.reaktor.blueprint.BlueprintBench
import dev.shibasis.reaktor.blueprint.BlueprintCanvas
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintPaintStatsTest {
    @Test fun warmLayersAreMeasuredAndLeavingCompositionReleasesTheView() {
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val shown = mutableStateOf(true)
            val canvas = GraphCanvasState(.01..4.0)
            val layout = BlueprintLayout<String>("paint-stats", mapOf("node" to Card("node", 40.0, 40.0, 280.0, 160.0, emptyList(), 0)), emptyList(), emptyList(), 400.0, 300.0)
            lateinit var scene: ImageComposeScene
            var tick = 0L
            fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
            fun frame() {
                repeat(24) {
                    edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }
                    Thread.sleep(10)
                }
            }
            edt {
                scene = ImageComposeScene(600, 400, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                        if (shown.value) BlueprintCanvas(layout, canvas, emptySet(), null,
                            linkStyle = { Blueprint.link(Color.Blue, false, false) },
                            frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                            cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, "Measured capture", "Recorded layer", face = BlueprintFace.Prose) },
                            onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                frame()
                val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                edt {
                    assertTrue(painter.stats.retainedPixelBytes > 0L, "Warm frame has no measured layers: $mode")
                    assertEquals(0L, painter.stats.releasedViews)
                    shown.value = false
                }
                frame()
                edt {
                    assertEquals(0L, painter.stats.retainedPixelBytes)
                    assertEquals(1L, painter.stats.releasedViews)
                    painter.release()
                    assertEquals(1L, painter.stats.releasedViews)
                }
            } finally { edt { scene.close() } }
        }
    }
}
