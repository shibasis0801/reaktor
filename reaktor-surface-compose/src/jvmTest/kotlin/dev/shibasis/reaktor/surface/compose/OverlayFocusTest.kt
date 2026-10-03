package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OverlayFocusTest {
    @Test
    fun aDialogTakesFocusAndTabWrapsInsideIt() = runComposeUiTest {
        var open by mutableStateOf(false)
        val outside = FocusRequester()
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Column {
                    Button({}, Modifier.focusRequester(outside).testTag("outside")) { BasicText("Outside") }
                    Dialog(open, { open = it }) {
                        Trigger(Modifier.testTag("open")) { BasicText("Open") }
                        Content {
                            Close(Modifier.testTag("keep")) { BasicText("Keep") }
                            Action("discard", {}, Modifier.testTag("discard")) { BasicText("Discard") }
                            Action("archive", {}, Modifier.testTag("archive")) { BasicText("Archive") }
                        }
                    }
                }
            }
        }
        onNodeWithTag("open").performClick()
        onNodeWithTag("keep").assertIsFocused()
        listOf("discard", "archive", "keep").forEach { next ->
            onRoot().performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag(next).assertIsFocused()
        }
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("archive").assertIsFocused()
        assertFalse(runOnIdle { outside.requestFocus(FocusDirection.Enter) }, "content beneath a modal must not take focus")
        onNodeWithTag("archive").assertIsFocused()
    }

    @Test
    fun closingADialogReturnsFocusToItsTrigger() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Dialog(open, { open = it }) {
                    Trigger(Modifier.testTag("open")) { BasicText("Open") }
                    Content { Close(Modifier.testTag("keep")) { BasicText("Keep") } }
                }
            }
        }
        onNodeWithTag("open").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("keep").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        onNodeWithTag("open").assertIsFocused()
    }

    @Test
    fun aMenuWithoutATriggerReturnsFocusToWhateverHadIt() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Column {
                    Button({}, Modifier.testTag("first")) { BasicText("First") }
                    Button({ open = true }, Modifier.testTag("more")) { BasicText("More") }
                    Menu(open, { open = it }) {
                        Popup { Item("copy", {}, Modifier.testTag("copy")) { BasicText("Copy") } }
                    }
                }
            }
        }
        onNodeWithTag("more").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        onNodeWithTag("more").assertIsFocused()
    }

    @Test
    fun escapeClosesTheFocusedOverlayBeforeAnOuterHandlerSeesIt() = runComposeUiTest {
        var open by mutableStateOf(false)
        val outer = mutableListOf<Key>()
        setContent {
            Box(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown) outer += it.key; false }) {
                SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                    Menu(open, { open = it }) {
                        Trigger(Modifier.testTag("actions")) { BasicText("Actions") }
                        Popup { Item("copy", {}, Modifier.testTag("copy")) { BasicText("Copy") } }
                    }
                }
            }
        }
        onNodeWithTag("actions").performClick()
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertFalse(open)
        assertTrue(outer.isEmpty(), "the menu that holds focus takes Escape first")
        onNodeWithTag("actions").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(listOf(Key.Escape), outer)
    }
}
