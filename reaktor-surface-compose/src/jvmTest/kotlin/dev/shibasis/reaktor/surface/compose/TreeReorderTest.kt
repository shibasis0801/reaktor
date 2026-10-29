package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.animateMoveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Edge
import dev.shibasis.reaktor.surface.treeSource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TreeReorderTest {
    @Test
    fun aHandleMovesTheSelectedRowsAndLeavesTheOrderToTheHost() = runComposeUiTest {
        val moves = mutableListOf<Triple<Set<String>, String, Edge>>()
        val source = treeSource(listOf("a", "b", "c"), { it }, { emptyList() }, emptySet())
        setContent {
            Tree(source, setOf("a", "b"), {}, { _, _ -> }, Modifier.height(300.dp),
                onMove = { keys, target, edge -> moves += Triple(keys, target, edge) }) {
                Row { BasicText(it); ReorderHandle(Modifier.size(32.dp).testTag("move-$it")) { BasicText("Move") } }
            }
        }
        onNodeWithTag("move-b", useUnmergedTree = true).performCustomAccessibilityActionWithLabel("Move down")
        assertEquals(listOf(Triple(setOf("a", "b"), "c", Edge.Last)), moves)
        assertEquals(listOf("a", "b", "c"), (0 until source.size).map(source::key))
    }

    @Test
    fun aDragRequestsTheRowAtItsReleasePosition() = runComposeUiTest {
        val moves = mutableListOf<Triple<Set<String>, String, Edge>>()
        setContent {
            Tree(treeSource(listOf("a", "b", "c"), { it }, { emptyList() }, emptySet()), emptySet(), {}, { _, _ -> },
                Modifier.height(300.dp), onMove = { keys, target, edge -> moves += Triple(keys, target, edge) }) {
                Row { BasicText(it); ReorderHandle(Modifier.size(32.dp).testTag("move-$it")) { BasicText("Move") } }
            }
        }
        val first = onNodeWithTag("move-a", useUnmergedTree = true)
        val last = onNodeWithTag("move-c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val start = first.fetchSemanticsNode().boundsInRoot
        first.performMouseInput {
            moveTo(center)
            press()
            animateMoveTo(center + Offset(0f, last.bottom - start.center.y - 2f), durationMillis = 400)
            release()
        }
        assertEquals(listOf(Triple(setOf("a"), "c", Edge.Last)), moves)
    }

    @Test
    fun aTreeWithoutAMoveCallbackHasNoReorderHandle() = runComposeUiTest {
        setContent {
            Tree(treeSource(listOf("a"), { it }, { emptyList() }, emptySet()), emptySet(), {}, { _, _ -> }) {
                ReorderHandle(Modifier.testTag("move")) { BasicText("Move") }
            }
        }
        onNodeWithTag("move", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aLazyFolderGetsChildrenAfterDisclosureAndKeepsSelection() = runComposeUiTest {
        var expanded by mutableStateOf(emptySet<String>())
        var selected by mutableStateOf(setOf("file"))
        var loaded by mutableStateOf(false)
        setContent {
            AutomationScope("lazy") {
                Tree(treeSource(listOf("folder", "file"), { it }, { if (it == "folder" && loaded) listOf("child") else emptyList() },
                    expanded, expandable = { it == "folder" }), selected, { selected = it }, { key, open ->
                    expanded = if (open) expanded + key else expanded - key
                    loaded = true
                }, Modifier.height(300.dp)) { BasicText(it) }
            }
        }
        onNodeWithTag("lazy/row/folder").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand) { it() }
        onNodeWithTag("lazy/row/child").fetchSemanticsNode()
        assertEquals(setOf("file"), selected)
    }
}
