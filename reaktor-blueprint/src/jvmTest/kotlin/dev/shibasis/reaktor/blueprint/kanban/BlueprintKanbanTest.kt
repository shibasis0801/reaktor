@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.kanban

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.editor.*
import dev.shibasis.reaktor.blueprint.editor.GraphChange
import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File
import kotlin.test.*

class BlueprintKanbanTest {
    private val lanes = listOf(KanbanLane("Todo", "To do"), KanbanLane("Doing", "Doing"), KanbanLane("Done", "Done"))
    private val icon = ImageVector.Builder("Task", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Blue)) { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close() }
    }.build()
    private val agent = BlueprintAgent(null, "(codex, xhigh, sol 6.1, designer)", BlueprintAgent.Activity.Claimed,
        otherAgents = 2, effort = "xhigh", model = "sol 6.1", role = "designer")
    private fun task(i: Int, status: String = lanes[i % lanes.size].id) = NodeShape("task-$i", "launch", "Work item $i", "Task", NodeKind.Node,
        attributes = mapOf("status" to status, "priority" to "High"))
    private fun editor(nodes: List<NodeShape>): GraphEditor = GraphEditor(GraphShape(listOf(ScopeShape("launch", "Launch")), nodes, emptyList(), emptyList()), Placement()).apply {
        permits = { _, _ -> true }; validate = { Verdict(true, "") }
        commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "1")) }
    }
    private fun proposal(node: NodeShape, lane: KanbanLane) = GraphPart(changes = listOf(GraphChange("changeNode", "Move ${node.label} to ${lane.title}",
        node = node.copy(attributes = node.attributes + ("status" to lane.id)), before = node)))

    @Test fun largeBoardsVirtualizeCardsAndSelectionLeavesHeadersLanesAndScrollStill() = runBlocking(Dispatchers.Swing) {
        val counts = mutableListOf<String>()
        for (count in listOf(644, 1542)) Scene(editor(List(count) { task(it) })).use { p ->
            p.settle()
            val before = lanes.associate { it.id to p.tag("kanban-header-${it.id}").boundsInRoot }
            val laneBounds = lanes.associate { it.id to p.tag("kanban-lane-${it.id}").boundsInRoot }
            val scrolls = lanes.associate { it.id to p.scroll(it.id) }
            val painted = p.tags("kanban-card-").size
            assertTrue(painted in 3..36, "$count mounts $painted cards")
            assertTrue(p.looks.size <= 48, "$count composed ${p.looks.size} distinct cards")
            p.editor.select(Selection(setOf("task-${count - 1}"))); p.settle()
            assertEquals(before, lanes.associate { it.id to p.tag("kanban-header-${it.id}").boundsInRoot })
            assertEquals(laneBounds, lanes.associate { it.id to p.tag("kanban-lane-${it.id}").boundsInRoot })
            assertEquals(scrolls, lanes.associate { it.id to p.scroll(it.id) })
            counts += "{\"items\":$count,\"mountedCards\":$painted,\"composedCards\":${p.looks.size}}"
            p.picture("scale-$count")
        }
        File("build/reports/blueprint-kanban").apply { mkdirs() }.resolve("scale.json").writeText("[${counts.joinToString(",")}]")
    }

    @Test fun physicalKeyboardAndDragShareSuccessfulAndRefusedStatusRules() = runBlocking(Dispatchers.Swing) {
        for (drag in listOf(false, true)) Scene(editor(listOf(task(0, "Todo")))).use { p ->
            p.editor.validate = { part -> if (part.changes.single().node!!.attributes["status"] == "Done") Verdict(false, "Checks must pass before closing") else Verdict(true, "") }
            p.settle(); p.click("kanban-card-task-0"); p.settle()
            val bounds = lanes.associate { it.id to p.tag("kanban-header-${it.id}").boundsInRoot }
            if (drag) p.drag("kanban-card-task-0", "kanban-lane-Doing") else p.key(Key.DirectionRight, shift = true)
            p.settle()
            assertEquals("Doing", p.editor.shape.node("task-0")!!.attributes["status"])
            assertEquals(setOf("task-0"), p.editor.selection.nodes)
            if (drag) p.drag("kanban-card-task-0", "kanban-lane-Done") else p.key(Key.DirectionRight, shift = true)
            p.settle()
            assertEquals("Doing", p.editor.shape.node("task-0")!!.attributes["status"])
            assertEquals("Checks must pass before closing", p.state.notice)
            assertEquals(bounds, lanes.associate { it.id to p.tag("kanban-header-${it.id}").boundsInRoot })
            assertNull(p.state.drag)
            assertFalse(p.state.moving)
            p.picture(if (drag) "drag-refused" else "keyboard-refused")
        }
    }

    @Test fun statusWritesRequireInstalledAuthorityAndRejectStaleNodesOrSource() = runBlocking {
        val editor = editor(listOf(task(0)))
        val p = KanbanProjection.from(editor.shape, lanes, { it.attributes["status"] })
        val state = BlueprintKanbanState()
        val shape = editor.shape
        assertIs<EditReceipt.Refused>(state.move("launch", editor, p, "task-0", lanes[1], null))
        assertIs<EditReceipt.Refused>(state.move("launch", editor, p, "task-0", lanes[1], ::proposal, "master"))
        editor.validate = { Verdict(false, "Prerequisite unfinished") }
        assertIs<EditReceipt.Refused>(state.move("launch", editor, p, "task-0", lanes[1], ::proposal))
        assertEquals(shape, editor.shape)
        assertIs<EditReceipt.Refused>(state.move("launch", editor, p, "missing", lanes[1], ::proposal))
        editor.validate = { Verdict(true, "") }; editor.permits = { _, _ -> false }
        assertIs<EditReceipt.Refused>(state.move("launch", editor, p, "task-0", lanes[1], ::proposal))
        assertEquals(shape, editor.shape)
    }

    @Test fun agentPortraitAndTupleUseTheSameGalleryAndClosedReasonsRemainVisible() = runBlocking(Dispatchers.Swing) {
        val node = task(0, "Done").copy(attributes = mapOf("status" to "Done", "resolution" to "Focused checks and pictures accepted"))
        Scene(editor(listOf(node))).use { p ->
            p.settle()
            for (part in listOf("icon", "tuple")) {
                assertTrue(p.tag("kanban-agent-task-0-$part").config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(agent, p.opened)
            }
            assertTrue(p.tag("kanban-agent-task-0-state").config[SemanticsProperties.Text].any { it.text == "Claimed · +2" })
            assertTrue(p.tag("kanban-reason-task-0").config[SemanticsProperties.Text].any { it.text == node.attributes["resolution"] })
        }
    }

    @Test fun visibleSelectionAndKeyboardNavigationNeverScrollALane() = runBlocking(Dispatchers.Swing) {
        Scene(editor(List(80) { task(it, "Todo") })).use { p ->
            p.settle(); p.click("kanban-card-task-0"); p.settle()
            val first = p.scroll("Todo")
            p.key(Key.MoveEnd); p.settle()
            assertEquals(setOf("task-79"), p.editor.selection.nodes)
            assertEquals(first, p.scroll("Todo"))
            p.key(Key.MoveHome); p.settle()
            assertEquals(setOf("task-0"), p.editor.selection.nodes)
            assertEquals(first, p.scroll("Todo"))
        }
    }

    private inner class Scene(val editor: GraphEditor, val mode: MachineSignalMode = MachineSignalMode.Dark) : AutoCloseable {
        val state = BlueprintKanbanState()
        val looks = hashSetOf<String>()
        var opened: BlueprintAgent? = null
        var clock = 0L
        val scene = ImageComposeScene(1000, 680, coroutineContext = Dispatchers.Swing) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                val projection = remember(editor.shape) { KanbanProjection.from(editor.shape, lanes, { it.attributes["status"] }) }
                BlueprintKanban("launch", projection, editor, cardLook = { node ->
                    looks += node.id
                    BlueprintCardLook(Color.Blue, icon, node.label, "Blueprint presentation", marks = listOf(BlueprintMark("High", Color.Blue)), agent = agent)
                }, modifier = Modifier.fillMaxSize(), state = state, proposal = ::proposal, onAgentOpen = { opened = it })
            }
        }
        suspend fun settle() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render(++clock * 16_666_667L).close(); delay(12) } }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun tags(prefix: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }
        fun tag(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun scroll(lane: String) = state.lane("launch", lane).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
        fun click(tag: String) { assertTrue(tag(tag).config[SemanticsActions.OnClick].action!!.invoke()) }
        fun key(key: Key, shift: Boolean = false) { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift)); scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift)) }
        suspend fun drag(card: String, lane: String) {
            val from = tag(card).boundsInRoot.topLeft + Offset(45f, 20f)
            val to = tag(lane).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, from)
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            repeat(8) { step -> scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * ((step + 1) / 8f), buttons = PointerButtons(isPrimaryPressed = true)); delay(16) }
            scene.sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = PointerButton.Primary)
        }
        fun picture(name: String) {
            val folder = File("build/reports/blueprint-kanban").apply { mkdirs() }
            scene.render(++clock * 16_666_667L).use { image -> image.encodeToData()!!.use { File(folder, "$name.png").writeBytes(it.bytes) } }
        }
        override fun close() = scene.close()
    }
}
