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
    const val SERVER_NAME = "reaktor"

    /** Where each harness reads its servers from. Codex is global; Claude's is per project. */
    data class Targets(val codexConfig: File, val claudeProjectConfig: File)

    fun targets(root: File, home: File = File(System.getProperty("user.home"))) = Targets(
        codexConfig = File(home, ".codex/config.toml"),
        claudeProjectConfig = File(root, ".mcp.json"),
    )

    data class Status(val codex: Boolean, val claude: Boolean)

    fun status(targets: Targets): Status = Status(
        codex = targets.codexConfig.takeIf { it.isFile }?.readText()?.contains("[mcp_servers.$SERVER_NAME]") == true,
        claude = claudeServers(targets.claudeProjectConfig).containsKey(SERVER_NAME),
    )

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

    fun install(targets: Targets, command: List<String>): List<String> = buildList {
        add(installCodex(targets.codexConfig, command))
        add(installClaude(targets.claudeProjectConfig, command))
    }

    fun uninstall(targets: Targets): List<String> = buildList {
        add(uninstallCodex(targets.codexConfig))
        add(uninstallClaude(targets.claudeProjectConfig))
    }

    // ---- Codex: a named table appended to config.toml ---------------------------------------

    private fun installCodex(config: File, command: List<String>): String {
        if (!config.isFile) return "codex: ${config.path} not found; skipped"
        val existing = config.readText()
        if (existing.contains("[mcp_servers.$SERVER_NAME]")) return "codex: already installed"
        val block = buildString {
            appendLine()
            appendLine("[mcp_servers.$SERVER_NAME]")
            appendLine("command = ${toml(command.first())}")
            appendLine("args = [${command.drop(1).joinToString(", ") { toml(it) }}]")
        }
        config.writeText(existing.trimEnd('\n') + "\n" + block)
        return "codex: installed in ${config.path}"
    }

    /**
     * Removes only the bundle's own table.
     *
     * A TOML table runs until the next header, so the removal is bounded by the next line starting
     * with `[` — including `[mcp_servers.reaktor.env]`, which belongs to this entry, and stopping
     * at anything that does not.
     */
    private fun uninstallCodex(config: File): String {
        if (!config.isFile) return "codex: ${config.path} not found; skipped"
        val lines = config.readText().lines()
        val start = lines.indexOfFirst { it.trim() == "[mcp_servers.$SERVER_NAME]" }
        if (start < 0) return "codex: not installed"
        var end = start + 1
        while (end < lines.size) {
            val trimmed = lines[end].trim()
            if (trimmed.startsWith("[") && !trimmed.startsWith("[mcp_servers.$SERVER_NAME.")) break
            end++
        }
        // Take the blank line the installer added, but never a line of someone else's.
        val from = if (start > 0 && lines[start - 1].isBlank()) start - 1 else start
        val kept = lines.subList(0, from) + lines.subList(end, lines.size)
        config.writeText(kept.joinToString("\n").trimEnd('\n') + "\n")
        return "codex: removed from ${config.path}"
    }

    private fun toml(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- Claude: a project-scoped .mcp.json --------------------------------------------------

    private fun claudeServers(config: File): Map<String, JsonElement> =
        if (!config.isFile) emptyMap()
        else runCatching {
            (Json.parseToJsonElement(config.readText()).jsonObject["mcpServers"] as? JsonObject).orEmpty()
        }.getOrDefault(emptyMap())

    private fun installClaude(config: File, command: List<String>): String {
        val servers = claudeServers(config)
        if (servers.containsKey(SERVER_NAME)) return "claude: already installed"
        val entry = buildJsonObject {
            put("command", command.first())
            putJsonArray("args") { command.drop(1).forEach { add(it) } }
        }
        write(config, servers + (SERVER_NAME to entry))
        return "claude: installed in ${config.path}"
    }

    private fun uninstallClaude(config: File): String {
        val servers = claudeServers(config)
        if (!servers.containsKey(SERVER_NAME)) return "claude: not installed"
        val remaining = servers - SERVER_NAME
        // A file that only ever held this entry is removed; one with neighbours keeps them.
        if (remaining.isEmpty() && config.isFile) {
            config.delete()
            return "claude: removed ${config.path}"
        }
        write(config, remaining)
        return "claude: removed from ${config.path}"
    }

    private fun write(config: File, servers: Map<String, JsonElement>) {
        val existing = if (config.isFile) runCatching { Json.parseToJsonElement(config.readText()).jsonObject }
            .getOrDefault(JsonObject(emptyMap())) else JsonObject(emptyMap())
        val merged = JsonObject(existing + ("mcpServers" to JsonObject(servers)))
        config.parentFile?.mkdirs()
        config.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), merged) + "\n")
    }
}
