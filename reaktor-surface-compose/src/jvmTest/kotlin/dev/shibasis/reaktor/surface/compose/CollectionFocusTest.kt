package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.listSource
import dev.shibasis.reaktor.surface.treeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionFocusTest {
    private val rows = listSource((0 until 10_000).map { "row-$it" }, { it }, text = { it })

    @Test
    fun tabEntersOnTheActiveRowAndLeavesAfterOneStop() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-3"))
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                AutomationScope("files") {
                    ListBox(rows, selection, { selection = it }, Modifier.height(200.dp)) { BasicText(it) }
                }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("files/row/row-3").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("files/row/row-3").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("before").assertIsFocused()
    }

    @Test
    fun tabEntersOnASelectionTheOwnerMadeWhileFocusWasElsewhere() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-3"))
        val chosen = mutableListOf<Set<String>>()
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                AutomationScope("files") {
                    ListBox(rows, selection, { chosen += it; selection = it }, Modifier.height(200.dp)) { BasicText(it) }
                }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }
        onNodeWithTag("before").requestFocus()
        selection = setOf("row-5")
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("files/row/row-5").assertIsFocused()
        selection = setOf("row-1")
        waitForIdle()
        onNodeWithTag("files/row/row-5").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("files/row/row-1").assertIsFocused()
        onNodeWithTag("after").requestFocus()
        onNodeWithTag("files/row/row-4").performMouseInput { click(center) }
        assertEquals(listOf(setOf("row-4")), chosen)
        onNodeWithTag("files/row/row-4").assertIsFocused()
    }

    @Test
    fun tabReachesTheActiveRowWhenItIsScrolledAwayAndShiftTabNeverStopsOnTheList() = runComposeUiTest {
        val list = LazyListState()
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                AutomationScope("files") {
                    ListBox(rows, setOf("row-3"), {}, Modifier.height(200.dp), state = list) { BasicText(it) }
                }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }
        runOnIdle { list.requestScrollToItem(5_000) }
        waitForIdle()
        onNodeWithTag("files/row/row-3").assertDoesNotExist()
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("files/row/row-3").assertIsFocused().assertIsDisplayed()
        runOnIdle { list.requestScrollToItem(5_000) }
        onNodeWithTag("after").requestFocus()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("files/row/row-3").assertIsFocused().assertIsDisplayed()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("before").assertIsFocused()
    }

    @Test
    fun endFocusesTheLastRowThatWasNotComposedAndHomeComesBack() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-0"))
        setContent {
            AutomationScope("files") { ListBox(rows, selection, { selection = it }, Modifier.height(200.dp)) { BasicText(it) } }
        }
        onNodeWithTag("files/row/row-9999").assertDoesNotExist()
        onNodeWithTag("files/row/row-0").requestFocus()
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("files/row/row-9999").assertIsFocused().assertIsDisplayed().assertIsSelected()
        assertEquals(setOf("row-9999"), selection)
        onRoot().performKeyInput { pressKey(Key.MoveHome) }
        onNodeWithTag("files/row/row-0").assertIsFocused().assertIsDisplayed()
        assertEquals(setOf("row-0"), selection)
    }

    @Test
    fun pageDownMovesByTheRowsInView() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-0"))
        setContent {
            AutomationScope("files") { ListBox(rows, selection, { selection = it }, Modifier.height(200.dp)) { BasicText(it) } }
        }
        onNodeWithTag("files/row/row-0").requestFocus()
        onRoot().performKeyInput { pressKey(Key.PageDown) }
        onNodeWithTag("files/row/row-6").assertIsFocused().assertIsDisplayed()
        onRoot().performKeyInput { pressKey(Key.PageDown) }
        onNodeWithTag("files/row/row-12").assertIsFocused().assertIsDisplayed()
        onRoot().performKeyInput { pressKey(Key.PageUp) }
        onNodeWithTag("files/row/row-6").assertIsFocused().assertIsDisplayed()
        assertEquals(setOf("row-6"), selection)
    }

    @Test
    fun aFocusedRowScrolledAwayByTheWheelKeepsFocusAndTheArrowsGoOnFromIt() = runComposeUiTest {
        var selection by mutableStateOf(setOf("row-2"))
        setContent {
            AutomationScope("files") { ListBox(rows, selection, { selection = it }, Modifier.height(200.dp).testTag("files")) { BasicText(it) } }
        }
        onNodeWithTag("files/row/row-2").requestFocus()
        onNodeWithTag("files").performMouseInput { scroll(60f) }
        waitForIdle()
        onNodeWithTag("files/row/row-2").assertIsFocused()
        assertTrue(onNodeWithTag("files/row/row-2").fetchSemanticsNode().boundsInRoot.bottom <= 0f)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("files/row/row-3").assertIsFocused().assertIsDisplayed()
        assertEquals(setOf("row-3"), selection)
    }

    @Test
    fun aReorderKeepsFocusOnTheSameKeyAndARemovalMovesItToTheNearestRow() = runComposeUiTest {
        var data by mutableStateOf((0 until 20).map { "row-$it" })
        setContent {
            val source = remember(data) { listSource(data, { it }, text = { it }) }
            AutomationScope("files") { ListBox(source, emptySet(), {}, Modifier.height(400.dp)) { BasicText(it) } }
        }
        onNodeWithTag("files/row/row-5").performClick()
        data = data.reversed()
        waitForIdle()
        onNodeWithTag("files/row/row-5").assertIsFocused()
        data = data - "row-5"
        waitForIdle()
        onNodeWithTag("files/row/row-4").assertIsFocused()
    }

    @Test
    fun collapsingTheBranchThatHoldsTheFocusedRowFocusesTheBranch() = runComposeUiTest {
        var open by mutableStateOf(setOf("src", "main"))
        setContent {
            val source = remember(open) { treeSource(Files, { it.key }, { it.children }, open, text = { it.key }) }
            AutomationScope("files") {
                Tree(source, emptySet(), {}, { key, expanded -> open = if (expanded) open + key else open - key }, Modifier.height(400.dp)) { BasicText(it.key) }
            }
        }
        onNodeWithTag("files/row/App.kt").performClick()
        onNodeWithTag("files/row/main").performSemanticsAction(SemanticsActions.Collapse)
        waitForIdle()
        assertEquals(setOf("src"), open)
        onNodeWithTag("files/row/App.kt").assertDoesNotExist()
        onNodeWithTag("files/row/main").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("files/row/test").assertIsFocused()
    }

    private class Node(val key: String, val children: List<Node> = emptyList())

    private companion object {
        val Files = listOf(
            Node("src", listOf(Node("main", listOf(Node("App.kt"), Node("Main.kt"))), Node("test", listOf(Node("AppTest.kt"))))),
            Node("README.md"),
        )
    }
}
