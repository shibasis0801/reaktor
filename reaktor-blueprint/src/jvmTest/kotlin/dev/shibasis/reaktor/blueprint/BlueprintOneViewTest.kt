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
import dev.shibasis.reaktor.blueprint.canvas.BlueprintDetailZoom
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlinx.coroutines.Dispatchers
import java.lang.Runnable
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlueprintOneViewTest {
    @Test fun denseMapKeepsEveryCardDetailedWhenZoomedOut() {
        val cards = List(600) { index ->
            Card("card-$index", (index % 30) * 320.0, (index / 30) * 112.0, 272.0, 72.0, emptyList(), 0)
        }
        val layout = BlueprintLayout("one-view", cards.associateBy { it.id }, emptyList(), emptyList(), 9552.0, 2200.0)
        val canvas = GraphCanvasState(.001..4.0)
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun edt(block: () -> Unit) { SwingUtilities.invokeAndWait(Runnable(block)) }
        fun frames() { repeat(24) { edt { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close() }; Thread.sleep(10) } }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun markers() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.count {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("one:") == true
        }
        edt {
            scene = ImageComposeScene(900, 600, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                BlueprintCanvas(layout, canvas, emptySet(), null,
                    cardLook = { BlueprintCardLook(Color.Blue, BlueprintBench.icon, "Readable ${it.id}", "Book",
                        tag = "one:${it.id}", action = "Select ${it.id}") },
                    linkStyle = { Blueprint.link(Color.Blue, false, false) },
                    frameLook = { BlueprintFrameLook(BlueprintBench.icon, "") },
                    onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
            }
        }
        try {
            frames()
            edt {
                val reading = assertNotNull(canvas.reading())
                assertTrue(canvas.zoom < BlueprintDetailZoom, "The fitted map sits below the overview zoom: ${canvas.zoom}")
                assertEquals(2, reading.level, "A host that does not opt in keeps one detailed view: $reading")
                assertEquals(0, reading[BlueprintTier.Overview], "$reading")
                assertEquals(600, markers())
            }
        } finally { edt { scene.close() } }
    }
}
