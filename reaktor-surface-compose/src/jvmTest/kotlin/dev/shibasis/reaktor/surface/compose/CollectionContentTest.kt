package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.listSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionContentTest {
    @Test
    fun contentRowsAreMeasuredAndFocusScrollsTheirParent() = runComposeUiTest {
        val scroll = ScrollState(0)
        val sizes = mutableMapOf<String, Int>()
        val source = listSource((0 until 10).map { "row-$it" }, { it })
        var selection by mutableStateOf(setOf("row-0"))
        val opened = mutableListOf<String>()
        setContent {
            Column(Modifier.height(180.dp).verticalScroll(scroll).testTag("parent")) {
                AutomationScope("files") {
                    ListBox(source, selection, { selection = it }, scrolling = false, onActivate = { opened += it }) { value ->
                        BasicText(List(index % 3 + 1) { value }.joinToString("\n"),
                            Modifier.fillMaxWidth().padding(8.dp).onSizeChanged { sizes[value] = it.height })
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(10, sizes.size)
        assertTrue(sizes.getValue("row-2") > sizes.getValue("row-0"))
        onNodeWithTag("files/row/row-1").performMouseInput { click(center) }
        assertEquals(setOf("row-1"), selection)
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("files/row/row-9").assertIsFocused().assertIsDisplayed()
        waitForIdle()
        assertTrue(scroll.value > 0)
        val last = onNodeWithTag("files/row/row-9").fetchSemanticsNode().boundsInRoot
        val viewport = onNodeWithTag("parent").fetchSemanticsNode().boundsInRoot
        assertTrue(last.top >= viewport.top && last.bottom <= viewport.bottom)
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("row-9"), opened)
        onRoot().performKeyInput { pressKey(Key.MoveHome) }
        onNodeWithTag("files/row/row-0").assertIsFocused().assertIsDisplayed()
        waitForIdle()
        assertEquals(0, scroll.value)
    }

    @Test
    fun prependingRowsKeepsAnUntouchedViewportAtTheTop() = runComposeUiTest {
        val scroll = LazyListState()
        var values by mutableStateOf((0 until 20).map { "row-$it" })
        setContent {
            AutomationScope("top") {
                ListBox(listSource(values, { it }), emptySet(), {}, Modifier.height(160.dp), state = scroll) { BasicText(it) }
            }
        }
        waitForIdle()
        assertEquals(0, scroll.firstVisibleItemIndex)
        runOnIdle { values = listOf("new-row") + values }
        waitForIdle()
        assertEquals(0, scroll.firstVisibleItemIndex)
        onNodeWithTag("top/row/new-row").assertIsDisplayed()
    }

    @Test
    fun prependingRowsPreservesTheVisibleKeyAfterTheOperatorScrolls() = runComposeUiTest {
        val scroll = LazyListState()
        var values by mutableStateOf((0 until 20).map { "row-$it" })
        var selection by mutableStateOf(emptySet<String>())
        setContent {
            AutomationScope("anchor") {
                ListBox(listSource(values, { it }), selection, { selection = it }, Modifier.height(160.dp), state = scroll) { BasicText(it) }
            }
        }
        waitForIdle()
        runOnIdle { scroll.requestScrollToItem(5) }
        waitForIdle()
        onNodeWithTag("anchor/row/row-5").performMouseInput { click(center) }
        val index = scroll.firstVisibleItemIndex
        val key = values[index]
        runOnIdle { values = listOf("new-row") + values }
        waitForIdle()
        assertEquals(key, values[scroll.firstVisibleItemIndex])
        onNodeWithTag("anchor/row/row-5").assertIsDisplayed()
    }

    @Test
    fun aContentRowsKeyboardMenuUsesItsMeasuredBounds() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        val invoked = mutableListOf<Set<String>>()
        setContent {
            AutomationScope("files") {
                ListBox(listSource(listOf("short", "tall", "last"), { it }), selection, { selection = it }, scrolling = false,
                    actions = RowActions({ CommandSet(listOf(Command(CommandId("copy"), "Copy"))) }, { _, keys -> invoked += keys })) {
                    BasicText(if (it == "tall") "first\nsecond\nthird" else it, Modifier.fillMaxWidth().padding(8.dp))
                }
            }
        }
        onNodeWithTag("files/row/tall").performMouseInput { click(center) }
        assertEquals(setOf("tall"), selection)
        val row = onNodeWithTag("files/row/tall").fetchSemanticsNode().boundsInRoot
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithTag("files/menu/copy").assertIsFocused()
        val menu = onNodeWithTag("files/menu/copy").fetchSemanticsNode().boundsInRoot
        val inset = with(density) { 8.dp.toPx() }
        assertEquals(row.bottom, menu.top - inset)
        onNodeWithTag("files/menu/copy").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(setOf("tall")), invoked)
        onNodeWithTag("files/row/tall").assertIsFocused()
    }
}
