package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.CardMarker
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintPinLook
import dev.shibasis.reaktor.blueprint.BlueprintTip
import dev.shibasis.reaktor.graph.core.PortWiring
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.model.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class GraphMarkerTooltipTest {
    @Test
    fun distantWorldCoordinatesKeepTheVisibleTooltipAnchorInteractive() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (zoom in listOf(.25, 1.0, 2.0)) for (world in listOf(0.0, 10_000.0)) {
            val state = GraphCanvasState()
            state.flow.setCanvasSize(androidx.compose.ui.unit.IntSize(400, 300))
            state.flow.setViewport(Viewport(100.0 - world * zoom, 100.0 - world * zoom, zoom))
            val card = Card("card", world, world, 200.0, 80.0, listOf(Pin("port", "State", false, 55.0, PortWiring.Connected)), 0)
            val boxes = listOf(card)
            val icon = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).build()
            var selected by mutableStateOf(false)
            val scene = ImageComposeScene(400, 300, Density(density), coroutineContext = Dispatchers.Swing) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    GraphView(null, state, hit = { _, _, _ -> true }, tap = { _, _, _ -> selected = true; true },
                        focusable = true, markers = {
                            GraphMarkers(state, boxes, { it }) { _, _ ->
                                CardMarker(card, BlueprintCardLook(Color.Black, icon, "Card title", "Subtitle", selected = selected,
                                    pins = listOf(BlueprintPinLook(Color.Black, "port", Color.Black, true))), false,
                                    { _, _ -> BlueprintTip { BasicText("Port contract") } }, {}, { _, _ -> selected = true })
                            }
                        }, draw = { _, _, _ -> })
                }
            }
            var frame = 0L
            suspend fun settle() { repeat(8) { Snapshot.sendApplyNotifications(); frame += 16_666_667L; scene.render(frame).close(); delay(16) } }
            try {
                settle()
                state.flow.setViewport(Viewport(100.0 - world * zoom, 100.0 - world * zoom, zoom))
                settle()
                val at = Offset((100.0 + 100.0 * zoom).toFloat(), (100.0 + 55.0 * zoom).toFloat())
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                settle()
                delay(400)
                settle()
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                assertTrue(scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.any { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Port contract" }
                }, "The marker at $world, zoom $zoom and density $density must receive the held pointer")
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                selected = true
                settle()
                val semantics = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                fun textNode(text: String) = semantics.first { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text } }
                assertTrue(textNode("Card title").boundsInWindow.bottom <= textNode("port").boundsInWindow.top)
                assertTrue(semantics.any { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Port contract" } },
                    "The tooltip must survive pointer release and owner selection")
            } finally { scene.close() }
        }
    }
}
