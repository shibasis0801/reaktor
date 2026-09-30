package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodeCompletion
import dev.shibasis.reaktor.code.CodeCompletionKind
import dev.shibasis.reaktor.code.CodePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeCompletionTest {
    private fun items(vararg labels: String, fromServer: Boolean = false) =
        labels.map { CodeCompletionItem(CodeCompletion(it), fromServer) }

    @Test fun theBufferAndTheLanguageBothSupplyWords() {
        val state = CodeEditorState("val renderPane = 1\n", CodeLanguage.Kotlin)
        val labels = documentCompletions(state).map { it.completion.label }
        assertTrue("renderPane" in labels, "identifier from the buffer")
        assertTrue("suspend" in labels, "keyword from the language")
        assertTrue("Int" in labels, "type from the language")
        assertTrue(documentCompletions(state).none { it.fromServer })
    }

    @Test fun filteringIsPrefixedAndServerItemsRankFirst() {
        val merged = items("renderer", fromServer = true) + items("renderPane", "render")
        val filtered = filterCompletions(merged, "render")
        assertEquals("renderer", filtered.first().completion.label)
        assertEquals(3, filtered.size)
        assertTrue(filterCompletions(merged, "zzz").isEmpty())
    }

    @Test fun anExactAndOnlyMatchIsNotWorthAPopup() {
        assertTrue(filterCompletions(items("render"), "render").isEmpty())
        assertEquals(2, filterCompletions(items("render", "renderPane"), "render").size)
    }

    @Test fun mergeKeepsServerItemsAndDropsDuplicateWords() {
        val merged = mergeCompletions(
            remote = listOf(CodeCompletion("render", kind = CodeCompletionKind.Function, detail = "(): Unit")),
            local = items("render", "renderPane"),
        )
        assertEquals(2, merged.size)
        assertEquals("(): Unit", merged.first { it.completion.label == "render" }.completion.detail)
        assertTrue(merged.first { it.completion.label == "render" }.fromServer)
    }

    @Test fun largeBuffersScanOnlyAroundTheCaret() {
        val small = CodeEditorState((1..10).joinToString("\n") { "val a$it = $it" }, CodeLanguage.Kotlin)
        assertNull(small.completionScanRange())

        val large = CodeEditorState((1..9_000).joinToString("\n") { "val a$it = $it" }, CodeLanguage.Kotlin)
        large.goToLine(5_000)
        val window = large.completionScanRange()
        assertEquals(4_499..5_499, window)
        val labels = documentCompletions(large).map { it.completion.label }
        assertTrue("a5000" in labels)
        assertTrue("a1" !in labels, "a distant identifier is out of the scanned window")
    }

    @Test fun completionKindsCarryASigilForTheList() {
        assertEquals("fn", CodeCompletionKind.Function.sigil)
        assertEquals("kw", CodeCompletionKind.Keyword.sigil)
        assertEquals("cl", CodeCompletionKind.Class.sigil)
    }

    @Test fun theWordLeftOfTheCaretIsNotOfferedBackToItself() {
        val state = CodeEditorState("renderPane", CodeLanguage.Kotlin)
        state.moveTo(CodePosition(0, 10))
        assertTrue(documentCompletions(state).none { it.completion.label == "renderPane" })
    }
}
