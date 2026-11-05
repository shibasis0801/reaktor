package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import dev.shibasis.reaktor.ui.machinesignal.LocalMachineSignalFonts
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalColors
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalFonts
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSnapshot
import dev.shibasis.reaktor.ui.machinesignal.colors

enum class CodePresentation { Source, Preview }

internal enum class MarkdownKind { Paragraph, Heading, List, Quote, Code }

internal data class MarkdownBlock(
    val kind: MarkdownKind,
    val text: String,
    val firstLine: Int,
    val lastLine: Int,
    val language: CodeLanguage = CodeLanguage.Plain,
)

internal class MarkdownProjection {
    private var source: CodeDocument? = null
    private var cached = emptyList<MarkdownBlock>()
    private var dirty = 0

    fun invalidate(line: Int) { dirty = minOf(dirty, line) }

    fun blocks(document: CodeDocument): List<MarkdownBlock> {
        if (source === document) return cached
        val touched = cached.indexOfFirst { it.lastLine >= dirty }.let { if (it < 0) cached.size else it }
        val keep = (touched - 1).coerceAtLeast(0)
        val from = cached.getOrNull(keep)?.firstLine ?: 0
        cached = cached.take(keep) + markdownBlocks(document, from)
        source = document
        dirty = document.lineCount
        return cached
    }
}

internal fun markdownBlocks(document: CodeDocument, from: Int = 0): List<MarkdownBlock> = buildList {
    var line = from
    while (line < document.lineCount) {
        val first = line
        val text = document.line(line++)
        if (text.isBlank()) continue
        val fence = MarkdownSyntax.fence(text)
        val heading = MarkdownSyntax.heading.matchEntire(text)
        when {
            fence != null -> {
                val start = line
                while (line < document.lineCount && !fence.closes(document.line(line))) line++
                val body = document.lines.subList(start, line).joinToString("\n")
                if (line < document.lineCount) line++
                add(MarkdownBlock(MarkdownKind.Code, body, first, line - 1, fence.language))
            }
            heading != null -> add(MarkdownBlock(MarkdownKind.Heading, heading.groupValues[2], first, first))
            else -> {
                val kind = markdownKind(text)
                while (line < document.lineCount) {
                    val next = document.line(line)
                    if (next.isBlank() || MarkdownSyntax.fence(next) != null ||
                        MarkdownSyntax.heading.matches(next) || markdownKind(next) != kind) break
                    line++
                }
                val body = (first until line).joinToString("\n") { index ->
                    val value = document.line(index)
                    when (kind) {
                        MarkdownKind.Quote -> value.trimStart().drop(1).removePrefix(" ")
                        MarkdownKind.List -> MarkdownSyntax.list.find(value)?.let { marker ->
                            val bullet = marker.groupValues[2]
                            val task = marker.groups[4]?.value?.let { if (it.equals("x", true)) "☑ " else "☐ " }.orEmpty()
                            val prefix = marker.groupValues[1] + when {
                                bullet.first().isDigit() -> "$bullet "
                                task.isNotEmpty() -> ""
                                else -> "• "
                            }
                            prefix + task + value.drop(marker.value.length)
                        } ?: value
                        else -> value
                    }
                }
                add(MarkdownBlock(kind, body, first, line - 1))
            }
        }
    }
}

private fun markdownKind(line: String): MarkdownKind = when {
    line.trimStart().startsWith('>') -> MarkdownKind.Quote
    MarkdownSyntax.list.containsMatchIn(line) -> MarkdownKind.List
    else -> MarkdownKind.Paragraph
}

internal fun inlineMarkdown(text: String, fonts: MachineSignalFonts, colors: MachineSignalColors) = buildAnnotatedString {
    var offset = 0
    MarkdownSyntax.inline.findAll(text).forEach { match ->
        append(text.substring(offset, match.range.first))
        val groups = match.groupValues
        when {
            match.value.startsWith('\\') -> append(match.value.drop(1))
            match.groups[1] != null && groups[2].isEmpty() -> append(match.value)
            match.groups[1] != null -> withStyle(SpanStyle(fontFamily = fonts.mono, color = colors.source, background = colors.raised)) { append(groups[2]) }
            match.groups[3] != null || match.groups[4] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(groups[3].ifEmpty { groups[4] }) }
            match.groups[5] != null || match.groups[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(groups[5].ifEmpty { groups[6] }) }
            match.groups[7] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(groups[7]) }
            else -> withLink(LinkAnnotation.Url(groups[9], TextLinkStyles(SpanStyle(color = colors.source)))) { append(groups[8]) }
        }
        offset = match.range.last + 1
    }
    append(text.substring(offset))
}

@Composable
internal fun MarkdownPreview(state: CodeEditorState, modifier: Modifier, tag: String, scrollable: Boolean = false) {
    val colors = MachineSignal.colors
    val fonts = (dev.shibasis.reaktor.surface.compose.LocalThemeSnapshot.current as? MachineSignalSnapshot)?.fonts
        ?: LocalMachineSignalFonts.current
    val blocks = state.markdown.blocks(state.document)
    val content: @Composable (MarkdownBlock) -> Unit = { block ->
        if (block.kind == MarkdownKind.Code) {
            CodeViewer(block.text, Modifier.fillMaxWidth().heightIn(min = MachineSignal.Editor.toolbarHeight,
                max = MachineSignal.Editor.toolbarHeight * 6)
                .height(MachineSignal.Editor.statusHeight * (block.lastLine - block.firstLine + 1)),
                language = block.language, showGutter = false, tag = "$tag-code-${block.firstLine}")
        } else {
            val title = block.kind == MarkdownKind.Heading
            val size = if (title) MachineSignal.Type.title else MachineSignal.Type.body
            val annotated = remember(block.text, fonts, colors) { inlineMarkdown(block.text, fonts, colors) }
            BasicText(annotated, style = TextStyle(color = colors.textStrong, fontFamily = fonts.ui,
                fontSize = size, fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = size * MachineSignal.Editor.Code.lineHeight),
                modifier = Modifier.fillMaxWidth().then(if (block.kind == MarkdownKind.Quote)
                    Modifier.background(colors.surface).padding(MachineSignal.Space.s3) else Modifier))
        }
    }
    SelectionContainer(modifier.testTag("$tag-preview")) {
        if (scrollable) LazyColumn(verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
            items(blocks, key = { it.firstLine }) { content(it) }
        } else Column(verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3)) {
            blocks.forEach { block -> key(block.firstLine) { content(block) } }
        }
    }
}
