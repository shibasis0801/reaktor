package dev.shibasis.reaktor.code

import java.nio.file.Files
import kotlin.test.*

class CodeWorkspaceTest {
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

    private fun fixture(test: (java.nio.file.Path, CodeWorkspace) -> Unit) {
        val root = Files.createTempDirectory("code-workspace-")
        try { test(root, CodeWorkspace(root)) }
        finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
