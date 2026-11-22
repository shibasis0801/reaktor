@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.kanban

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import dev.shibasis.reaktor.blueprint.product.*
import dev.shibasis.reaktor.blueprint.editor.EditReceipt
import dev.shibasis.reaktor.blueprint.editor.EditRevision
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlin.test.*

class KanbanGestureRevisionTest {
    @Test fun eachDragKeepsTheAuthorityVisibleWhenItStarted() = runBlocking(Dispatchers.Swing) {
        var revision by mutableIntStateOf(1)
        val called = mutableListOf<Int>()
        var tick = 0L
        val scene = ImageComposeScene(1000, 720, coroutineContext = Dispatchers.Swing) {
            val drawn = revision
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                SurfaceGraphBoard(listOf(SurfaceGraphNode("a", "A", "")), listOf(KanbanLane("todo", "To do"), KanbanLane("doing", "Doing")),
                    { "todo" }, null, SurfaceGraphAppearance(Color.Black, Color.DarkGray, Color.White, Color.Gray, Color.Gray, Color.Blue), {}, Modifier.fillMaxSize(),
                    onMoveResult = { _, _ -> called += drawn; EditReceipt.Accepted(EditRevision(drawn.toString(), "")) })
            }
        }
        suspend fun frame() { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close(); delay(10) }
        fun tag(id: String): SemanticsNode {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single { it.config.getOrNull(SemanticsProperties.TestTag) == id }
        }
        suspend fun drag(changeDuring: Boolean) {
            val from = tag("kanban-card-a").boundsInRoot.topLeft + Offset(45f, 20f)
            val to = tag("kanban-lane-doing").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, from)
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            repeat(8) { step ->
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * ((step + 1) / 8f), buttons = PointerButtons(isPrimaryPressed = true))
                frame()
                if (step == 3 && changeDuring) { revision++; repeat(4) { frame() } }
            }
            val ghost = tag("kanban-drag").boundsInRoot
            assertTrue(ghost.left < to.x - 10f && ghost.top < to.y - 10f, "The full card keeps the pointer inside its grabbed face")
            scene.sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = PointerButton.Primary)
            repeat(10) { frame() }
        }
        try {
            repeat(10) { frame() }
            revision = 2; repeat(10) { frame() }
            drag(true)
            assertEquals(listOf(2), called)
            drag(false)
            assertEquals(listOf(2, 3), called)
        } finally { scene.close() }
    }
}
