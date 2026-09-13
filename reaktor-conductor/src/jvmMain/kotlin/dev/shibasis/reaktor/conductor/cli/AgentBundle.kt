package dev.shibasis.reaktor.conductor.cli

import kotlinx.serialization.json.*
import java.io.File

/**
 * Installs Reaktor into the harnesses the operator already runs.
 *
 * The point is that a person typing `claude` or `codex` in a terminal, with no desktop open, can
 * reach the same workspace. That means writing a named entry into each harness's own configuration
 * rather than wrapping it — and it means uninstalling has to give the file back unchanged apart
 * from the entry the bundle put there. Nothing here edits a value it did not write.
 */
object AgentBundle {
    /** The workspace: tasks, runs, transcripts. Reached over stdio, so it needs no port. */
    const val SERVER_NAME = "reaktor"

    /**
     * The kernel's graph surface: describe, then bounded queries. A separate entry because it is a
     * separate server with a separate lifetime — the workspace runs per project, the kernel runs
     * with the desktop — and because naming them apart is what lets uninstall remove exactly ours.
     */
    const val GRAPH_SERVER_NAME = "reaktor-graph"

    const val DEFAULT_GRAPH_URL = "http://127.0.0.1:8765/mcp"

    /** Where each harness reads its servers from. Codex is global; Claude's is per project. */
    data class Targets(val codexConfig: File, val claudeProjectConfig: File)

    fun targets(root: File, home: File = File(System.getProperty("user.home"))) = Targets(
        codexConfig = File(home, ".codex/config.toml"),
        claudeProjectConfig = File(root, ".mcp.json"),
    )

    data class Status(
        val codex: Boolean,
        val claude: Boolean,
        val codexGraph: Boolean = false,
        val claudeGraph: Boolean = false,
    )

    fun status(targets: Targets): Status {
        val codexText = targets.codexConfig.takeIf { it.isFile }?.readText().orEmpty()
        val claude = claudeServers(targets.claudeProjectConfig)
        return Status(
            codex = codexText.contains("[mcp_servers.$SERVER_NAME]"),
            claude = claude.containsKey(SERVER_NAME),
            codexGraph = codexText.contains("[mcp_servers.$GRAPH_SERVER_NAME]"),
            claudeGraph = claude.containsKey(GRAPH_SERVER_NAME),
        )
    }

    /**
     * The command a harness runs to reach this workspace.
     *
     * Captured from how this process was launched rather than guessed, because a hardcoded `java
     * -jar` path is wrong the moment anyone packages it differently.
     */
    fun launchCommand(root: File, override: List<String>?): List<String> {
        if (override != null) {
            require(override.isNotEmpty()) { "A launch command needs at least an executable" }
            return override + listOf("workspace", "mcp", "--dir", root.canonicalPath)
        }
        val executable = ProcessHandle.current().info().command().orElse(null)
            ?: error("Could not determine how this process was launched; pass --command")
        val classpath = System.getProperty("java.class.path") ?: error("No classpath to reuse; pass --command")
        return listOf(executable, "-cp", classpath, "dev.shibasis.reaktor.conductor.cli.ConductorCliKt",
            "workspace", "mcp", "--dir", root.canonicalPath)
    }

    fun install(targets: Targets, command: List<String>, graphUrl: String = DEFAULT_GRAPH_URL): List<String> = buildList {
        add(installCodex(targets.codexConfig, SERVER_NAME) { tomlCommand(command) })
        add(installCodex(targets.codexConfig, GRAPH_SERVER_NAME) { "url = ${toml(graphUrl)}\n" })
        add(installClaude(targets.claudeProjectConfig, SERVER_NAME, buildJsonObject {
            put("command", command.first())
            putJsonArray("args") { command.drop(1).forEach { add(it) } }
        }))
        add(installClaude(targets.claudeProjectConfig, GRAPH_SERVER_NAME, buildJsonObject {
            put("type", "http")
            put("url", graphUrl)
        }))
    }

    fun uninstall(targets: Targets): List<String> = buildList {
        add(uninstallCodex(targets.codexConfig, SERVER_NAME))
        add(uninstallCodex(targets.codexConfig, GRAPH_SERVER_NAME))
        add(uninstallClaude(targets.claudeProjectConfig, SERVER_NAME))
        add(uninstallClaude(targets.claudeProjectConfig, GRAPH_SERVER_NAME))
    }

    private fun tomlCommand(command: List<String>) = buildString {
        appendLine("command = ${toml(command.first())}")
        appendLine("args = [${command.drop(1).joinToString(", ") { toml(it) }}]")
    }

    // ---- Codex: a named table appended to config.toml ---------------------------------------

    private fun installCodex(config: File, name: String, body: () -> String): String {
        if (!config.isFile) return "codex/$name: ${config.path} not found; skipped"
        val existing = config.readText()
        if (existing.contains("[mcp_servers.$name]")) return "codex/$name: already installed"
        config.writeText(existing.trimEnd('\n') + "\n\n[mcp_servers.$name]\n" + body())
        return "codex/$name: installed in ${config.path}"
    }

    /**
     * Removes only the bundle's own table.
     *
     * A TOML table runs until the next header, so the removal is bounded by the next line starting
     * with `[` — including `[mcp_servers.reaktor.env]`, which belongs to this entry, and stopping
     * at anything that does not.
     */
    private fun uninstallCodex(config: File, name: String): String {
        if (!config.isFile) return "codex/$name: ${config.path} not found; skipped"
        val lines = config.readText().lines()
        val start = lines.indexOfFirst { it.trim() == "[mcp_servers.$name]" }
        if (start < 0) return "codex/$name: not installed"
        var end = start + 1
        while (end < lines.size) {
            val trimmed = lines[end].trim()
            if (trimmed.startsWith("[") && !trimmed.startsWith("[mcp_servers.$name.")) break
            end++
        }
        // Take the blank line the installer added, but never a line of someone else's.
        val from = if (start > 0 && lines[start - 1].isBlank()) start - 1 else start
        val kept = lines.subList(0, from) + lines.subList(end, lines.size)
        config.writeText(kept.joinToString("\n").trimEnd('\n') + "\n")
        return "codex/$name: removed from ${config.path}"
    }

    private fun toml(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- Claude: a project-scoped .mcp.json --------------------------------------------------

    private fun claudeServers(config: File): Map<String, JsonElement> =
        if (!config.isFile) emptyMap()
        else runCatching {
            (Json.parseToJsonElement(config.readText()).jsonObject["mcpServers"] as? JsonObject).orEmpty()
        }.getOrDefault(emptyMap())

    private fun installClaude(config: File, name: String, entry: JsonObject): String {
        val servers = claudeServers(config)
        if (servers.containsKey(name)) return "claude/$name: already installed"
        write(config, servers + (name to entry))
        return "claude/$name: installed in ${config.path}"
    }

    private fun uninstallClaude(config: File, name: String): String {
        val servers = claudeServers(config)
        if (!servers.containsKey(name)) return "claude/$name: not installed"
        val remaining = servers - name
        // A file that only ever held this entry is removed; one with neighbours keeps them.
        if (remaining.isEmpty() && config.isFile) {
            config.delete()
            return "claude/$name: removed ${config.path}"
        }
        write(config, remaining)
        return "claude/$name: removed from ${config.path}"
    }

    private fun write(config: File, servers: Map<String, JsonElement>) {
        val existing = if (config.isFile) runCatching { Json.parseToJsonElement(config.readText()).jsonObject }
            .getOrDefault(JsonObject(emptyMap())) else JsonObject(emptyMap())
        val merged = JsonObject(existing + ("mcpServers" to JsonObject(servers)))
        config.parentFile?.mkdirs()
        config.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), merged) + "\n")
    }
}
