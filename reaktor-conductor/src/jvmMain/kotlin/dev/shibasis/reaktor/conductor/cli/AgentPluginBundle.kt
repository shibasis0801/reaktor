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
        val workspace = following.zipWithNext().indexOfLast { it == ("workspace" to "mcp") }
        require(workspace > 0) { "Export needs a launcher before workspace mcp" }
        val hooks = hooks(harness, following.take(workspace))
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
                put("hooks", "./hooks/hooks.json")
            }
        }
        return buildMap {
            put(manifestPath, manifest.toString() + "\n")
            put(mcpPath, mcp.toString() + "\n")
            hooks?.let { put("hooks/hooks.json", it.toString() + "\n") }
            put("HOOKS.md", hookGuide(harness))
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

    private fun hooks(harness: Harness, launcher: List<String>): JsonObject? {
        val mode = when (harness) {
            Harness.Claude -> "claude-context"
            Harness.Codex -> "codex-context"
            Harness.Antigravity -> return null
        }
        fun handler(command: String) = buildJsonObject {
            put("type", "command")
            put("command", (launcher + listOf("hook", command)).joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" })
            put("timeout", 10)
        }
        return buildJsonObject {
            putJsonObject("hooks") {
                listOf("SessionStart", "UserPromptSubmit", "PostToolUse").forEach { event ->
                    putJsonArray(event) { add(buildJsonObject { putJsonArray("hooks") { add(handler(mode)) } }) }
                }
                if (harness == Harness.Codex) putJsonArray("Stop") {
                    add(buildJsonObject { putJsonArray("hooks") { add(handler("codex-stop")) } })
                }
            }
        }
    }

    private fun hookGuide(harness: Harness): String = if (harness == Harness.Antigravity) """
        The Antigravity package has no receiving lifecycle hook adapter. Inbox delivery and session-start recall are unverified in a native Antigravity session. Use the authenticated door and prompt-derived skills for explicit recall and inbox reads. Managed Hangar runs use the plane's context delivery.

        Package files do not attest installation, native loading or hook trust. Do not add another harness's hook configuration to this package.
    """.trimIndent() + "\n" else """
        SessionStart calls the running workspace owner's agent.identity and memory.recall operations, using the seat's registered plan when available. Recalled assertions include scope, revision and provenance; source freshness remains unknown until checked against source. The hook does not start an owner or call a model.

        SessionStart, UserPromptSubmit and PostToolUse deliver only the process-authenticated thread's inbox through ThreadInbox. Delivery records consumption; explicit message.ack remains the reader's action. A payload session id cannot select another caller.

        ${if (harness == Harness.Codex) "Stop calls seat.stop through the running owner. Register the current native thread through seat.keep-working with the real plan and paused=false; use paused=true to pause. Hangar registers its interactive Codex runs. Open /hooks in Codex and review the exact hook definitions before trusting them. Changed definitions need a new review. Preserve existing hook sources until a real continuation proves this replacement." else "Review this package and its commands in Claude Code's /hooks view. Native hook configuration and execution remain subject to the person's harness settings."}

        Exporting or enabling this package does not attest native loading, hook trust or delivery. Missing identity or unavailable services produce an empty response. Cancellation stops the hook. Qualify native recall, inbox delivery and Codex continuation separately after installation.
    """.trimIndent() + "\n"

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
