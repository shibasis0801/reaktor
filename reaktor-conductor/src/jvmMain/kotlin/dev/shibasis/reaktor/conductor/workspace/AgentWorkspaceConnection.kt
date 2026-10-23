package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.appserver.AgentRuntimes
import dev.shibasis.reaktor.conductor.cli.CliCapabilities
import dev.shibasis.reaktor.conductor.cli.AgentBundle
import dev.shibasis.reaktor.conductor.cli.ClaudeCodeRuntime
import dev.shibasis.reaktor.conductor.cli.CodexRuntime
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.mcp.LoopbackMcpServer
import dev.shibasis.reaktor.mcp.REAKTOR_MCP_PROTOCOL_VERSION
import dev.shibasis.reaktor.mcp.REAKTOR_MCP_PROTOCOL_VERSIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
private data class AgentEndpoint(val version: Int, val workspaceRoot: String, val port: Int, val token: String,
    val processId: Long, val incarnation: String, val catalogDigest: String, val hostKind: String,
    val invocationVersion: Int? = null)

/** Both embedded and external hosts attach to the same authenticated operation owner. */
class AgentWorkspaceConnection private constructor(
    @Volatile private var endpoint: AgentEndpoint,
    val discoveryFile: Path,
    val ownsService: Boolean,
    private val remote: AgentRemoteWorkspace? = null,
    private val closeOwner: () -> Unit = {},
    /** The ChatGPT planning surface this process is serving, when it was asked to serve one. */
    val hybridConnector: HybridConnector? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    @Volatile private var protocolVersion = REAKTOR_MCP_PROTOCOL_VERSION
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    val url: String get() = if (remote == null) "http://127.0.0.1:${endpoint.port}/mcp" else "ssh workspace"

    suspend fun info(): AgentWorkspaceInfo = ConductorJson.decodeFromJsonElement(AgentWorkspaceInfo.serializer(), call("agent_workspace_info"))
    suspend fun submit(request: AgentSubmission): AgentRunRecord = decode(call("agent_submit", ConductorJson.encodeToJsonElement(AgentSubmission.serializer(), request).jsonObject))
    suspend fun get(id: String): AgentRunRecord = decode(call("agent_run", buildJsonObject { put("runId", id) }))
    suspend fun list(limit: Int = 20): List<AgentRunRecord> = ConductorJson.decodeFromJsonElement(ListSerializer(AgentRunRecord.serializer()),
        call("agent_runs", buildJsonObject { put("limit", limit) }).jsonObject.getValue("runs"))
    suspend fun tasks(query: String = "", offset: Int = 0): AgentTasksPage = ConductorJson.decodeFromJsonElement(AgentTasksPage.serializer(),
        call("agent_tasks_wait", buildJsonObject { put("revision", 0); put("query", query); put("offset", offset); put("limit", 50); put("timeoutMillis", 0) }))
    suspend fun waitTasks(revision: Long, query: String = ""): AgentTasksPage = ConductorJson.decodeFromJsonElement(AgentTasksPage.serializer(),
        call("agent_tasks_wait", buildJsonObject { put("revision", revision); put("query", query); put("limit", 50) }))
    suspend fun wait(id: String, afterRevision: Long, timeoutMillis: Long = 30000): AgentRunRecord = decode(call("agent_wait", buildJsonObject {
        put("runId", id); put("afterRevision", afterRevision); put("timeoutMillis", timeoutMillis)
    }))
    suspend fun cancel(id: String): AgentRunRecord = decode(call("agent_cancel", buildJsonObject { put("runId", id) }))

    /** Re-attaches to a run after a disconnect. Observation only; never takes the execution lease. */
    suspend fun attach(runId: String): AgentAttachment = ConductorJson.decodeFromJsonElement(
        AgentAttachment.serializer(), call("agent_attach", buildJsonObject { put("runId", runId) }))

    /** Answers a request a provider is blocked on. Unsupported when that run holds no session. */
    suspend fun answer(runId: String, agent: String, requestId: String, decision: AgentDecision): CommandOutcome =
        ConductorJson.decodeFromJsonElement(CommandOutcome.serializer(), call("agent_answer", buildJsonObject {
            put("runId", runId); put("agent", agent); put("requestId", requestId)
            when (decision) {
                AgentDecision.Approve -> put("decision", "approve")
                is AgentDecision.Deny -> { put("decision", "deny"); decision.reason?.let { put("text", it) } }
                is AgentDecision.Answer -> { put("decision", "answer"); put("text", decision.text) }
                is AgentDecision.Answers -> { put("decision", "answers"); put("answers", buildJsonObject {
                    decision.values.forEach { (id, values) -> put(id, JsonArray(values.map(::JsonPrimitive))) }
                }) }
                is AgentDecision.Form -> { put("decision", "form"); put("form", decision.values) }
            }
        }))

    suspend fun steer(runId: String, agent: String, text: String, expectedTurn: String? = null): CommandOutcome =
        ConductorJson.decodeFromJsonElement(CommandOutcome.serializer(), call("agent_steer", buildJsonObject {
            put("runId", runId); put("agent", agent); put("text", text)
            expectedTurn?.let { put("expectedTurn", it) }
        }))
    suspend fun transcript(id: String, before: Int? = null, limit: Int = 20): AgentTranscript = ConductorJson.decodeFromJsonElement(AgentTranscript.serializer(),
        call("agent_transcript", buildJsonObject { put("threadId", id); before?.let { put("before", it) }; put("limit", limit) }))

    suspend fun board(query: String = ""): BoardPage = ConductorJson.decodeFromJsonElement(BoardPage.serializer(),
        call("agent_board", buildJsonObject { put("query", query) }))
    suspend fun boardTask(id: String): BoardEntry = boardEntry(call("agent_board_task", buildJsonObject { put("taskId", id) }))
    suspend fun createBoardTask(draft: BoardDraft, by: BoardActor): BoardEntry = boardEntry(call("agent_board_create",
        JsonObject(AgentWorkspaceJson.encodeToJsonElement(BoardDraft.serializer(), draft).jsonObject + ("by" to actor(by)))))
    suspend fun updateBoardTask(id: String, patch: BoardPatch, by: BoardActor, revision: Long? = null): BoardEntry = boardEntry(call("agent_board_update",
        JsonObject(ConductorJson.encodeToJsonElement(BoardPatch.serializer(), patch).jsonObject + buildJsonObject {
            put("taskId", id); put("by", actor(by)); revision?.let { put("revision", it) }
        })))
    suspend fun moveBoardTask(id: String, stage: BoardStage, by: BoardActor, revision: Long? = null): BoardEntry = boardEntry(call("agent_board_move", buildJsonObject {
        put("taskId", id); put("stage", stage.name); put("by", actor(by)); revision?.let { put("revision", it) }
    }))
    suspend fun linkBoardWork(id: String, work: String, linked: Boolean, by: BoardActor): BoardEntry = boardEntry(call("agent_board_link", buildJsonObject {
        put("taskId", id); put("work", work); put("linked", linked); put("by", actor(by))
    }))
    suspend fun taskGraphs(): List<TaskGraphSummary> = ConductorJson.decodeFromJsonElement(ListSerializer(TaskGraphSummary.serializer()),
        call("agent_taskgraph_list").jsonObject.getValue("graphs"))
    suspend fun taskGraph(id: String): TaskGraphView = ConductorJson.decodeFromJsonElement(TaskGraphView.serializer(), call("agent_taskgraph_read", buildJsonObject { put("graph", id) }))
    suspend fun taskGraphShape(id: String): dev.shibasis.reaktor.graph.core.GraphShape =
        ConductorJson.decodeFromJsonElement(dev.shibasis.reaktor.graph.core.GraphShape.serializer(), call("agent_taskgraph_shape", buildJsonObject { put("graph", id) }))
    suspend fun proposeTaskGraph(draft: TaskGraphDraft, by: BoardActor): TaskGraphView = ConductorJson.decodeFromJsonElement(TaskGraphView.serializer(),
        call("agent_taskgraph_propose", JsonObject(AgentWorkspaceJson.encodeToJsonElement(TaskGraphDraft.serializer(), draft).jsonObject + ("by" to actor(by)))))
    suspend fun patchTaskGraph(id: String, patch: TaskGraphPatch, by: BoardActor): TaskGraph = graphOf(call("agent_taskgraph_patch", buildJsonObject {
        put("graph", id); put("ops", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(TaskGraphOp.serializer()), patch.ops)); put("reason", patch.reason)
        patch.expectedRevision?.let { put("expectedRevision", it) }; put("by", actor(by))
    }).jsonObject.getValue("graph"))
    suspend fun startTaskGraph(id: String, revision: Long?, by: BoardActor): TaskGraph = graphOf(call("agent_taskgraph_start", buildJsonObject {
        put("graph", id); revision?.let { put("expectedRevision", it) }; put("by", actor(by))
    }))
    suspend fun pauseTaskGraph(id: String, reason: String, by: BoardActor): TaskGraph = graphOf(call("agent_taskgraph_pause", buildJsonObject {
        put("graph", id); put("reason", reason); put("by", actor(by))
    }))
    suspend fun cancelTaskGraph(id: String, by: BoardActor): TaskGraph = graphOf(call("agent_taskgraph_cancel", buildJsonObject { put("graph", id); put("by", actor(by)) }))
    suspend fun revertTaskGraph(id: String, revision: Long, by: BoardActor): TaskGraph = graphOf(call("agent_taskgraph_revert", buildJsonObject {
        put("graph", id); put("revision", revision); put("by", actor(by))
    }).jsonObject.getValue("graph"))
    suspend fun reportTaskGraph(id: String, task: String, report: TaskReport, outcome: TaskOutcome, by: BoardActor): BoardTask =
        ConductorJson.decodeFromJsonElement(BoardTask.serializer(), call("agent_taskgraph_report", buildJsonObject {
            put("graph", id); put("task", task); put("status", report.name); put("summary", outcome.summary)
            outcome.verdict?.let { put("verdict", it) }; put("by", actor(by))
        }))
    private fun graphOf(value: JsonElement) = ConductorJson.decodeFromJsonElement(TaskGraph.serializer(), value)
    private fun boardEntry(value: JsonElement) = ConductorJson.decodeFromJsonElement(BoardEntry.serializer(), value)
    private fun actor(by: BoardActor) = ConductorJson.encodeToJsonElement(BoardActor.serializer(), by)

    suspend fun call(name: String, args: JsonObject = buildJsonObject {}, timeout: Duration? = null): JsonElement {
        withContext(Dispatchers.IO) { refreshEndpoint() }
        val patience = patience(args, timeout)
        if (remote != null || endpoint.invocationVersion == null) return callMcp(name, args, patience)
        check(endpoint.invocationVersion == 1) { "Unsupported workspace invocation version" }
        val payload = buildJsonObject {
            put("version", 1); put("workspaceRoot", endpoint.workspaceRoot)
            put("operation", name); put("arguments", args)
        }
        val result = request(payload.toString(), "/operations", patience)?.jsonObject ?: error("Expected workspace response")
        check(result["isError"]?.jsonPrimitive?.booleanOrNull != true) {
            result["data"]?.jsonObject?.get("error")?.jsonPrimitive?.content
                ?: result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: "Workspace operation failed"
        }
        return result.getValue("data")
    }

    private suspend fun callMcp(name: String, args: JsonObject, patience: Duration): JsonElement {
        val request = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", args) }
        }
        val envelope = exchange(request.toString(), patience)?.jsonObject ?: error("Expected workspace response")
        envelope["error"]?.let { error("Workspace protocol error: $it") }
        val result = envelope.getValue("result").jsonObject
        check(result["isError"]?.jsonPrimitive?.booleanOrNull != true) {
            result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Workspace tool failed"
        }
        return result.getValue("structuredContent")
    }

    suspend fun exchange(message: String, patience: Duration = Patience): JsonElement? = request(message, "/mcp", patience)

    private fun refreshEndpoint() {
        check(!closed.get()) { "Connection is closed" }
        if (!ownsService && Files.exists(discoveryFile)) {
            endpoint = readEndpoint(discoveryFile, endpoint.workspaceRoot)
        }
    }

    private suspend fun request(message: String, path: String, patience: Duration): JsonElement? = withContext(Dispatchers.IO) {
        refreshEndpoint()
        require(message.length <= 250000) { "Workspace message exceeds the transport budget" }
        if (remote != null) return@withContext remote.exchange(message)
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${endpoint.port}$path")).timeout(patience)
            .header("Authorization", "Bearer ${endpoint.token}").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(message))
        if (path == "/mcp") builder.header("MCP-Protocol-Version", protocolVersion)
        val response = runInterruptible { http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
        val bytes = response.body().use { it.readNBytes(2_000_001) }
        check(bytes.size <= 2_000_000) { "Workspace response exceeded the transport budget" }
        if (response.statusCode() == 202 && bytes.isEmpty()) return@withContext null
        check(response.statusCode() == 200) { "Workspace connection failed (HTTP ${response.statusCode()}); reconnect to its current owner" }
        ConductorJson.parseToJsonElement(bytes.decodeToString()).also { envelope ->
            (envelope as? JsonObject)?.get("result")?.let { it as? JsonObject }?.get("protocolVersion")
                ?.jsonPrimitive?.contentOrNull?.takeIf { it in REAKTOR_MCP_PROTOCOL_VERSIONS }?.let { protocolVersion = it }
        }
    }

    private fun decode(value: JsonElement) = ConductorJson.decodeFromJsonElement(AgentRunRecord.serializer(), value)
    override fun close() { if (closed.compareAndSet(false, true)) { try { http.shutdownNow() } finally { closeOwner() } } }

    companion object {
        private val Patience = Duration.ofSeconds(35)
        private val LongestPatience = Duration.ofMinutes(15)

        internal fun patience(args: JsonObject, timeout: Duration? = null): Duration {
            fun JsonObject.seconds(): Long? = (this["timeoutSeconds"] as? JsonPrimitive)?.longOrNull
                ?: (this["timeoutMillis"] as? JsonPrimitive)?.longOrNull?.let { (it + 999) / 1000 }
            val asked = args.seconds() ?: (args["arguments"] as? JsonObject)?.seconds()
            val wait = asked?.let { Duration.ofSeconds(it + 10) } ?: Patience
            return maxOf(wait, timeout ?: Patience).coerceIn(Patience, LongestPatience)
        }

        fun remote(profile: AgentRemoteProfile): AgentWorkspaceConnection = AgentWorkspaceConnection(
            AgentEndpoint(version = 2, workspaceRoot = profile.workspaceRoot, port = 0, token = "",
                processId = 0, incarnation = "", catalogDigest = "", hostKind = "service"), Path.of("."), false, remote = AgentRemoteWorkspace(profile))
        fun defaultDirectory(root: File): Path = Path.of(System.getProperty("user.home"), ".reaktor", "agents", digest(root.canonicalPath))

        /** A published record is a reason to attach and validate, never to create a replacement owner. */
        fun ownerRecorded(root: File, directory: Path = defaultDirectory(root)): Boolean =
            Files.exists(directory.resolve("owner.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS)

        private fun readEndpoint(discovery: Path, workspaceRoot: String): AgentEndpoint {
            require(Files.isRegularFile(discovery, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(discovery) <= 4096) { "Invalid workspace owner record" }
            return ConductorJson.decodeFromString(AgentEndpoint.serializer(), Files.readString(discovery)).also {
                require(it.version == 2 && it.workspaceRoot == workspaceRoot && it.port in 1..65535 && it.token.length >= 32 &&
                    it.processId > 0 && it.incarnation.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) &&
                    it.catalogDigest.matches(Regex("[0-9a-f]{64}")) && it.hostKind in setOf("service", "hangar")) { "Invalid workspace owner record" }
            }
        }

        fun open(root: File, directory: Path = defaultDirectory(root), runtimes: Map<RuntimeKind, AgentRuntime>? = null,
                 allowStart: Boolean = true,
                 background: Boolean = false,
                 hostKind: String = "service",
                 graphUrl: String? = null,
                 extraTools: (AgentWorkspace) -> List<dev.shibasis.reaktor.mcp.McpTool> = { emptyList() },
                 workflowCheck: (suspend (String, String, WorkflowStage) -> WorkflowCheckResult)? = null,
                 externalBusy: () -> Boolean = { false },
                 graphOwner: () -> dev.shibasis.reaktor.graph.core.Graph? = { null },
                 mcpProjection: Set<String>? = null,
                 bindOperations: (dev.shibasis.reaktor.mcp.ToolNode) -> AutoCloseable = { AutoCloseable {} },
                 // Injectable so a test states a capability instead of probing whichever CLIs the host has.
                 discover: (RuntimeKind) -> ProviderCapability = CliCapabilities::probe,
                 /**
                  * Serve the narrow ChatGPT planning surface from this process.
                  *
                  * Default off, and it stays a decision rather than a configuration: this is the one
                  * surface here meant to be reachable from outside the machine, and it should exist
                  * only while somebody is deliberately letting ChatGPT drive.
                  */
                 hybridConnector: Boolean = false,
                 kernelRead: (suspend (String, JsonObject) -> JsonElement)? = null,
                 harnessMcpCommand: List<String>? = null): AgentWorkspaceConnection {
            require(root.isDirectory)
            require(hostKind in setOf("service", "hangar")) { "Unknown workspace owner host" }
            require(harnessMcpCommand == null || harnessMcpCommand.isNotEmpty()) { "Harness MCP command needs an executable" }
            val discovery = directory.resolve("owner.json")
            if (!allowStart) {
                require(Files.isRegularFile(discovery) && Files.isRegularFile(directory.resolve("owner.lock"))) { "No workspace owner is running" }
                FileChannel.open(directory.resolve("owner.lock"), WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock != null) {
                        lock.release()
                        error("No workspace owner is running")
                    }
                    return AgentWorkspaceConnection(readEndpoint(discovery, root.canonicalPath), discovery, false)
                }
            }
            privateDirectory(directory)
            val channel = FileChannel.open(directory.resolve("owner.lock"), CREATE, WRITE)
            val ownerLock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (ownerLock == null) {
                channel.close()
                // A different process may hold the lock while it publishes its first endpoint.
                repeat(100) { if (!Files.exists(discovery)) Thread.sleep(20) }
                return AgentWorkspaceConnection(readEndpoint(discovery, root.canonicalPath), discovery, false)
            }
            var executor: SupervisedProcessExecutor? = null
            var workspace: AgentWorkspace? = null
            var server: LoopbackMcpServer? = null
            var operations: AgentWorkspaceOperations? = null
            var operationBinding: AutoCloseable? = null
            val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val binding = directory.resolve("workspace-root")
                if (Files.exists(binding)) require(Files.readString(binding) == root.canonicalPath) { "Agent data belongs to a different workspace" }
                else atomicWrite(binding, root.canonicalPath)
                Files.deleteIfExists(discovery)
                val graphDiscovery = directory.resolve("graph-connection.json")
                if (graphUrl != null) atomicWrite(graphDiscovery, buildJsonObject { put("workspaceRoot", root.canonicalPath); put("url", graphUrl) }.toString())
                else Files.deleteIfExists(graphDiscovery)
                val batch = if (runtimes == null) AgentRuntimes.batch(SupervisedProcessExecutor().also { executor = it }) else emptyMap()
                val configured = runtimes ?: AgentRuntimes.interactive(runtimeScope)
                val mcpConfig = if (runtimes == null && harnessMcpCommand != null) directory.resolve("harness-mcp.json").also { path ->
                    val command = harnessMcpCommand
                    atomicWrite(path, buildJsonObject { putJsonObject("mcpServers") {
                        putJsonObject(AgentBundle.SERVER_NAME) { put("command", command.first()); put("args", JsonArray(command.drop(1).map(::JsonPrimitive))) }
                    } }.toString())
                }.toString() else null
                val hostedWorkspace = AgentWorkspace(root.canonicalFile, directory, configured, discover = discover, batchRuntimes = batch,
                    harnessMcpConfig = mcpConfig, background = background, workflowCheck = workflowCheck, externalBusy = externalBusy, kernelRead = kernelRead).also { workspace = it }
                val registry = AgentWorkspaceOperations(hostedWorkspace, agentWorkspaceTools(hostedWorkspace, extraTools(hostedWorkspace)), graphOwner(), mcpProjection).also { operations = it }
                operationBinding = bindOperations(registry.node)
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                val hostedServer = LoopbackMcpServer.start(0, { registry }, bearerToken = token, post = registry::post,
                    read = registry::read, postWithHeaders = registry::peerPost).also { server = it }
                registry.endpointUrl = "http://127.0.0.1:${hostedServer.port()}"
                val endpoint = AgentEndpoint(version = 2, workspaceRoot = root.canonicalPath, port = hostedServer.port(), token = token, invocationVersion = 1,
                    processId = ProcessHandle.current().pid(), incarnation = java.util.UUID.randomUUID().toString(),
                    catalogDigest = digest(registry.node.definitions.sortedBy { it.name }.joinToString("\n")), hostKind = hostKind)
                atomicWrite(discovery, ConductorJson.encodeToString(AgentEndpoint.serializer(), endpoint))
                // Off unless the caller says otherwise. Opening a workspace must never publish it.
                // Reuse the port and secret this workspace published before. A tunnel points at a
                // port and a connector stores a URL, so regenerating either on every restart means
                // reconfiguring both by hand; the identity belongs to the workspace, not the process.
                val connectorFile = directory.resolve("hybrid-connector.json")
                val connector = if (hybridConnector) {
                    val saved = runCatching { ConductorJson.parseToJsonElement(Files.readString(connectorFile)).jsonObject }.getOrNull()
                    val secret = (saved?.get("secret") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length >= 32 }
                        ?: HybridConnector.newSecret()
                    val preferred = (saved?.get("port") as? JsonPrimitive)?.intOrNull ?: 0
                    // A port something else has taken is not a reason to refuse to serve.
                    val auditPath = directory.resolve("hybrid-connector-audit.jsonl")
                    (runCatching { HybridConnector.start(hostedWorkspace, preferred, secret, auditPath) }
                        .getOrElse { HybridConnector.start(hostedWorkspace, 0, secret, auditPath) }).also {
                        atomicWrite(connectorFile, buildJsonObject {
                            put("port", it.port()); put("secret", it.secret); put("path", "/mcp/" + it.secret)
                        }.toString())
                    }
                } else null
                hostedWorkspace.recoverInBackground()
                return AgentWorkspaceConnection(endpoint, discovery, true, hybridConnector = connector, closeOwner = {
                    try { connector?.close() }
                    finally { try { hostedServer.close(); operationBinding?.close(); registry.close() }
                    finally { try { if (background) hostedWorkspace.suspendAndClose() else hostedWorkspace.close() }
                    finally { try { runtimeScope.cancel(); executor?.close(); Files.deleteIfExists(discovery); Files.deleteIfExists(graphDiscovery) }
                    finally { ownerLock.release(); channel.close() } } } }
                })
            } catch (failure: Throwable) {
                try { server?.close(); operationBinding?.close(); operations?.close(); workspace?.close(); runtimeScope.cancel(); executor?.close() }
                finally { ownerLock.release(); channel.close() }
                throw failure
            }
        }
    }
}
