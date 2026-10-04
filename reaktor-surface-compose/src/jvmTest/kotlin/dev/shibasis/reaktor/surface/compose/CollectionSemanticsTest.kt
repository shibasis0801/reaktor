package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.listSource
import dev.shibasis.reaktor.surface.treeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionSemanticsTest {
    private val rows = listSource((0 until 10_000).map { "row-$it" }, { it }, text = { it })

    @Test
    fun eachRowIsOneNodeWithItsTextItsSelectionAndItsPlace() = runComposeUiTest {
        setContent {
            AutomationScope("files") {
                ListBox(rows, setOf("row-1"), {}, Modifier.height(200.dp).testTag("files")) {
                    Row {
                        BasicText(it)
                        BasicText("detail")
                    }
                }
            }
        }
        onNodeWithTag("files/row/row-1").assertIsSelected().assertTextEquals("row-1", "detail")
        onNodeWithTag("files/row/row-2").assertIsNotSelected()
        onAllNodesWithText("row-1").assertCountEquals(1)
        assertEquals(2, onNodeWithTag("files/row/row-2").fetchSemanticsNode().config[SemanticsProperties.CollectionItemInfo].rowIndex)
        val list = onNodeWithTag("files").fetchSemanticsNode().config
        assertEquals(10_000, list[SemanticsProperties.CollectionInfo].rowCount)
        assertTrue(SemanticsProperties.SelectableGroup in list)
    }

    @Test
    fun aRowSelectsOnClickAndOpensThroughItsAction() = runComposeUiTest {
        var selection by mutableStateOf(emptySet<String>())
        val opened = mutableListOf<String>()
        setContent {
            AutomationScope("files") {
                ListBox(rows, selection, { selection = it }, Modifier.height(200.dp), onActivate = { opened += it }) { BasicText(it) }
            }
        }
        onNodeWithTag("files/row/row-3").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(setOf("row-3"), selection)
        val actions = onNodeWithTag("files/row/row-4").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        runOnIdle { actions.single { it.label == "Open" }.action() }
        assertEquals(listOf("row-4"), opened)
    }

    @Test
    fun treeRowsExpandAndCollapseThroughTheirActionsAndLeavesHaveNeither() = runComposeUiTest {
        var open by mutableStateOf(setOf("src"))
        setContent {
            val source = remember(open) { treeSource(Files, { it.key }, { it.children }, open, text = { it.key }) }
            AutomationScope("files") {
                Tree(source, emptySet(), {}, { key, expanded -> open = if (expanded) open + key else open - key }, Modifier.height(400.dp)) { BasicText(it.key) }
            }
        }
        onNodeWithTag("files/row/main").performSemanticsAction(SemanticsActions.Expand)
        assertEquals(setOf("src", "main"), open)
        onNodeWithTag("files/row/App.kt").assertTextEquals("App.kt")
        val main = onNodeWithTag("files/row/main").fetchSemanticsNode().config
        assertTrue(SemanticsActions.Collapse in main)
        assertFalse(SemanticsActions.Expand in main)
        onNodeWithTag("files/row/main").assertTextEquals("main")
        val leaf = onNodeWithTag("files/row/App.kt").fetchSemanticsNode().config
        assertFalse(SemanticsActions.Expand in leaf)
        assertFalse(SemanticsActions.Collapse in leaf)
        onNodeWithTag("files/row/main").performSemanticsAction(SemanticsActions.Collapse)
        assertEquals(setOf("src"), open)
    }

    @Test
    fun theVisibleTreeToggleUsesItsOwnSemanticActionWithoutSelectingTheRow() = runComposeUiTest {
        var open by mutableStateOf(setOf("src"))
        var selection by mutableStateOf(emptySet<String>())
        val look = object : RowAppearance {
            @Composable
            override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
                BareRow.Content(properties, state, theme, feedback, RowSlots(slots.content,
                    slots.toggle?.let { Modifier.testTag("toggle-${properties.index}").then(it) }))
            }
        }
        setContent {
            Tree(treeSource(Files, { it.key }, { it.children }, open), selection, { selection = it },
                { key, expanded -> open = if (expanded) open + key else open - key }, Modifier.height(400.dp), appearance = look) { BasicText(it.key) }
        }
        onNodeWithTag("toggle-0", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(emptySet(), open)
        assertEquals(emptySet(), selection)
        onNodeWithTag("toggle-0", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(setOf("src"), open)
        assertEquals(emptySet(), selection)
    }

    @Test
    fun aRowShowsItsItemsNewTextWhenTheItemChangesButKeepsItsKey() = runComposeUiTest {
        var labels by mutableStateOf((0 until 20).map { "row-$it" to "first $it" })
        setContent {
            val source = remember(labels) { listSource(labels, { it.first }) }
            AutomationScope("files") { ListBox(source, setOf("row-2"), {}, Modifier.height(200.dp)) { BasicText(it.second) } }
        }
        onNodeWithTag("files/row/row-2").assertTextEquals("first 2")
        labels = labels.map { (key, _) -> key to "second ${key.removePrefix("row-")}" }
        onNodeWithTag("files/row/row-2").assertTextEquals("second 2")
        onNodeWithTag("files/row/row-3").assertTextEquals("second 3")
    }

    @Test
    fun aRowKnowsItsNewPlaceAfterAReorder() = runComposeUiTest {
        var keys by mutableStateOf((0 until 5).map { "row-$it" })
        setContent {
            val source = remember(keys) { listSource(keys, { it }) }
            AutomationScope("files") { ListBox(source, emptySet(), {}, Modifier.height(200.dp)) { BasicText("$index $it") } }
        }
        onNodeWithTag("files/row/row-1").assertTextEquals("1 row-1")
        keys = keys.reversed()
        onNodeWithTag("files/row/row-1").assertTextEquals("3 row-1")
    }

    @Test
    fun aRowsContentNeverTakesFocus() = runComposeUiTest {
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                AutomationScope("files") {
                    ListBox(rows, setOf("row-0"), {}, Modifier.height(200.dp)) {
                        Row {
                            BasicText(it)
                            Button({}, Modifier.testTag("copy-$it")) { BasicText("Copy") }
                        }
                    }
                }
                Button({}, Modifier.testTag("after")) { BasicText("After") }
            }
        }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("files/row/row-0").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("files/row/row-0").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("before").assertIsFocused()
    }

    private class Node(val key: String, val children: List<Node> = emptyList())

    private companion object {
        val Files = listOf(
            Node("src", listOf(Node("main", listOf(Node("App.kt"), Node("Main.kt"))), Node("test", listOf(Node("AppTest.kt"))))),
            Node("README.md"),
        )
    }
}
