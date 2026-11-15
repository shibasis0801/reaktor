package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.performImeAction
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
                SecureTextField(state, Modifier.testTag("secret").semantics { contentDescription = "Secret" })
            }
        }
        onNodeWithTag("secret").performClick().performTextInput("private-token")
        assertEquals("private-token", state.text.toString())
        val node = onNodeWithTag("secret").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.Password))
        assertEquals(listOf("Secret"), node.config[SemanticsProperties.ContentDescription])
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        assertEquals("•".repeat(state.text.length), layouts.single().layoutInput.text.text)
        assertFalse(node.config.contains(SemanticsActions.CopyText))
        assertFalse(node.config.contains(SemanticsActions.CutText))
    }
    @Test fun labelAndSecureSubmissionWorkWithoutExposingTheValue() = runComposeUiTest {
        val state = TextFieldState()
        var submits = 0
        setContent {
            SecureTextField(state, Modifier.testTag("credential"), label = "Credential", imeAction = ImeAction.Done,
                onKeyboardAction = KeyboardActionHandler { submits++ })
        }
        onNodeWithTag("credential").performClick().performTextInput("private-token")
        assertEquals(0, submits)
        onNodeWithTag("credential").performImeAction()
        assertEquals(1, submits)
        val node = onNodeWithTag("credential").fetchSemanticsNode()
        assertEquals(listOf("Credential"), node.config[SemanticsProperties.ContentDescription])
        assertEquals(ImeAction.Done, node.config[SemanticsProperties.ImeAction])
        assertTrue(node.config.contains(SemanticsProperties.Password))
        assertFalse(node.config.contains(SemanticsActions.CopyText))
    }

    @Test fun changingTheImeActionPreservesSecureKeyboardDefaultsAndRedaction() = runComposeUiTest {
        val recorded = linkedMapOf<String?, SurfaceCall>()
        val studio = object : SurfaceStudio {
            @androidx.compose.runtime.Composable
            override fun Element(call: SurfaceCall, render: @androidx.compose.runtime.Composable (SurfaceEdits) -> Unit) {
                recorded[call.fields.firstOrNull { it.name == "label" }?.value] = call
            }
        }
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalSurfaceStudio provides studio) {
                SecureTextField(TextFieldState("default-secret"), label = "Default")
                SecureTextField(TextFieldState("done-secret"), label = "Done", imeAction = ImeAction.Done)
            }
        }
        waitForIdle()
        assertEquals(2, recorded.size)
        recorded.values.forEach { call ->
            val fields = call.fields.associate { it.name to it.value }
            assertEquals("Password", fields["keyboardType"])
            assertEquals(false, StudioValues.AutoCorrections[fields["autoCorrect"]])
            assertFalse("value" in fields)
        }
        assertEquals("Default", recorded.getValue("Default").fields.first { it.name == "imeAction" }.value)
        assertEquals("Done", recorded.getValue("Done").fields.first { it.name == "imeAction" }.value)
    }

}
