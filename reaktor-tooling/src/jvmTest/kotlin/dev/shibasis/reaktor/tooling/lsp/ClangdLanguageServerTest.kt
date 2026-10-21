package dev.shibasis.reaktor.tooling.lsp

import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClangdLanguageServerTest {
    @Test fun findsClangdNamedByTheEnvironmentAndNothingWhereThereIsNone() {
        val directory = Files.createTempDirectory("reaktor-clangd").toFile()
        val binary = File(directory, "clangd").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        assertEquals(binary, ClangdLanguageServer.locate(mapOf("REAKTOR_CLANGD" to binary.path), "", { null }))
        assertEquals(binary, ClangdLanguageServer.locate(emptyMap(), directory.path, { null }))
        val launch = ClangdLanguageServer.launch(binary, directory)
        assertTrue("--compile-commands-dir=${directory.absolutePath}" in launch.command && "--background-index" in launch.command, "${launch.command}")
        directory.deleteRecursivelyInTemp()
    }

    @Test fun aServedProjectAnswersSymbolsAnOutlineAndReferences() = runBlocking {
        assumeTrue("clangd is not installed here", ClangdLanguageServer.locate() != null)
        val root = Files.createTempDirectory("reaktor-clangd-project").toFile().canonicalFile
        try {
            File(root, "lib.h").writeText("int add(int a, int b);\n")
            File(root, "lib.cpp").writeText("#include \"lib.h\"\n\nint add(int a, int b) { return a + b; }\n")
            File(root, "main.cpp").writeText("#include \"lib.h\"\n\nint twice(int x) { return add(x, x); }\n\nint main() { return add(1, 2) + twice(3); }\n")
            File(root, "compile_commands.json").writeText("[" + listOf("lib.cpp", "main.cpp").joinToString(",") { file ->
                "{\"directory\": \"${root.path}\", \"file\": \"${File(root, file).path}\", \"arguments\": [\"clang++\", \"-std=c++17\", \"-c\", \"$file\"]}"
            } + "]")
            val session = assertIs<LanguageServerSession>(ClangdLanguageServer.start(root, root))
            session.use {
                val library = File(root, "lib.cpp").toURI().toString()
                val main = File(root, "main.cpp").toURI().toString()
                it.opened(CodeSource(library, "cpp", File(root, "lib.cpp").readText(), 1))
                it.opened(CodeSource(main, "cpp", File(root, "main.cpp").readText(), 1))
                assertEquals(listOf("twice", "main"), it.outline(main).map { symbol -> symbol.name })
                val found = poll { it.symbols("add").filter { symbol -> symbol.name == "add" } }
                assertTrue(found.isNotEmpty(), "clangd found no add")
                val uses = poll { it.references(library, CodePosition(2, 4)).filter { location -> location.uri.endsWith("main.cpp") } }
                assertEquals(listOf(2, 4), uses.map { location -> location.span.start.line }, "$uses")
                val renamed = settle({ it.rename(library, CodePosition(2, 4), "sum") }) { edits -> edits.keys.any { uri -> uri.endsWith("/lib.h") } }
                assertEquals(setOf("lib.h", "lib.cpp", "main.cpp"), renamed.keys.map { uri -> uri.substringAfterLast('/') }.toSet(), "$renamed")
                assertTrue(renamed.values.flatten().all { edit -> edit.second == "sum" }, "$renamed")
                assertEquals(2, renamed.entries.single { entry -> entry.key.endsWith("main.cpp") }.value.size)
            }
        } finally { root.deleteRecursivelyInTemp() }
    }

    private suspend fun <T> poll(read: suspend () -> List<T>): List<T> {
        repeat(60) {
            read().takeIf { it.isNotEmpty() }?.let { return it }
            delay(500)
        }
        return read()
    }

    private suspend fun <T> settle(read: suspend () -> T, done: (T) -> Boolean): T {
        repeat(60) {
            read().takeIf(done)?.let { return it }
            delay(500)
        }
        return read()
    }

    private fun File.deleteRecursivelyInTemp() {
        require(canonicalPath.startsWith(File(System.getProperty("java.io.tmpdir")).canonicalPath))
        Files.walk(toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
