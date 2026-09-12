package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.code.CodeDiagnostic
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CodeEditorRenderTest {
    private val source = "fun main() {\n    println(\"hi\")\n}"

    @Test fun rendersTheBufferAndItsChrome() = runComposeUiTest {
        val state = CodeEditorState(source, CodeLanguage.Kotlin)
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp)) }
        onNodeWithTag("code-editor").assertExists()
        onNodeWithTag("code-editor-status").assertExists()
        onNodeWithTag("code-editor-caret").assertTextEquals("Ln 1, Col 1")
        onNode(hasText("println", substring = true)).assertExists()
    }

    @Test fun theStatusBarFollowsTheCaret() = runComposeUiTest {
        val state = CodeEditorState(source, CodeLanguage.Kotlin)
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp)) }
        state.moveTo(CodePosition(1, 4))
        waitForIdle()
        onNodeWithTag("code-editor-caret").assertTextEquals("Ln 2, Col 5")
    }

    @Test fun diagnosticsAndReadOnlyReachTheStatusBar() = runComposeUiTest {
        val state = CodeEditorState(source, CodeLanguage.Kotlin, readOnly = true)
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp)) }
        state.diagnostics = listOf(CodeDiagnostic(CodeSpan.of(1, 4, 11), "unresolved reference"))
        waitForIdle()
        onNodeWithTag("code-editor-errors").assertTextEquals("1 error")
        onNodeWithTag("code-editor-readonly").assertExists()
    }

    @Test fun findBarOpensWithTheMatchCount() = runComposeUiTest {
        val state = CodeEditorState("alpha\nalpha\nbeta", CodeLanguage.Kotlin)
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp)) }
        state.openFind("alpha")
        waitForIdle()
        onNodeWithTag("code-editor-find-count").assertTextEquals("1 of 2")
    }

    @Test fun typedKeysReachTheBufferThroughTheModifierChain() = runComposeUiTest {
        val state = CodeEditorState("", CodeLanguage.Kotlin)
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp)) }
        onNodeWithTag("code-editor-surface").requestFocus()
        onNodeWithTag("code-editor-surface").performKeyInput {
            pressKey(Key.V)
            pressKey(Key.A)
            pressKey(Key.L)
        }
        waitForIdle()
        assertEquals("val", state.text)
    }

    @Test fun aLongBufferRendersWithoutMaterialisingEveryLine() = runComposeUiTest {
        val state = CodeEditorState((1..20_000).joinToString("\n") { "val line$it = $it" }, CodeLanguage.Kotlin)
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp)) }
        onNodeWithTag("code-editor").assertExists()
        state.goToLine(19_000)
        waitForIdle()
        onNodeWithTag("code-editor-caret").assertTextEquals("Ln 19000, Col 1")
    }
}
