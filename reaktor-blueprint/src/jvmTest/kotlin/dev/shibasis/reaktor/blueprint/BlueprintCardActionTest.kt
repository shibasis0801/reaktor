@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphAppearance
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphNode
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphRow
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSimple
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.blueprint.product.look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlueprintCardActionTest {
    @Test
    fun actionClickAndDragKeepSelectionCameraAndPlacementStill() = runBlocking(Dispatchers.Swing) {
        val node = SurfaceGraphNode("source", "Source", "Book", rows = listOf(SurfaceGraphRow("unit", "Unit")))
        val appearance = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.Blue)
        val model = SurfaceGraphSimple(listOf(node), emptyList(), appearance)
        val canvas = GraphCanvasState()
        var opened = 0
        val scene = ImageComposeScene(960, 640, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            BlueprintGraph(model.editor, canvas, { model.look(it.id, appearance).copy(actions = listOf(
                BlueprintCardAction("open", "Open source", SurfaceGraphSymbol) { opened++ })) },
                { BlueprintFrameLook(SurfaceGraphSymbol, "") }, { Blueprint.link(Color.Gray, false, false) }, { null }, Modifier.fillMaxSize())
        }
        var time = 0L
        suspend fun frames(count: Int) { repeat(count) { Snapshot.sendApplyNotifications(); time += 16_666_667; scene.render(time).close(); delay(10) } }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun action() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.firstOrNull {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "blueprint-card-action:source:open"
        }
        val pressed = PointerButtons(isPrimaryPressed = true)
        try {
            frames(30)
            val card = model.editor.layout.cards.getValue("source")
            canvas.look(card.x + card.width / 2, card.y + card.height / 2, 2.0)
            frames(40)
            val hit = assertNotNull(action())
            assertEquals(listOf("Open source"), hit.config[SemanticsProperties.ContentDescription])
            val before = model.editor.placement
            val selection = model.editor.selection
            val camera = canvas.viewport()
            val center = hit.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center, buttons = pressed, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, center, button = PointerButton.Primary)
            frames(5)
            assertEquals(1, opened)
            assertEquals(selection, model.editor.selection)
            assertEquals(before, model.editor.placement)
            scene.sendPointerEvent(PointerEventType.Press, center, buttons = pressed, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, center + Offset(90f, 60f), buttons = pressed)
            scene.sendPointerEvent(PointerEventType.Release, center + Offset(90f, 60f), button = PointerButton.Primary)
            frames(10)
            assertEquals(before, model.editor.placement)
            assertEquals(selection, model.editor.selection)
            assertEquals(camera, canvas.viewport())
            assertTrue(hit.boundsInRoot.width > 0)
            canvas.look(card.x + card.width / 2, card.y + card.height / 2, .03)
            frames(40)
            assertEquals(null, action())
        } finally { scene.close() }
    }
}
