package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.NativeEffort
import dev.shibasis.reaktor.conductor.ReasoningFidelity
import dev.shibasis.reaktor.conductor.RuntimeKind
import java.nio.file.Files
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

    @Test
    fun claudeModelAliasesAreReadFromItsModelFlag() {
        val models = CliCapabilities.parseClaudeModels(claudeModelHelp)
        assertEquals(listOf("fable" to "Fable", "opus" to "Opus", "sonnet" to "Sonnet"), models.map { it.id to it.name })
        val capability = CliCapabilities.claude("2.1.289", claudeModelHelp, defaultModel = "opus")
        assertEquals(models, capability.models)
        assertEquals("Opus", capability.model(capability.defaultModel)?.name)
        assertTrue(CliCapabilities.parseClaudeModels(claudeHelp).isEmpty())
    }

    @Test
    fun codexModelsComeFromItsOwnCacheInPriorityOrderWithTheirEffortLevels() {
        val models = CliCapabilities.parseCodexModels(codexCache)
        assertEquals(listOf("lead" to "Lead", "helper" to "Helper"), models.map { it.id to it.name })
        assertEquals(listOf("low", "high", "max").map(::NativeEffort), models.first().effort?.supported)
        assertEquals(NativeEffort("high"), models.first().effort?.default)
        assertNull(models.last().effort?.supported, "A model that lists no levels keeps an unknown set")
        assertTrue(CliCapabilities.parseCodexModels("{not json").isEmpty())
    }

    @Test
    fun codexDefaultsAreTheTopLevelConfigKeysNotAProfiles() {
        val config = """
            model = "lead"
            model_reasoning_effort = "max"

            [profiles.fast]
            model = "helper"
        """.trimIndent()
        assertEquals("lead", CliCapabilities.parseTomlString(config, "model"))
        assertEquals("max", CliCapabilities.parseTomlString(config, "model_reasoning_effort"))
        assertNull(CliCapabilities.parseTomlString("[profiles.fast]\nmodel = \"helper\"", "model"))
    }

    @Test
    fun theProbeReadsEachHarnessOwnModelFiles() {
        val home = Files.createTempDirectory("capability-home").toFile()
        home.resolve("codex").apply { mkdirs() }.let { codex ->
            codex.resolve("models_cache.json").writeText(codexCache)
            codex.resolve("config.toml").writeText("model = \"helper\"\nmodel_reasoning_effort = \"low\"\n")
        }
        home.resolve(".gemini/antigravity-cli").apply { mkdirs() }.resolve("settings.json").writeText("""{"model":"Gemini 3.5 Flash (High)"}""")
        home.resolve("claude").apply { mkdirs() }.resolve("settings.json").writeText("""{"model":"sonnet"}""")
        val codex = CliCapabilities.probe(RuntimeKind.Codex, "codex-does-not-exist-here", home, mapOf("CODEX_HOME" to home.resolve("codex").path))
        assertEquals(listOf("lead", "helper"), codex.models.map { it.id })
        assertEquals("helper", codex.defaultModel)
        assertEquals(NativeEffort("low"), codex.effort?.default)
        val antigravity = CliCapabilities.probe(RuntimeKind.Gemini, "agy-does-not-exist-here", home, emptyMap())
        assertEquals(antigravityModelNames.keys.toList(), antigravity.models.map { it.id })
        assertEquals(defaultAntigravityModels.first(), antigravity.defaultModel, "Hangar runs its own model ladder, not the CLI's saved pick")
        val claude = CliCapabilities.probe(RuntimeKind.ClaudeCode, "claude-does-not-exist-here", home, mapOf("CLAUDE_CONFIG_DIR" to home.resolve("claude").path))
        assertEquals("sonnet", claude.defaultModel)
        assertTrue(CliCapabilities.probe(RuntimeKind.ChatGptGemini, "agy-does-not-exist-here", home, emptyMap()).models.isEmpty())
    }

    private val codexCache = """
        {"client_version":"0.154.0","etag":"e","fetched_at":"2026-10-18T00:00:00Z","models":[
          {"slug":"helper","display_name":"Helper","priority":2,"visibility":"list","default_reasoning_level":"medium","supported_reasoning_levels":[]},
          {"slug":"hidden","display_name":"Hidden","priority":0,"visibility":"hide","default_reasoning_level":"low","supported_reasoning_levels":[{"effort":"low","description":"d"}]},
          {"slug":"lead","display_name":"Lead","priority":1,"visibility":"list","default_reasoning_level":"high",
           "supported_reasoning_levels":[{"effort":"low","description":"d"},{"effort":"high","description":"d"},{"effort":"max","description":"d"}]}
        ]}
    """.trimIndent()

    private val claudeModelHelp = """
  --model <model>                       Model for the current session. Provide
                                        an alias for the latest model (e.g.
                                        'fable', 'opus', or 'sonnet') or a
                                        model's full name.
  -n, --name <name>                     Set a display name for this session
    """.trimIndent()

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
