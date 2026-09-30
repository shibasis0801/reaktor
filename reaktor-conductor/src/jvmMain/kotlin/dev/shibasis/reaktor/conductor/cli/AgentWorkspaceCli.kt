package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.workspace.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.*
import dev.shibasis.reaktor.tooling.mcp.door.DoorConfig
import dev.shibasis.reaktor.tooling.mcp.door.DoorTransport

internal suspend fun workspaceCli(args: List<String>) {
    val action = args.firstOrNull() ?: "help"
    if (action == "help") {
        println("""
            workspace serve --dir <workspace> [--dry] [--background]
            workspace start|stop --dir <workspace>
            workspace upgrade --dir <workspace> --request <Java-command-array.json>
            workspace call --dir <workspace> --tool <toolName> [--request <arguments.json>] [--remote <SSH-profile.json>]
            workspace mcp --dir <workspace> [--seat <agent>] [--plain]
            workspace info|runs --dir <workspace>
            workspace submit --dir <workspace> --request <AgentSubmission.json>
            workspace read|wait|cancel|resume|activity --dir <workspace> --id <runId> [--after <revision>]
            workspace transcript --dir <workspace> --id <threadId>
            workspace install|uninstall|bundle --dir <workspace> [--command "<executable> <args...>"]
            workspace verify --dir <workspace> [--seat <agent>] [--only <provider,provider>]
            workspace trust --dir <workspace>
            workspace approvals --dir <workspace>
            workspace approve|deny --dir <workspace> --id <approvalId>

            install writes a named MCP entry into your own Codex and Claude configuration, so a
            terminal session reaches this workspace with no desktop open. uninstall removes only
            what install wrote. The desktop, start and mcp attach to a supervised macOS background owner.
            serve runs an explicitly foreground owner; other commands attach.
            mcp is the one stdio server Codex/Claude need: the workspace, the kernel and every provider in
            .reaktor/mcp-providers.json behind one name. --seat records which agent is calling; it is not
            a credential. --plain serves the workspace owner alone, as mcp did before.
            --dry serves only the Echo provider and never invokes a model.
            verify starts every provider behind the mcp server and makes every read that needs no arguments,
            through the same policy an agent meets. It changes nothing and is safe to run at any time.
            trust shows the workspace's provider list (.reaktor/mcp-providers.json) and accepts exactly that
            content. It names commands to run and servers to call, so until a person has accepted it the
            mcp server leaves it out; editing the file means accepting it again.
            approve and deny answer a call the mcp server held back because it would change something
            outside Reaktor. They need a terminal with a person at it: an approval is a person's decision
            about one exact call, and an agent's own shell is not a person.
        """.trimIndent())
        return
    }
    val options = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val key = args[index++]
        require(key in setOf("--dir", "--request", "--id", "--after", "--dry", "--background", "--command", "--graph-url", "--tool", "--remote", "--expected-revision", "--seat", "--plain", "--only")) { "Unknown option: $key" }
        require(key !in options) { "Duplicate option: $key" }
        options[key] = if (key in setOf("--dry", "--background", "--plain")) "true" else args.getOrNull(index++) ?: error("Missing value for $key")
    }
    require(action in setOf("serve", "start", "stop", "resume", "activity", "mcp", "info", "runs", "submit", "read", "wait", "cancel", "transcript",
        "install", "uninstall", "bundle", "graph-mcp", "upgrade", "call", "approvals", "approve", "deny", "trust", "verify"))
    require("--dry" !in options || action == "serve")
    require("--remote" !in options || action in setOf("mcp", "info", "runs", "submit", "read", "wait", "cancel", "resume", "transcript", "activity", "call")) { "Remote connections attach to an already provisioned host" }
    val root = File(options["--dir"] ?: ".").canonicalFile
    if (action == "upgrade") {
        val command = ConductorJson.parseToJsonElement(File(options["--request"] ?: error("--request must name a JSON array with the new Java launch command")).readText()).jsonArray.map { it.jsonPrimitive.content }
        AgentBackgroundService.upgrade(root, command).use { println(it.call("agent_workspace_info")) }; return
    }
    if (action == "stop") { println(AgentBackgroundService.stop(root)); return }
    if (action == "start") { AgentBackgroundService.connect(root).use { println(it.call("agent_workspace_info")) }; return }
    if (action == "graph-mcp") {
        WorkspaceGraphBridge(root, options["--graph-url"]).use { bridge ->
            bridgeStdio(bridge::exchange)
        }
        return
    }

    if (action == "verify") {
        verifyDoor(root, options["--seat"] ?: "verify", options["--only"]?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty())
        return
    }

    if (action == "trust") {
        val list = DoorConfig.workspaceList(root)
        if (!list.isFile) { println("${list.path} does not exist; there is nothing to accept."); return }
        val digest = DoorConfig.digest(list)
        if (WorkspaceDoor.trustedList(root) == digest) { println("Already accepted: ${list.path}"); return }
        val console = System.console() ?: run {
            System.err.println("Not accepted. A provider list is accepted by a person at a terminal; run this command yourself, in your own shell.")
            return
        }
        console.printf("%s names these providers:\n", list.path)
        DoorConfig.readList(list).providers.filter { it.transport != null }.forEach { provider ->
            val what = when (val transport = provider.transport) {
                is DoorTransport.Stdio -> "runs   " + transport.command.joinToString(" ") + (if (transport.secretEnv.isEmpty()) "" else "   secrets: " + transport.secretEnv.keys)
                is DoorTransport.Http -> "calls  " + transport.url + (transport.bearer?.let { "   credential: $it" } ?: "")
                null -> ""
            }
            console.printf("  %-16s %-8s %s\n", provider.id, provider.mode.name, what)
        }
        check(console.readLine("Type 'trust' to accept exactly this content: ")?.trim() == "trust") { "Not accepted." }
        WorkspaceDoor.trustList(root, digest)
        println("Accepted. New sessions mount these providers; editing the file means accepting it again.")
        return
    }

    if (action in setOf("approvals", "approve", "deny")) {
        val approvals = WorkspaceDoor.approvals(root)
        if (action == "approvals") {
            approvals.pending().ifEmpty { println("Nothing is waiting for approval."); return }.forEach { waiting ->
                println("${waiting.id}  ${waiting.provider} ${waiting.call}  ${waiting.effect.name}  seat=${waiting.seat ?: "?"}  " +
                    "expires in ${(waiting.expiresAtEpochMillis - System.currentTimeMillis()) / 1000}s\n    ${waiting.arguments}")
            }
            return
        }
        val id = options["--id"] ?: error("--id is required")
        val waiting = approvals.pending().firstOrNull { it.id == id } ?: run {
            System.err.println("No approval '$id' is waiting; it may have expired or been answered. `workspace approvals` lists what is.")
            return
        }
        val console = System.console() ?: run {
            System.err.println("Not ${if (action == "approve") "approved" else "denied"}. An approval is answered by a person at a terminal; run this command yourself, in your own shell.")
            return
        }
        console.printf("%s wants to call %s %s\n  effect:    %s\n  arguments: %s\n", waiting.seat ?: "An agent", waiting.provider, waiting.call, waiting.effect.name,
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), waiting.arguments).replace("\n", "\n             "))
        if (action == "approve") {
            val typed = console.readLine("Type the approval id to allow exactly this call, once: ")?.trim()
            check(typed == id) { "Not approved: the id did not match." }
        }
        approvals.decide(id, approve = action == "approve", by = System.getProperty("user.name"))
        println(if (action == "approve") "Approved. The agent has to make the same call again; the approval is spent by that call." else "Denied.")
        return
    }

    if (action == "mcp" && "--remote" !in options && "--plain" !in options) {
        options["--seat"]?.let { require(Regex("[a-z][a-z0-9-]{0,31}").matches(it)) { "--seat is a short lowercase name" } }
        coroutineScope {
            val providers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var announce: (JsonElement) -> Unit = {}
            val changed = buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/tools/list_changed") }
            WorkspaceDoor(root, options["--seat"], providers) { announce(changed) }.use { workspace ->
                workspace.door.open()
                try { bridgeStdio({ workspace.door.handle(it) }) { announce = it } } finally { providers.cancel() }
            }
        }
        return
    }

    // Bundle actions touch configuration files, never the workspace owner, so they neither start
    // one nor require one to be running.
    if (action in setOf("install", "uninstall", "bundle")) {
        val targets = AgentBundle.targets(root)
        when (action) {
            "install" -> AgentBundle.install(targets, AgentBundle.compact(
                AgentBundle.launchCommand(root, options["--command"]?.split(" ")?.filter { it.isNotBlank() }),
                AgentWorkspaceConnection.defaultDirectory(root).resolve("launcher.argfile").toFile()))
            "uninstall" -> AgentBundle.uninstall(targets)
            else -> AgentBundle.status(targets).let { status ->
                fun say(present: Boolean) = if (present) "installed" else "not installed"
                listOf("codex: " + say(status.codex), "claude: " + say(status.claude), "antigravity: " + say(status.antigravity), "antigravity graph: " + say(status.antigravityGraph))
            }
        }.forEach(::println)
        return
    }

    val runtimes = if ("--dry" in options) mapOf(RuntimeKind.Echo to EchoRuntime()) else null
    (if (options["--remote"] != null) AgentWorkspaceConnection.remote(ConductorJson.decodeFromString(AgentRemoteProfile.serializer(), File(options.getValue("--remote")).readText()))
        else if (action == "mcp") AgentBackgroundService.connect(root) else
        AgentWorkspaceConnection.open(root, runtimes = runtimes, allowStart = action == "serve", background = "--background" in options,
            hybridConnector = "--connector" in options)).use { connection ->
        if (action == "serve") {
            check(connection.ownsService) { "A workspace owner is already running at ${connection.url}" }
            System.err.println("Agent workspace ${if (runtimes == null) "Codex/Claude" else "Echo dry run"}: ${root.path}\n${connection.url}\nDiscovery: ${connection.discoveryFile}")
            // Publishing is a separate, visible act from serving: the tunnel is its own process and
            // its address is printed once, because that address is the credential.
            val tunnel = connection.hybridConnector?.let { announceConnector(it, options["--tunnel-origin"]) }
            val stopped = CountDownLatch(1)
            val hook = Thread { tunnel?.close(); connection.close(); stopped.countDown() }
            Runtime.getRuntime().addShutdownHook(hook)
            try { withContext(Dispatchers.IO) { stopped.await() } }
            finally { runCatching { Runtime.getRuntime().removeShutdownHook(hook) }; tunnel?.close() }
            return
        }
        if (action == "mcp") {
            bridgeStdio(connection::exchange)
            return
        }
        fun id() = options["--id"] ?: error("--id is required")
        val value = when (action) {
            "call" -> connection.call(options["--tool"] ?: error("--tool is required"), options["--request"]?.let { file ->
                val path = File(file); require(path.length() in 1..250000); ConductorJson.parseToJsonElement(path.readText()).jsonObject
            } ?: buildJsonObject {})
            "info" -> connection.call("agent_workspace_info")
            "runs" -> connection.call("agent_runs")
            "submit" -> {
                val file = File(options["--request"] ?: error("--request is required"))
                require(file.length() in 1..250000)
                val request = ConductorJson.decodeFromString(AgentSubmission.serializer(), file.readText())
                AgentWorkspaceJson.encodeToJsonElement(AgentRunRecord.serializer(), connection.submit(request))
            }
            "transcript" -> connection.call("agent_transcript", buildJsonObject { put("threadId", id()) })
            "resume" -> connection.call("agent_resume", buildJsonObject { put("runId", id()); options["--expected-revision"]?.let { put("expectedRevision", it.toLong()) } })
            "activity" -> connection.call("agent_activity", buildJsonObject { put("runId", id()); put("after", options["--after"]?.toLong() ?: 0) })
            else -> connection.call(when (action) { "read" -> "agent_run"; "wait" -> "agent_wait"; else -> "agent_cancel" },
                buildJsonObject { put("runId", id()); if (action == "wait") put("afterRevision", options["--after"]?.toLong() ?: 0) })
        }
        println(value)
    }
}

private suspend fun bridgeStdio(exchange: suspend (String) -> JsonElement?, emitter: ((JsonElement) -> Unit) -> Unit = {}) = coroutineScope {
    val outputLock = Any()
    emitter { message -> synchronized(outputLock) { println(message) } }
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

/**
 * Brings up the public front for the ChatGPT planning surface and prints where to paste it.
 *
 * Printed to stderr and exactly once, because the address contains the secret that stands in for
 * the credential ChatGPT connectors cannot send. Anyone holding that line holds the connector.
 */
private fun announceConnector(connector: HybridConnector, origin: String?): HybridTunnel? {
    val tunnel = runCatching { HybridTunnel.start(connector.port(), origin = origin) }.getOrElse { failure ->
        System.err.println("Connector is serving on ${connector.url("http://127.0.0.1:" + connector.port())} " +
            "but is not reachable from outside this machine: ${failure.message}")
        return null
    }
    System.err.println(
        "\nChatGPT connector URL — this is the credential, treat it like a password:\n" +
            "  ${tunnel.url(connector)}\n\n" +
            "In ChatGPT: Settings > Apps > Advanced > Developer mode, add it as a custom connector with\n" +
            "No authentication, then ask it to work on your waiting Reaktor tasks.\n" +
            "It stops answering the moment this command exits.\n",
    )
    return tunnel
}
