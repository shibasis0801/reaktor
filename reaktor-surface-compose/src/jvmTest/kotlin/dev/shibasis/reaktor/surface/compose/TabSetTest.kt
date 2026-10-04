package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabSetTest {
    private val documents = listOf("graph", "data", "cloud", "testing")

    @Composable
    private fun Strip(open: List<String>, selected: String, onSelect: (String) -> Unit, onClose: (String) -> Unit = {}, modifier: Modifier = Modifier) =
        Column {
            Button({}, Modifier.testTag("before")) { BasicText("Before") }
            AutomationScope("docs") {
                TabSet(open, selected, onSelect, modifier, onClose) { key ->
                    BasicText(key, Modifier.testTag("label-$key"))
                    if (key != "graph") Close(Modifier.testTag("close-$key")) { BasicText("×") }
                }
            }
            Button({}, Modifier.testTag("after")) { BasicText("After") }
        }

    @Test
    fun theStripIsOneTabStopAndArrowsMoveFocusWithoutSwitching() = runComposeUiTest {
        var selected by mutableStateOf("data")
        setContent { Strip(documents, selected, { selected = it }) }
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("docs/data").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("docs/cloud").assertIsFocused().assertIsNotSelected()
        assertEquals("data", selected)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("docs/cloud").assertIsFocused()
    }

    @Test
    fun enterAndAClickSwitch() = runComposeUiTest {
        var selected by mutableStateOf("graph")
        setContent { Strip(documents, selected, { selected = it }) }
        onNodeWithTag("docs/graph").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals("data", selected)
        onNodeWithTag("docs/data").assertIsSelected()
        onNodeWithTag("label-testing", useUnmergedTree = true).performClick()
        assertEquals("testing", selected)
    }

    @Test
    fun aClosePartMakesATabClosableAndDeleteOrTheActionRequestsTheClose() = runComposeUiTest {
        var open by mutableStateOf(documents)
        val closed = mutableListOf<String>()
        setContent { Strip(open, "data", {}, { closed += it; open = open - it }) }
        assertNull(onNodeWithTag("docs/graph").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions))
        val actions = onNodeWithTag("docs/cloud").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        runOnIdle { actions.single { it.label == "Close" }.action() }
        assertEquals(listOf("cloud"), closed)
        onNodeWithTag("docs/cloud").assertDoesNotExist()
        onNodeWithTag("docs/data").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Delete) }
        assertEquals(listOf("cloud", "data"), closed)
        onNodeWithTag("docs/testing").assertIsFocused()
        onNodeWithTag("close-testing").performClick()
        assertEquals(listOf("cloud", "data", "testing"), closed)
        onNodeWithTag("docs/graph").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Delete) }
        assertEquals(listOf("cloud", "data", "testing"), closed)
        onNodeWithTag("close-testing").assertDoesNotExist()
    }

    @Test
    fun theClosePartIsAButtonOfItsOwnThatTakesNoTabStop() = runComposeUiTest {
        setContent { Strip(documents, "graph", {}) }
        val close = onNodeWithTag("close-data").fetchSemanticsNode().config
        assertTrue(SemanticsActions.OnClick in close)
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("docs/graph").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
    }

    @Test
    fun aNewSelectionIsScrolledIntoView() = runComposeUiTest {
        val many = (1..30).map { "doc-$it" }
        var selected by mutableStateOf("doc-1")
        setContent {
            Row(Modifier.width(300.dp).height(40.dp)) {
                TabSet(many, selected, { selected = it }) { key -> BasicText(key, Modifier.width(80.dp).testTag("label-$key")) }
            }
        }
        onNodeWithTag("label-doc-30", useUnmergedTree = true).assertDoesNotExistOrIsOffscreen()
        selected = "doc-30"
        waitForIdle()
        onNodeWithTag("label-doc-30", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrIsOffscreen() {
        val nodes = fetchSemanticsNodes()
        if (nodes.isEmpty()) return
        assertTrue(nodes.single().boundsInRoot.left >= 300f || nodes.single().boundsInRoot.right <= 0f)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodes() =
        runCatching { listOf(fetchSemanticsNode()) }.getOrDefault(emptyList())
}
