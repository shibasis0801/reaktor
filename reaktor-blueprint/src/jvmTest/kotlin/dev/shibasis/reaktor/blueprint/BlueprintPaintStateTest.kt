@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.jvm.internal.CallableReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlueprintPaintStateTest {
    @Test fun panningNeverPublishesSnapshotStateFromTheBannerDraw() {
        val layout = BlueprintBench.layout(3)
        val canvas = GraphCanvasState(.002..16.0)
        lateinit var scene: ImageComposeScene
        var writes = 0
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(1512, 982, coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    CompositionLocalProvider(LocalBlueprintMapLabels provides true) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(Color.Blue, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "Items") },
                        cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, it.id, "") },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            repeat(12) { Snapshot.sendApplyNotifications(); scene.render(it * 16_666_667L).close() }
            val painter = assertNotNull((canvas.reading as? CallableReference)?.boundReceiver)
            val labels = painter.javaClass.getDeclaredField("frameLabels").let { it.isAccessible = true; it.get(painter) }
            val observer = Snapshot.registerGlobalWriteObserver { if (it === labels) writes++ }
            try {
                canvas.restore(canvas.viewport().x + 60, canvas.viewport().y + 30, canvas.viewport().zoom)
                repeat(12) { Snapshot.sendApplyNotifications(); scene.render((it + 12) * 16_666_667L).close() }
                assertEquals(0, writes, "A pan draw must not publish frame-label snapshot state")
            } finally { observer.dispose(); scene.close() }
        }
    }
}
