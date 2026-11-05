package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CodeMarkdownRenderTest {
    @Test fun producerLineEndingsAreNormalizedBeforeRendering() = runComposeUiTest {
        var text by mutableStateOf("one\r\ntwo")
        setContent { CodeViewer(text, Modifier.size(600.dp, 300.dp), language = CodeLanguage.Markdown,
            presentation = CodePresentation.Preview) }
        onNodeWithText("one\ntwo").assertExists()
        text = "three\r\nfour"
        waitForIdle()
        onNodeWithText("three\nfour").assertExists()
    }

    @Test fun streamingSourceKeepsTheReadingViewport() = runComposeUiTest {
        val state = CodeEditorState((0..500).joinToString("\n") { "line$it" }, CodeLanguage.Kotlin, readOnly = true)
        setContent { CodeEditor(state, Modifier.size(600.dp, 300.dp)) }
        onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(300)
        onNodeWithText("line300").assertIsDisplayed()
        state.append("\nmore")
        waitForIdle()
        onNodeWithText("line300").assertIsDisplayed()
    }

    @Test fun unclosedBackticksRemainLiteralInPreview() = runComposeUiTest {
        val state = CodeEditorState("Keep `` literal", CodeLanguage.Markdown, readOnly = true)
        state.presentation = CodePresentation.Preview
        setContent { CodeEditor(state, Modifier.size(600.dp, 300.dp)) }
        onNodeWithText("Keep `` literal").assertExists()
    }

    @Test fun clickingBelowTheBufferFocusesItsEnd() = runComposeUiTest {
        val state = CodeEditorState("draft", CodeLanguage.Markdown)
        setContent { CodeEditor(state, Modifier.size(600.dp, 300.dp)) }
        onNodeWithTag("code-editor-surface").performMouseInput { click(center) }
        onNodeWithTag("code-editor-surface").performKeyInput { pressKey(Key.X) }
        assertEquals("draftx", state.text)
    }

    @Test fun previewAndSourceShareHistory() = runComposeUiTest {
        val state = CodeEditorState("# Draft", CodeLanguage.Markdown)
        setContent { CodeEditor(state, Modifier.size(600.dp, 300.dp)) }
        state.moveTo(state.document.end)
        state.type("!")
        onNodeWithTag("code-editor-presentation").performClick()
        onNodeWithTag("code-editor-preview").assertExists()
        onNodeWithText("Draft!").assertExists()
        onNodeWithTag("code-editor-presentation").performClick()
        onNodeWithTag("code-editor-surface").requestFocus()
        state.undo()
        assertEquals("# Draft", state.text)
    }

    @Test fun streamingPreviewKeepsCodeLiteralAndLanguage() = runComposeUiTest {
        val state = CodeEditorState("## Result\n\n```kotlin\nval value = \"**literal**\"", CodeLanguage.Markdown, readOnly = true)
        state.presentation = CodePresentation.Preview
        setContent { CodeEditor(state, Modifier.size(600.dp, 300.dp)) }
        onNodeWithText("Result").assertExists()
        onNodeWithContentDescription("Kotlin editor, 1 lines").assertExists()
        onNode(hasText("**literal**", substring = true)).assertExists()
        state.append("\n```\n\nFinished")
        waitForIdle()
        onNodeWithText("Finished").assertExists()
    }
}
