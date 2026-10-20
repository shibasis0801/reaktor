package dev.shibasis.reaktor.code

import java.nio.file.Files
import kotlin.test.*

class CodeWorkspaceTest {
    @Test fun aStructuralPatternBindsItsHolesAndRunsOfArgumentsAndAHoleUsedTwiceMatchesTheSameText() = fixture { root, code ->
        Files.writeString(root.resolve("Demo.kt"), """
            package demo
            fun use(a: Api, b: Api) {
                a.call(1, 2)
                b.items.first().call()
                call(3)
                same(a, a)
                same(a, b)
                a.call(a.call(4))
            }
        """.trimIndent())
        val calls = code.structural("Demo.kt", "\$R.call(\$\$\$ARGS)")
        assertEquals(listOf("a" to "1, 2", "b.items.first()" to "", "a" to "a.call(4)"), calls.map { it.texts.getValue("R") to it.texts.getValue("ARGS") })
        assertEquals(CodeSpan(CodePosition(2, 4), CodePosition(2, 16)), calls.first().span)
        assertEquals(CodeSpan(CodePosition(2, 11), CodePosition(2, 15)), calls.first().holes.getValue("ARGS"))
        assertEquals(listOf(5), code.structural("Demo.kt", "same(\$X, \$X)").map { it.span.start.line })
        assertEquals(listOf(4, 7), code.structural("Demo.kt", "call(\$N)").map { it.span.start.line }, "the call after a receiver's dot is a call too")
        assertFailsWith<IllegalArgumentException> { code.structural("Demo.kt", "fun (") }
    }

    @Test fun kotlinOutlinePreservesDeclarationsSignaturesAndUtf16Ranges() = fixture { root, code ->
        val source = """
            package sample
            import java.time.Instant as Time
            // class FakeComment
            val text = "class FakeString"
            /** A useful service. */
            class Service(val name: String, ignored: Int) {
                fun render(value: String): String = value
                fun render(value: Int): String { val local = value; return local.toString() }
                val lazyValue by lazy { "hello" }
                object Nested { fun ping() = Unit }
                companion object { const val Version = 1 }
            }
            typealias Alias = Service
            enum class State { Ready, Done }
            fun String.decorate() = this
            fun factory() = object { val hiddenFromExpression = 1 }
            val holder = object { fun hiddenFromInitializer() = Unit }
            val emoji = "😀"; fun afterEmoji() = Unit
        """.trimIndent().replace("\n", "\r\n")
        Files.writeString(root.resolve("Service.kt"), source)
        val outline = code.outline("Service.kt")
        assertFalse(outline.partial, outline.diagnostics.toString())
        assertTrue(outline.provider.startsWith("kotlin-psi/"))
        assertEquals(code.read("Service.kt").digest, outline.digest)
        assertEquals("sample", outline.packageName)
        assertEquals(listOf("import java.time.Instant as Time"), outline.imports)
        assertTrue(outline.diagnostics.isEmpty())
        assertFalse(outline.declarations.any { it.name in setOf("FakeComment", "FakeString", "ignored", "local", "hiddenFromExpression", "hiddenFromInitializer") })
        val render = outline.declarations.filter { it.name == "render" }
        assertEquals(2, render.size)
        assertEquals(setOf("fun render(value: String): String", "fun render(value: Int): String"), render.map { it.signature }.toSet())
        assertTrue(render.all { it.container == "Service" })
        assertEquals("constructor-property", outline.declarations.single { it.name == "name" }.kind)
        assertEquals("Service.Nested", outline.declarations.single { it.name == "ping" }.container)
        assertEquals("val lazyValue", outline.declarations.single { it.name == "lazyValue" }.signature)
        assertEquals("typealias", outline.declarations.single { it.name == "Alias" }.kind)
        assertEquals("enum-entry", outline.declarations.single { it.name == "Ready" }.kind)
        assertEquals("fun String.decorate()", outline.declarations.single { it.name == "decorate" }.signature)
        outline.declarations.forEach { declaration ->
            val lines = source.split('\n')
            val start = declaration.selection.start
            val end = declaration.selection.end
            assertEquals(start.line, end.line)
            assertEquals(declaration.name, lines[start.line].substring(start.column, end.column))
            assertTrue(declaration.selection.start >= declaration.span.start)
            assertTrue(declaration.selection.end <= declaration.span.end)
        }
        assertEquals(source.substringAfterLast('\n').indexOf("afterEmoji"), outline.declarations.single { it.name == "afterEmoji" }.selection.start.column)
    }

    @Test fun kotlinIdentifiersExcludeCommentsStringsAndLongerNamesButIncludeInterpolation() = fixture { root, code ->
        val source = """
            val answer = 42
            val answerSuffix = 1
            // answer
            /* answer */
            val plain = "answer"
            val interpolated = "${'$'}answer"
            val block = "${'$'}{answer + 1}"
            val call = answer + answer
            val `odd name` = answer
            val backtick = `odd name`
        """.trimIndent()
        Files.writeString(root.resolve("Test.kt"), source)
        val result = code.identifiers(listOf("Test.kt", "Test.kt"), "answer")
        assertEquals(listOf(1, 6, 7, 8, 9), result.matches.map { it.line })
        assertEquals(1, result.scannedFiles)
        assertFalse(result.partial)
        assertTrue(result.provider.startsWith("kotlin-lexer/"))
        assertTrue(result.matches.all { it.digest == code.read("Test.kt").digest })
        assertEquals(listOf(9, 10), code.identifiers(listOf("Test.kt"), "odd name").matches.map { it.line })
        assertTrue(code.query(listOf("Test.kt"), "answer").matches.size > result.matches.size)
        val limited = code.identifiers(listOf("Test.kt"), "answer", 1)
        assertEquals(1, limited.matches.size)
        assertTrue(limited.partial)
        assertTrue(code.identifiers(listOf("Test.kt"), "missing").matches.isEmpty())
    }

    @Test fun outlinesRemainUsefulForBrokenSourceAndExplicitlyReportLimits() = fixture { root, code ->
        Files.writeString(root.resolve("Broken.kt"), "class Good\nfun broken(\n")
        val broken = code.outline("Broken.kt")
        assertTrue(broken.partial)
        assertTrue(broken.diagnostics.isNotEmpty())
        assertTrue(broken.declarations.any { it.name == "Good" })
        Files.writeString(root.resolve("Test.kts"), "val first = 1\nval second = 2\n")
        val limited = code.outline("Test.kts", 1)
        assertEquals(listOf("first"), limited.declarations.map { it.name })
        assertTrue(limited.partial)
        val before = code.outline("Test.kts")
        Files.writeString(root.resolve("Test.kts"), "val third = 1\nval second = 2\n")
        val after = code.outline("Test.kts")
        assertNotEquals(before.digest, after.digest)
        assertEquals(listOf("third", "second"), after.declarations.map { it.name })
        Files.writeString(root.resolve("Test.ts"), "const answer = 42;")
        assertEquals(1, code.query(listOf("Test.ts"), "answer").matches.size)
        assertFailsWith<IllegalArgumentException> { code.outline("Test.ts") }
        assertFailsWith<IllegalArgumentException> { code.identifiers(listOf("Test.ts"), "answer") }
    }

    @Test fun queryCarriesStrongIdentityAndReportsOutputOmissions() = fixture { root, code ->
        Files.writeString(root.resolve("Test.kt"), "val name = 1\nval name = 2\n")
        val result = code.query(listOf("Test.kt", "Test.kt"), "name", 1)
        assertEquals(1, result.scannedFiles)
        assertEquals(1, result.matches.size)
        assertTrue(result.partial)
        assertEquals(code.read("Test.kt").digest, result.matches.single().digest)
        val original = result.matches.single().digest
        Files.writeString(root.resolve("Test.kt"), "val name = 3\nval name = 2\n")
        assertNotEquals(original, code.read("Test.kt").digest)
        Files.writeString(root.resolve("Long.kt"), "x".repeat(2000) + "name" + "y".repeat(2000))
        val bounded = code.query(listOf("Long.kt"), "name")
        assertTrue(bounded.partial)
        assertTrue(bounded.matches.single().text.contains("name"))
        assertTrue(bounded.matches.single().text.length <= 600)
    }

    @Test fun planRejectsStaleSourceAndCannotOverwriteUnrelatedChanges() = fixture { root, code ->
        Files.writeString(root.resolve("Test.kt"), "val oldName = 1\n")
        val plan = code.plan("Test.kt", "oldName", "newName")
        Files.writeString(root.resolve("Test.kt"), "val oldName = 2\n")
        assertFailsWith<IllegalArgumentException> { code.apply(plan, plan.fingerprint) }
        assertEquals("val oldName = 2\n", code.read("Test.kt").text)
        val fresh = code.plan("Test.kt", "oldName", "newName")
        assertFailsWith<IllegalArgumentException> { code.apply(fresh, plan.fingerprint) }
        val applied = code.apply(fresh, fresh.fingerprint)
        assertEquals("val newName = 2\n", applied.text)
        assertEquals(applied, code.apply(fresh, fresh.fingerprint))
        assertFailsWith<IllegalArgumentException> { code.apply(fresh.copy(after = "destroyed"), fresh.fingerprint) }
        assertFailsWith<IllegalArgumentException> { code.apply(fresh.copy(replacement = "anotherName"), fresh.fingerprint) }
    }

    @Test fun rejectsEscapingSymlinkBinaryAndUnboundedInputs() = fixture { root, code ->
        Files.writeString(root.resolve("Test.kt"), "x")
        Files.createSymbolicLink(root.resolve("Alias.kt"), root.resolve("Test.kt"))
        assertFailsWith<IllegalArgumentException> { code.read("Alias.kt") }
        assertFailsWith<IllegalArgumentException> { code.read("../outside") }
        Files.write(root.resolve("Binary.kt"), byteArrayOf(0))
        assertFailsWith<IllegalArgumentException> { code.read("Binary.kt") }
        Files.writeString(root.resolve("Large.kt"), "x".repeat(524289))
        assertFailsWith<IllegalArgumentException> { code.read("Large.kt") }
        assertFailsWith<IllegalArgumentException> { code.plan("Test.kt", "x", "xx") }
        assertFailsWith<IllegalArgumentException> { code.query(emptyList(), "x") }
    }

    @Test fun aStatementSpansTheWholeStatementOrBranchAroundAPosition() = fixture { root, code ->
        Files.writeString(root.resolve("Record.kt"), """
            package sample
            class Audit {
                fun record(event: Event): Entry {
                    val entry = Entry(
                        id = event.id,
                        scopes = event.scopes,
                    )
                    if (event.urgent) {
                        notify(entry)
                    }
                    return when (event.kind) {
                        Kind.Grant -> entry.copy(granted = true)
                        else -> entry
                    }
                }
            }
            val limit = listOf(
                1,
            )
        """.trimIndent() + "\n")
        fun lines(line: Int, column: Int) = code.statement("Record.kt", CodePosition(line, column)).let { it.start.line to it.end.line }
        assertEquals(3 to 6, lines(5, 28))
        assertEquals(8 to 8, lines(8, 12))
        assertEquals(11 to 11, lines(11, 30))
        assertEquals(2 to 14, lines(2, 16))
        assertEquals(16 to 18, lines(17, 4))
    }

    @Test fun aChangeWritesEveryFileOrNoneAndReplaysOnce() = fixture { root, code ->
        Files.createDirectories(root.resolve("a")); Files.createDirectories(root.resolve("b"))
        Files.writeString(root.resolve("a/One.kt"), "fun oldName() = 1\n")
        Files.writeString(root.resolve("b/Two.kt"), "val x = oldName()\r\nval y = oldName()\r\n")
        val edits = listOf(CodeTextEdit("b/Two.kt", CodeSpan.of(1, 8, 15), "newName"), CodeTextEdit("a/One.kt", CodeSpan.of(0, 4, 11), "newName"),
            CodeTextEdit("b/Two.kt", CodeSpan.of(0, 8, 15), "newName"))
        val plan = code.change("rename-test", edits)
        assertEquals(3, plan.edits)
        assertEquals(listOf("a/One.kt", "b/Two.kt"), plan.files.map { it.before.path })
        val applied = code.apply(plan, plan.fingerprint)
        assertEquals(listOf("fun newName() = 1\n", "val x = newName()\r\nval y = newName()\r\n"), applied.map { it.text })
        assertEquals(applied, code.apply(plan, plan.fingerprint), "applying the same plan again replays it")

        Files.writeString(root.resolve("a/One.kt"), "fun oldName() = 1\n")
        Files.writeString(root.resolve("b/Two.kt"), "val x = oldName()\r\nval y = oldName()\r\n")
        val second = code.change("rename-test", edits)
        Files.writeString(root.resolve("b/Two.kt"), "val x = oldName()\r\nval y = oldName() + 1\r\n")
        assertFailsWith<IllegalArgumentException> { code.apply(second, second.fingerprint) }
        assertEquals("fun oldName() = 1\n", code.read("a/One.kt").text, "a stale file stops the change before anything is written")
        assertFailsWith<IllegalArgumentException> { code.apply(second.copy(recipe = "other"), second.fingerprint) }
        assertFailsWith<IllegalArgumentException> {
            code.change("overlap", listOf(CodeTextEdit("a/One.kt", CodeSpan.of(0, 4, 11), "x"), CodeTextEdit("a/One.kt", CodeSpan.of(0, 6, 9), "y")))
        }
        assertFailsWith<IllegalArgumentException> { code.change("past the line", listOf(CodeTextEdit("a/One.kt", CodeSpan.of(0, 4, 40), "x"))) }
    }

    @Test fun aChangeThatFailsPartWayRestoresTheFilesItWrote() = fixture { root, code ->
        Files.createDirectories(root.resolve("a")); Files.createDirectories(root.resolve("b"))
        Files.writeString(root.resolve("a/One.kt"), "val one = 1\n")
        Files.writeString(root.resolve("b/Two.kt"), "val two = 2\n")
        val plan = code.change("edit-test", listOf(CodeTextEdit("a/One.kt", CodeSpan.of(0, 10, 11), "10"), CodeTextEdit("b/Two.kt", CodeSpan.of(0, 10, 11), "20")))
        val locked = root.resolve("b").toFile()
        assertTrue(locked.setWritable(false))
        try {
            val failure = assertFailsWith<IllegalStateException> { code.apply(plan, plan.fingerprint) }
            assertTrue(failure.message!!.startsWith("The change stopped at b/Two.kt; the 1 file written before it restored"), failure.message)
        } finally { locked.setWritable(true) }
        assertEquals("val one = 1\n", code.read("a/One.kt").text)
        assertEquals("val two = 2\n", code.read("b/Two.kt").text)
    }

    @Test fun outlinesFromManyThreadsAgree() = fixture { root, code ->
        Files.writeString(root.resolve("Many.kt"), "package sample\n\n" + (1..200).joinToString("\n") { "class Type$it { fun call$it() = $it }" } + "\n")
        val expected = code.outline("Many.kt", 1000)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val outlines = (1..16).map { pool.submit<CodeOutline> { code.outline("Many.kt", 1000) } }.map { it.get() }
            assertTrue(outlines.all { it == expected })
            assertEquals(400, expected.declarations.size)
        } finally { pool.shutdownNow() }
    }

    private fun fixture(test: (java.nio.file.Path, CodeWorkspace) -> Unit) {
        val root = Files.createTempDirectory("code-workspace-")
        try { test(root, CodeWorkspace(root)) }
        finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
