package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ActivationTest {
    @Test
    fun enterAndSpaceEachActivateExactlyOnce() = runComposeUiTest {
        var activations = 0
        setContent { Button({ activations++ }, Modifier.testTag("run")) { BasicText("Run") } }
        onNodeWithTag("run").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, activations)
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(2, activations)
        onRoot().performKeyInput { pressKey(Key.NumPadEnter) }
        waitForIdle()
        assertEquals(3, activations)
    }

    @Test
    fun aFocusedButtonThatTurnsBusyKeepsFocusAndIgnoresKeysUntilItIsFree() = runComposeUiTest {
        var busy by mutableStateOf(false)
        var activations = 0
        setContent { Button({ activations++ }, Modifier.testTag("save"), busy = busy) { BasicText("Save") } }
        onNodeWithTag("save").requestFocus()
        busy = true
        waitForIdle()
        onNodeWithTag("save").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(0, activations)
        onNodeWithTag("save").assertIsFocused()
        busy = false
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, activations)
    }

    @Test
    fun aMouseClickFocusesTheButtonItPresses() = runComposeUiTest {
        setContent {
            Column {
                Button({}, Modifier.testTag("first")) { BasicText("First") }
                Button({}, Modifier.testTag("second")) { BasicText("Second") }
                ListRow({}, Modifier.testTag("row")) { BasicText("Row") }
            }
        }
        onNodeWithTag("first").requestFocus()
        onNodeWithTag("second").performMouseInput { click() }
        onNodeWithTag("second").assertIsFocused()
        onNodeWithTag("row").performMouseInput { click() }
        onNodeWithTag("row").assertIsFocused()
    }

    @Test
    fun aDisabledButtonDoesNothing() = runComposeUiTest {
        var activations = 0
        setContent { Button({ activations++ }, Modifier.testTag("off"), enabled = false) { BasicText("Off") } }
        onNodeWithTag("off").assertIsNotEnabled()
        onNodeWithTag("off").performClick()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(0, activations)
    }
}
