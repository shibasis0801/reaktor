package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodePosition
import kotlin.test.*

class CodeRowsTest {
    @Test fun verticalMovementCrossesAShortWrappedRowAndRetainsTheDesiredColumn() {
        val state = CodeEditorState("one two three", CodeLanguage.Markdown)
        val rows = CodeRows(state.document, 6)
        state.moveTo(CodePosition(0, 13))
        state.moveVertical(-1, false, rows)
        assertEquals(1, rows.indexOf(state.caret))
        state.moveVertical(-1, false, rows)
        assertEquals(0, rows.indexOf(state.caret))
        state.moveVertical(2, false, rows)
        assertEquals(CodePosition(0, 13), state.caret)
    }

    @Test fun wrappingKeepsEveryCharacterAndPrefersWordBoundaries() {
        val doc = CodeDocument.of("one two three\n\n😀abcd")
        val rows = CodeRows(doc, 6)
        assertEquals(listOf("one ", "two ", "three", "", "😀abcd"),
            (0 until rows.size).map { rows[it].let { row -> doc.line(row.line).substring(row.start, row.end) } })
        assertEquals(1, rows.indexOf(CodePosition(0, 4)))
        assertEquals(CodePosition(0, 6), rows.positionAt(1, 2))
    }

    @Test fun wrappingNeverSplitsASurrogatePairAndUnwrappedRowsStayDirect() {
        val doc = CodeDocument.of("😀x\nend")
        val rows = CodeRows(doc, 1)
        assertEquals(CodeRow(0, 0, 2), rows[0])
        assertEquals(CodeRow(0, 2, 3), rows[1])
        val direct = CodeRows(doc)
        assertEquals(2, direct.size)
        assertEquals(CodeRow(1, 0, 3), direct[1])
    }
}
