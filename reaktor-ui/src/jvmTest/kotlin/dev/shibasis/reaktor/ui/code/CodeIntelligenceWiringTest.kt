package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.code.CodeCompletion
import dev.shibasis.reaktor.code.CodeCompletionKind
import dev.shibasis.reaktor.code.CodeDiagnostic
import dev.shibasis.reaktor.code.CodeHover
import dev.shibasis.reaktor.code.CodeIntelligence
import dev.shibasis.reaktor.code.CodeIntelligenceStatus
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSource
import dev.shibasis.reaktor.code.CodeSpan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's half of the [CodeIntelligence] contract, exercised against doubles rather than a
 * real server. Each test declares the server behaviour it needs inline; this source set runs every
 * named class it contains as a test, so there is no shared fixture class to reach for.
 */
@OptIn(ExperimentalTestApi::class)
class CodeIntelligenceWiringTest {
    private val available = CodeIntelligenceStatus("Fake LSP", available = true, detail = "test double")

    @Test fun theDocumentIsOpenedAndEveryEditIsPublished() = runComposeUiTest {
        var opened: CodeSource? = null
        var lastChange: CodeSource? = null
        val server = object : CodeIntelligence {
            override val status = available
            override suspend fun opened(source: CodeSource) { opened = source }
            override suspend fun changed(source: CodeSource) { lastChange = source }
        }
        val state = CodeEditorState("val a = 1", CodeLanguage.Kotlin, uri = "file:///tmp/A.kt")
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp), intelligence = server) }
        waitUntil { opened != null }
        assertEquals("file:///tmp/A.kt", opened?.uri)
        assertEquals("kotlin", opened?.languageId)

        state.moveTo(state.document.end)
        state.type("2")
        waitUntil { lastChange?.text == "val a = 12" }
        assertEquals(state.version, lastChange?.version)
    }

    @Test fun publishedDiagnosticsReachTheStatusBarAndTheCaretTip() = runComposeUiTest {
        val published = MutableStateFlow<List<CodeDiagnostic>>(emptyList())
        val server = object : CodeIntelligence {
            override val status = available
            override fun diagnostics(uri: String): Flow<List<CodeDiagnostic>> = published
        }
        val state = CodeEditorState("val a = oops", CodeLanguage.Kotlin, uri = "file:///tmp/B.kt")
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp), intelligence = server) }
        published.value = listOf(CodeDiagnostic(CodeSpan.of(0, 8, 12), "unresolved reference: oops"))
        waitUntil { state.diagnostics.isNotEmpty() }
        onNodeWithTag("code-editor-errors").assertTextContains("1 error")
        state.moveTo(CodePosition(0, 9))
        waitUntil { runCatching { onNodeWithTag("code-editor-diagnostic").assertExists() }.isSuccess }
        onNodeWithTag("code-editor-diagnostic").assertTextContains("unresolved reference: oops", substring = true)
    }

    @Test fun serverCompletionsRankAboveTheBuffersOwnWords() = runComposeUiTest {
        val server = object : CodeIntelligence {
            override val status = available
            override suspend fun completions(uri: String, at: CodePosition) = listOf(
                CodeCompletion("valueFromServer", kind = CodeCompletionKind.Property, detail = "Int"),
            )
        }
        val state = CodeEditorState("", CodeLanguage.Kotlin, uri = "file:///tmp/C.kt")
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp), intelligence = server) }
        onNodeWithTag("code-editor-surface").requestFocus()
        onNodeWithTag("code-editor-surface").performKeyInput { pressKey(Key.V) }
        waitUntil { runCatching { onNodeWithTag("code-editor-completions").assertExists() }.isSuccess }
        onNodeWithTag("code-editor-completion-0").assertTextContains("valueFromServer", substring = true)
    }

    @Test fun hoverFromTheServerSurfacesAtTheCaret() = runComposeUiTest {
        val server = object : CodeIntelligence {
            override val status = available
            override suspend fun hover(uri: String, at: CodePosition) = CodeHover("val answer: Int")
        }
        val state = CodeEditorState("val answer = 42", CodeLanguage.Kotlin, uri = "file:///tmp/D.kt")
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp), intelligence = server) }
        state.moveTo(CodePosition(0, 5))
        waitUntil { runCatching { onNodeWithTag("code-editor-hover").assertExists() }.isSuccess }
        onNodeWithTag("code-editor-hover").assertTextContains("val answer: Int", substring = true)
    }

    @Test fun noServerLeavesTheEditorUsableAndSaysSo() = runComposeUiTest {
        val state = CodeEditorState("val a = 1", CodeLanguage.Kotlin)
        setContent { CodeEditor(state, Modifier.size(700.dp, 320.dp)) }
        onNodeWithTag("code-editor-intelligence").assertTextContains("none")
        onNodeWithTag("code-editor-surface").requestFocus()
        onNodeWithTag("code-editor-surface").performKeyInput { pressKey(Key.X) }
        waitForIdle()
        assertTrue(state.text.startsWith("x"), "typing still works with no intelligence attached")
    }
}
