package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodeCompletion
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeEditorStateTest {
    private fun state(text: String = "", language: CodeLanguage = CodeLanguage.Kotlin) =
        CodeEditorState(text, language)

    private fun CodeEditorState.typeAll(input: String) = input.forEach { type(it.toString()) }

    @Test fun typingLandsTheCaretAfterTheInsertion() {
        val editor = state()
        editor.typeAll("val")
        assertEquals("val", editor.text)
        assertEquals(CodePosition(0, 3), editor.caret)
    }

    @Test fun bracketsAutoCloseAndTypingTheCloserSkipsOver() {
        val editor = state()
        editor.type("(")
        assertEquals("()", editor.text)
        assertEquals(CodePosition(0, 1), editor.caret)
        editor.type(")")
        assertEquals("()", editor.text)
        assertEquals(CodePosition(0, 2), editor.caret)
    }

    @Test fun quotesDoNotAutoCloseAfterAWord() {
        val editor = state("ab")
        editor.moveTo(CodePosition(0, 2))
        editor.type("\"")
        assertEquals("ab\"", editor.text)
    }

    @Test fun typingOverASelectionWrapsItInTheBracketPair() {
        val editor = state("name")
        editor.selectAll()
        editor.type("(")
        assertEquals("(name)", editor.text)
    }

    @Test fun newlineKeepsIndentAndOpensABlock() {
        val editor = state("    if (x) {")
        editor.moveTo(editor.document.end)
        editor.newline()
        assertEquals(listOf("    if (x) {", "        "), editor.document.lines)
        assertEquals(CodePosition(1, 8), editor.caret)
    }

    @Test fun newlineBetweenBracesPutsTheCloserOnItsOwnLine() {
        val editor = state("  fun f() {}")
        editor.moveTo(CodePosition(0, 11))
        editor.newline()
        assertEquals(listOf("  fun f() {", "      ", "  }"), editor.document.lines)
        assertEquals(CodePosition(1, 6), editor.caret)
    }

    @Test fun backspaceRemovesAWholeIndentStepInsideLeadingSpace() {
        val editor = state("        x")
        editor.moveTo(CodePosition(0, 8))
        editor.backspace()
        assertEquals("    x", editor.text)
    }

    @Test fun backspaceRemovesBothHalvesOfAnAutoClosedPair() {
        val editor = state()
        editor.type("(")
        editor.backspace()
        assertEquals("", editor.text)
    }

    @Test fun backspaceJoinsLines() {
        val editor = state("a\nb")
        editor.moveTo(CodePosition(1, 0))
        editor.backspace()
        assertEquals("ab", editor.text)
        assertEquals(CodePosition(0, 1), editor.caret)
    }

    @Test fun undoCoalescesAWordButNotAcrossAGap() {
        val editor = state()
        editor.typeAll("alpha beta")
        assertEquals("alpha beta", editor.text)
        editor.undo()
        assertEquals("alpha ", editor.text)
        editor.undo()
        assertEquals("alpha", editor.text)
    }

    @Test fun redoReplaysWhatUndoTookBack() {
        val editor = state()
        editor.typeAll("abc")
        editor.undo()
        assertEquals("", editor.text)
        editor.redo()
        assertEquals("abc", editor.text)
        assertEquals(CodePosition(0, 3), editor.caret)
    }

    @Test fun undoRestoresTheCaretAndTheSelection() {
        val editor = state("hello world")
        editor.selectLines(0, 0)
        editor.type("x")
        assertEquals("x", editor.text)
        editor.undo()
        assertEquals("hello world", editor.text)
        assertEquals(CodePosition(0, 11), editor.caret)
        assertEquals(CodePosition(0, 0), editor.anchor)
    }

    @Test fun indentAndOutdentActOnEverySelectedLine() {
        val editor = state("a\nb")
        editor.selectAll()
        editor.indent(add = true)
        assertEquals(listOf("    a", "    b"), editor.document.lines)
        editor.indent(add = false)
        assertEquals(listOf("a", "b"), editor.document.lines)
    }

    @Test fun toggleCommentIsSymmetricAndPreservesIndent() {
        val editor = state("    val a = 1\n    val b = 2")
        editor.selectAll()
        editor.toggleComment()
        assertEquals(listOf("    // val a = 1", "    // val b = 2"), editor.document.lines)
        editor.selectAll()
        editor.toggleComment()
        assertEquals(listOf("    val a = 1", "    val b = 2"), editor.document.lines)
    }

    @Test fun toggleCommentSkipsBlankLines() {
        val editor = state("a\n\nb")
        editor.selectAll()
        editor.toggleComment()
        assertEquals(listOf("// a", "", "// b"), editor.document.lines)
    }

    @Test fun lineOperationsMoveDuplicateAndDelete() {
        val editor = state("one\ntwo\nthree")
        editor.moveTo(CodePosition(1, 0))
        editor.duplicateLines()
        assertEquals(listOf("one", "two", "two", "three"), editor.document.lines)
        editor.deleteLines()
        assertEquals(listOf("one", "two", "three"), editor.document.lines)
        editor.moveTo(CodePosition(2, 0))
        editor.moveLines(up = true)
        assertEquals(listOf("one", "three", "two"), editor.document.lines)
    }

    @Test fun deleteLinesOnTheLastLineDoesNotLeaveAStrayBreak() {
        val editor = state("one\ntwo")
        editor.moveTo(CodePosition(1, 0))
        editor.deleteLines()
        assertEquals("one", editor.text)
    }

    @Test fun verticalMotionRemembersTheDesiredColumn() {
        val editor = state("longest line\nx\nanother long line")
        editor.moveTo(CodePosition(0, 11))
        editor.moveVertical(1, extend = false)
        assertEquals(CodePosition(1, 1), editor.caret)
        editor.moveVertical(1, extend = false)
        assertEquals(CodePosition(2, 11), editor.caret)
    }

    @Test fun homeAlternatesBetweenFirstTextAndMargin() {
        val editor = state("    value")
        editor.moveTo(CodePosition(0, 9))
        editor.moveLineEdge(toStart = true, extend = false)
        assertEquals(CodePosition(0, 4), editor.caret)
        editor.moveLineEdge(toStart = true, extend = false)
        assertEquals(CodePosition(0, 0), editor.caret)
    }

    @Test fun selectionOnReportsColumnsPerLine() {
        val editor = state("abcd\nefgh\nijkl")
        editor.moveTo(CodePosition(0, 2))
        editor.moveTo(CodePosition(2, 1), extend = true)
        assertEquals(2 until 5, editor.selectionOn(0))
        assertEquals(0 until 5, editor.selectionOn(1))
        assertEquals(0 until 1, editor.selectionOn(2))
    }

    @Test fun readOnlyRefusesEditsButStillNavigates() {
        val editor = CodeEditorState("text", CodeLanguage.Kotlin, readOnly = true)
        assertFalse(editor.type("x"))
        assertFalse(editor.newline())
        assertFalse(editor.backspace())
        assertEquals("text", editor.text)
        editor.moveTo(CodePosition(0, 2))
        assertEquals(CodePosition(0, 2), editor.caret)
    }

    @Test fun syntaxStateInvalidatesOnlyFromTheEditedLine() {
        val editor = state("/* a\nb\nc */\nd")
        assertEquals(CodeLanguage.Code, editor.entryState(3))
        editor.moveTo(CodePosition(0, 0))
        editor.typeAll("x")
        assertEquals(CodeLanguage.Code, editor.entryState(0))
        assertTrue(editor.entryState(1) != CodeLanguage.Code, "line 1 is still inside the comment")
    }

    @Test fun switchingLanguageRecoloursTheBuffer() {
        val editor = state("-- note", CodeLanguage.Kotlin)
        assertTrue(editor.spans(0).none { it.token == CodeToken.Comment })
        editor.language = CodeLanguage.Sql
        assertTrue(editor.spans(0).any { it.token == CodeToken.Comment })
    }

    @Test fun findWalksMatchesForwardAndWraps() {
        val editor = state("a\nfind\nb\nfind")
        editor.findQuery = "find"
        assertEquals(2, editor.findMatches.size)
        editor.findNext(forward = true)
        assertEquals(CodePosition(1, 4), editor.caret)
        editor.findNext(forward = true)
        assertEquals(CodePosition(3, 4), editor.caret)
        editor.findNext(forward = true)
        assertEquals(CodePosition(1, 4), editor.caret)
    }

    @Test fun replaceAllRewritesEveryMatch() {
        val editor = state("x = 1\ny = 1")
        editor.findQuery = "1"
        assertEquals(2, editor.replaceAllMatches("2"))
        assertEquals("x = 2\ny = 2", editor.text)
    }

    @Test fun completionReplacesOnlyThePrefixLeftOfTheCaret() {
        val editor = state("val ren")
        editor.moveTo(CodePosition(0, 7))
        editor.applyCompletion(CodeCompletion("renderPane"))
        assertEquals("val renderPane", editor.text)
    }

    @Test fun completionHonoursAServerSuppliedRange() {
        val editor = state("import a.b.c")
        editor.moveTo(CodePosition(0, 12))
        editor.applyCompletion(CodeCompletion("d", replaces = CodeSpan.of(0, 7, 12)))
        assertEquals("import d", editor.text)
    }

    @Test fun loadDropsHistoryAndSelection() {
        val editor = state("old")
        editor.typeAll("x")
        editor.selectAll()
        editor.load("new", CodeLanguage.Json)
        assertEquals("new", editor.text)
        assertNull(editor.anchor)
        assertFalse(editor.canUndo)
        assertEquals(CodeLanguage.Json, editor.language)
    }

    @Test fun versionAdvancesOncePerEdit() {
        val editor = state()
        val before = editor.version
        editor.type("a")
        editor.type("b")
        assertEquals(before + 2, editor.version)
    }
}
