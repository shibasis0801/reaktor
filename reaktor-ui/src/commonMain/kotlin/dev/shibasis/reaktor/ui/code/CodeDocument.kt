package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan

/**
 * An immutable line buffer. Everything the editor does is expressed as one [replace] over a span,
 * which is also the shape of an LSP incremental change, so the server and the screen never drift.
 */
class CodeDocument private constructor(val lines: List<String>, val lineSeparator: String) {
    val lineCount get() = lines.size
    val lastLine get() = lines.lastIndex
    val end get() = CodePosition(lastLine, lines.last().length)
    val longestLine get() = lines.maxOf { it.length }

    fun line(index: Int): String = lines.getOrElse(index) { "" }

    fun lineEnd(index: Int) = CodePosition(index, line(index).length)

    fun lineSpan(index: Int) = CodeSpan(CodePosition(index, 0), lineEnd(index))

    fun indentOf(index: Int): String = line(index).takeWhile { it == ' ' || it == '\t' }

    fun clamp(position: CodePosition): CodePosition {
        val line = position.line.coerceIn(0, lastLine)
        return CodePosition(line, position.column.coerceIn(0, this.line(line).length))
    }

    fun clamp(span: CodeSpan) = CodeSpan(clamp(span.start), clamp(span.end))

    fun slice(span: CodeSpan): String {
        val (start, end) = clamp(span).ordered.let { it.start to it.end }
        if (start.line == end.line) return line(start.line).substring(start.column, end.column)
        return buildString {
            append(line(start.line).substring(start.column))
            for (index in start.line + 1 until end.line) { append('\n'); append(line(index)) }
            append('\n')
            append(line(end.line).take(end.column))
        }
    }

    fun replace(span: CodeSpan, with: String): CodeDocument {
        val (start, end) = clamp(span).ordered.let { it.start to it.end }
        val stitched = line(start.line).take(start.column) + with + line(end.line).drop(end.column)
        val replacement = stitched.split('\n')
        val updated = ArrayList<String>(lines.size - (end.line - start.line) + replacement.size)
        updated.addAll(lines.subList(0, start.line))
        updated.addAll(replacement)
        updated.addAll(lines.subList(minOf(end.line + 1, lines.size), lines.size))
        return CodeDocument(updated, lineSeparator)
    }

    /** Where the caret lands after [inserted] is typed at [start]. */
    fun positionAfter(start: CodePosition, inserted: String): CodePosition {
        val breaks = inserted.count { it == '\n' }
        return if (breaks == 0) CodePosition(start.line, start.column + inserted.length)
        else CodePosition(start.line + breaks, inserted.length - inserted.lastIndexOf('\n') - 1)
    }

    fun wordAt(position: CodePosition): CodeSpan? {
        val text = line(position.line)
        val column = position.column.coerceIn(0, text.length)
        var start = column
        var end = column
        while (start > 0 && text[start - 1].isWordChar()) start--
        while (end < text.length && text[end].isWordChar()) end++
        return if (start == end) null else CodeSpan.of(position.line, start, end)
    }

    /** The identifier prefix immediately left of the caret, which is what completion filters on. */
    fun prefixAt(position: CodePosition): String {
        val text = line(position.line)
        val column = position.column.coerceIn(0, text.length)
        var start = column
        while (start > 0 && text[start - 1].isWordChar()) start--
        return text.substring(start, column)
    }

    fun wordBoundary(position: CodePosition, forward: Boolean): CodePosition {
        val here = clamp(position)
        val text = line(here.line)
        if (forward) {
            if (here.column >= text.length) return if (here.line < lastLine) CodePosition(here.line + 1, 0) else here
            var index = here.column
            while (index < text.length && text[index].isWhitespace()) index++
            if (index < text.length) {
                val word = text[index].isWordChar()
                while (index < text.length && !text[index].isWhitespace() && text[index].isWordChar() == word) index++
            }
            return CodePosition(here.line, index)
        }
        if (here.column == 0) return if (here.line > 0) lineEnd(here.line - 1) else here
        var index = here.column
        while (index > 0 && text[index - 1].isWhitespace()) index--
        if (index > 0) {
            val word = text[index - 1].isWordChar()
            while (index > 0 && !text[index - 1].isWhitespace() && text[index - 1].isWordChar() == word) index--
        }
        return CodePosition(here.line, index)
    }

    /** Single-line matches only: find in this editor serves a scan, not a refactor. */
    fun matches(query: String, caseSensitive: Boolean = false, wholeWord: Boolean = false): List<CodeSpan> {
        if (query.isEmpty() || query.contains('\n')) return emptyList()
        return buildList {
            lines.forEachIndexed { index, text ->
                var from = 0
                while (from <= text.length - query.length) {
                    val at = text.indexOf(query, from, ignoreCase = !caseSensitive)
                    if (at < 0) break
                    val boundedLeft = at == 0 || !text[at - 1].isWordChar()
                    val boundedRight = at + query.length == text.length || !text[at + query.length].isWordChar()
                    if (!wholeWord || (boundedLeft && boundedRight)) add(CodeSpan.of(index, at, at + query.length))
                    from = at + maxOf(1, query.length)
                }
            }
        }
    }

    /**
     * Distinct identifiers, the document's own completion vocabulary. [within] bounds the scan so a
     * large file does not re-read itself on every keystroke.
     */
    fun identifiers(minimumLength: Int = 3, within: IntRange? = null): Set<String> = buildSet {
        val scanned = within?.let { lines.subList(it.first.coerceIn(0, lines.size), (it.last + 1).coerceIn(0, lines.size)) } ?: lines
        scanned.forEach { text ->
            var index = 0
            while (index < text.length) {
                if (text[index].isLetter() || text[index] == '_') {
                    var end = index
                    while (end < text.length && text[end].isWordChar()) end++
                    if (end - index >= minimumLength) add(text.substring(index, end))
                    index = end
                } else index++
            }
        }
    }

    override fun toString() = lines.joinToString("\n")

    /** The text as it should be written back, with the separator the file arrived with. */
    fun render() = lines.joinToString(lineSeparator)

    companion object {
        fun of(text: String): CodeDocument {
            val separator = if (text.contains("\r\n")) "\r\n" else "\n"
            return CodeDocument(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'), separator)
        }
    }
}

internal fun Char.isWordChar() = isLetterOrDigit() || this == '_'
