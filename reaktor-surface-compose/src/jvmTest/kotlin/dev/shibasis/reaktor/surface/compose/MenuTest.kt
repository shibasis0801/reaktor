package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandEntry
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.Mark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MenuTest {
    private val chosen = mutableListOf<String>()

    @Composable
    private fun Edit(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) =
        Menu(expanded, onExpandedChange) {
            Trigger(Modifier.testTag("edit")) { BasicText("Edit") }
            Popup {
                Item("copy", { chosen += "copy" }, Modifier.testTag("copy"), typeahead = "Copy") { BasicText("Copy") }
                Item("cut", { chosen += "cut" }, Modifier.testTag("cut"), typeahead = "Cut") { BasicText("Cut") }
                Separator()
                Label { BasicText("Clipboard") }
                Item("delete", { chosen += "delete" }, Modifier.testTag("delete"), enabled = false, typeahead = "Delete") { BasicText("Delete") }
                Item("paste", { chosen += "paste" }, Modifier.testTag("paste"), typeahead = "Paste") { BasicText("Paste") }
            }
        }

    @Test
    fun downAndUpOnTheTriggerOpenOnTheFirstAndLastItems() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { Edit(open) { open = it } } }
        onNodeWithTag("edit").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        assertTrue(open)
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        onNodeWithTag("edit").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("paste").assertIsFocused()
    }

    @Test
    fun arrowsHomeAndEndMoveAndSkipDisabledItemsSeparatorsAndLabels() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { Edit(open) { open = it } } }
        onNodeWithTag("edit").performClick()
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("cut").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("paste").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("paste").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("cut").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.MoveHome) }
        onNodeWithTag("copy").assertIsFocused()
        assertTrue(open)
    }

    @Test
    fun typingJumpsToAnItemUntilTheTypedTextExpires() = runComposeUiTest {
        var open by mutableStateOf(true)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { Edit(open) { open = it } } }
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.P) }
        onNodeWithTag("paste").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.C) }
        onNodeWithTag("paste").assertIsFocused()
        mainClock.advanceTimeBy(600)
        onRoot().performKeyInput { pressKey(Key.C) }
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.C) }
        onNodeWithTag("cut").assertIsFocused()
    }

    @Test
    fun enterAndSpaceActivateOnceThenTheMenuClosesAndFocusReturnsToTheTrigger() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { Edit(open) { open = it } } }
        onNodeWithTag("edit").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("cut"), chosen)
        assertFalse(open)
        onNodeWithTag("edit").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(listOf("cut", "copy"), chosen)
        onNodeWithTag("edit").assertIsFocused()
    }

    @Test
    fun tabClosesTheMenuAndReturnsFocusToTheTrigger() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Column {
                    Edit(open) { open = it }
                    Button({}, Modifier.testTag("after")) { BasicText("After") }
                }
            }
        }
        onNodeWithTag("edit").performClick()
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        assertFalse(open)
        onNodeWithTag("edit").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun aMenuWithoutATriggerReturnsFocusToTheElementThatOpenedIt() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Column {
                    Button({}, Modifier.testTag("first")) { BasicText("First") }
                    Box {
                        Button({ open = true }, Modifier.testTag("columns")) { BasicText("Columns") }
                        Menu(open, { open = it }) { Popup { Item("all", { chosen += "all" }, Modifier.testTag("all")) { BasicText("Show all") } } }
                    }
                }
            }
        }
        onNodeWithTag("first").requestFocus()
        onNodeWithTag("columns").performMouseInput { click() }
        onNodeWithTag("all").assertIsFocused()
        onNodeWithTag("all").performMouseInput { click() }
        assertEquals(listOf("all"), chosen)
        assertFalse(open)
        waitForIdle()
        onNodeWithTag("columns").assertIsFocused()
    }

    @Test
    fun hoveringAnItemMovesFocusToItWithoutAFocusRing() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { SurfaceEnvironmentProvider(SurfaceEnvironment()) { Edit(open) { open = it } } }
        onNodeWithTag("edit").performClick()
        onNodeWithTag("paste").performMouseInput { moveTo(center) }
        onNodeWithTag("paste").assertIsFocused()
        onNodeWithTag("copy").assertIsNotFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("cut").assertIsFocused()
    }

    @Test
    fun checkAndRadioItemsExposeTheirStateAndTheIndicatorDrawsOnlyWhenOn() = runComposeUiTest {
        var wrap by mutableStateOf(false)
        var view by mutableStateOf("rows")
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Menu(true, {}) {
                    Popup {
                        CheckItem("wrap", wrap, { wrap = it }, Modifier.testTag("wrap")) { Indicator { BasicText("on") }; BasicText("Wrap lines") }
                        RadioItem("rows", view == "rows", { view = "rows" }, Modifier.testTag("rows")) { Indicator { BasicText("•") }; BasicText("Rows") }
                        RadioItem("chart", view == "chart", { view = "chart" }, Modifier.testTag("chart")) { BasicText("Chart") }
                    }
                }
            }
        }
        onNodeWithTag("wrap").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertEquals(0, onAllText("on"))
        onNodeWithTag("rows").assertIsSelected()
        onNodeWithTag("chart").assertIsNotSelected()
        assertEquals(1, onAllText("•"))
        wrap = true
        waitForIdle()
        onNodeWithTag("wrap").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertEquals(1, onAllText("on"))
    }

    @Test
    fun aMenuOpensAtItsAnchorWithItsPlacement() = runComposeUiTest {
        val anchor = Rect(100f, 200f, 180f, 230f)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.requiredSize(600.dp, 600.dp)) {
                    Box(Modifier.absoluteOffset(20.dp, 20.dp)) {
                        Menu(true, {}, anchor = OverlayAnchor.Bounds(anchor), placement = Placement(Side.Below, Align.Start, 0.dp)) {
                            Popup { Item("copy", {}, Modifier.testTag("copy")) { BasicText("Copy") } }
                        }
                    }
                }
            }
        }
        val item = onNodeWithTag("copy").fetchSemanticsNode().boundsInWindow.roundToIntRect()
        assertEquals(100, item.left - 8)
        assertEquals(230, item.top - 8)
    }

    @Test
    fun commandsRenderAsItemsChecksChoicesGroupsAndCaptionsWithIsolatedChords() = runComposeUiTest {
        val invoked = mutableListOf<String>()
        val set = CommandSet(
            listOf(
                Command(CommandId("copy"), "Copy", Chord.Of(KeyName.C, primary = true)),
                Command(CommandId("wrap"), "Wrap lines", mark = Mark.Check(true)),
                Command(CommandId("rows"), "Rows", mark = Mark.Choice(true)),
                Command(CommandId("drop"), "Drop table", availability = Availability.Unavailable("Read-only connection")),
                Command(CommandId("csv"), "CSV"),
            ),
        )
        val entries = listOf(
            CommandEntry.Caption("Edit"),
            CommandEntry.Item(CommandId("copy")),
            CommandEntry.Item(CommandId("wrap")),
            CommandEntry.Item(CommandId("rows")),
            CommandEntry.Separator,
            CommandEntry.Item(CommandId("drop")),
            CommandEntry.Group("Export", listOf(CommandEntry.Item(CommandId("csv")))),
        )
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment(keys = KeyConvention.Mac)) {
                Menu(true, {}) { Popup { Commands(set, entries, { invoked += it.value }) } }
            }
        }
        onNodeWithText("⁦⌘C⁩").assertExists()
        onNodeWithText("Edit").assertExists()
        onNodeWithTag("wrap").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        onNodeWithTag("rows").assertIsSelected()
        onNodeWithTag("drop").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        onNodeWithTag("copy").performClick()
        assertEquals(listOf("copy"), invoked)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllText(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
}
