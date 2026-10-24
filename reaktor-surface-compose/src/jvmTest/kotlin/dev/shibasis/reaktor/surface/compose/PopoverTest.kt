package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PopoverTest {
    @Composable
    private fun Workspace(open: Boolean, onOpen: (Boolean) -> Unit) = SurfaceEnvironmentProvider(SurfaceEnvironment()) {
        Box(Modifier.requiredSize(600.dp, 400.dp)) {
            Column {
                Button({}, Modifier.testTag("before")) { BasicText("Before") }
                Popover(open, onOpen) {
                    Trigger(Modifier.testTag("workspace")) { BasicText("Workspace") }
                    Content {
                        BasicTextField(TextFieldState("/repo"), Modifier.testTag("path"))
                        Action("open", {}, Modifier.testTag("open")) { BasicText("Open") }
                    }
                }
            }
        }
    }

    @Test
    fun focusMovesInWhenThePopoverOpensAndBackToTheTriggerWhenItCloses() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { Workspace(open) { open = it } }
        onNodeWithTag("workspace").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertTrue(open)
        onNodeWithTag("path").assertIsFocused()
        val trigger = onNodeWithTag("workspace").fetchSemanticsNode().boundsInWindow.roundToIntRect()
        val field = onNodeWithTag("path").fetchSemanticsNode().boundsInWindow.roundToIntRect()
        assertTrue(field.top >= trigger.bottom, "the popover opens below its trigger")
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        onNodeWithTag("workspace").assertIsFocused()
    }

    @Test
    fun anOutsideClickDismissesThePopover() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { Workspace(open) { open = it } }
        onNodeWithTag("workspace").performMouseInput { click() }
        assertTrue(open)
        onRoot().performMouseInput { click(Offset(580f, 380f)) }
        assertFalse(open)
        onNodeWithTag("path").assertDoesNotExist()
    }

    @Test
    fun focusLeavingThePopoverDismissesIt() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent { Workspace(open) { open = it } }
        onNodeWithTag("workspace").performMouseInput { click() }
        onNodeWithTag("path").assertIsFocused()
        onNodeWithTag("open").requestFocus()
        assertTrue(open, "moving focus inside the popover keeps it open")
        onNodeWithTag("before").requestFocus()
        waitForIdle()
        assertFalse(open)
        onNodeWithTag("path").assertDoesNotExist()
    }

    @Test
    fun aPopoverWithNothingFocusableStillTakesFocusAndEscapeCloses() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Popover(open, { open = it }) {
                    Trigger(Modifier.testTag("about")) { BasicText("About") }
                    Content { BasicText("Build 7b026d8c30") }
                }
            }
        }
        onNodeWithTag("about").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertTrue(open)
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        onNodeWithTag("about").assertIsFocused()
    }

    @Test
    fun aPopoverOpenedFromAMenuItemKeepsTheFocusTheClosingMenuWouldReturn() = runComposeUiTest {
        var request by mutableStateOf<String?>(null)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.requiredSize(600.dp, 400.dp)) {
                    Button({ request = "menu" }, Modifier.testTag("canvas")) { BasicText("Canvas") }
                    if (request == "menu") Menu(true, { if (!it && request == "menu") request = null }, anchor = OverlayAnchor.Point(Offset(120f, 120f))) {
                        Popup { Item("group", { request = "naming" }, Modifier.testTag("group")) { BasicText("Group into a new graph") } }
                    }
                    if (request == "naming") Popover(true, { if (!it && request == "naming") request = null }) {
                        Content { BasicTextField(TextFieldState(), Modifier.testTag("name")) }
                    }
                }
            }
        }
        onNodeWithTag("canvas").performMouseInput { click() }
        onNodeWithTag("group").performMouseInput { click() }
        waitForIdle()
        assertEquals("naming", request)
        onNodeWithTag("name").assertIsFocused()
    }

    @Test
    fun aPopoverOnAnEmptyAnchorOpensBesideItRatherThanAtTheCorner() = runComposeUiTest {
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.requiredSize(600.dp, 400.dp)) {
                    Box(Modifier.offset(200.dp, 150.dp)) {
                        Popover(true, {}) { Content { BasicText("Find", Modifier.testTag("find")) } }
                    }
                }
            }
        }
        val panel = onNodeWithTag("find").fetchSemanticsNode().boundsInWindow
        val anchor = with(density) { Offset(200.dp.toPx(), 150.dp.toPx()) }
        assertTrue(panel.left >= anchor.x && panel.top >= anchor.y, "the popover opens at its empty anchor, not at $panel")
    }
}
