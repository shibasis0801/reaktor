package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.workspace.AgentBackgroundService
import dev.shibasis.reaktor.conductor.workspace.AgentWorkspaceConnection
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.mcp.door.CallLog
import dev.shibasis.reaktor.tooling.mcp.door.CallSnapshots
import dev.shibasis.reaktor.tooling.mcp.door.ChildEnvironment
import dev.shibasis.reaktor.tooling.mcp.door.DoorConfig
import dev.shibasis.reaktor.tooling.mcp.door.DoorApprovals
import dev.shibasis.reaktor.tooling.mcp.door.DoorCredentials
import dev.shibasis.reaktor.tooling.mcp.door.DoorPolicy
import dev.shibasis.reaktor.tooling.mcp.door.DoorMode
import dev.shibasis.reaktor.tooling.mcp.door.DoorMount
import dev.shibasis.reaktor.tooling.mcp.door.DoorProviderConfig
import dev.shibasis.reaktor.tooling.mcp.door.DoorTransport
import dev.shibasis.reaktor.tooling.mcp.door.FunctionMcpLink
import dev.shibasis.reaktor.tooling.mcp.door.McpDoor
import dev.shibasis.reaktor.tooling.mcp.door.McpLinkProvider
import dev.shibasis.reaktor.tooling.mcp.door.StdioMcpLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.shibasis.reaktor.tooling.mcp.door.HttpMcpLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Everything this workspace offers an agent, behind the one server the harness already knows.
 *
 * The workspace owner and the kernel stay the separate processes they were. They are providers
 * here, reached the way the two bridges reached them, so neither learns that anything changed.
 */
internal class WorkspaceDoor(private val root: File, private val seat: String?, scope: CoroutineScope, onCallsChanged: () -> Unit) : AutoCloseable {
    private val connecting = Mutex()
    @Volatile private var owner: AgentWorkspaceConnection? = null
    private val graph = WorkspaceGraphBridge(root)
    private val desktopEndpoint = LoopbackNamed("reaktor-desktop", 8765..8774)
    private val children = mutableListOf<McpLinkProvider>()
    private val credentials = DoorCredentials(workspace = root)

    val door: McpDoor

    init {
        val loaded = DoorConfig.load(root) { digest -> trustedList(root) == digest }
        val configured = loaded.providers.associateBy(DoorProviderConfig::id)
        fun builtIn(id: String, title: String, safety: SafetyClass, hint: String, send: suspend (String) -> JsonElement?): DoorMount {
            val entry = configured[id]
            return DoorMount(McpLinkProvider(id, FunctionMcpLink(id, send), safety, entry?.hide.orEmpty().toSet()),
                entry?.mode ?: DoorMode.OnDemand, entry?.prefix.orEmpty(), entry?.title ?: title, entry?.offlineHint ?: hint, selfGoverned = true)
        }
        val desktop = configured[DESKTOP].let { entry ->
            val link = HttpMcpLink(DESKTOP, desktopEndpoint::find, cheap = true, timeout = Duration.ofSeconds(35), whenMissing = "the desktop app is not running")
            DoorMount(McpLinkProvider(DESKTOP, link, SafetyClass.LiveRead, entry?.hide.orEmpty().toSet()), entry?.mode ?: DoorMode.OnDemand,
                entry?.prefix.orEmpty(), entry?.title ?: "Reaktor desktop", entry?.offlineHint ?: "Start the Reaktor desktop app.", selfGoverned = true)
        }
        val mounts = listOf(
            builtIn(WORKSPACE, "Agent workspace", SafetyClass.UnknownRemoteEffect, "Run `workspace start --dir ${root.path}`.") { owner().exchange(it) },
            builtIn(KERNEL, "Reaktor kernel", SafetyClass.LiveRead, "Start the Reaktor desktop app, or run `reaktor mcp` in ${root.path}.") { graph.exchange(it) },
            // After the kernel: both answer the same reads, the kernel is bound to this workspace, and the desktop stands in when it is the one running.
            desktop,
        ) + configured.values.filter { it.id !in BUILT_IN }.map { entry ->
            val link = when (val transport = entry.transport ?: error("${DoorConfig.PATH}: provider '${entry.id}' needs a transport")) {
                is DoorTransport.Stdio -> StdioMcpLink(entry.id, ChildEnvironment.resolve(transport, root, seat),
                    transport.cwd?.let { File(it.replace(ChildEnvironment.WORKSPACE, root.path)).let { dir -> if (dir.isAbsolute) dir else File(root, dir.path) } } ?: root,
                    ChildEnvironment.of(transport, root), scope, transport.startupTimeoutMillis, transport.callTimeoutMillis, transport.idleStopMillis, onCallsChanged,
                    secrets = { transport.secretEnv.mapValues { credentials.resolve(it.value) } })
                is DoorTransport.Http -> {
                    val target = URI(transport.url)
                    val secret = listOfNotNull(transport.bearer) + transport.secretHeaders.values
                    HttpMcpLink(entry.id, { target }, headers = {
                        transport.headers + transport.secretHeaders.mapValues { credentials.resolve(it.value) } +
                            (transport.bearer?.let { mapOf("Authorization" to "Bearer ${credentials.resolve(it)}") } ?: emptyMap())
                    }, cheap = transport.local, timeout = Duration.ofMillis(transport.callTimeoutMillis), onRejected = { secret.forEach(credentials::forget) },
                        signIn = secret.firstNotNullOfOrNull { it.whenMissing }?.let { "the server refused the credential. $it" })
                }
            }
            DoorMount(McpLinkProvider(entry.id, link, entry.safety, entry.hide.toSet()).also(children::add),
                entry.mode, entry.prefix ?: entry.id.replace('-', '_'), entry.title ?: entry.id, entry.offlineHint,
                callClasses = entry.calls, trustReadOnlyHint = entry.trustReadOnlyHint, sqlArguments = entry.sqlArguments)
        }
        val directory = AgentWorkspaceConnection.defaultDirectory(root).resolve("door")
        val caller = CallCaller(seat, System.getenv(RUN_ID)?.takeIf(String::isNotBlank))
        val policy = DoorPolicy.load(root)
        val approvals = approvals(root, policy)
        val notices = listOfNotNull(loaded.untrusted?.let {
            "This workspace has a provider list nobody has accepted yet, so its providers are not mounted. A person runs `workspace trust --dir ${root.path}` in a terminal to read and accept it."
        })
        door = McpDoor(mounts, CallSnapshots(directory.resolve("snapshots")), CallLog(directory.resolve("calls.jsonl"), caller), caller, scope,
            onCallsChanged = onCallsChanged, policy = policy, approvals = approvals, workspace = root.path, notices = notices,
            onApprovalNeeded = { waiting -> if (policy.approvalDialog) scope.launch(Dispatchers.IO) { ApprovalDialog.ask(waiting, approvals) } })
    }

    /** Attached on first use. An owner that cannot start costs the workspace provider, not the door. */
    private suspend fun owner(): AgentWorkspaceConnection = owner ?: connecting.withLock {
        owner ?: AgentBackgroundService.connect(root).also { owner = it }
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { children.forEach { runCatching { it.close() } } }
        runCatching { owner?.close() }
        graph.close()
    }

    companion object {
        /** What `workspace verify` may call on a provider that does not say which of its calls only read. */
        fun probes(root: File): Map<String, List<String>> =
            DoorConfig.load(root) { digest -> trustedList(root) == digest }.providers.associate { it.id to it.probe }

        private fun trustFile(root: File) = AgentWorkspaceConnection.defaultDirectory(root).resolve("door").resolve("trusted-providers")

        /** The digest of the workspace's provider list a person last accepted, if any. */
        fun trustedList(root: File): String? = trustFile(root).takeIf(java.nio.file.Files::isRegularFile)?.let { java.nio.file.Files.readString(it).trim() }

        fun trustList(root: File, digest: String) { trustFile(root).also { java.nio.file.Files.createDirectories(it.parent) }.let { java.nio.file.Files.writeString(it, digest + "\n") } }

        /** One directory for the door that asks and the terminal that answers. */
        fun approvals(root: File, policy: DoorPolicy = DoorPolicy.load(root)) =
            DoorApprovals(AgentWorkspaceConnection.defaultDirectory(root).resolve("door").resolve("approvals"), policy.approvalMinutes)

        const val WORKSPACE = "workspace"
        const val KERNEL = "kernel"
        const val DESKTOP = "desktop"
        val BUILT_IN = setOf(WORKSPACE, KERNEL, DESKTOP)
        const val RUN_ID = "REAKTOR_AGENT_RUN_ID"
    }
}

/**
 * Finds a loopback server by the name it gives itself rather than by a port it may not have got.
 *
 * The desktop takes the first free port of ten, and the same range is the kernel's default, so a
 * listening port says nothing about who is behind it.
 */
private class LoopbackNamed(private val name: String, private val ports: IntRange) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
    @Volatile private var found: Int? = null

    fun find(): URI? {
        val port = found?.takeIf(::listening) ?: ports.firstOrNull { listening(it) && named(it) }.also { found = it }
        return port?.let { URI("http://127.0.0.1:$it/mcp") }
    }

    private fun listening(port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) } }.isSuccess

    private fun named(port: Int): Boolean = runCatching {
        val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 200 && (Json.parseToJsonElement(response.body()).jsonObject["name"] as? JsonPrimitive)?.contentOrNull == name
    }.getOrDefault(false)
}

/**
 * A second face for an approval, for a person who is not looking at a terminal.
 *
 * It is a window the operating system draws, so answering it takes a hand on the machine. An agent
 * that has been given control of the screen could click it; one that has only a shell cannot.
 */
internal object ApprovalDialog {
    fun ask(waiting: dev.shibasis.reaktor.tooling.mcp.door.DoorApproval, approvals: DoorApprovals) {
        if (!System.getProperty("os.name").lowercase().contains("mac") || System.getenv("REAKTOR_DOOR_NO_DIALOG") == "1") return
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val text = "${waiting.seat ?: "An agent"} wants to call ${waiting.provider} ${waiting.call}\n\nEffect: ${waiting.effect.name}\nArguments: ${waiting.arguments.toString().take(600)}\n\n" +
            "Allowing it covers this exact call, once. Approval ${waiting.id}."
        val seconds = ((waiting.expiresAtEpochMillis - System.currentTimeMillis()) / 1000).coerceIn(30, 900)
        val script = "display dialog ${quoted(text)} with title \"Reaktor: approve this call?\" buttons {\"Deny\", \"Allow once\"} default button \"Deny\" with icon caution giving up after $seconds"
        val process = runCatching { ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start() }.getOrNull() ?: return
        val answer = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val approve = answer.contains("button returned:Allow once")
        if (!approve && !answer.contains("button returned:Deny")) return  // gave up, or no display: it stays pending for a terminal
        runCatching { approvals.decide(waiting.id, approve, System.getProperty("user.name") + " (dialog)") }
    }
}
