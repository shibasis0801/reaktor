package dev.shibasis.reaktor.ui.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * A read-only code plane over text a pane already holds: payloads, plans, responses, command
 * output. Reloads when [text] changes, and brings the gutter, find and virtualisation with it, so
 * a caller never needs to truncate a large payload by hand.
 *
 * It scrolls vertically like [CodeEditor], so it needs a bounded height — inside a `LazyColumn`
 * item or an unbounded `Column`, give it an explicit one.
 */
@Composable
fun CodeViewer(
    text: String,
    modifier: Modifier = Modifier,
    language: CodeLanguage = CodeLanguage.sniff(text),
    showGutter: Boolean = true,
    showStatusBar: Boolean = false,
    tag: String = "code-viewer",
) {
    val state = remember(tag) { CodeEditorState("", language, "reaktor://view/$tag", readOnly = true) }
    LaunchedEffect(text, language) { state.load(text, language) }
    CodeEditor(state, modifier, showGutter = showGutter, showStatusBar = showStatusBar, tag = tag)
}
