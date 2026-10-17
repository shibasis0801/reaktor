package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Grant matching, against a stand-in that answers like the CLI.
 *
 * The prefix rule is the part worth pinning: it is why one `command(./gradlew)` covers every Gradle
 * invocation, and getting it wrong in either direction is expensive — too strict burns a cycle per
 * missing rule, too loose tells the operator they are covered when they are not.
 */
class AntigravityGrantsTest {

    @Test fun aRuleCoversEveryCommandLineItPrefixesAndNothingElse() {
        val fake = fakeAgy(listOf("command(git status)", "command(./gradlew)", "command(python3)"))
        val missing = AntigravityGrants.missing(
            listOf("./gradlew :engine:compileKotlinJvm", "git status", "python3 test.py", "git add", "npm run build"),
            fake.absolutePath,
        )
        assertEquals(listOf("git add", "npm run build"), missing,
            "a grant covers the command lines it prefixes; it must not cover a different binary")
    }

    @Test fun aCommandThatMerelySharesAPrefixIsNotCovered() {
        val fake = fakeAgy(listOf("command(git status)"))
        // "git stash" starts with "git st" but is not "git status" at a word boundary.
        assertEquals(listOf("git stash"), AntigravityGrants.missing(listOf("git stash"), fake.absolutePath))
    }

    @Test fun anUnreadableAllowListIsNotAnEmptyOne() {
        assertNull(AntigravityGrants.missing(listOf("git status"), "/nonexistent/agy"),
            "not knowing what is granted must not read as everything being granted")
        assertNull(AntigravityGrants.advisory(File("/tmp"), writes = true, binary = "/nonexistent/agy"))
    }

    @Test fun theAdvisoryNamesTheRuleToAddAndStaysSilentWhenCovered() {
        val workspace = Files.createTempDirectory("grants").toFile()
        File(workspace, "gradlew").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
        val sparse = fakeAgy(listOf("command(git status)"))
        val advisory = requireNotNull(AntigravityGrants.advisory(workspace, writes = true, binary = sparse.absolutePath))
        assertTrue(advisory.contains("command(./gradlew)"), advisory)
        assertTrue(advisory.contains("command(git add)"), advisory)
        assertFalse(advisory.contains("command(git status)"), "what is already granted is not advice")

        val complete = fakeAgy(listOf("command(git status)", "command(git diff)", "command(git log)",
            "command(git add)", "command(./gradlew)"))
        assertNull(AntigravityGrants.advisory(workspace, writes = true, binary = complete.absolutePath))
        workspace.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
    }

    @Test fun anAllowListThatNeverFinishesRespectsItsDeadline() {
        val fake = fakeAgy(listOf("command(git status)"))
        fake.appendText("sleep 15\n")
        val start = System.nanoTime()
        assertNull(AntigravityGrants.allowed(fake.absolutePath, timeoutSeconds = 1))
        assertTrue((System.nanoTime() - start) / 1e9 < 6, "The reader waited for output beyond its process deadline")
    }

    @Test fun aSlowPermissionAdvisoryDoesNotHoldUpTheHandoff() {
        val fake = fakeAgy(listOf("command(git status)"))
        fake.appendText("sleep 15\n")
        val start = System.nanoTime()
        assertNull(AntigravityGrants.advisory(File("/tmp"), writes = true, binary = fake.absolutePath))
        assertTrue((System.nanoTime() - start) / 1e9 < 6, "The optional permission advisory held up the handoff")
    }

    /** Answers `--print=/permissions` with the tab-separated shape the real CLI prints. */
    private fun fakeAgy(rules: List<String>): File {
        val script = Files.createTempFile("fake-agy", ".sh").toFile()
        script.writeText("#!/bin/sh\n" + rules.joinToString("\n") { "printf 'shared\\tallow\\t$it\\n'" } + "\n")
        script.setExecutable(true)
        script.deleteOnExit()
        return script
    }
}
