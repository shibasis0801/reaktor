package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SecureTextFieldTest {
    @Test
    fun secretsStayHiddenAndCannotBeCopiedOrCut() = runComposeUiTest {
        val state = TextFieldState()
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                SecureTextField(state, Modifier.testTag("secret"), label = "Secret")
            }
        }
        onNodeWithTag("secret").performClick().performTextInput("private-token")
        assertEquals("private-token", state.text.toString())
        val node = onNodeWithTag("secret").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.Password))
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        assertEquals("•".repeat(state.text.length), layouts.single().layoutInput.text.text)
        assertFalse(node.config.contains(SemanticsActions.CopyText))
        assertFalse(node.config.contains(SemanticsActions.CutText))
    }
}
