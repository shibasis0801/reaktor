package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.compose.Island

@Composable
fun CodeView(
    text: String,
    modifier: Modifier = Modifier,
    language: CodeLanguage = CodeLanguage.sniff(text),
    tag: String = "code-view",
) {
    val state = remember(tag) { CodeEditorState("", language, "reaktor://view/$tag", readOnly = true) }
    LaunchedEffect(text, language) { state.load(text, language) }
    val clipboard = LocalClipboardManager.current
    Island(
        CodeViewCommands,
        { id ->
            when (id) {
                CopyCode -> clipboard.setText(AnnotatedString(state.selectedText ?: state.text))
                FindInCode -> state.openFind(state.selectedText)
            }
        },
        modifier,
    ) {
        CodeEditor(state, Modifier.fillMaxSize(), showStatusBar = false, tag = tag)
    }
}

private val CopyCode = CommandId("copy")

private val FindInCode = CommandId("find")

private val CodeViewCommands = CommandSet(
    listOf(
        Command(CopyCode, "Copy", Chord.Of(KeyName.C, primary = true)),
        Command(FindInCode, "Find", Chord.Of(KeyName.F, primary = true)),
    ),
)
