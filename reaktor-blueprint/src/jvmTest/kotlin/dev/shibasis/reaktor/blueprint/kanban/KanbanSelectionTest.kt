@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.kanban

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File
import kotlin.test.*

class KanbanSelectionTest {
    private val lanes = listOf(KanbanLane("Todo", "To do"), KanbanLane("Doing", "Doing"), KanbanLane("Done", "Done"))
    private val icon = ImageVector.Builder("Task", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Blue)) { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close() }
    }.build()
    private val longTitle = "DAG and Kanban keep the exact source, item and selection, and each lane’s saved scroll"
    private fun graph(count: Int) = GraphEditor(GraphShape(listOf(ScopeShape("launch", "Blueprint launch")),
        List(count) { i -> NodeShape("item-$i", "launch", if (i == 0) longTitle else "Work item $i", "Task", NodeKind.Node,
            attributes = mapOf("status" to if (i % 2 == 0) "Todo" else "Doing")) }, emptyList(), emptyList()), Placement())

    @Test fun switchingRealDagAndKanbanRetainsSelectionEachLaneScrollAndCamera() = runBlocking(Dispatchers.Swing) {
        Host().use { p ->
            p.settle(); p.editor.select(Selection(setOf("item-300", "item-301"))); p.settle()
            p.state.lane("launch", "Todo").scrollToItem(27, 19)
            p.state.lane("launch", "Doing").scrollToItem(34, 7)
            p.settle()
            val scroll = lanes.associate { it.id to p.scroll(it.id) }
            val original = p.editor
            p.kanban = false; p.settle()
            p.camera.restore(-140.0, -90.0, 0.8); p.settle()
            val camera = p.camera.viewport()
            p.editor.select(Selection(setOf("item-428"))); p.settle()
            p.kanban = true; p.settle()
            assertSame(original, p.editor)
            assertEquals("launch", p.source)
            assertEquals(setOf("item-428"), p.editor.selection.nodes)
            assertEquals(scroll, lanes.associate { it.id to p.scroll(it.id) })
            p.editor.select(Selection(setOf("item-429", "item-430"))); p.settle()
            p.kanban = false; p.settle()
            assertEquals(setOf("item-429", "item-430"), p.editor.selection.nodes)
            assertEquals(camera, p.camera.viewport())
        }
    }

    @Test fun overlappingItemKeysKeepTheirOwnSourceSelectionScrollAndCamera() = runBlocking(Dispatchers.Swing) {
        Host().use { p ->
            p.settle(); p.editor.select(Selection(setOf("item-40")))
            p.state.lane("launch", "Todo").scrollToItem(18, 13); p.settle()
            val launchScroll = p.scroll("Todo")
            p.source = "master"; p.settle()
            assertTrue(p.editor.selection.nodes.isEmpty())
            assertEquals(0 to 0, p.scroll("Todo"))
            p.editor.select(Selection(setOf("item-41")))
            p.state.lane("master", "Todo").scrollToItem(33, 11); p.settle()
            val masterScroll = p.scroll("Todo")
            p.kanban = false; p.settle(); p.camera.restore(-75.0, 20.0, 0.7); p.settle()
            val masterCamera = p.camera.viewport()
            p.source = "launch"; p.settle()
            assertEquals(setOf("item-40"), p.editor.selection.nodes)
            p.kanban = true; p.settle(); assertEquals(launchScroll, p.scroll("Todo"))
            p.source = "master"; p.settle()
            assertEquals(setOf("item-41"), p.editor.selection.nodes)
            assertEquals(masterScroll, p.scroll("Todo"))
            p.kanban = false; p.settle(); assertEquals(masterCamera, p.camera.viewport())
        }
    }

    @Test fun narrowLongTitlesEmptyLanesAndSelectionRemainReadableInBothThemes() = runBlocking(Dispatchers.Swing) {
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) Host(width = 420, count = 6, mode = mode).use { p ->
            p.settle(); p.editor.select(Selection(setOf("item-0"))); p.settle()
            val card = p.tag("kanban-card-item-0")
            val title = p.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == longTitle } == true }
            assertTrue(title.boundsInRoot.height > 30)
            assertTrue(card.boundsInRoot.contains(title.boundsInRoot.topLeft))
            assertTrue(card.boundsInRoot.contains(title.boundsInRoot.bottomRight))
            p.picture("${mode.name.lowercase()}-long-title")
            p.state.board("launch").scrollToItem(2); p.settle()
            val scroll = p.state.board("launch").firstVisibleItemIndex to p.state.board("launch").firstVisibleItemScrollOffset
            assertTrue(p.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "No items" } == true })
            p.picture("${mode.name.lowercase()}-empty-lane")
            p.kanban = false; p.settle(); p.kanban = true; p.settle()
            assertEquals(setOf("item-0"), p.editor.selection.nodes)
            assertEquals(scroll, p.state.board("launch").firstVisibleItemIndex to p.state.board("launch").firstVisibleItemScrollOffset)
            p.picture("${mode.name.lowercase()}-restored")
        }
    }

    private inner class Host(width: Int = 1000, count: Int = 644, mode: MachineSignalMode = MachineSignalMode.Dark) : AutoCloseable {
        val editors = mapOf("launch" to graph(count), "master" to graph(count))
        val cameras = editors.mapValues { GraphCanvasState() }
        val state = BlueprintKanbanState()
        var source by mutableStateOf("launch")
        var kanban by mutableStateOf(true)
        val editor get() = editors.getValue(source)
        val camera get() = cameras.getValue(source)
        var clock = 0L
        val scene = ImageComposeScene(width, 680, coroutineContext = Dispatchers.Swing) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                val colors = MachineSignal.colors
                val current = editor
                val look: @Composable (String) -> BlueprintCardLook = { label -> BlueprintCardLook(colors.controlAccent, icon, label, "Blueprint launch") }
                if (kanban) BlueprintKanban(source, KanbanProjection.from(current.shape, lanes, { it.attributes["status"] }), current,
                    cardLook = { look(it.label) }, state = state, modifier = Modifier.fillMaxSize())
                else BlueprintCanvas(current.layout, camera, current.selection.nodes, current.selection.nodes.singleOrNull(),
                    linkStyle = { Blueprint.link(colors.controlAccent, false, false) }, frameLook = { BlueprintFrameLook(icon, "") },
                    cardLook = { look(current.shape.nodes.single { node -> node.id == it.id }.label) }, onCardClick = { current.select(Selection(setOf(it))) }, onLinkClick = {},
                    onPaneClick = {}, editor = current, initialFit = {}, modifier = Modifier.fillMaxSize())
            }
        }
        suspend fun settle() { repeat(10) { Snapshot.sendApplyNotifications(); scene.render(++clock * 16_666_667L).close(); delay(12) } }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun tag(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun scroll(lane: String) = state.lane(source, lane).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
        fun picture(name: String) {
            val folder = File("build/reports/blueprint-kanban-twins").apply { mkdirs() }
            scene.render(++clock * 16_666_667L).use { image -> image.encodeToData()!!.use { File(folder, "$name.png").writeBytes(it.bytes) } }
        }
        override fun close() = scene.close()
    }
}
