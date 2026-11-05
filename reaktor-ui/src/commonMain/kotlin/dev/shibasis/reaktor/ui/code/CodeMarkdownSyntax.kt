package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan

internal data class MarkdownFence(
    val marker: Char,
    val width: Int,
    val language: CodeLanguage,
    val nested: CodeLexState = CodeLanguage.Code,
) : CodeLexState {
    fun closes(line: String): Boolean {
        val text = line.dropWhile { it == ' ' }.takeIf { line.length - it.length <= 3 } ?: return false
        return text.takeWhile { it == marker }.length >= width && text.dropWhile { it == marker }.isBlank()
    }
}

internal object MarkdownSyntax {
    val heading = Regex("^ {0,3}(#{1,6})[ \\t]+(.*)$")
    val list = Regex("^([ \\t]*(?:> ?)*)([-+*]|[0-9]+[.)])([ \\t]+)(?:\\[([ xX])\\][ \\t]+)?")
    val inline = Regex("\\\\.|(`+)([^`]*?)\\1|\\*\\*([^*]+)\\*\\*|__([^_]+)__|\\*([^*]+)\\*|_([^_]+)_|~~([^~]+)~~|\\[([^\\[\\]]+)\\]\\((https?://[^\\s)]+)\\)")

    fun fence(line: String): MarkdownFence? {
        val text = line.dropWhile { it == ' ' }.takeIf { line.length - it.length <= 3 } ?: return null
        val marker = text.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val width = text.takeWhile { it == marker }.length
        if (width < 3 || (marker == '`' && '`' in text.drop(width))) return null
        val info = text.drop(width).trim().substringBefore(' ').substringBefore('\t')
        return MarkdownFence(marker, width, CodeLanguage.forId(info))
    }

    fun lex(line: String, entry: CodeLexState, emit: (Int, Int, CodeToken) -> Unit): CodeLexState {
        if (entry is MarkdownFence) {
            if (entry.closes(line)) {
                emit(0, line.length, CodeToken.Operator)
                return CodeLanguage.Code
            }
            val next = entry.language.lex(line, entry.nested, emit)
            return if (next == entry.nested) entry else entry.copy(nested = next)
        }
        fence(line)?.let {
            emit(0, line.length, CodeToken.Operator)
            return it
        }
        val title = heading.matchEntire(line)
        if (title != null) {
            emit(0, line.length, CodeToken.Keyword)
            return CodeLanguage.Code
        }
        val marker = list.find(line)
        val start = when {
            marker != null -> marker.value.length.also { emit(0, it, CodeToken.Operator) }
            line.trimStart().startsWith('>') -> (line.indexOf('>') + 1).also { emit(0, it, CodeToken.Operator) }
            else -> 0
        }
        inline.findAll(line, start).forEach { match ->
            val token = when {
                match.groups[1] != null -> CodeToken.Text
                match.groups[8] != null -> CodeToken.Annotation
                else -> CodeToken.Doc
            }
            emit(match.range.first, match.range.last + 1, token)
        }
        return CodeLanguage.Code
    }

    fun newline(state: CodeEditorState): Boolean {
        val target = (state.selection ?: CodeSpan.at(state.caret)).ordered
        val before = state.document.line(target.start.line).take(target.start.column)
        val marker = list.find(before)
        if (marker != null) {
            val prefix = marker.groupValues[1]
            if (before.drop(marker.value.length).isBlank()) {
                return state.edit(CodeSpan(CodePosition(target.start.line, prefix.length), target.end), "")
            }
            val bullet = marker.groupValues[2]
            val next = if (bullet.first().isDigit()) {
                bullet.dropLast(1).toLongOrNull()?.takeIf { it < Long.MAX_VALUE }?.let { "${it + 1}${bullet.last()}" } ?: bullet
            } else bullet
            val task = if (marker.groups[4] != null) "[ ] " else ""
            return state.edit(target, "\n$prefix$next${marker.groupValues[3]}$task")
        }
        val quote = before.takeWhile { it == ' ' || it == '\t' || it == '>' }
        val prefix = if ('>' in quote) quote.trimEnd() + " " else state.document.indentOf(target.start.line)
        return state.edit(target, "\n$prefix")
    }
}
