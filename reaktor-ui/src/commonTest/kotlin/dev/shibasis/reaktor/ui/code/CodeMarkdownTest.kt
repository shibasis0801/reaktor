package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan
import kotlin.test.*

class CodeMarkdownTest {
    @Test fun tablesAndHeadingLevelsRetainReadableStructure() {
        val blocks = markdownBlocks(CodeDocument.of("# Title\n\n## Section\n\n| Market | Work |\n| --- | --- |\n| EU | **Privacy** |\n| UK | Safety |\n\n---"))
        assertEquals(listOf(MarkdownKind.Heading, MarkdownKind.Heading, MarkdownKind.Table, MarkdownKind.Rule), blocks.map { it.kind })
        assertEquals(listOf(1, 2), blocks.take(2).map { it.level })
        assertEquals(listOf("Market", "Work"), markdownTableCells(blocks[2].text.lines().first()))
        assertEquals(3, blocks[2].text.lines().size)
        assertTrue(markdownTableSeparator("| :--- | ---: |"))
        assertFalse(markdownTableSeparator("| Market | Work |"))
    }

    @Test fun fencedLanguagesCarryTheirOwnMultilineState() {
        val state = CodeEditorState("# Example\n\n```kotlin\n/* open\nstill a comment\n*/ val done = true\n```\nplain", CodeLanguage.Markdown)
        assertEquals(CodeToken.Comment, state.spans(4).single().token)
        assertTrue(state.spans(5).any { it.token == CodeToken.Keyword })
        assertSame(CodeLanguage.Code, state.entryState(7))
        state.edit(CodeSpan.of(2, 3, 9), "typescript")
        assertSame(CodeLanguage.TypeScript, (state.entryState(4) as MarkdownFence).language)
    }

    @Test fun fenceLengthAndMarkerProtectEmbeddedCode() {
        val blocks = markdownBlocks(CodeDocument.of("~~~~kotlin\n```\n~~~\nval value = 1\n~~~~\nAfter"))
        assertEquals("```\n~~~\nval value = 1", blocks.first().text)
        assertSame(CodeLanguage.Kotlin, blocks.first().language)
        assertEquals("After", blocks.last().text)
    }

    @Test fun unknownLanguagesKeepTheirSource() {
        val blocks = markdownBlocks(CodeDocument.of("```unknown-language\nstd::cout << value;\n```"))
        assertSame(CodeLanguage.Plain, blocks.single().language)
        assertEquals("std::cout << value;", blocks.single().text)
    }

    @Test fun languageExtensionsNeedNoRendererChanges() {
        data class Continuation(val open: Boolean) : CodeLexState
        val language = object : CodeLanguage("custom", "Custom") {
            override fun lex(line: String, entry: CodeLexState, emit: (Int, Int, CodeToken) -> Unit): CodeLexState {
                if ((entry as? Continuation)?.open == true) emit(0, line.length, CodeToken.Text)
                return Continuation(line.endsWith('['))
            }
        }
        val state = CodeEditorState("[\nbody", language)
        assertEquals(CodeToken.Text, state.spans(1).single().token)
    }

    @Test fun listsContinueAndEmptyItemsExit() {
        val state = CodeEditorState("9. [x] verified", CodeLanguage.Markdown)
        state.moveTo(state.document.end)
        state.newline()
        assertEquals("9. [x] verified\n10. [ ] ", state.text)
        state.newline()
        assertEquals("9. [x] verified\n", state.text)
        state.undo()
        assertEquals("9. [x] verified\n10. [ ] ", state.text)
    }

    @Test fun quoteAndCodeIndentationUseTheActiveLanguage() {
        val quote = CodeEditorState("> - evidence", CodeLanguage.Markdown)
        quote.moveTo(quote.document.end)
        quote.newline()
        assertEquals("> - evidence\n> - ", quote.text)
        val code = CodeEditorState("```kotlin\nfun run() {}", CodeLanguage.Markdown)
        code.moveTo(CodePosition(1, 11))
        code.newline()
        assertEquals("```kotlin\nfun run() {\n    \n}", code.text)
    }

    @Test fun streamingKeepsCompletedBlocksAndSelection() {
        val state = CodeEditorState("# Title\n\nFirst\n\nSecond\n\nStreaming", CodeLanguage.Markdown, readOnly = true)
        val first = state.markdown.blocks(state.document)
        state.moveTo(CodePosition(2, 2))
        state.moveTo(CodePosition(2, 5), extend = true)
        state.append(" more")
        val next = state.markdown.blocks(state.document)
        assertSame(first[0], next[0])
        assertSame(first[1], next[1])
        assertEquals("Streaming more", next.last().text)
        assertEquals("rst", state.selectedText)
        assertSame(next, state.markdown.blocks(state.document))
        state.update("# Replacement")
        assertEquals("Replacement", state.markdown.blocks(state.document).single().text)
    }

    @Test fun editingAnOpeningFenceReprojectsTheFollowingBlocks() {
        val state = CodeEditorState("```kotlin\nval draft = 1\n```\n\nAfter", CodeLanguage.Markdown)
        state.markdown.blocks(state.document)
        state.edit(CodeSpan.of(0, 0, 3), "")
        val next = state.markdown.blocks(state.document)
        assertEquals(MarkdownKind.Paragraph, next.first().kind)
        assertEquals("kotlin\nval draft = 1", next.first().text)
        state.undo()
        assertEquals(MarkdownKind.Code, state.markdown.blocks(state.document).first().kind)
    }

    @Test fun previewPreservesDocumentHistoryAndCaret() {
        val state = CodeEditorState("# Draft", CodeLanguage.Markdown)
        state.moveTo(state.document.end)
        state.type("!")
        state.presentation = CodePresentation.Preview
        assertEquals("Draft!", state.markdown.blocks(state.document).single().text)
        state.presentation = CodePresentation.Source
        assertEquals(CodePosition(0, 8), state.caret)
        state.undo()
        assertEquals("# Draft", state.text)
    }

    @Test fun controlledInputPublishesBeforeSubmitAndNormalizesPastedNewlines() {
        var value = ""
        val state = CodeEditorState(onChange = { value = it })
        state.type("a\r\nb\rc")
        assertEquals("a\nb\nc", value)
        assertEquals(CodePosition(2, 1), state.caret)
        state.undo()
        assertEquals("", value)
        state.redo()
        assertEquals("a\nb\nc", value)
        state.readOnly = true
        assertFalse(state.undo())
        assertEquals(value, state.text)
    }

    @Test fun unicodeMovementAndDeletionNeverSplitSurrogatePairs() {
        val state = CodeEditorState("a😀b")
        state.moveTo(CodePosition(0, 1))
        state.moveHorizontal(forward = true, extend = false)
        assertEquals(3, state.caret.column)
        state.backspace()
        assertEquals("ab", state.text)
        state.undo()
        state.moveTo(CodePosition(0, 1))
        state.deleteForward()
        assertEquals("ab", state.text)
        state.undo()
        state.moveTo(CodePosition(0, 2))
        assertEquals(1, state.caret.column)
    }
}
