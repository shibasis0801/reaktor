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

    data class Targets(val codexConfig: File, val claudeProjectConfig: File) {
        val manifest: File get() = File(claudeProjectConfig.parentFile, ".reaktor/agent-bundle.json")
    }

    @Suppress("UNUSED_PARAMETER")
    fun targets(root: File, home: File = File(System.getProperty("user.home"))) = Targets(
        File(root, ".codex/config.toml"), File(root, ".mcp.json"))

    data class Status(val codex: Boolean, val claude: Boolean, val codexGraph: Boolean = false, val claudeGraph: Boolean = false)
    fun status(targets: Targets): Status {
        val text = targets.codexConfig.takeIf { it.isFile }?.readText().orEmpty()
        val servers = readObject(targets.claudeProjectConfig).servers()
        return Status(hasTable(text, SERVER_NAME), SERVER_NAME in servers,
            hasTable(text, GRAPH_SERVER_NAME), GRAPH_SERVER_NAME in servers)
    }

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

    fun install(targets: Targets, command: List<String>, graphUrl: String = DEFAULT_GRAPH_URL): List<String> = locked(targets) {
        require(command.isNotEmpty())
        val workspaceIndex = command.indexOfLast { it == "workspace" }
        require(workspaceIndex >= 0 && command.getOrNull(workspaceIndex + 1) == "mcp") { "Expected a workspace mcp launch command" }
        val graphCommand = command.toMutableList().apply { this[workspaceIndex + 1] = "graph-mcp" } + listOf("--graph-url", graphUrl)
        val configs = listOf(SERVER_NAME to command, GRAPH_SERVER_NAME to graphCommand)
        // Parse before touching either config; malformed JSON must never become an empty config.
        var claude = readObject(targets.claudeProjectConfig)
        var text = targets.codexConfig.takeIf { it.isFile }?.readText().orEmpty()
        val manifest = readObject(targets.manifest).toMutableMap()
        manifest.putIfAbsent("codexCreated", JsonPrimitive(!targets.codexConfig.exists()))
        manifest.putIfAbsent("claudeCreated", JsonPrimitive(!targets.claudeProjectConfig.exists()))
        val owned = (manifest["entries"] as? JsonObject).orEmpty().toMutableMap()
        val messages = mutableListOf<String>()
        configs.forEach { (name, argv) ->
            val key = "codex/$name"
            val chunk = "\n# reaktor-bundle begin $name\n[mcp_servers.$name]\n" +
                "command = ${toml(argv.first())}\nargs = [${argv.drop(1).joinToString(", ") { toml(it) }}]\n" +
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
            val servers = claude.servers()
            val claudeKey = "claude/$name"
            val entry = buildJsonObject { put("command", argv.first()); put("args", JsonArray(argv.drop(1).map(::JsonPrimitive))) }
            when {
                name in servers && owned[claudeKey] != servers[name] -> messages += "$claudeKey: collision; preserved unowned or modified entry"
                else -> {
                    claude = JsonObject(claude + ("mcpServers" to JsonObject(servers + (name to entry))))
                    owned[claudeKey] = entry
                    messages += "$claudeKey: " + if (servers[name] == entry) "already installed" else if (name in servers) "updated owned entry" else "installed in ${targets.claudeProjectConfig.path}"
                }
            }
        }
        writeText(targets.codexConfig, text)
        writeObject(targets.claudeProjectConfig, claude)
        writeObject(targets.manifest, JsonObject(manifest + ("entries" to JsonObject(owned))))
        messages
    }

    fun uninstall(targets: Targets): List<String> = locked(targets) {
        val manifest = readObject(targets.manifest)
        val owned = (manifest["entries"] as? JsonObject).orEmpty()
        var text = targets.codexConfig.takeIf { it.isFile }?.readText().orEmpty()
        var claude = readObject(targets.claudeProjectConfig)
        val messages = mutableListOf<String>()
        owned.forEach { (key, value) ->
            val name = key.substringAfter('/')
            if (key.startsWith("codex/")) {
                val chunk = value.jsonPrimitive.content
                // If a nested table was added after our marker, the entry was extended by its owner.
                val extended = Regex("(?m)^\\s*\\[mcp_servers\\." + Regex.escape(name) + "\\.").containsMatchIn(text)
                if (text.contains(chunk) && !extended) { text = text.replace(chunk, ""); messages += "$key: removed" }
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
        messages.ifEmpty { listOf("No owned entries; existing configuration preserved") }
    }

    private fun <T> locked(targets: Targets, action: () -> T): T = synchronized(this) {
        targets.manifest.parentFile.mkdirs()
        FileChannel.open(File(targets.manifest.parentFile, "agent-bundle.lock").toPath(), CREATE, WRITE).use { channel ->
            channel.lock().use { action() }
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
