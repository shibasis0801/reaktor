package dev.shibasis.dependeasy

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CredentialChecksTest {
    @TempDir lateinit var directory: File

    @Test fun `approved paths are excluded without weakening the default scan`() {
        command("git", "init", "--quiet")
        val approved = directory.resolve("config/signing.fixture").apply {
            parentFile.mkdirs()
            writeText("BEGIN PRIVATE KEY\nfixture-only-marker\n")
        }
        command("git", "add", ".")
        assertEquals(1, scan().first)
        assertEquals(0, scan(":(exclude)config/**").first)

        approved.copyTo(directory.resolve("unapproved.fixture"))
        command("git", "add", ".")
        val rejected = scan(":(exclude)config/**")
        assertEquals(1, rejected.first)
        assertContains(rejected.second, "credential containment smoke failed: fixture")
        assertFalse(rejected.second.contains("fixture-only-marker"))
    }

    private fun scan(vararg pathspecs: String): Pair<Int, String> = process("bash", "-c", """
        source "${'$'}1"
        shift
        reject_tracked_credential_literal fixture 'BEGIN PRIVATE KEY' "${'$'}@"
    """.trimIndent(), "--", File("checks/credentials.sh").absolutePath, *pathspecs)

    private fun command(vararg arguments: String) {
        val (status, output) = process(*arguments)
        assertEquals(0, status, output)
    }

    private fun process(vararg arguments: String): Pair<Int, String> {
        val running = ProcessBuilder(*arguments).directory(directory).redirectErrorStream(true).start()
        val output = running.inputStream.bufferedReader().use { it.readText() }
        return running.waitFor() to output
    }
}
