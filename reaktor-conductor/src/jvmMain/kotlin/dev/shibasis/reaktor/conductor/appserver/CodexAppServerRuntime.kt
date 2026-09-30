package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.codexMcpArgs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File

/**
 * Codex over its App Server, rather than `codex exec`.
 *
 * The two transports are not the same product with a different wire format, and the differences
 * are the reason this exists. Verified against `codex app-server` 0.154.0:
 *
 *  - `thread/start` answers with the thread's **effective** model and `reasoningEffort`, so effort
 *    stops being an unverified request and becomes an observed fact.
 *  - `item/agentMessage/delta` streams token by token; `exec --json` only ever reports whole items.
 *  - `thread/tokenUsage/updated` and `account/rateLimits/updated` arrive while the turn runs.
 *  - Turns can be steered and interrupted, and the server can ask questions of its own.
 *
 * Reasoning summaries have their own item events here. Whether a given model emits them depends on
 * its configuration, so none arriving is an ordinary state and produces no [AgentEvent.Reasoning] —
 * the same rule the `exec` adapter follows for a transport that has none at all.
 */
class CodexAppServerRuntime(
    private val binary: String = "codex",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : InteractiveAgentRuntime {
    override val kind: RuntimeKind = RuntimeKind.Codex

    override val interactive = Qualification(
        advertised = true, configured = true, implemented = true,
        qualifiedBy = "CodexAppServerLiveTest (0.154.0)",
    )

    /** Batch shape: open a session, run one turn, close. Cancellation interrupts the turn first. */
    override fun run(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val session = runCatching { open(request) }.getOrElse { failure ->
            send(AgentEvent.Finished(request.agent.id, AgentOutcome(
                request.agent.id, "", false, failure = failure.message ?: "Could not start $binary app-server")))
            return@channelFlow
        }
        try {
            session.events.collect { send(it) }
        } finally {
            withContext(NonCancellable) {
                runCatching { session.interrupt() }
                session.close()
            }
        }
    }

    override suspend fun open(request: AgentRequest): AgentSession {
        val directory = File(request.workingDirectory)
        require(directory.isDirectory) { "Working directory does not exist: ${directory.absolutePath}" }
        require(!request.agent.tools.isolateOperatorConfig) { "App Server configuration isolation is not supported; use the batch transport" }
        require(!request.agent.tools.strictMcpConfig && request.agent.tools.allow.isEmpty() && request.agent.tools.deny.isEmpty()) {
            "These tool restrictions have no App Server mapping; use native configuration or a supported transport"
        }

        val process = ProcessBuilder(buildList {
            add(binary)
            addAll(codexMcpArgs(request.agent.tools.mcpConfig))
            request.agent.tools.additionalDirectories.forEach { add("--add-dir"); add(it) }
            addAll(request.agent.harnessArgs)
            // Config overrides are global flags and must precede the subcommand, as with `exec`.
            add("app-server")
        }).directory(directory).redirectErrorStream(false).start()

        val stderr = StringBuilder()
        scope.launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    synchronized(stderr) { if (stderr.length < 8000) stderr.append(it).append('\n') }
                }
            }
        }

        val rpc = JsonRpcStdio(
            input = process.inputStream.bufferedReader() as BufferedReader,
            output = process.outputStream.writer(),
            scope = scope,
        )
        return CodexAppServerSession(request, process, rpc, scope) { synchronized(stderr) { stderr.toString() } }
    }
}

private class CodexAppServerSession(
    private val request: AgentRequest,
    private val process: Process,
    private val rpc: JsonRpcStdio,
    private val scope: CoroutineScope,
    private val stderr: () -> String,
) : AgentSession {
    private val agent = request.agent.id
    private var threadId: String? = null
    @Volatile private var turn: String? = null
    private var effort = EffortRecord.none
    private var model: String? = null
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CodexRequest>()
    private val generation = java.util.UUID.randomUUID().toString()
    private val children = java.util.concurrent.ConcurrentHashMap<String, NativeAgentState>()
    override fun nativeAgents() = children.values.toList().sortedBy { it.id }

    override suspend fun controlNative(id: String, expectedTurn: String, text: String?): CommandOutcome {
        val child = children[id] ?: return CommandOutcome.Unsupported("unowned native subagent")
        if (child.turnId == null || child.turnId != expectedTurn) return CommandOutcome.Stale(expectedTurn, child.turnId)
        return runCatching {
            rpc.request(if (text == null) "turn/interrupt" else "turn/steer", buildJsonObject {
                put("threadId", id)
                if (text == null) put("turnId", expectedTurn) else {
                    put("expectedTurnId", expectedTurn)
                    putJsonArray("input") { addJsonObject { put("type", "text"); put("text", text) } }
                }
            })
            CommandOutcome.Accepted
        }.getOrElse { CommandOutcome.Failed(it.message ?: "Native child control failed") }
    }

    override val activeTurn: String? get() = turn

    /**
     * channelFlow, not flow: the inbound pump runs in its own coroutine, and Flow forbids emitting
     * across coroutines. `send` is the one that is safe to call from the pump.
     */
    override val events: Flow<AgentEvent> = channelFlow {
        rpc.request("initialize", buildJsonObject {
            putJsonObject("clientInfo") { put("name", "reaktor"); put("title", "Reaktor"); put("version", "1") }
        })

        rpc.notify("initialized")

        val resume = request.resume?.takeIf { it.runtime == RuntimeKind.Codex }
        val thread = if (resume != null) {
            rpc.request("thread/resume", buildJsonObject {
                put("threadId", resume.sessionId)
                put("cwd", request.workingDirectory)
                put("sandbox", if (request.agent.tools.allowWrites) "workspace-write" else "read-only")
                put("approvalPolicy", "on-request")
                request.agent.model?.let { put("model", it) }
            })
        } else {
            rpc.request("thread/start", buildJsonObject {
                put("cwd", request.workingDirectory)
                put("sandbox", if (request.agent.tools.allowWrites) "workspace-write" else "read-only")
                // Approvals are surfaced to policy rather than auto-answered, so the server is told
                // to ask. `never` would silently narrow what the harness is allowed to attempt.
                put("approvalPolicy", "on-request")
                request.agent.model?.let { put("model", it) }
                request.agent.effort?.let { put("config", buildJsonObject { put("model_reasoning_effort", it.value) }) }
            })
        }.objectOrEmpty("thread").let { it.ifEmpty { rpcThreadFallback(resume) } }

        threadId = thread["id"]?.jsonPrimitive?.contentOrNull
            ?: error("app-server did not return a thread id")
        model = thread["model"]?.jsonPrimitive?.contentOrNull
        // The one thing `exec --json` cannot tell us: what effort the thread is actually running at.
        effort = EffortRecord(
            requested = request.agent.effort,
            resolved = request.agent.effort,
            observed = thread["reasoningEffort"]?.jsonPrimitive?.contentOrNull?.let(::NativeEffort),
        )
        send(AgentEvent.Started(agent, ProviderSession(RuntimeKind.Codex, threadId!!)))

        val terminal = CompletableDeferred<AgentOutcome>()
        val text = StringBuilder()
        var finalText: String? = null
        var usage: AgentUsage? = null

        val pump = launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.inbound.collect { message ->
                if (message is JsonRpcInbound.Notification) {
                    val origin = message.params["threadId"]?.jsonPrimitive?.contentOrNull
                    if (origin != null && origin != threadId) {
                        val child = children[origin]
                        if (child != null) {
                            val detail = message.params.objectOrEmpty("turn")
                            val next = when (message.method) {
                                "turn/started" -> child.copy(status = "running", turnId = detail["id"]?.jsonPrimitive?.contentOrNull)
                                "turn/completed" -> child.copy(status = detail["status"]?.jsonPrimitive?.contentOrNull ?: "completed", turnId = null)
                                else -> child
                            }
                            children[origin] = next
                            if (next != child) send(AgentEvent.Activity(agent, AgentActivityItem("native:$origin", ActivityKind.Agent,
                                next.name ?: origin, if (next.turnId != null) ActivityStatus.Started else ActivityStatus.Completed,
                                parentId = child.parentId, nativeAgents = listOf(next))))
                            if (message.method in listOf("item/started", "item/completed")) codexActivity(agent, message.params.objectOrEmpty("item"), message.method == "item/completed")?.let {
                                it.item.nativeAgents.forEach { descendant -> children.compute(descendant.id) { _, prior -> descendant.copy(parentId = origin, turnId = prior?.turnId) } }
                                send(it.copy(item = it.item.copy(parentId = "native:$origin")))
                            }
                        }
                        // Child completion/deltas must never finish or contaminate the parent turn.
                        return@collect
                    }
                }
                when (message) {
                    is JsonRpcInbound.ServerRequest -> {
                        val ask = codexRequest(message, generation)
                        if (ask != null) {
                            pending[ask.pending.id] = ask
                            send(AgentEvent.RequestPending(agent, ask.pending))
                        } else {
                            // Unknown server request: answer nothing rather than guess, but do not
                            // leave the peer blocked forever on a method we do not model.
                            rpc.reject(message.id, message.method)
                        }
                    }

                    is JsonRpcInbound.Notification -> when (message.method) {
                        "turn/started" -> {
                            turn = message.params.objectOrEmpty("turn")["id"]?.jsonPrimitive?.contentOrNull
                            turn?.let { send(AgentEvent.TurnStarted(agent, it)) }
                        }
                        "serverRequest/resolved" -> {
                            val raw = message.params["requestId"]?.jsonPrimitive?.contentOrNull
                            pending.values.firstOrNull { it.pending.providerRequestId == raw }?.let {
                                pending.remove(it.pending.id)
                                send(AgentEvent.RequestResolved(agent, it.pending.id, null))
                            }
                        }

                        "item/agentMessage/delta" -> message.params["delta"]?.jsonPrimitive?.contentOrNull?.let {
                            text.append(it)
                            send(AgentEvent.Delta(agent, it))
                        }

                        "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" ->
                            message.params["delta"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                                // Codex names these separately and so do we: a summary is not the
                                // model's reasoning text, and the distinction is the provider's.
                                val fidelity = if (message.method.contains("summary")) ReasoningFidelity.Summary
                                else ReasoningFidelity.Thinking
                                send(AgentEvent.Reasoning(agent, it, fidelity))
                            }

                        "item/started", "item/completed" -> message.params.objectOrEmpty("item").let { item ->
                            codexActivity(agent, item, message.method == "item/completed")?.let { event ->
                                event.item.nativeAgents.forEach { child -> children.compute(child.id) { _, prior ->
                                    child.copy(parentId = child.parentId ?: threadId, turnId = prior?.turnId)
                                } }
                                send(event)
                            }
                            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                                "agentMessage" -> if (message.method == "item/completed")
                                    finalText = item["text"]?.jsonPrimitive?.contentOrNull ?: finalText
                                "commandExecution" -> if (message.method == "item/started")
                                    send(AgentEvent.ToolUse(agent, "command", item["command"]?.jsonPrimitive?.contentOrNull))
                                "fileChange", "patchApply" -> if (message.method == "item/started")
                                    send(AgentEvent.ToolUse(agent, "file_change"))
                                "mcpToolCall" -> if (message.method == "item/started")
                                    send(AgentEvent.ToolUse(agent, item["tool"]?.jsonPrimitive?.contentOrNull ?: "mcp"))
                                else -> Unit
                            }
                        }

                        "turn/plan/updated" -> send(AgentEvent.Activity(agent, AgentActivityItem(
                            "plan-${turn ?: "current"}", ActivityKind.Plan, "Execution plan", output = message.params.toString())))

                        "thread/tokenUsage/updated" -> message.params.objectOrEmpty("tokenUsage").toUsage()?.let { usage = it }

                        "turn/completed" -> {
                            val completed = message.params.objectOrEmpty("turn")
                            val answer = completed["items"]?.jsonArray.orEmpty()
                                .mapNotNull { it as? JsonObject }
                                .lastOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "agentMessage" }
                                ?.get("text")?.jsonPrimitive?.contentOrNull
                            val failed = completed["error"]?.takeIf { it !is JsonNull }
                            terminal.complete(AgentOutcome(
                                agent = agent,
                                text = answer ?: finalText ?: text.toString(),
                                ok = failed == null && completed["status"]?.jsonPrimitive?.contentOrNull == "completed",
                                interrupted = completed["status"]?.jsonPrimitive?.contentOrNull == "interrupted",
                                failure = failed?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull,
                                session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) },
                                usage = usage,
                                effort = effort,
                            ))
                            turn = null
                            pending.clear()
                        }

                        "turn/failed", "error" -> terminal.complete(AgentOutcome(agent, text.toString(), false,
                            failure = message.params["message"]?.jsonPrimitive?.contentOrNull ?: "app-server reported an error",
                            session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) }, usage = usage, effort = effort))

                        else -> Unit
                    }
                }
            }
        }

        scope.launch {
            rpc.closed.await()
            if (!terminal.isCompleted) terminal.complete(AgentOutcome(agent, text.toString(), false,
                failure = stderr().ifBlank { "app-server closed before the turn completed" }.take(2000),
                session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) }, usage = usage, effort = effort))
        }

        rpc.request("turn/start", buildJsonObject {
            put("threadId", threadId)
            putJsonArray("input") { addJsonObject { put("type", "text"); put("text", request.prompt) } }
            request.agent.effort?.let { put("effort", it.value) }
            request.agent.model?.let { put("model", it) }
        })

        send(AgentEvent.Finished(agent, terminal.await()))
        pump.cancel()
    }

    private suspend fun rpcThreadFallback(resume: ProviderSession?): JsonObject =
        if (resume == null) JsonObject(emptyMap())
        else rpc.request("thread/read", buildJsonObject { put("threadId", resume.sessionId) }).objectOrEmpty("thread")

    override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
        val active = turn ?: return CommandOutcome.Stale(expectedTurn, null)
        if (expectedTurn != null && expectedTurn != active) return CommandOutcome.Stale(expectedTurn, active)
        return runCatching {
            rpc.request("turn/steer", buildJsonObject {
                put("threadId", threadId)
                put("expectedTurnId", active)
                putJsonArray("input") { addJsonObject { put("type", "text"); put("text", text) } }
            })
            CommandOutcome.Accepted
        }.getOrElse { CommandOutcome.Failed(it.message ?: "steer rejected") }
    }

    override suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome {
        return synchronized(pending) {
            val ask = pending[requestId] ?: return@synchronized CommandOutcome.Stale(requestId, null)
            val origin = ask.wire.params["threadId"]?.jsonPrimitive?.contentOrNull
            val owningTurn = if (origin != null && origin != threadId) children[origin]?.turnId else turn
            if (ask.pending.turnId != null && ask.pending.turnId != owningTurn)
                return@synchronized CommandOutcome.Stale(ask.pending.turnId, owningTurn)
            runCatching {
                rpc.respond(ask.wire.id, ask.response(decision))
                pending.remove(requestId)
                CommandOutcome.Accepted
            }.getOrElse { CommandOutcome.Failed(it.message ?: "Response could not be sent") }
        }
    }

    override suspend fun interrupt(): CommandOutcome {
        val active = turn ?: return CommandOutcome.Stale(null, null)
        return runCatching {
            rpc.request("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", active) })
            CommandOutcome.Accepted
        }.getOrElse { CommandOutcome.Failed(it.message ?: "interrupt rejected") }
    }

    override fun close() {
        stopNativeProcess(process)
        rpc.close()
    }
}

/**
 * `thread/tokenUsage/updated` carries two breakdowns: `total` for the thread so far and `last` for
 * the most recent turn. The session total is taken, because that is the convention the `exec`
 * adapter already reports and mixing the two would make a continuation look free.
 */
private fun JsonObject.toUsage(): AgentUsage? {
    val breakdown = (this["total"] as? JsonObject) ?: (this["last"] as? JsonObject) ?: return null
    fun field(name: String) = breakdown[name]?.jsonPrimitive?.longOrNull
    return AgentUsage(
        scope = UsageScope.ProviderSessionTotal,
        inputTokens = field("inputTokens"),
        cachedInputTokens = field("cachedInputTokens"),
        cacheWriteInputTokens = field("cacheWriteInputTokens"),
        outputTokens = field("outputTokens"),
        reasoningOutputTokens = field("reasoningOutputTokens"),
    )
}
