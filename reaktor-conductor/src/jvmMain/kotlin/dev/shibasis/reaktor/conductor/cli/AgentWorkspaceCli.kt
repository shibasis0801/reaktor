package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.workspace.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.*

internal suspend fun workspaceCli(args: List<String>) {
    val action = args.firstOrNull() ?: "help"
    if (action == "help") {
        println("""
            workspace serve --dir <workspace> [--dry]
            workspace mcp --dir <workspace>
            workspace info|runs --dir <workspace>
            workspace submit --dir <workspace> --request <AgentSubmission.json>
            workspace read|wait|cancel --dir <workspace> --id <runId> [--after <revision>]
            workspace transcript --dir <workspace> --id <threadId>
            workspace install|uninstall|bundle --dir <workspace> [--command "<executable> <args...>"]

            install writes a named MCP entry into your own Codex and Claude configuration, so a
            terminal session reaches this workspace with no desktop open. uninstall removes only
            what install wrote. Start one owner using serve or the desktop; other commands attach.
            mcp is a stdio bridge for Codex/Claude; it reads the private endpoint credential locally.
            --dry serves only the Echo provider and never invokes a model.
        """.trimIndent())
        return
    }
    val options = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val key = args[index++]
        require(key in setOf("--dir", "--request", "--id", "--after", "--dry", "--command", "--graph-url")) { "Unknown option: $key" }
        require(key !in options) { "Duplicate option: $key" }
        options[key] = if (key == "--dry") "true" else args.getOrNull(index++) ?: error("Missing value for $key")
    }
    require(action in setOf("serve", "mcp", "info", "runs", "submit", "read", "wait", "cancel", "transcript",
        "install", "uninstall", "bundle", "graph-mcp"))
    require("--dry" !in options || action == "serve")
    val root = File(options["--dir"] ?: ".").canonicalFile
    if (action == "graph-mcp") {
        WorkspaceGraphBridge(root, options["--graph-url"] ?: AgentBundle.DEFAULT_GRAPH_URL).use { bridge ->
            bridgeStdio(bridge::exchange)
        }
        return
    }

    // Bundle actions touch configuration files, never the workspace owner, so they neither start
    // one nor require one to be running.
    if (action in setOf("install", "uninstall", "bundle")) {
        val targets = AgentBundle.targets(root)
        when (action) {
            "install" -> AgentBundle.install(targets,
                AgentBundle.launchCommand(root, options["--command"]?.split(" ")?.filter { it.isNotBlank() }))
            "uninstall" -> AgentBundle.uninstall(targets)
            else -> AgentBundle.status(targets).let { status ->
                fun say(present: Boolean) = if (present) "installed" else "not installed"
                listOf("codex: " + say(status.codex), "claude: " + say(status.claude))
            }
        }.forEach(::println)
        return
    }

    val runtimes = if ("--dry" in options) mapOf(RuntimeKind.Echo to EchoRuntime()) else null
    AgentWorkspaceConnection.open(root, runtimes = runtimes, allowStart = action == "serve").use { connection ->
        if (action == "serve") {
            check(connection.ownsService) { "A workspace owner is already running at ${connection.url}" }
            System.err.println("Agent workspace ${if (runtimes == null) "Codex/Claude" else "Echo dry run"}: ${root.path}\n${connection.url}\nDiscovery: ${connection.discoveryFile}")
            val stopped = CountDownLatch(1)
            val hook = Thread { connection.close(); stopped.countDown() }
            Runtime.getRuntime().addShutdownHook(hook)
            try { withContext(Dispatchers.IO) { stopped.await() } }
            finally { runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
            return
        }
        if (action == "mcp") {
            bridgeStdio(connection::exchange)
            return
        }
        fun id() = options["--id"] ?: error("--id is required")
        val value = when (action) {
            "info" -> connection.call("agent_workspace_info")
            "runs" -> connection.call("agent_runs")
            "submit" -> {
                val file = File(options["--request"] ?: error("--request is required"))
                require(file.length() in 1..250000)
                val request = ConductorJson.decodeFromString(AgentSubmission.serializer(), file.readText())
                AgentWorkspaceJson.encodeToJsonElement(AgentRunRecord.serializer(), connection.submit(request))
            }
            "transcript" -> connection.call("agent_transcript", buildJsonObject { put("threadId", id()) })
            else -> connection.call(when (action) { "read" -> "agent_run"; "wait" -> "agent_wait"; else -> "agent_cancel" },
                buildJsonObject { put("runId", id()); if (action == "wait") put("afterRevision", options["--after"]?.toLong() ?: 0) })
        }
        println(value)
    }
}

private suspend fun bridgeStdio(exchange: suspend (String) -> JsonElement?) = coroutineScope {
    val outputLock = Any()
    val admission = java.util.concurrent.Semaphore(4)
    val input = System.`in`.reader(Charsets.UTF_8).buffered()
    while (true) {
        // Read a bounded line so a malformed client cannot allocate an unbounded input buffer.
        val line = withContext(Dispatchers.IO) {
            val value = StringBuilder()
            while (true) {
                val char = input.read()
                if (char == -1) return@withContext value.toString().takeIf { it.isNotEmpty() }
                if (char == 10) return@withContext value.toString()
                require(value.length < 250000) { "MCP input line exceeds the transport budget" }
                value.append(char.toChar())
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: break
        if (line.isBlank()) continue
        val parsed = runCatching { ConductorJson.parseToJsonElement(line).jsonObject }.getOrNull()
        val id = parsed?.get("id")
        fun failure(message: String, code: Int): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id ?: JsonNull)
            putJsonObject("error") { put("code", code); put("message", message) }
        }
        if (!admission.tryAcquire()) {
            if (id != null) synchronized(outputLock) { println(failure("Bridge capacity is busy", -32000)) }
            continue
        }
        launch(Dispatchers.IO) {
            try {
                val result = runCatching { exchange(line) }.getOrElse { failure(it.message ?: "Workspace unavailable", -32000) }
                if (result != null) synchronized(outputLock) { println(result) }
            } finally { admission.release() }
        }
    }
}
