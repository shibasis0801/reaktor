package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodePosition

internal data class CodeRow(val line: Int, val start: Int, val end: Int)

internal class CodeRows(private val document: CodeDocument, columns: Int = Int.MAX_VALUE) {
    private val wrapped: List<CodeRow>? = if (columns == Int.MAX_VALUE) null else buildList {
        document.lines.forEachIndexed { line, text ->
            var start = 0
            do {
                var end = minOf(text.length, start + columns.coerceAtLeast(1))
                if (end < text.length) {
                    if (text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
                    if (end == start) end = minOf(text.length, start + 2)
                    var space = end - 1
                    while (space > start && text[space] != ' ') space--
                    if (space > start) end = space + 1
                }
                add(CodeRow(line, start, end))
                start = end
            } while (start < text.length)
        }
    }

    val size get() = wrapped?.size ?: document.lineCount

    operator fun get(index: Int): CodeRow = wrapped?.get(index)
        ?: CodeRow(index, 0, document.line(index).length)

    fun indexOf(position: CodePosition): Int {
        val rows = wrapped ?: return position.line.coerceIn(0, document.lastLine)
        var low = 0
        var high = rows.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            val row = rows[middle]
            if (row.line < position.line || row.line == position.line && row.start <= position.column) low = middle
            else high = middle - 1
        }
        return low
    }

    fun positionAt(index: Int, column: Int): CodePosition {
        val row = get(index.coerceIn(0, size - 1))
        return document.clamp(CodePosition(row.line, (row.start + column).coerceIn(row.start, row.end)))
    }
}
