package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeDocumentTest {
    private val sample = CodeDocument.of("fun main() {\n    println(\"hi\")\n}")

    @Test fun splitsAndRejoinsWithoutLosingLines() {
        assertEquals(3, sample.lineCount)
        assertEquals("    println(\"hi\")", sample.line(1))
        assertEquals(CodePosition(2, 1), sample.end)
    }

    @Test fun keepsTheSeparatorTheFileArrivedWith() {
        val windows = CodeDocument.of("a\r\nb")
        assertEquals(listOf("a", "b"), windows.lines)
        assertEquals("a\r\nb", windows.render())
        assertEquals("a\nb", windows.toString())
    }

    @Test fun replaceCollapsesAMultiLineSpan() {
        val span = CodeSpan(CodePosition(0, 10), CodePosition(2, 1))
        assertEquals("fun main()", sample.replace(span, "").toString())
    }

    @Test fun replaceInsertsLines() {
        val updated = sample.replace(CodeSpan.at(CodePosition(0, 12)), "\n    // note")
        assertEquals(4, updated.lineCount)
        assertEquals("    // note", updated.line(1))
    }

    @Test fun positionAfterCountsTheTailOfTheInsertion() {
        assertEquals(CodePosition(0, 5), sample.positionAfter(CodePosition(0, 2), "abc"))
        assertEquals(CodePosition(2, 3), sample.positionAfter(CodePosition(0, 2), "a\nb\nxyz"))
    }

    @Test fun sliceReadsAcrossLines() {
        assertEquals("() {\n    ", sample.slice(CodeSpan(CodePosition(0, 8), CodePosition(1, 4))))
    }

    @Test fun wordAtExpandsBothWays() {
        assertEquals(CodeSpan.of(0, 4, 8), sample.wordAt(CodePosition(0, 6)))
        assertEquals(CodeSpan.of(0, 4, 8), sample.wordAt(CodePosition(0, 8)))
        assertNull(sample.wordAt(CodePosition(0, 10)))
    }

    @Test fun prefixStopsAtTheCaret() {
        assertEquals("mai", sample.prefixAt(CodePosition(0, 7)))
        assertEquals("", sample.prefixAt(CodePosition(0, 4)))
    }

    @Test fun wordBoundaryCrossesLineEnds() {
        assertEquals(CodePosition(1, 0), sample.wordBoundary(CodePosition(0, 12), forward = true))
        assertEquals(CodePosition(0, 12), sample.wordBoundary(CodePosition(1, 0), forward = false))
    }

    @Test fun matchesRespectCaseAndWordBounds() {
        val document = CodeDocument.of("value valueOf VALUE")
        assertEquals(3, document.matches("value").size)
        assertEquals(2, document.matches("value", caseSensitive = true).size)
        assertEquals(1, document.matches("value", caseSensitive = true, wholeWord = true).size)
    }

    @Test fun identifiersSkipShortWordsAndDuplicates() {
        val words = CodeDocument.of("val alpha = alpha + be").identifiers()
        assertTrue("alpha" in words)
        assertTrue("val" in words)
        assertTrue("be" !in words)
    }

    @Test fun clampKeepsPositionsInsideTheBuffer() {
        assertEquals(CodePosition(2, 1), sample.clamp(CodePosition(99, 99)))
        assertEquals(CodePosition(0, 0), sample.clamp(CodePosition(-4, -4)))
    }
}
