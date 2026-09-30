package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import dev.shibasis.reaktor.ui.code.CodeViewer

/** Bounded agent prose: headings, paragraphs, lists, quotes, code and safe web links; HTML stays text. */
@Composable
fun SignalMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { markdownBlocks(text) }
    val fonts = LocalMachineSignalFonts.current
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
            blocks.forEachIndexed { index, block ->
                if (block.kind == "code") {
                    CodeViewer(block.text, Modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.toolbarHeight,
                        max = MachineSignal.Editor.toolbarHeight * 6).height(MachineSignal.Editor.statusHeight * (block.text.lines().size + 1)),
                        showGutter = false, tag = "prose-code-$index")
                } else BasicText(
                    inlineMarkdown(block.text, fonts),
                    style = TextStyle(color = MachineSignal.Editor.Text, fontFamily = fonts.ui,
                        fontSize = if (block.kind == "heading") MachineSignal.Type.title else MachineSignal.Type.body,
                        fontWeight = if (block.kind == "heading") FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = (if (block.kind == "heading") MachineSignal.Type.title else MachineSignal.Type.body) * MachineSignal.Editor.Code.lineHeight),
                    modifier = Modifier.fillMaxWidth().then(if (block.kind == "quote") Modifier.background(MachineSignal.Editor.Surface).padding(MachineSignal.Space.s3) else Modifier),
                )
            }
        }
    }
}

internal data class MarkdownBlock(val kind: String, val text: String)
internal fun markdownBlocks(text: String): List<MarkdownBlock> = buildList {
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i++]
        when {
            line.isBlank() -> Unit
            line.startsWith("```") -> {
                val code = mutableListOf<String>()
                while (i < lines.size && !lines[i].startsWith("```")) code += lines[i++]
                if (i < lines.size) i++
                add(MarkdownBlock("code", code.joinToString("\n")))
            }
            Regex("^#{1,6} ").containsMatchIn(line) -> add(MarkdownBlock("heading", line.substringAfter(' ')))
            line.startsWith("> ") -> add(MarkdownBlock("quote", line.drop(2)))
            else -> {
                val paragraph = mutableListOf(line)
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("```") && !lines[i].startsWith('#') && !lines[i].startsWith("> ")) paragraph += lines[i++]
                add(MarkdownBlock("paragraph", paragraph.joinToString("\n") { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }))
            }
        }
    }
}

internal fun inlineMarkdown(text: String, fonts: MachineSignalFonts) = buildAnnotatedString {
    val pattern = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\[[^\\]]+\\]\\(https?://[^)]+\\))")
    var offset = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(offset, match.range.first))
        val token = match.value
        when {
            token.startsWith('`') -> withStyle(SpanStyle(fontFamily = fonts.mono, color = MachineSignal.Editor.Source, background = MachineSignal.Editor.Raised)) { append(token.drop(1).dropLast(1)) }
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(token.drop(2).dropLast(2)) }
            else -> {
                val end = token.indexOf("](")
                withLink(LinkAnnotation.Url(token.substring(end + 2).dropLast(1), TextLinkStyles(SpanStyle(color = MachineSignal.Editor.Source)))) { append(token.substring(1, end)) }
            }
        }
        offset = match.range.last + 1
    }
    append(text.substring(offset))
}
