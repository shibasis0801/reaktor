package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.ui.code.CodeLanguage
import dev.shibasis.reaktor.ui.code.CodePresentation
import dev.shibasis.reaktor.ui.code.CodeViewer

@Composable
fun SignalMarkdown(text: String, modifier: Modifier = Modifier) =
    CodeViewer(text, modifier, language = CodeLanguage.Markdown, showGutter = false,
        presentation = CodePresentation.Preview, tag = "agent-markdown")
