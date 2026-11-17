@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintProseSubtitleTest {
    private val evidence = "Verified 519 of 519 tests, 251 of 251 flows and 47 of 47 parity checks; production release receipt 5a3b76fb"

    @Test fun aLongSubtitleWrapsToASecondLineInsteadOfWideningTheCard() {
        val node = BlueprintNode("item", 0)
        val look = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Release the learning pages", "Done", face = BlueprintFace.Prose)
        lateinit var scene: ImageComposeScene
        var short: BlueprintBody? = null
        var long: BlueprintBody? = null
        edt {
            scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    short = measureBlueprintBody(node, look)
                    long = measureBlueprintBody(node, look.copy(subtitle = evidence))
                }
            }
            repeat(8) { Snapshot.sendApplyNotifications(); scene.render(it * 16_666_667L).close() }
        }
        try {
            val one = requireNotNull(short)
            val two = requireNotNull(long)
            assertEquals(BlueprintEngine.CardWidth, one.width)
            assertEquals(one.width, two.width)
            assertTrue(two.height - one.height in 12.0..BlueprintEngine.RowHeight, "second subtitle line adds ${two.height - one.height}")
        } finally { edt { scene.close() } }
    }

    private fun edt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
}
