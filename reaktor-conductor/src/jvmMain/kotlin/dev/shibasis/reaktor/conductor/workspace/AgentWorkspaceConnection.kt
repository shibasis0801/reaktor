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
private data class AgentEndpoint(val version: Int = 1, val workspaceRoot: String, val port: Int, val token: String)

/** Both embedded and external hosts use this same authenticated MCP boundary. */
class AgentWorkspaceConnection private constructor(
    @Volatile private var endpoint: AgentEndpoint,
    val discoveryFile: Path,
    val ownsService: Boolean,
    private val remote: AgentRemoteWorkspace? = null,
    private val closeOwner: () -> Unit = {},
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
    suspend fun transcript(id: String): AgentTranscript = ConductorJson.decodeFromJsonElement(AgentTranscript.serializer(),
        call("agent_transcript", buildJsonObject { put("threadId", id) }))

    suspend fun call(name: String, args: JsonObject = buildJsonObject {}): JsonElement {
        val request = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", args) }
        }
        val envelope = exchange(request.toString())?.jsonObject ?: error("Expected workspace response")
        envelope["error"]?.let { error("Workspace protocol error: $it") }
        val result = envelope.getValue("result").jsonObject
        check(result["isError"]?.jsonPrimitive?.booleanOrNull != true) {
            result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Workspace tool failed"
        }
        return result.getValue("structuredContent")
    }

    suspend fun exchange(message: String): JsonElement? = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Connection is closed" }
        require(message.length <= 250000) { "MCP message exceeds the transport budget" }
        if (remote != null) return@withContext remote.exchange(message)
        if (!ownsService && Files.exists(discoveryFile)) {
            require(Files.size(discoveryFile) <= 4096) { "Invalid workspace discovery file" }
            val current = ConductorJson.decodeFromString(AgentEndpoint.serializer(), Files.readString(discoveryFile))
            require(current.version == 1 && current.workspaceRoot == endpoint.workspaceRoot && current.port in 1..65535 && current.token.length >= 32)
            endpoint = current
        }
        val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(35))
            .header("Authorization", "Bearer ${endpoint.token}").header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocolVersion)
            .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofInputStream())
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
        fun remote(profile: AgentRemoteProfile): AgentWorkspaceConnection = AgentWorkspaceConnection(
            AgentEndpoint(workspaceRoot = profile.workspaceRoot, port = 0, token = ""), Path.of("."), false, remote = AgentRemoteWorkspace(profile))
        fun defaultDirectory(root: File): Path = Path.of(System.getProperty("user.home"), ".reaktor", "agents", digest(root.canonicalPath))

        fun open(root: File, directory: Path = defaultDirectory(root), runtimes: Map<RuntimeKind, AgentRuntime>? = null,
                 allowStart: Boolean = true,
                 background: Boolean = false,
                 graphUrl: String? = null,
                 extraTools: (AgentWorkspace) -> List<dev.shibasis.reaktor.mcp.McpTool> = { emptyList() },
                 workflowCheck: (suspend (String, String, WorkflowStage) -> WorkflowCheckResult)? = null,
                 externalBusy: () -> Boolean = { false },
                 // Injectable so a test states a capability instead of probing whichever CLIs the host has.
                 discover: (RuntimeKind) -> ProviderCapability = CliCapabilities::probe): AgentWorkspaceConnection {
            require(root.isDirectory)
            privateDirectory(directory)
            val discovery = directory.resolve("connection.json")
            val channel = FileChannel.open(directory.resolve("owner.lock"), CREATE, WRITE)
            val ownerLock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (ownerLock == null) {
                channel.close()
                // A different process may hold the lock while it publishes its first endpoint.
                repeat(100) { if (!Files.exists(discovery)) Thread.sleep(20) }
                val endpoint = ConductorJson.decodeFromString(AgentEndpoint.serializer(), Files.readString(discovery))
                require(endpoint.version == 1 && endpoint.workspaceRoot == root.canonicalPath && endpoint.port in 1..65535 && endpoint.token.length >= 32)
                return AgentWorkspaceConnection(endpoint, discovery, false)
            }
            if (!allowStart) {
                ownerLock.release()
                channel.close()
                error("No workspace owner is running. Open the desktop Agent pane or run workspace serve --dir <workspace>.")
            }
            var executor: SupervisedProcessExecutor? = null
            var workspace: AgentWorkspace? = null
            var server: LoopbackMcpServer? = null
            val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val binding = directory.resolve("workspace-root")
                if (Files.exists(binding)) require(Files.readString(binding) == root.canonicalPath) { "Agent data belongs to a different workspace" }
                else atomicWrite(binding, root.canonicalPath)
                Files.deleteIfExists(discovery)
                val batch = if (runtimes == null) AgentRuntimes.batch(SupervisedProcessExecutor().also { executor = it }) else emptyMap()
                val configured = runtimes ?: AgentRuntimes.interactive(runtimeScope)
                val mcpConfig = if (runtimes == null) directory.resolve("harness-mcp.json").also { path ->
                    val command = AgentBundle.launchCommand(root, null)
                    val graphCommand = command.toMutableList().apply { this[indexOfLast { it == "workspace" } + 1] = "graph-mcp"
                        graphUrl?.let { addAll(listOf("--graph-url", it)) } }
                    atomicWrite(path, buildJsonObject { putJsonObject("mcpServers") {
                        mapOf("reaktor" to command, "reaktor-graph" to graphCommand).forEach { (name, argv) ->
                            putJsonObject(name) { put("command", argv.first()); put("args", JsonArray(argv.drop(1).map(::JsonPrimitive))) }
                        }
                    } }.toString())
                }.toString() else null
                val hostedWorkspace = AgentWorkspace(root.canonicalFile, directory, configured, discover = discover, batchRuntimes = batch,
                    harnessMcpConfig = mcpConfig, background = background, workflowCheck = workflowCheck, externalBusy = externalBusy).also { workspace = it }
                val registry = agentWorkspaceMcp(hostedWorkspace, extraTools(hostedWorkspace))
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                val hostedServer = LoopbackMcpServer.start(0, { registry }, bearerToken = token).also { server = it }
                val endpoint = AgentEndpoint(workspaceRoot = root.canonicalPath, port = hostedServer.port(), token = token)
                atomicWrite(discovery, ConductorJson.encodeToString(AgentEndpoint.serializer(), endpoint))
                hostedWorkspace.recoverInBackground()
                return AgentWorkspaceConnection(endpoint, discovery, true) {
                    try { hostedServer.close() }
                    finally { try { if (background) hostedWorkspace.suspendAndClose() else hostedWorkspace.close() }
                    finally { try { runtimeScope.cancel(); executor?.close(); Files.deleteIfExists(discovery) }
                    finally { ownerLock.release(); channel.close() } } }
                }
            } catch (failure: Throwable) {
                try { server?.close(); workspace?.close(); runtimeScope.cancel(); executor?.close() }
                finally { ownerLock.release(); channel.close() }
                throw failure
            }
        }
    }
}
