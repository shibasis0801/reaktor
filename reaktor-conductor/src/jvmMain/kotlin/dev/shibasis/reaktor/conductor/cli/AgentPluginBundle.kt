package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.mcp.McpPrompt
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files

object AgentPluginBundle {
    enum class Harness(val directory: String, val vendor: HangarSkill.Vendor, val seat: String) {
        Claude("claude", HangarSkill.Vendor.Claude, "claude-code"),
        Codex("codex", HangarSkill.Vendor.Codex, "codex"),
        Antigravity("antigravity", HangarSkill.Vendor.Antigravity, "gemini"),
    }

    fun files(harness: Harness, command: List<String>): Map<String, String> {
        require(command.isNotEmpty() && command.zipWithNext().any { it == ("workspace" to "mcp") }) { "Export needs a workspace mcp launch command" }
        require("--seat" !in command) { "The plugin supplies its harness seat" }
        val following = AgentBundle.workspaceFollowingCommand(command)
        val mcp = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(AgentBundle.SERVER_NAME) {
                    put("command", following.first())
                    putJsonArray("args") { (following.drop(1) + listOf("--seat", harness.seat)).forEach { add(it) } }
                }
            }
        }
        val manifestPath = when (harness) {
            Harness.Claude -> ".claude-plugin/plugin.json"
            Harness.Codex -> ".codex-plugin/plugin.json"
            Harness.Antigravity -> ".antigravity-plugin/plugin.json"
        }
        val mcpPath = if (harness == Harness.Antigravity) "mcp_config.json" else ".mcp.json"
        val manifest = buildJsonObject {
            put("name", "reaktor")
            put("version", "1.0.0")
            put("description", "The Reaktor workspace plane: shared plans, scoped memory and agent coordination")
            putJsonObject("author") { put("name", "Reaktor") }
            if (harness != Harness.Antigravity) {
                put("skills", "./skills/")
                put("mcpServers", "./$mcpPath")
            }
        }
        return buildMap {
            put(manifestPath, manifest.toString() + "\n")
            put(mcpPath, mcp.toString() + "\n")
            put(if (harness == Harness.Antigravity) "rules/AGENTS.md" else "AGENTS.md", guide)
            put("skills/hangar/SKILL.md", HangarSkill.skill(harness.vendor))
            if (harness == Harness.Codex) put("skills/hangar/agents/openai.yaml", HangarSkill.content("codex-agent"))
            HangarWorkflow.prompts.forEach { put("skills/reaktor-${it.name}/SKILL.md", workflow(it)) }
        }
    }

    fun export(destination: File, command: List<String>): List<File> {
        val packages = Harness.entries.associateWith { files(it, command) }
        Files.createDirectory(destination.toPath())
        return packages.map { (harness, files) ->
            File(destination, harness.directory).also { root ->
                files.forEach { (path, text) ->
                    val target = File(root, path)
                    Files.createDirectories(target.parentFile.toPath())
                    Files.writeString(target.toPath(), text)
                }
            }
        }
    }

    private fun workflow(prompt: McpPrompt): String = buildString {
        appendLine("---")
        appendLine("name: reaktor-${prompt.name}")
        appendLine("description: ${prompt.description} through the Reaktor workspace plane. Use for ${prompt.name} in an existing shared Hangar plan.")
        appendLine("---")
        appendLine()
        appendLine("Start by calling the reaktor MCP server's kernel tool with operation plan.read and arguments containing the actual plan thread from the current task. This authenticated door is the source of current claims and plan news.")
        appendLine()
        appendLine("Fetch the reaktor MCP prompt ${prompt.name} with prompts/get. Supply these arguments from the current task, never invented values:")
        prompt.arguments.forEach { appendLine("- ${it.name}${if (it.required) " (required)" else ""}: ${it.description}") }
        appendLine()
        appendLine("Follow the returned workflow and the workspace AGENTS.md. A missing MCP connection or required context must be resolved before work dependent on it; this skill does not replace the plane's instructions.")
        appendLine()
        append(guide)
    }

    private val guide = """
        Read AGENTS.md in the current workspace before changing source. It defines the repository's boundaries and build loops. Discover source with atlas_query and atlas_read through the same Reaktor door.

        For Hangar UI changes, run the dedicated flow task from the workspace:

        ./gradlew :reaktorDesktop:hangarFlows -Dhangar.flows=<ids> -x :exportBuildModel -x :reaktorDesktop:test

        Flow receipts are in targets/reaktorDesktop/build/hangar-shards. Filtered unit tests do not run these flows. Record actual checks and remaining evidence in the shared plan.

        This package is an export. Loading it, enabling hooks and trusting their commands are separate harness actions. Do not claim those actions succeeded from these files alone.
    """.trimIndent() + "\n"
}
