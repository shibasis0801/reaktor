package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.test.*

class AgentPluginBundleTest {
    private val command = listOf("/path with spaces/java", "-cp", "runtime.jar", "Main", "workspace", "mcp", "--dir", "/private/project")

    @Test fun everyHarnessHasOneWorkspaceFollowingDoorAndItsOwnSeat() {
        AgentPluginBundle.Harness.entries.forEach { harness ->
            val files = AgentPluginBundle.files(harness, command)
            val manifest = Json.parseToJsonElement(files.entries.single { it.key.endsWith("plugin.json") }.value).jsonObject
            assertEquals("reaktor", manifest.getValue("name").jsonPrimitive.content)
            val mcpPath = if (harness == AgentPluginBundle.Harness.Antigravity) "mcp_config.json" else ".mcp.json"
            val servers = Json.parseToJsonElement(files.getValue(mcpPath)).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(setOf("reaktor"), servers.keys)
            val server = servers.getValue("reaktor").jsonObject
            assertEquals(command.first(), server.getValue("command").jsonPrimitive.content)
            val args = server.getValue("args").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(command.drop(1).dropLast(2) + listOf("--seat", harness.seat), args)
            assertFalse("--dir" in args)
            assertFalse(files.values.any { "/private/project" in it })
            if (harness != AgentPluginBundle.Harness.Antigravity) {
                assertTrue(files.containsKey(manifest.getValue("mcpServers").jsonPrimitive.content.removePrefix("./")))
            }
            assertTrue(files.keys.all { !it.startsWith("/") && ".." !in it.split("/") })
        }
    }

    @Test fun workflowSkillsFetchThePlanePromptWithItsDeclaredArguments() {
        AgentPluginBundle.Harness.entries.forEach { harness ->
            val files = AgentPluginBundle.files(harness, command)
            HangarWorkflow.prompts.forEach { prompt ->
                val skill = files.getValue("skills/reaktor-${prompt.name}/SKILL.md")
                assertTrue("name: reaktor-${prompt.name}" in skill)
                assertTrue(prompt.description in skill)
                assertTrue(skill.indexOf("operation plan.read") < skill.indexOf("prompts/get"))
                assertTrue("Fetch the reaktor MCP prompt ${prompt.name}" in skill)
                prompt.arguments.forEach { assertTrue("- ${it.name}${if (it.required) " (required)" else ""}: ${it.description}" in skill) }
                assertTrue("AGENTS.md" in skill)
                assertTrue(":reaktorDesktop:hangarFlows -Dhangar.flows=<ids> -x :exportBuildModel -x :reaktorDesktop:test" in skill)
                assertTrue("targets/reaktorDesktop/build/hangar-shards" in skill)
            }
            assertEquals(HangarSkill.skill(harness.vendor), files.getValue("skills/hangar/SKILL.md"))
        }
        val codex = AgentPluginBundle.files(AgentPluginBundle.Harness.Codex, command)
        assertTrue("allow_implicit_invocation: false" in codex.getValue("skills/hangar/agents/openai.yaml"))
    }

    @Test fun exportWritesCompletePackagesAndNeverReplacesAnExistingDestination() {
        val parent = Files.createTempDirectory("plugin-export").toFile()
        try {
            val destination = parent.resolve("packages")
            val roots = AgentPluginBundle.export(destination, command)
            assertEquals(AgentPluginBundle.Harness.entries.map { it.directory }, roots.map { it.name })
            AgentPluginBundle.Harness.entries.forEach { harness ->
                AgentPluginBundle.files(harness, command).forEach { (path, text) ->
                    assertEquals(text, destination.resolve(harness.directory).resolve(path).readText())
                }
            }
            val owned = destination.resolve("claude/AGENTS.md")
            owned.writeText("Person's changes")
            assertFailsWith<FileAlreadyExistsException> { AgentPluginBundle.export(destination, command) }
            assertEquals("Person's changes", owned.readText())
            val linked = parent.resolve("linked")
            Files.createSymbolicLink(linked.toPath(), destination.toPath())
            assertFailsWith<FileAlreadyExistsException> { AgentPluginBundle.export(linked, command) }
            assertEquals("Person's changes", owned.readText())
        } finally { parent.deleteTreeSafely(within = parent.parentFile) }
    }

    @Test fun invalidCommandsCannotCreatePartialExportsOrOverrideTheSeat() {
        val parent = Files.createTempDirectory("plugin-export").toFile()
        try {
            val destination = parent.resolve("packages")
            listOf(emptyList(), listOf("other", "mcp"), command + listOf("--seat", "another")).forEach { invalid ->
                assertFailsWith<IllegalArgumentException> { AgentPluginBundle.export(destination, invalid) }
                assertFalse(destination.exists())
            }
        } finally { parent.deleteTreeSafely(within = parent.parentFile) }
    }
}
