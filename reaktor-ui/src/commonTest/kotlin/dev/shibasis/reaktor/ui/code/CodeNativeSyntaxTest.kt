package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan
import kotlin.test.*

class CodeNativeSyntaxTest {
    private fun tokens(language: CodeLanguage, line: String, entry: CodeLexState = CodeLanguage.Code) =
        language.spans(line, entry).map { it.token to line.substring(it.start, it.end) }

    @Test fun profilesResolveWithoutChangingConsumers() {
        for (id in listOf("cpp", "c++", "cxx", "C++")) assertSame(CodeLanguage.Cpp, CodeLanguage.forId(id))
        for (path in listOf("main.cpp", "lib.CC", "include/a.hpp", "module.cppm")) assertSame(CodeLanguage.Cpp, CodeLanguage.forPath(path))
        for (id in listOf("fga", "openfga", "OpenFGA")) assertSame(CodeLanguage.OpenFga, CodeLanguage.forId(id))
        for (path in listOf("model.fga", "model.openfga")) assertSame(CodeLanguage.OpenFga, CodeLanguage.forPath(path))
        assertNotSame(CodeLanguage.OpenFga, CodeLanguage.forPath("store.fga.yaml"))
        assertSame(CodeLanguage.OpenFga, CodeLanguage.sniff("model\n  schema 1.1\ntype user"))
        assertSame(CodeLanguage.Cpp, CodeLanguage.sniff("#include <vector>\nint main() {}"))
    }

    @Test fun cppRawDelimitersShieldQuotesCommentsAndBracesAcrossLines() {
        val language = CodeLanguage.Cpp
        val inside = language.state("auto text = u8R\"tag(first")
        assertNotEquals(CodeLanguage.Code, inside)
        assertEquals(listOf(CodeToken.Text to "// \" )wrong\" { }"), tokens(language, "// \" )wrong\" { }", inside))
        val end = tokens(language, ")tag\"; return true;", inside)
        assertTrue(CodeToken.Text to ")tag\"" in end)
        assertTrue(CodeToken.Keyword to "return" in end)
        assertEquals(CodeLanguage.Code, language.state(")tag\";", inside))
        assertTrue(tokens(language, "R\"(plain)\"").all { it.first == CodeToken.Text })
    }

    @Test fun cppCommentsAndEncodedLiteralsKeepTheirBoundaries() {
        val language = CodeLanguage.Cpp
        val block = language.state("/* R\"tag(")
        assertEquals(listOf(CodeToken.Comment to "still comment"), tokens(language, "still comment", block))
        assertTrue(CodeToken.Keyword to "constexpr" in tokens(language, "*/ constexpr int count = 1;", block))
        val line = "u8\"https://host\" U'X' // tail"
        val runs = tokens(language, line)
        assertTrue(CodeToken.Text to "u8\"https://host\"" in runs)
        assertTrue(CodeToken.Text to "U'X'" in runs)
        assertTrue(CodeToken.Comment to "// tail" in runs)
        assertEquals(CodeLanguage.Code, language.state("R\"invalid delimiter("))
    }

    @Test fun cppNumbersKeepDigitSeparatorsAndHexExponents() {
        val runs = tokens(CodeLanguage.Cpp, "constexpr auto count = 1'000ULL + 0x1.fp+3 + .25f;")
        for (number in listOf("1'000ULL", "0x1.fp+3", ".25f")) assertTrue(CodeToken.Number to number in runs)
        assertTrue(CodeToken.Keyword to "constexpr" in runs)
        assertTrue(CodeToken.Annotation to "#include" in tokens(CodeLanguage.Cpp, "#include <vector>"))
        assertEquals("/* comment", tokens(CodeLanguage.Cpp, "auto x=/* comment").filter { it.first == CodeToken.Comment }.joinToString("") { it.second })
        assertTrue(CodeToken.Comment to "// comment" in tokens(CodeLanguage.Cpp, "x=// comment"))
        assertTrue(CodeToken.Number to ".5" in tokens(CodeLanguage.Cpp, "x=.5;"))
    }

    @Test fun openFgaUsersetsAreNotComments() {
        val language = CodeLanguage.OpenFga
        val line = "define viewer: [user:*, group#member] or editor from parent # note"
        val runs = tokens(language, line)
        assertEquals(listOf(CodeToken.Comment to "# note"), runs.filter { it.first == CodeToken.Comment })
        for (word in listOf("define", "or", "from")) assertTrue(CodeToken.Keyword to word in runs)
        assertEquals(listOf(CodeToken.Comment to "# condition fake()"), tokens(language, "  # condition fake()"))
        val names = tokens(language, "define read-only: [folder/sub-type, resource.type]")
        assertFalse(names.any { it.second == "only" || it.second == "type" })
        assertFalse(tokens(language, "DEFINE viewer: [user]").any { it.first == CodeToken.Keyword })
    }

    @Test fun openFgaConditionsShieldStringsAndContinueTripleLiterals() {
        val language = CodeLanguage.OpenFga
        val line = "claims == \"https://host/#frag\" && enabled // note"
        val runs = tokens(language, line)
        assertTrue(CodeToken.Text to "\"https://host/#frag\"" in runs)
        assertEquals(listOf(CodeToken.Comment to "// note"), runs.filter { it.first == CodeToken.Comment })
        for (prefix in listOf("", "r", "b", "br", "BR")) {
            val inside = language.state("value == $prefix\"\"\"first")
            assertNotEquals(CodeLanguage.Code, inside)
            assertEquals(listOf(CodeToken.Text to "# // { body }"), tokens(language, "# // { body }", inside))
            assertEquals(CodeLanguage.Code, language.state("\"\"\"", inside))
        }
        assertTrue(CodeToken.Type to "timestamp" in tokens(language, "condition recent(now: timestamp) {"))
        assertTrue(CodeToken.Text to "r'raw\\'" in tokens(language, "r'raw\\'"))
    }

    @Test fun markdownFencesUseTheNewProfilesAndEditingRules() {
        val state = CodeEditorState("```c++\nauto text = R\"tag(first\n// literal\n)tag\";\n```\n```openfga\ndefine viewer: [group#member]\n```", CodeLanguage.Markdown)
        assertEquals(CodeToken.Text, state.spans(2).single().token)
        assertSame(CodeLanguage.Cpp, (state.entryState(2) as MarkdownFence).language)
        assertSame(CodeLanguage.OpenFga, (state.entryState(6) as MarkdownFence).language)
        state.moveTo(CodePosition(6, 0))
        assertTrue(state.toggleComment())
        assertEquals("# define viewer: [group#member]", state.document.line(6))
        state.edit(CodeSpan.of(0, 3, 6), "kotlin")
        assertSame(CodeLanguage.Kotlin, (state.entryState(2) as MarkdownFence).language)
    }

    @Test fun lexicalSpansStayBoundedForPartialInput() {
        val fixtures = listOf("u8R\"tag(body)tag\" // done", "br'''body'''", "'unfinished\\", "R\"abcdefghijklmnop(body)abcdefghijklmnop\"", "define x: [group#member] # tail")
        for (language in listOf(CodeLanguage.Cpp, CodeLanguage.OpenFga, CodeLanguage.Kotlin)) {
            for (fixture in fixtures) for (length in 0..fixture.length) {
                val line = fixture.take(length)
                val spans = language.spans(line)
                assertTrue(spans.all { it.start >= 0 && it.end <= line.length && it.start < it.end }, "$language: $line")
                assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start }, "$language: $line")
            }
        }
    }
}
