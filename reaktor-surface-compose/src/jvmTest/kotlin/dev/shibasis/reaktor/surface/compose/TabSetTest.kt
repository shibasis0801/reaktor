package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
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
        onNodeWithTag("docs/data").assertIsFocused()
    }

    @Test
    fun tabEntersOnASelectionTheOwnerMadeWhileFocusWasElsewhere() = runComposeUiTest {
        var selected by mutableStateOf("data")
        val chosen = mutableListOf<String>()
        setContent { Strip(documents, selected, { chosen += it; selected = it }) }
        onNodeWithTag("after").requestFocus()
        selected = "testing"
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("docs/testing").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        selected = "graph"
        waitForIdle()
        onNodeWithTag("docs/cloud").assertIsFocused()
        onNodeWithTag("after").requestFocus()
        onNodeWithTag("label-data", useUnmergedTree = true).performClick()
        assertEquals(listOf("data"), chosen)
        onNodeWithTag("docs/data").assertIsFocused().assertIsSelected()
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
        onNodeWithTag("docs/testing").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithTag("docs/cloud").assertIsFocused().assertIsNotSelected()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals("cloud", selected)
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

    @Test
    fun typeaheadUsesDisplayedTextWhileKeepingStableKeys() = runComposeUiTest {
        var selected by mutableStateOf("pane-1")
        val labels = mapOf("pane-1" to "Graph", "pane-2" to "Data", "pane-3" to "Cloud")
        setContent {
            AutomationScope("docs") {
                TabSet(labels.keys.toList(), selected, { selected = it }, text = labels::getValue) { key -> BasicText(labels.getValue(key)) }
            }
        }
        onNodeWithTag("docs/pane-1").requestFocus()
        onRoot().performKeyInput { pressKey(Key.C) }
        onNodeWithTag("docs/pane-3").assertIsFocused().assertIsNotSelected()
        assertEquals("pane-1", selected)
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals("pane-3", selected)
        onNodeWithTag("docs/pane-3").assertIsSelected()
    }

    @Test
    fun eachTabAndClosePartShowsItsOwnHoverPressAndFocus() = runComposeUiTest {
        val tabLook = object : ItemAppearance {
            @Composable
            override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) =
                Box(Modifier.semantics { stateDescription = state.described() }) { slots.content() }
        }
        val closeLook = object : ButtonAppearance {
            @Composable
            override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) =
                Box(Modifier.semantics { stateDescription = state.described() }) { slots.content() }
        }
        setContent {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                AutomationScope("docs") {
                    TabSet(documents, "data", {}, appearance = tabLook) { key ->
                        BasicText(key)
                        if (key != "graph") Close(Modifier.testTag("close-$key"), appearance = closeLook) { BasicText("×") }
                    }
                }
            }
        }
        onNodeWithTag("docs/cloud").performMouseInput { moveTo(center) }
        onNodeWithTag("docs/cloud").assertState("hovered")
        onNodeWithTag("docs/data").assertState("")
        onNodeWithTag("close-cloud").performMouseInput {
            moveTo(center)
            press()
        }
        onNodeWithTag("close-cloud").assertState("hovered pressed")
        onNodeWithTag("docs/cloud").assertState("hovered")
        onNodeWithTag("close-cloud").performMouseInput { release() }
        onNodeWithTag("close-cloud").assertState("hovered")
        onNodeWithTag("docs/cloud").performMouseInput { moveTo(Offset(-100f, -100f)) }
        onNodeWithTag("docs/cloud").assertState("")
        onNodeWithTag("close-cloud").assertState("")
        onNodeWithTag("before").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("docs/data").assertIsFocused().assertState("focused visible")
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("docs/cloud").assertIsFocused().assertState("focused visible")
        onNodeWithTag("docs/data").assertState("")
    }

    private fun PressState.described() = listOfNotNull("hovered".takeIf { hovered }, "pressed".takeIf { pressed }, "focused".takeIf { focused }, "visible".takeIf { focusVisible }).joinToString(" ")

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertState(described: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, described))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrIsOffscreen() {
        val nodes = fetchSemanticsNodes()
        if (nodes.isEmpty()) return
        assertTrue(nodes.single().boundsInRoot.left >= 300f || nodes.single().boundsInRoot.right <= 0f)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodes() =
        runCatching { listOf(fetchSemanticsNode()) }.getOrDefault(emptyList())
}
