@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.jvm.internal.CallableReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlueprintTextReuseTest {
    @Test fun aMeasuredProseFaceReusesItsTextWhenTheSelectedControlsMountAgain() {
        val card = Card("item", 40.0, 40.0, 280.0, 210.0, emptyList(), 0)
        val layout = BlueprintLayout("text-reuse", mapOf(card.id to card), emptyList(), emptyList(), 400.0, 300.0)
        val look = BlueprintCardLook(Color.Blue, BlueprintBench.icon, "A measured task title", "builder · 2m", face = BlueprintFace.Prose,
            eyebrow = "Task", titleContent = {})
        val canvas = GraphCanvasState(.01..4.0)
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(600, 400, coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    BlueprintCanvas(layout, canvas, emptySet(), null, linkStyle = { Blueprint.link(Color.Blue, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") }, cardLook = { look },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                }
            }
            try {
                repeat(16) { Snapshot.sendApplyNotifications(); scene.render(it * 16666667L).close() }
                val painter = assertNotNull((canvas.reading as? CallableReference)?.boundReceiver) as BlueprintPainter
                fun field(owner: Any, key: String): Any = owner.javaClass.getDeclaredField(key).let { it.isAccessible = true; it.get(owner) }
                val cache = field(field(field(painter, "measurer"), "textLayoutCache"), "cache")
                fun calls(): Int = listOf("hitCount", "missCount").sumOf { method -> cache.javaClass.methods.single { it.name == method && it.parameterCount == 0 }.invoke(cache) as Int }
                painter.cardHeaderSlots(card, look, false)
                val measured = calls()
                repeat(4) { painter.cardHeaderSlots(card, look, false) }
                assertEquals(measured, calls(), "Mounting controls for an already measured face must reuse its title and subtitle layout")
            } finally { scene.close() }
        }
    }
}
