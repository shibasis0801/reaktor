@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.Blueprint
import dev.shibasis.reaktor.blueprint.BlueprintCanvas
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlueprintLayeredMarkerTest {
    @Test fun aSelectedCardControlDoesNotRestartTheMapMarkerBatchOnLayoutChange() = runBlocking<Unit>(Dispatchers.Swing) {
        val card = Card("target", 30.0, 30.0, 240.0, 180.0, emptyList(), 0)
        val layout = mutableStateOf(BlueprintLayout("controls", mapOf(card.id to card), emptyList(), emptyList(), 600.0, 400.0))
        val canvas = GraphCanvasState()
        val scene = ImageComposeScene(800, 600, coroutineContext = Dispatchers.Unconfined) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                BlueprintCanvas(layout.value, canvas, emptySet(), card.id,
                    cardLook = { BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Card", "", tag = "card-marker",
                        titleContent = { Text("Selected", Modifier.testTag("selected-control")) }) },
                    linkStyle = { Blueprint.link(Color.Blue, false, false) },
                    frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                    onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(),
                    initialFit = { canvas.restore(Viewport(80.0, 80.0, 1.0)) }, mapLayers = true)
            }
        }
        var frame = 0L
        fun render() { Snapshot.sendApplyNotifications(); scene.render(++frame * 16_666_667L).close() }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun control() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "selected-control" }
        try {
            repeat(24) { render(); delay(5) }
            assertNotNull(control())
            layout.value = layout.value.copy(cards = mapOf(card.id to card.copy(height = 210.0)))
            render()
            assertNotNull(control(), "A selected control remains mounted after a layout update")
        } finally { scene.close() }
    }

    @Test fun aLayeredCardMarkerFollowsTheCameraOnTheNextFrame() = runBlocking(Dispatchers.Swing) {
        val card = Card("target", 30.0, 30.0, 240.0, 180.0, emptyList(), 0)
        val layout = BlueprintLayout("markers", mapOf(card.id to card), emptyList(), emptyList(), 600.0, 400.0)
        val canvas = GraphCanvasState()
        val scene = ImageComposeScene(800, 600, coroutineContext = Dispatchers.Unconfined) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                BlueprintCanvas(layout, canvas, emptySet(), null,
                    cardLook = { BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Card", "", tag = "card-marker") },
                    linkStyle = { Blueprint.link(Color.Blue, false, false) },
                    frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
                    onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(),
                    initialFit = { canvas.restore(Viewport(80.0, 80.0, 1.0)) }, mapLayers = true)
            }
        }
        var frame = 0L
        fun render() { Snapshot.sendApplyNotifications(); scene.render(++frame * 16_666_667L).close() }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun marker() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            .single { it.config.getOrNull(SemanticsProperties.TestTag) == "card-marker" }.boundsInRoot
        try {
            repeat(24) { render(); delay(5) }
            val before = marker()
            val view = canvas.viewport()
            canvas.restore(view.copy(x = view.x + 100.0))
            render()
            assertEquals(before.left + 100f, marker().left, 1f)
        } finally { scene.close() }
    }
}
