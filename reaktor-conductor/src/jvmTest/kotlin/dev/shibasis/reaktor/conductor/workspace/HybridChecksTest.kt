package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.HybridCheck
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The acceptance gate.
 *
 * Two properties matter and they pull against each other: a permitted check must really run and
 * really report its exit code, and an unpermitted one must not run at all. The second is what makes
 * it safe to let a model on the far side of a public connector name commands.
 */
class HybridChecksTest {

    @Test fun apermittedCheckRunsAndItsExitCodeIsTheVerdict() {
        val workspace = Files.createTempDirectory("checks").toFile()
        File(workspace, "ok.sh").also { it.writeText("#!/bin/sh\necho ALL GOOD\nexit 0\n"); it.setExecutable(true) }
        File(workspace, "bad.sh").also { it.writeText("#!/bin/sh\necho BROKEN\nexit 3\n"); it.setExecutable(true) }

        val runs = HybridChecks.run(workspace,
            listOf(HybridCheck("passes", "./ok.sh"), HybridCheck("fails", "./bad.sh")),
            allowed = listOf("./ok.sh", "./bad.sh"))

        assertEquals(listOf(true, false), runs.map { it.ok })
        assertEquals(listOf(0, 3), runs.map { it.exitCode })
        assertTrue(runs[0].output.contains("ALL GOOD"), runs[0].output)
        assertTrue(runs[1].output.contains("BROKEN"), runs[1].output)
        workspace.deleteRecursively()
    }

    @Test fun acommandTheOperatorDidNotPermitIsRefusedRatherThanRun() {
        val workspace = Files.createTempDirectory("checks-refuse").toFile()
        val witness = File(workspace, "ran.txt")
        File(workspace, "sneak.sh").also {
            it.writeText("#!/bin/sh\necho ran > '" + witness.absolutePath + "'\n"); it.setExecutable(true)
        }
        val runs = HybridChecks.run(workspace, listOf(HybridCheck("sneaky", "./sneak.sh")), allowed = listOf("./gradlew"))
        assertFalse(runs.single().ok)
        assertNull(runs.single().exitCode, "a refusal never produced an exit code and must not look like one")
        assertTrue(runs.single().output.startsWith("Refused"), runs.single().output)
        assertFalse(witness.exists(), "the refused command must not have executed")
        workspace.deleteRecursively()
    }

    @Test fun anEmptyAllowListPermitsNothing() {
        val workspace = Files.createTempDirectory("checks-empty").toFile()
        val runs = HybridChecks.run(workspace, listOf(HybridCheck("any", "echo hi")), allowed = emptyList())
        assertFalse(runs.single().ok)
        assertTrue(runs.single().output.contains("no check commands"), runs.single().output)
        workspace.deleteRecursively()
    }

    @Test fun aprefixRuleCoversItsArgumentsButNotAnotherBinary() {
        val workspace = Files.createTempDirectory("checks-prefix").toFile()
        File(workspace, "gradlew").also { it.writeText("#!/bin/sh\nexit 0\n"); it.setExecutable(true) }
        val allowed = listOf("./gradlew")
        assertTrue(HybridChecks.run(workspace, listOf(HybridCheck("build", "./gradlew :engine:compile")), allowed).single().ok)
        assertTrue(HybridChecks.run(workspace, listOf(HybridCheck("other", "./gradlewx")), allowed).single()
            .output.startsWith("Refused"), "a longer binary name is not the permitted one")
        workspace.deleteRecursively()
    }

    @Test fun quotingCannotSmuggleASecondCommand() {
        // The command is argv, never a shell line, so shell metacharacters are literal arguments.
        assertEquals(listOf("./gradlew", "test", "&&", "rm", "-rf", "/"), HybridChecks.tokenize("./gradlew test && rm -rf /"))
        assertEquals(listOf("echo", "one two", "three"), HybridChecks.tokenize("""echo "one two" three"""))
    }
}
