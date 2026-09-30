package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.NativeEffort
import dev.shibasis.reaktor.conductor.ReasoningFidelity
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capability discovery against real help output.
 *
 * The fixtures marked CAPTURED are the actual text `claude 2.1.270` and `codex-cli 0.154.0` printed
 * on 2026-09-13. Help formatting belongs to those CLIs, so a parser written against a tidied-up
 * version of it is one that breaks on the next release without anyone noticing.
 */
class CliCapabilitiesTest {
    @Test
    fun claudeEffortLevelsSurviveTheHelpFormattersLineWrap() {
        val levels = CliCapabilities.parseClaudeEfforts(claudeHelp)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max").map(::NativeEffort), levels)
    }

    @Test
    fun aCliWithNoEffortFlagReportsNoneRatherThanAnEmptySet() {
        val older = claudeHelp.lines().filterNot { it.contains("--effort") || it.contains("(low, medium") }.joinToString("\n")
        assertNull(CliCapabilities.parseClaudeEfforts(older))
        val capability = CliCapabilities.claude("2.0.0", older)
        assertNull(capability.effort)
        assertFalse(capability.effortControl.usable, "No flag means no effort control, not a broken one")
        assertTrue(capability.notes.any { it.contains("--effort") })
    }

    @Test
    fun versionsAreReadFromEachCliOwnBanner() {
        assertEquals("2.1.270", CliCapabilities.parseVersion("2.1.270 (Claude Code)"))
        assertEquals("0.154.0", CliCapabilities.parseVersion("codex-cli 0.154.0"))
        assertNull(CliCapabilities.parseVersion(null), "A missing binary has no version, and that is an answer")
        assertNull(CliCapabilities.parseVersion("command not found"))
    }

    @Test
    fun theTwoTransportsReportDifferentReasoningFidelity() {
        // Verified 2026-09-13: claude stream-json emits real thinking blocks; codex exec --json
        // emits no reasoning items at all, so claiming a summary here would be an invention.
        assertEquals(ReasoningFidelity.Thinking, CliCapabilities.claude("2.1.270", claudeHelp).reasoning)
        val codex = CliCapabilities.codex("0.154.0")
        assertEquals(ReasoningFidelity.Unavailable, codex.reasoning)
        assertFalse(codex.reasoningControl.usable)
        assertTrue(codex.notes.any { it.contains("App Server") })
    }

    @Test
    fun codexAdvertisesEffortWithAnUnknownSupportedSet() {
        val codex = CliCapabilities.codex("0.154.0")
        assertEquals(RuntimeKind.Codex, codex.runtime)
        assertTrue(codex.effortControl.usable)
        assertNull(codex.effort?.supported, "The set cannot be enumerated without a terminal")
        assertTrue(codex.effort!!.accepts(NativeEffort("xhigh")))
    }

    @Test
    fun aMissingBinaryYieldsAnUnusableCapabilityRatherThanAnError() {
        val absent = CliCapabilities.probe(RuntimeKind.Codex, binary = "codex-does-not-exist-here")
        assertNull(absent.version)
        assertFalse(absent.effortControl.usable)
    }

    /** CAPTURED: the relevant window of `claude --help`, wrapping and all. */
    private val claudeHelp = """
  --disable-slash-commands              Disable all skills
  --disallowedTools, --disallowed-tools <tools...>
      Comma or space-separated list of tool names to deny (e.g. "Bash(git *)
      Edit")
  --effort <level>                      Effort level for the current session
                                        (low, medium, high, xhigh, max)
  --environment <environment_id>        Create a new cloud session that runs on
                                        the given self-hosted environment
                                        (ccpool_...).
    """.trimIndent()
}
