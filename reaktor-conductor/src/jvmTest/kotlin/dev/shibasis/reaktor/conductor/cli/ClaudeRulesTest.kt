package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ClaudeRulesTest {
    private fun request(arguments: List<String> = emptyList(), directory: String = "/tmp") = AgentRequest(
        AgentSpec(AgentId("claude"), "Claude", RuntimeKind.ClaudeCode, "Read the shared rules", harnessArgs = arguments),
        "Read the shared rules", directory)

    private fun settings(argv: List<String>): JsonObject {
        assertEquals(1, argv.count { it == "--settings" })
        assertFalse(argv.any { it.startsWith("--settings=") })
        return Json.parseToJsonElement(argv[argv.indexOf("--settings") + 1]).jsonObject
    }

    @Test fun everyTurnLoadsBothInstructionFilesIncludingResume() {
        for (resume in listOf(null, ProviderSession(RuntimeKind.ClaudeCode, "existing-session"))) {
            val argv = claudeArgv(request().copy(resume = resume))
            assertEquals("claude-md-and-agents-md", settings(argv)["pluginConfigs"]!!.jsonObject["agents-md@builtin"]!!.jsonObject["options"]!!.jsonObject["instructionFiles"]!!.jsonPrimitive.content)
            if (resume != null) assertTrue(argv.windowed(2).contains(listOf("--resume", resume.sessionId)))
        }
    }

    @Test fun inlineSettingsKeepPermissionsAndOtherPluginOptions() {
        val argv = claudeArgv(request(listOf("--permission-mode", "default", "--settings=" + """{"permissions":{"ask":["Write"]},"pluginConfigs":{"cc-plugin-agents-md@builtin":{"options":{"instructionFiles":"claude-md","other":true}},"another":{"enabled":true}}}""")))
        val settings = settings(argv)
        assertEquals("Write", settings["permissions"]!!.jsonObject["ask"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(argv.windowed(2).contains(listOf("--permission-mode", "default")))
        val plugins = settings["pluginConfigs"]!!.jsonObject
        assertTrue(plugins["another"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        val options = plugins["cc-plugin-agents-md@builtin"]!!.jsonObject["options"]!!.jsonObject
        assertEquals("claude-md-and-agents-md", options["instructionFiles"]!!.jsonPrimitive.content)
        assertTrue(options["other"]!!.jsonPrimitive.boolean)
    }

    @Test fun settingsFilesResolveInTheWorkspaceAndLastSettingsKeepNativePrecedence() {
        val root = Files.createTempDirectory("claude-rules").toFile()
        try {
            root.resolve("custom settings.json").writeText("""{"permissions":{"deny":["Bash"]},"disableAllHooks":true}""")
            val argv = claudeArgv(request(listOf("--settings", """{"permissions":{"ask":["Write"]}}""", "--settings", "custom settings.json"), root.path))
            val settings = settings(argv)
            val permissions = settings["permissions"]!!.jsonObject
            assertNull(permissions["ask"])
            assertEquals("Bash", permissions["deny"]!!.jsonArray.single().jsonPrimitive.content)
            assertTrue(settings["disableAllHooks"]!!.jsonPrimitive.boolean)
            assertNotNull(settings["pluginConfigs"])
        } finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun nativeRecordedStartupShowsSharedRulesWithoutAnApiTurn() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/cli/claude-2.1.299-rules-startup.json")!!.readText()).jsonObject
        assertEquals("2.1.299", fixture["version"]!!.jsonPrimitive.content)
        val before = fixture["before"]!!.jsonObject
        val shared = fixture["shared"]!!.jsonObject
        assertEquals(settings(claudeArgv(request()))["pluginConfigs"], shared["settings"]!!.jsonObject["pluginConfigs"])
        val beforeFiles = before["memoryFiles"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        val sharedFiles = shared["memoryFiles"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        assertFalse(beforeFiles.any { it.endsWith("AGENTS.md") })
        assertTrue(sharedFiles.containsAll(beforeFiles))
        assertTrue(sharedFiles.any { it.endsWith("AGENTS.md") })
        assertTrue(sharedFiles.any { it.endsWith("CLAUDE.md") })
        assertEquals(854, shared["memoryFiles"]!!.jsonArray.sumOf { it.jsonObject["tokens"]!!.jsonPrimitive.int } - before["memoryFiles"]!!.jsonArray.sumOf { it.jsonObject["tokens"]!!.jsonPrimitive.int })
        for (capture in listOf(before, shared)) {
            assertEquals(0, capture["result"]!!.jsonObject["num_turns"]!!.jsonPrimitive.int)
            assertEquals(0, capture["result"]!!.jsonObject["duration_api_ms"]!!.jsonPrimitive.int)
            assertEquals(0.0, capture["result"]!!.jsonObject["total_cost_usd"]!!.jsonPrimitive.double)
        }
    }
}
