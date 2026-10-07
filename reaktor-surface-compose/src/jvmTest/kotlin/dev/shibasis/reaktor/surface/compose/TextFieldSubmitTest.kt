@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import kotlin.test.*

class TextFieldSubmitTest {
    @Test fun aDesktopEnterSubmitsOnceAndTypingDoesNotSubmit() = runComposeUiTest {
        var submits = 0
        val state = TextFieldState()
        setContent { TextField(state, Modifier.testTag("scope"), appearance = BareField,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), onKeyboardAction = KeyboardActionHandler { submits++ }) }
        onNodeWithTag("scope").performClick()
        onNodeWithTag("scope").performTextInput("scope")
        runOnIdle { assertEquals(0, submits) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, submits) }
    }
}
