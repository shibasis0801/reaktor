package dev.shibasis.reaktor.ui.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodeSyntaxTest {
    private fun tokensOf(language: CodeLanguage, line: String, entry: Int = CodeLanguage.Code) =
        language.spans(line, entry).map { it.token to line.substring(it.start, it.end) }

    @Test fun kotlinClassifiesTheUsualRuns() {
        val tokens = tokensOf(CodeLanguage.Kotlin, "val count: Int = 42 // tally")
        assertTrue(CodeToken.Keyword to "val" in tokens)
        assertTrue(CodeToken.Type to "Int" in tokens)
        assertTrue(CodeToken.Number to "42" in tokens)
        assertTrue(CodeToken.Comment to "// tally" in tokens)
    }

    @Test fun callsReadAsFunctionsAndCapitalsAsTypes() {
        val tokens = tokensOf(CodeLanguage.Kotlin, "renderPane(PaneSpec)")
        assertTrue(CodeToken.Function to "renderPane" in tokens)
        assertTrue(CodeToken.Type to "PaneSpec" in tokens)
    }

    @Test fun blockCommentsCarryAcrossLines() {
        val opened = CodeLanguage.Kotlin.state("/* still", CodeLanguage.Code)
        assertNotEquals(CodeLanguage.Code, opened)
        assertEquals(listOf(CodeToken.Comment to "going"), tokensOf(CodeLanguage.Kotlin, "going", opened))
        assertEquals(CodeLanguage.Code, CodeLanguage.Kotlin.state("done */", opened))
    }

    @Test fun kotlinBlockCommentsNest() {
        val once = CodeLanguage.Kotlin.state("/* outer", CodeLanguage.Code)
        val twice = CodeLanguage.Kotlin.state("/* inner", once)
        assertNotEquals(CodeLanguage.Code, CodeLanguage.Kotlin.state("*/", twice))
        assertEquals(CodeLanguage.Code, CodeLanguage.Kotlin.state("*/ */", twice))
    }

    @Test fun docCommentsAreTheirOwnClass() {
        val tokens = tokensOf(CodeLanguage.Kotlin, "/** why this exists */")
        assertTrue(tokens.all { it.first == CodeToken.Doc })
    }

    @Test fun rawStringsCarryUntilTheirClose() {
        val inside = CodeLanguage.Kotlin.state("val q = \"\"\"select", CodeLanguage.Code)
        assertNotEquals(CodeLanguage.Code, inside)
        assertEquals(listOf(CodeToken.Text to "from t"), tokensOf(CodeLanguage.Kotlin, "from t", inside))
        assertEquals(CodeLanguage.Code, CodeLanguage.Kotlin.state("\"\"\"", inside))
    }

    @Test fun escapedQuotesDoNotEndAString() {
        val tokens = tokensOf(CodeLanguage.Kotlin, """val s = "a\"b" + 1""")
        assertTrue(CodeToken.Text to """"a\"b"""" in tokens)
        assertTrue(CodeToken.Number to "1" in tokens)
    }

    @Test fun annotationsReadAsOneRun() {
        assertTrue(CodeToken.Annotation to "@Composable" in tokensOf(CodeLanguage.Kotlin, "@Composable fun X()"))
    }

    @Test fun sqlKeywordsIgnoreCase() {
        val tokens = tokensOf(CodeLanguage.Sql, "Select COUNT(*) from users -- note")
        assertTrue(CodeToken.Keyword to "Select" in tokens)
        assertTrue(CodeToken.Builtin to "COUNT" in tokens)
        assertTrue(CodeToken.Comment to "-- note" in tokens)
    }

    @Test fun cypherReadsItsOwnVocabulary() {
        val tokens = tokensOf(CodeLanguage.Cypher, "MATCH (n:Node) RETURN n")
        assertTrue(CodeToken.Keyword to "MATCH" in tokens)
        assertTrue(CodeToken.Keyword to "RETURN" in tokens)
    }

    @Test fun languageResolvesFromPath() {
        assertEquals(CodeLanguage.Kotlin, CodeLanguage.forPath("src/Main.kt"))
        assertEquals(CodeLanguage.Sql, CodeLanguage.forPath("q.sql"))
        assertEquals(CodeLanguage.Plain, CodeLanguage.forPath("LICENSE"))
        assertEquals(CodeLanguage.Json, CodeLanguage.forId("json"))
    }

    @Test fun spansStayOrderedAndInsideTheLine() {
        val line = "fun f(a: Int) = a + 1 /* tail"
        val spans = CodeLanguage.Kotlin.spans(line)
        spans.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "overlap at ${a.end}") }
        spans.forEach { assertTrue(it.start >= 0 && it.end <= line.length) }
    }
}
