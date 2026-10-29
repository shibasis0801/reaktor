package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.CardMarker
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Request
import dev.shibasis.reaktor.blueprint.editor.Spot
import dev.shibasis.reaktor.blueprint.editor.Target
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalComposeUiApi::class)
class GraphEditorPointerTest {
    @Test
    fun secondaryClickOnTheVisibleHeaderTargetsItsCard() = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (world in listOf(0.0, 10_000.0)) {
            val shape = GraphShape(listOf(ScopeShape("app", "App")),
                listOf(NodeShape("card", "app", "Card title", "Node", NodeKind.Service)), emptyList(), emptyList())
            val editor = GraphEditor(shape, Placement(mapOf("card" to Spot(world, world))))
            val card = editor.layout.cards.getValue("card")
            val state = GraphCanvasState()
            val icon = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).build()
            val scene = ImageComposeScene(1000, 800, Density(density), coroutineContext = Dispatchers.Swing) {
                Box(Modifier.fillMaxSize().padding(start = 160.dp, top = 47.dp)) {
                    GraphView(null, state, { _, _, _ -> true }, { _, _, _ -> true }, editing = editor, markers = {
                        GraphMarkers(state, listOf(card), { "card" }) { _, _ ->
                            CardMarker(card, BlueprintCardLook(Color.Black, icon, "Card title", "Subtitle"), false, { _, _ -> null }, {}, null)
                        }
                    }, draw = { _, _, _ -> })
                }
            }
            var frame = 0L
            suspend fun settle() { repeat(16) { Snapshot.sendApplyNotifications(); frame += 16_666_667L; scene.render(frame).close(); delay(16) } }
            try {
                settle()
                state.flow.setViewport(Viewport(100.0 - card.x * .9, 100.0 - card.y * .9, .9))
                settle()
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                val title = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.single { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Card title" }
                }
                val at = title.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary)
                settle()
                assertEquals(Target.OnCard("card"), assertIs<Request.Menu>(editor.request).target, "world $world, density $density, at $at")
            } finally { scene.close() }
        }
    }
}
