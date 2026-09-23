package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.workspace.atomicWrite
import kotlinx.serialization.json.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*

object AgentBundle {
    const val SERVER_NAME = "reaktor"
    const val GRAPH_SERVER_NAME = "reaktor-graph"
    const val DEFAULT_GRAPH_URL = "http://127.0.0.1:8765/mcp"

    /**
     * Where each harness actually reads its servers from.
     *
     * Codex and Claude are configured per project, so their entries live beside the source they
     * serve. Antigravity has no project-scoped MCP configuration — `agy` reads one file per user —
     * so [antigravityConfig] is a machine-wide file the operator shares with every other project,
     * and it is passed in rather than derived so no caller can reach the real home by accident.
     */
    data class Targets(val codexConfig: File, val claudeProjectConfig: File, val antigravityConfig: File) {
        val manifest: File get() = File(claudeProjectConfig.parentFile, ".reaktor/agent-bundle.json")
    }

    fun targets(root: File, home: File = File(System.getProperty("user.home"))) = Targets(
        File(root, ".codex/config.toml"), File(root, ".mcp.json"), File(home, ".gemini/config/mcp_config.json"))

    data class Status(val codex: Boolean, val claude: Boolean, val codexGraph: Boolean = false, val claudeGraph: Boolean = false,
        val antigravity: Boolean = false, val antigravityGraph: Boolean = false)
    fun status(targets: Targets): Status {
        val text = targets.codexConfig.takeIf { it.isFile }?.readText().orEmpty()
        val servers = readObject(targets.claudeProjectConfig).servers()
        return Status(hasTable(text, SERVER_NAME), SERVER_NAME in servers,
            hasTable(text, GRAPH_SERVER_NAME), GRAPH_SERVER_NAME in servers,
            SERVER_NAME in readObject(targets.antigravityConfig).servers(), GRAPH_SERVER_NAME in readObject(targets.antigravityConfig).servers())
    }

    /**
     * The same bridge with its workspace left to the harness's working directory.
     *
     * One file serves every project here, so pinning `--dir` would point Gemini at whichever
     * workspace happened to be installed last. Without it the bridge resolves the directory `agy`
     * was started in, and says so plainly when that is not a Reaktor workspace.
     */
    fun workspaceFollowingCommand(command: List<String>): List<String> {
        val directory = command.indexOfLast { it == "--dir" }
        return if (directory < 0) command else command.filterIndexed { index, _ -> index != directory && index != directory + 1 }
    }

    fun launchCommand(root: File, override: List<String>?): List<String> {
        if (override != null) {
            require(override.isNotEmpty()) { "A launch command needs at least an executable" }
            return override + listOf("workspace", "mcp", "--dir", root.canonicalPath)
        }
        val javaName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val executable = File(System.getProperty("java.home"), "bin/$javaName").also {
            require(it.canExecute()) { "This runtime has no Java launcher; configure a service launch command" }
        }.absolutePath
        val classpath = System.getProperty("java.class.path") ?: error("No classpath to reuse; pass --command")
        return listOf(executable, "-cp", classpath, "dev.shibasis.reaktor.conductor.cli.ConductorCliKt",
            "workspace", "mcp", "--dir", root.canonicalPath)
    }

    /**
     * One entry per harness. The kernel used to need a second one; it is a provider behind this
     * server now, so an earlier install's `reaktor-graph` entry is retired here, if it is still ours.
     */
    fun install(targets: Targets, command: List<String>): List<String> = locked(targets) {
        require(command.isNotEmpty())
        val workspaceIndex = command.indexOfLast { it == "workspace" }
        require(workspaceIndex >= 0 && command.getOrNull(workspaceIndex + 1) == "mcp") { "Expected a workspace mcp launch command" }
        // Parse before touching either config; malformed JSON must never become an empty config.
        var claude = readObject(targets.claudeProjectConfig)
        var antigravity = readObject(targets.antigravityConfig)
        var text = targets.codexConfig.takeIf { it.isFile }?.readText().orEmpty()
        val manifest = readObject(targets.manifest).toMutableMap()
        manifest.putIfAbsent("codexCreated", JsonPrimitive(!targets.codexConfig.exists()))
        manifest.putIfAbsent("claudeCreated", JsonPrimitive(!targets.claudeProjectConfig.exists()))
        manifest.putIfAbsent("antigravityCreated", JsonPrimitive(!targets.antigravityConfig.exists()))
        val owned = (manifest["entries"] as? JsonObject).orEmpty().toMutableMap()
        val messages = mutableListOf<String>()
        val name = SERVER_NAME
        // The seat says which harness is calling. It is written here, per harness, so a call can be attributed; it grants nothing.
        fun seated(seat: String) = command + listOf("--seat", seat)
        fun entry(argv: List<String>) = buildJsonObject { put("command", argv.first()); put("args", JsonArray(argv.drop(1).map(::JsonPrimitive))) }

        val codexArgv = seated("codex")
        val key = "codex/$name"
        val chunk = "\n# reaktor-bundle begin $name\n[mcp_servers.$name]\n" +
            "command = ${toml(codexArgv.first())}\nargs = [${codexArgv.drop(1).joinToString(", ") { toml(it) }}]\n" +
            "# reaktor-bundle end $name\n"
        val previous = (owned[key] as? JsonPrimitive)?.content
        when {
            previous != null && text.contains(previous) -> {
                text = text.replace(previous, chunk)
                owned[key] = JsonPrimitive(chunk)
                messages += "$key: " + if (previous == chunk) "already installed" else "updated owned entry"
            }
            hasTable(text, name) -> messages += "$key: collision; preserved unowned or modified entry"
            else -> { text += chunk; owned[key] = JsonPrimitive(chunk); messages += "$key: installed in ${targets.codexConfig.path}" }
        }

        val agyServers = antigravity.servers()
        val agyKey = "antigravity/$name"
        val agyEntry = entry(workspaceFollowingCommand(seated("gemini")))
        if (name in agyServers && owned[agyKey] != agyServers[name]) messages += "$agyKey: collision; preserved unowned or modified entry"
        else {
            antigravity = JsonObject(antigravity + ("mcpServers" to JsonObject(agyServers + (name to agyEntry))))
            owned[agyKey] = agyEntry
            messages += "$agyKey: " + if (agyServers[name] == agyEntry) "already installed"
                else if (name in agyServers) "updated owned entry"
                else "installed for every Antigravity project in ${targets.antigravityConfig.path}; it serves the workspace agy is started in"
        }

        val servers = claude.servers()
        val claudeKey = "claude/$name"
        val claudeEntry = entry(seated("claude-code"))
        if (name in servers && owned[claudeKey] != servers[name]) messages += "$claudeKey: collision; preserved unowned or modified entry"
        else {
            claude = JsonObject(claude + ("mcpServers" to JsonObject(servers + (name to claudeEntry))))
            owned[claudeKey] = claudeEntry
            messages += "$claudeKey: " + if (servers[name] == claudeEntry) "already installed" else if (name in servers) "updated owned entry" else "installed in ${targets.claudeProjectConfig.path}"
        }

        listOf("codex", "claude", "antigravity").forEach { harness ->
            val graphKey = "$harness/$GRAPH_SERVER_NAME"
            val value = owned[graphKey] ?: return@forEach
            val retired = when (harness) {
                "codex" -> value.jsonPrimitive.content.let { old -> text.contains(old).also { if (it) text = text.replace(old, "") } }
                "claude" -> (claude.servers()[GRAPH_SERVER_NAME] == value).also { if (it) claude = JsonObject(claude + ("mcpServers" to JsonObject(claude.servers() - GRAPH_SERVER_NAME))) }
                else -> (antigravity.servers()[GRAPH_SERVER_NAME] == value).also { if (it) antigravity = JsonObject(antigravity + ("mcpServers" to JsonObject(antigravity.servers() - GRAPH_SERVER_NAME))) }
            }
            owned.remove(graphKey)
            messages += "$graphKey: " + if (retired) "retired; the kernel is now a provider behind $name" else "modified; preserved, and no longer tracked"
        }

        // Registering the server is not the same as being allowed to call it: Antigravity defaults
        // `mcp` to Ask, and a headless turn cannot answer a prompt, so it reports the denial
        // instead. Reaktor does not edit the operator's permission policy; it names the rule.
        if (messages.any { it.startsWith("antigravity/$name") && !it.contains("collision") && !it.contains("already installed") })
            messages += "antigravity: add \"mcp(reaktor/*)\" to permissions.allow in " +
                File(targets.antigravityConfig.parentFile.parentFile, "antigravity-cli/settings.json").path +
                "; without it a headless Gemini turn reports these tools as denied"
        writeText(targets.codexConfig, text)
        writeObject(targets.claudeProjectConfig, claude)
        writeObject(targets.antigravityConfig, antigravity)
        writeObject(targets.manifest, JsonObject(manifest + ("entries" to JsonObject(owned))))
        messages
    }

    /**
     * A Java launch with its classpath moved into an argument file.
     *
     * The classpath of a packaged app runs to tens of kilobytes. Written into three harness configs
     * it makes them unreadable and pins each one to a build; in a file beside the workspace's other
     * state, a reinstall refreshes every harness at once and the entries themselves never change.
     */
    fun compact(command: List<String>, argfile: File): List<String> {
        val classpath = command.indexOf("-cp").takeIf { it == 1 && command.size > 3 } ?: return command
        if (command[classpath + 1].length < 2_000) return command
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        writeText(argfile, "-cp\n${quoted(command[classpath + 1])}\n${command[classpath + 2]}\n")
        return listOf(command.first(), "@${argfile.absolutePath}") + command.drop(classpath + 3)
    }

    fun uninstall(targets: Targets): List<String> = locked(targets) {
        val manifest = readObject(targets.manifest)
        val owned = (manifest["entries"] as? JsonObject).orEmpty()
        var text = targets.codexConfig.takeIf { it.isFile }?.readText().orEmpty()
        var claude = readObject(targets.claudeProjectConfig)
        var antigravity = readObject(targets.antigravityConfig)
        val messages = mutableListOf<String>()
        owned.forEach { (key, value) ->
            val name = key.substringAfter('/')
            if (key.startsWith("codex/")) {
                val chunk = value.jsonPrimitive.content
                // If a nested table was added after our marker, the entry was extended by its owner.
                val extended = Regex("(?m)^\\s*\\[mcp_servers\\." + Regex.escape(name) + "\\.").containsMatchIn(text)
                if (text.contains(chunk) && !extended) { text = text.replace(chunk, ""); messages += "$key: removed" }
                else messages += "$key: modified; preserved"
            } else if (key.startsWith("antigravity/")) {
                val servers = antigravity.servers()
                if (servers[name] == value) { antigravity = JsonObject(antigravity + ("mcpServers" to JsonObject(servers - name))); messages += "$key: removed" }
                else messages += "$key: modified; preserved"
            } else if (key.startsWith("claude/")) {
                val servers = claude.servers()
                if (servers[name] == value) {
                    claude = JsonObject(claude + ("mcpServers" to JsonObject(servers - name)))
                    messages += "$key: removed"
                } else messages += "$key: modified; preserved"
            }
        }
        if (targets.codexConfig.exists()) {
            if (text.isEmpty() && manifest["codexCreated"] == JsonPrimitive(true)) targets.codexConfig.delete()
            else writeText(targets.codexConfig, text)
        }
        if (targets.claudeProjectConfig.exists()) {
            if (claude.keys == setOf("mcpServers") && claude.servers().isEmpty() && manifest["claudeCreated"] == JsonPrimitive(true)) targets.claudeProjectConfig.delete()
            else writeObject(targets.claudeProjectConfig, claude)
        }
        targets.manifest.delete()
        if (targets.antigravityConfig.exists()) {
            if (antigravity.keys == setOf("mcpServers") && antigravity.servers().isEmpty() && manifest["antigravityCreated"] == JsonPrimitive(true)) targets.antigravityConfig.delete()
            else writeObject(targets.antigravityConfig, antigravity)
        }
        messages.ifEmpty { listOf("No owned entries; existing configuration preserved") }
    }

    /**
     * The project's lock, and a second one beside the shared Antigravity file.
     *
     * The manifest lock is per project, which was enough while every target was too. Antigravity's
     * is one file for the whole machine, so two projects installing at once would otherwise write
     * over each other's entry through it.
     */
    private fun <T> locked(targets: Targets, action: () -> T): T = synchronized(this) {
        targets.manifest.parentFile.mkdirs()
        targets.antigravityConfig.parentFile.mkdirs()
        FileChannel.open(File(targets.manifest.parentFile, "agent-bundle.lock").toPath(), CREATE, WRITE).use { project ->
            project.lock().use {
                FileChannel.open(File(targets.antigravityConfig.parentFile, ".reaktor-agent-bundle.lock").toPath(), CREATE, WRITE).use { shared ->
                    shared.lock().use { action() }
                }
            }
        }
    }
    private fun hasTable(text: String, name: String): Boolean =
        Regex("(?m)^\\s*\\[\\s*mcp_servers\\.(?:" + Regex.escape(name) + "|\"" + Regex.escape(name) + "\"|'" + Regex.escape(name) + "')(?:\\]|\\.)").containsMatchIn(text)
    private fun readObject(file: File): JsonObject = if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject else buildJsonObject {}
    private fun JsonObject.servers(): JsonObject = get("mcpServers")?.jsonObject ?: buildJsonObject {}
    private fun toml(value: String): String = JsonPrimitive(value).toString()
    private fun writeText(file: File, value: String) { file.parentFile.mkdirs(); atomicWrite(file.toPath(), value) }
    private fun writeObject(file: File, value: JsonObject) = writeText(file, value.toString() + "\n")
}
