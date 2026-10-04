package dev.shibasis.reaktor.ui.code

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shibasis.reaktor.code.CodeCompletion
import dev.shibasis.reaktor.code.CodeDiagnostic
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSource
import dev.shibasis.reaktor.code.CodeSpan

/**
 * Every editing rule in one testable place. The composable above it only draws what this says and
 * forwards keys and clicks back in, so the editor's behaviour is verifiable without a window.
 */
@Stable
class CodeEditorState(
    text: String = "",
    language: CodeLanguage = CodeLanguage.Plain,
    val uri: String = "reaktor://scratch",
    readOnly: Boolean = false,
) {
    var document by mutableStateOf(CodeDocument.of(text))
        private set

    var language by mutableStateOf(language)

    var readOnly by mutableStateOf(readOnly)

    var caret by mutableStateOf(CodePosition.Start)
        private set

    /** The fixed end of a selection. Null when the caret stands alone. */
    var anchor by mutableStateOf<CodePosition?>(null)
        private set

    /** Bumped by every edit, so a language server and a save indicator both have a clock. */
    var version by mutableIntStateOf(0)
        private set

    var diagnostics by mutableStateOf<List<CodeDiagnostic>>(emptyList())

    var findVisible by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findCaseSensitive by mutableStateOf(false)
    var findIndex by mutableIntStateOf(0)

    private var desiredColumn: Int? = null
    private var syntaxStates = IntArray(64)
    private var validStates = 1
    private val undoStack = ArrayDeque<UndoStep>()
    private val redoStack = ArrayDeque<UndoStep>()
    private var matchKey: Triple<Int, String, Boolean>? = null
    private var matchCache: List<CodeSpan> = emptyList()
    private var languageKey = language

    val selection: CodeSpan?
        get() = anchor?.takeIf { it != caret }?.let { CodeSpan(it, caret).ordered }

    val text: String get() = document.toString()

    val dirty: Boolean get() = undoStack.isNotEmpty()

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val source: CodeSource get() = CodeSource(uri, language.id, text, version)

    val selectedText: String? get() = selection?.let { document.slice(it) }

    // ---- syntax -------------------------------------------------------------------------------

    /** The lexer state entering [line], validated lazily and only as far up as the viewport needs. */
    fun entryState(line: Int): Int {
        if (language !== languageKey) { languageKey = language; validStates = 1 }
        if (line <= 0) return CodeLanguage.Code
        if (syntaxStates.size <= line) syntaxStates = syntaxStates.copyOf(maxOf(line + 1, syntaxStates.size * 2))
        validStates = validStates.coerceAtMost(document.lineCount)
        while (validStates <= line && validStates < document.lineCount) {
            syntaxStates[validStates] = language.state(document.line(validStates - 1), syntaxStates[validStates - 1])
            validStates++
        }
        return if (line < validStates) syntaxStates[line] else CodeLanguage.Code
    }

    fun spans(line: Int): List<CodeTokenSpan> = language.spans(document.line(line), entryState(line))

    private fun invalidateSyntaxFrom(line: Int) {
        validStates = validStates.coerceAtMost(maxOf(1, line + 1))
    }

    // ---- edits --------------------------------------------------------------------------------

    /**
     * The one mutation. Returns false when the surface is read-only, which callers use to decide
     * whether a keystroke was theirs to handle.
     */
    fun edit(span: CodeSpan, replacement: String, caretAfter: CodePosition? = null, coalesce: Boolean = false): Boolean {
        if (readOnly) return false
        val target = document.clamp(span).ordered
        val replaced = document.slice(target)
        push(UndoStep(target, replacement, replaced, caret, anchor), coalesce)
        document = document.replace(target, replacement)
        invalidateSyntaxFrom(target.start.line)
        caret = document.clamp(caretAfter ?: document.positionAfter(target.start, replacement))
        anchor = null
        desiredColumn = null
        version++
        return true
    }

    fun replaceAll(newText: String) {
        edit(CodeSpan(CodePosition.Start, document.end), newText, caretAfter = CodePosition.Start)
    }

    /** Drops the document and the history: a different file, not an edit to this one. */
    fun load(newText: String, newLanguage: CodeLanguage = language) {
        document = CodeDocument.of(newText)
        language = newLanguage
        languageKey = newLanguage
        validStates = 1
        undoStack.clear()
        redoStack.clear()
        caret = CodePosition.Start
        anchor = null
        desiredColumn = null
        diagnostics = emptyList()
        version++
    }

    fun type(input: String): Boolean {
        if (readOnly) return false
        val active = selection
        if (input.length == 1) {
            val char = input[0]
            val closer = Pairs[char]
            val text = document.line(caret.line)
            if (active != null && closer != null && active.singleLine) {
                val body = document.slice(active)
                return edit(active, "$char$body$closer", caretAfter = CodePosition(active.start.line, active.end.column + 1))
                    .also { anchor = CodePosition(active.start.line, active.start.column + 1) }
            }
            if (active == null && char in Closers && text.getOrNull(caret.column) == char) {
                caret = caret.copy(column = caret.column + 1)
                return true
            }
            if (active == null && closer != null && autoCloses(char, text)) {
                return edit(CodeSpan.at(caret), "$char$closer", caretAfter = caret.copy(column = caret.column + 1))
            }
        }
        return edit(active ?: CodeSpan.at(caret), input, coalesce = input.length == 1 && input[0].isWordChar())
    }

    fun newline(): Boolean {
        if (readOnly) return false
        val target = (selection ?: CodeSpan.at(caret)).ordered
        val indent = document.indentOf(target.start.line)
        val before = document.line(target.start.line).take(target.start.column).trimEnd()
        val after = document.line(target.end.line).drop(target.end.column).trimStart()
        val opens = before.lastOrNull() in setOf('{', '(', '[')
        val inner = if (opens) indent + language.indent else indent
        if (opens && after.firstOrNull() in setOf('}', ')', ']')) {
            return edit(target, "\n$inner\n$indent", caretAfter = CodePosition(target.start.line + 1, inner.length))
        }
        return edit(target, "\n$inner")
    }

    fun backspace(): Boolean {
        if (readOnly) return false
        selection?.let { return edit(it, "") }
        if (caret == CodePosition.Start) return true
        val text = document.line(caret.line)
        val before = text.take(caret.column)
        if (before.isNotEmpty() && before.isBlank()) {
            val width = maxOf(1, language.indent.length)
            val remove = if (before.last() == '\t') 1 else ((caret.column - 1) % width) + 1
            return edit(CodeSpan.of(caret.line, caret.column - remove, caret.column), "")
        }
        val previous = text.getOrNull(caret.column - 1)
        if (previous != null && Pairs[previous] != null && Pairs[previous] == text.getOrNull(caret.column)) {
            return edit(CodeSpan.of(caret.line, caret.column - 1, caret.column + 1), "")
        }
        val from = if (caret.column > 0) caret.copy(column = caret.column - 1) else document.lineEnd(caret.line - 1)
        return edit(CodeSpan(from, caret), "", caretAfter = from)
    }

    fun deleteForward(): Boolean {
        if (readOnly) return false
        selection?.let { return edit(it, "") }
        if (caret == document.end) return true
        val to = if (caret.column < document.line(caret.line).length) caret.copy(column = caret.column + 1)
        else CodePosition(caret.line + 1, 0)
        return edit(CodeSpan(caret, to), "", caretAfter = caret)
    }

    fun deleteWord(forward: Boolean): Boolean {
        if (readOnly) return false
        selection?.let { return edit(it, "") }
        val to = document.wordBoundary(caret, forward)
        if (to == caret) return true
        return edit(CodeSpan(caret, to), "", caretAfter = minOf(caret, to))
    }

    fun indent(add: Boolean): Boolean {
        if (readOnly) return false
        val active = selection
        if (active == null && add) return type(language.indent)
        val range = active?.ordered ?: CodeSpan.at(caret)
        val first = range.start.line
        val last = if (range.end.column == 0 && range.end.line > first) range.end.line - 1 else range.end.line
        val updated = (first..last).joinToString("\n") { index ->
            val line = document.line(index)
            if (add) language.indent + line
            else {
                var strip = 0
                while (strip < language.indent.length && strip < line.length && (line[strip] == ' ' || line[strip] == '\t')) strip++
                line.drop(strip)
            }
        }
        val span = CodeSpan(CodePosition(first, 0), document.lineEnd(last))
        val shift = if (add) language.indent.length else -language.indent.length
        val applied = edit(span, updated, caretAfter = CodePosition(last, maxOf(0, caret.column + shift)))
        if (applied && active != null) selectLines(first, last)
        return applied
    }

    fun toggleComment(): Boolean {
        if (readOnly) return false
        val prefix = language.lineComment ?: return false
        val range = (selection ?: CodeSpan.at(caret)).ordered
        val first = range.start.line
        val last = if (range.end.column == 0 && range.end.line > first) range.end.line - 1 else range.end.line
        val hadSelection = selection != null
        val body = (first..last).map { document.line(it) }
        val meaningful = body.filter { it.isNotBlank() }
        val commented = meaningful.isNotEmpty() && meaningful.all { it.trimStart().startsWith(prefix) }
        val updated = body.joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                commented -> {
                    val at = line.indexOf(prefix)
                    if (at < 0) line
                    else line.removeRange(at, at + prefix.length + if (line.startsWith("$prefix ", at)) 1 else 0)
                }
                else -> {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    indent + prefix + " " + line.drop(indent.length)
                }
            }
        }
        val span = CodeSpan(CodePosition(first, 0), document.lineEnd(last))
        val applied = edit(span, updated, caretAfter = document.clamp(caret))
        if (applied && hadSelection) selectLines(first, last)
        return applied
    }

    fun duplicateLines(): Boolean {
        if (readOnly) return false
        val range = (selection ?: CodeSpan.at(caret)).ordered
        val block = (range.start.line..range.end.line).joinToString("\n") { document.line(it) }
        val at = document.lineEnd(range.end.line)
        return edit(CodeSpan(at, at), "\n$block", caretAfter = CodePosition(caret.line + (range.end.line - range.start.line) + 1, caret.column))
    }

    fun deleteLines(): Boolean {
        if (readOnly) return false
        val range = (selection ?: CodeSpan.at(caret)).ordered
        val span = if (range.end.line < document.lastLine)
            CodeSpan(CodePosition(range.start.line, 0), CodePosition(range.end.line + 1, 0))
        else CodeSpan(
            if (range.start.line > 0) document.lineEnd(range.start.line - 1) else CodePosition.Start,
            document.lineEnd(range.end.line),
        )
        return edit(span, "", caretAfter = CodePosition(range.start.line, 0))
    }

    fun moveLines(up: Boolean): Boolean {
        if (readOnly) return false
        val range = (selection ?: CodeSpan.at(caret)).ordered
        val first = range.start.line
        val last = range.end.line
        if (up && first == 0) return true
        if (!up && last >= document.lastLine) return true
        val hadSelection = selection != null
        val block = (first..last).map { document.line(it) }
        val neighbour = if (up) first - 1 else last + 1
        val reordered = if (up) block + document.line(neighbour) else listOf(document.line(neighbour)) + block
        val from = minOf(first, neighbour)
        val to = maxOf(last, neighbour)
        val span = CodeSpan(CodePosition(from, 0), document.lineEnd(to))
        val shift = if (up) -1 else 1
        val applied = edit(span, reordered.joinToString("\n"), caretAfter = CodePosition(caret.line + shift, caret.column))
        if (applied && hadSelection) selectLines(first + shift, last + shift)
        return applied
    }

    fun undo(): Boolean {
        val step = undoStack.removeLastOrNull() ?: return false
        val inserted = CodeSpan(step.span.start, document.positionAfter(step.span.start, step.inserted))
        document = document.replace(inserted, step.replaced)
        invalidateSyntaxFrom(step.span.start.line)
        caret = document.clamp(step.caretBefore)
        anchor = step.anchorBefore?.let { document.clamp(it) }
        redoStack.addLast(step)
        version++
        return true
    }

    fun redo(): Boolean {
        val step = redoStack.removeLastOrNull() ?: return false
        document = document.replace(step.span, step.inserted)
        invalidateSyntaxFrom(step.span.start.line)
        caret = document.positionAfter(step.span.start, step.inserted)
        anchor = null
        undoStack.addLast(step)
        version++
        return true
    }

    fun applyCompletion(item: CodeCompletion): Boolean {
        val prefix = document.prefixAt(caret)
        val span = item.replaces ?: CodeSpan(caret.copy(column = caret.column - prefix.length), caret)
        return edit(span, item.insert)
    }

    // ---- movement and selection ---------------------------------------------------------------

    fun moveTo(position: CodePosition, extend: Boolean = false) {
        anchor = if (extend) anchor ?: caret else null
        caret = document.clamp(position)
        desiredColumn = null
    }

    fun moveHorizontal(forward: Boolean, extend: Boolean) {
        val active = selection
        if (!extend && active != null) {
            anchor = null
            caret = if (forward) active.end else active.start
            desiredColumn = null
            return
        }
        val next = when {
            forward && caret.column < document.line(caret.line).length -> caret.copy(column = caret.column + 1)
            forward && caret.line < document.lastLine -> CodePosition(caret.line + 1, 0)
            !forward && caret.column > 0 -> caret.copy(column = caret.column - 1)
            !forward && caret.line > 0 -> document.lineEnd(caret.line - 1)
            else -> caret
        }
        moveTo(next, extend)
    }

    fun moveVertical(delta: Int, extend: Boolean) {
        val column = desiredColumn ?: caret.column
        anchor = if (extend) anchor ?: caret else null
        caret = document.clamp(CodePosition((caret.line + delta).coerceIn(0, document.lastLine), column))
        desiredColumn = column
    }

    fun moveWord(forward: Boolean, extend: Boolean) = moveTo(document.wordBoundary(caret, forward), extend)

    /** Home lands on the first non-blank, then on the margin, which is how a code editor behaves. */
    fun moveLineEdge(toStart: Boolean, extend: Boolean) {
        val line = document.line(caret.line)
        val target = if (!toStart) line.length else {
            val firstText = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
            if (caret.column == firstText) 0 else firstText
        }
        moveTo(CodePosition(caret.line, target), extend)
    }

    fun moveDocumentEdge(toStart: Boolean, extend: Boolean) =
        moveTo(if (toStart) CodePosition.Start else document.end, extend)

    fun selectAll() {
        anchor = CodePosition.Start
        caret = document.end
    }

    fun selectWordAt(position: CodePosition) {
        val word = document.wordAt(position) ?: return moveTo(position)
        anchor = word.start
        caret = word.end
    }

    fun selectLines(first: Int, last: Int) {
        anchor = CodePosition(first.coerceIn(0, document.lastLine), 0)
        caret = document.lineEnd(last.coerceIn(0, document.lastLine))
    }

    fun clearSelection() { anchor = null }

    /** The columns of [line] covered by the selection, or null. The end may exceed the line to show a swallowed break. */
    fun selectionOn(line: Int): IntRange? {
        val span = selection ?: return null
        if (line < span.start.line || line > span.end.line) return null
        val from = if (line == span.start.line) span.start.column else 0
        val to = if (line == span.end.line) span.end.column else document.line(line).length + 1
        return from until maxOf(from, to)
    }

    // ---- find ---------------------------------------------------------------------------------

    val findMatches: List<CodeSpan>
        get() {
            val key = Triple(version, findQuery, findCaseSensitive)
            if (matchKey != key) {
                matchCache = if (findQuery.isBlank()) emptyList() else document.matches(findQuery, findCaseSensitive)
                matchKey = key
            }
            return matchCache
        }

    fun findNext(forward: Boolean = true) {
        val matches = findMatches
        if (matches.isEmpty()) return
        val index = if (forward) matches.indexOfFirst { it.start > caret }.takeIf { it >= 0 } ?: 0
        else matches.indexOfLast { it.start < caret }.takeIf { it >= 0 } ?: matches.lastIndex
        findIndex = index
        anchor = matches[index].start
        caret = matches[index].end
    }

    fun openFind(seed: String? = null) {
        findVisible = true
        seed?.takeIf { it.isNotBlank() && !it.contains('\n') }?.let { findQuery = it }
    }

    fun closeFind() {
        findVisible = false
    }

    fun replaceCurrent(with: String): Boolean {
        val match = findMatches.getOrNull(findIndex) ?: return false
        return edit(match, with)
    }

    fun replaceAllMatches(with: String): Int {
        val matches = findMatches
        if (matches.isEmpty() || readOnly) return 0
        matches.asReversed().forEach { edit(it, with) }
        return matches.size
    }

    /** Null for a buffer small enough to read whole; otherwise a window around the caret. */
    fun completionScanRange(): IntRange? =
        if (document.lineCount <= WholeBufferScanLines) null
        else (caret.line - CompletionScanRadius)..(caret.line + CompletionScanRadius)

    fun goToLine(line: Int) = moveTo(CodePosition((line - 1).coerceIn(0, document.lastLine), 0))

    // ---- internals ----------------------------------------------------------------------------

    private fun autoCloses(char: Char, line: String): Boolean {
        val next = line.getOrNull(caret.column)
        val quote = char == '"' || char == '\'' || char == '`'
        if (quote && line.getOrNull(caret.column - 1)?.isWordChar() == true) return false
        return next == null || next.isWhitespace() || next in Closers || next == ','
    }

    private fun push(step: UndoStep, coalesce: Boolean) {
        redoStack.clear()
        val last = undoStack.lastOrNull()
        // A run of word characters typed straight on undoes as one word, not one keystroke.
        if (last != null && coalesce && last.replaced.isEmpty() && step.replaced.isEmpty() &&
            last.inserted.lastOrNull()?.isWordChar() == true &&
            document.positionAfter(last.span.start, last.inserted) == step.span.start
        ) {
            undoStack[undoStack.lastIndex] = last.copy(inserted = last.inserted + step.inserted)
            return
        }
        undoStack.addLast(step)
        if (undoStack.size > UndoLimit) undoStack.removeFirst()
    }

    private data class UndoStep(
        val span: CodeSpan,
        val inserted: String,
        val replaced: String,
        val caretBefore: CodePosition,
        val anchorBefore: CodePosition?,
    )

    companion object {
        private const val UndoLimit = 400
        private const val WholeBufferScanLines = 4_000
        private const val CompletionScanRadius = 500
        private val Pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
        private val Closers = setOf(')', ']', '}', '"', '\'', '`')
    }
}
