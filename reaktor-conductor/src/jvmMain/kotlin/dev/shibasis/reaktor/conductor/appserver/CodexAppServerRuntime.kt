package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.codexApprovals
import dev.shibasis.reaktor.conductor.cli.codexMcpArgs
import dev.shibasis.reaktor.conductor.cli.codexSandbox
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

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
        if (codexDesktopAppAvailable(binary) && request.outputSchema == null && codexDesktopHonours(request.agent.tools, codexDesktopDefaults())) {
            return CodexDesktopSession(request, binary, scope)
        }

        val process = ProcessBuilder(buildList {
            add(binary)
            addAll(codexMcpArgs(request.agent.tools.mcpConfig))
            request.agent.tools.additionalDirectories.forEach { add("--add-dir"); add(it) }
            addAll(request.agent.harnessArgs)
            // Config overrides are global flags and must precede the subcommand, as with `exec`.
            add("app-server")
        }).directory(directory).redirectErrorStream(false).apply { environment().putAll(request.environment) }.start()

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

internal class CodexDesktopSession(
    private val request: AgentRequest,
    private val binary: String,
    private val scope: CoroutineScope,
    private val home: File = codexHome(),
    private val launch: (suspend (List<String>) -> Boolean)? = null,
) : AgentSession {
    private val directory = File(request.agent.attributes["codexProjectDirectory"] ?: request.workingDirectory)
    private val agent = request.agent.id
    private var threadId = request.resume?.takeIf { it.runtime == RuntimeKind.Codex }?.sessionId
    @Volatile private var turn: String? = null
    private var usage: AgentUsage? = null
    private var effort = EffortRecord.none

    override val activeTurn: String? get() = turn

    override val events: Flow<AgentEvent> = flow {
        try {
            val thread = threadId?.let { it to EffortRecord.none } ?: startThread().also { threadId = it.first }
            effort = thread.second
            val beforeQueue = findRollout(thread.first)?.length() ?: 0L
            check(command(listOf(binary, "queue", "--thread", thread.first, "--message", request.prompt))) {
                "Codex could not queue the prompt"
            }
            check(command(listOf("open", "codex://threads/wake/${thread.first}"))) {
                "ChatGPT.app could not wake Codex thread ${thread.first}"
            }
            emit(AgentEvent.Started(agent, ProviderSession(RuntimeKind.Codex, thread.first)))
            val rollout = withTimeoutOrNull(30_000.milliseconds) {
                while (true) {
                    findRollout(thread.first)?.let { return@withTimeoutOrNull it }
                    delay(100.milliseconds)
                }
                error("Unreachable")
            } ?: error("Codex did not create a rollout for thread ${thread.first}")
            val outcome = follow(rollout, beforeQueue)
            emit(AgentEvent.Finished(agent, outcome))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit(AgentEvent.Finished(agent, AgentOutcome(
                agent, "", false, failure = failure.message ?: "Codex app thread failed",
                session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) }, usage = usage, effort = effort,
            )))
        }
    }

    private suspend fun startThread(): Pair<String, EffortRecord> {
        val process = ProcessBuilder(binary, "app-server").directory(directory)
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment().putAll(request.environment) }.start()
        val rpc = JsonRpcStdio(process.inputStream.bufferedReader(), process.outputStream.writer(), scope)
        return try {
            rpc.request("initialize", buildJsonObject {
                putJsonObject("clientInfo") { put("name", "reaktor"); put("title", "Reaktor"); put("version", "1") }
            })
            rpc.notify("initialized")
            val thread = rpc.request("thread/start", buildJsonObject {
                put("cwd", directory.path)
                put("sandbox", request.agent.tools.codexSandbox())
                put("approvalPolicy", request.agent.tools.codexApprovals())
                request.agent.model?.let { put("model", it) }
                request.agent.effort?.let { put("config", buildJsonObject { put("model_reasoning_effort", it.value) }) }
            }).objectOrEmpty("thread")
            val id = thread["id"]?.jsonPrimitive?.contentOrNull ?: error("app-server did not return a thread id")
            id to EffortRecord(
                requested = request.agent.effort,
                resolved = request.agent.effort,
                observed = thread["reasoningEffort"]?.jsonPrimitive?.contentOrNull?.let(::NativeEffort),
            )
        } finally {
            rpc.close()
            stopNativeProcess(process)
        }
    }

    private suspend fun FlowCollector<AgentEvent>.follow(file: File, offset: Long): AgentOutcome {
        val answer = StringBuilder()
        var completed: AgentOutcome? = null
        var shortfall: String? = null
        file.toPath().let { path ->
            Files.newByteChannel(path, StandardOpenOption.READ).use { channel ->
                channel.position(offset)
                val buffer = ByteArray(8192)
                val line = ByteArrayOutputStream()
                var heard = System.currentTimeMillis()
                var stalled = false
                while (completed == null && currentCoroutineContext().isActive) {
                    val count = channel.read(ByteBuffer.wrap(buffer))
                    if (count <= 0) {
                        if (!stalled && System.currentTimeMillis() - heard > DesktopStallMillis) {
                            stalled = true
                            emit(AgentEvent.Activity(agent, AgentActivityItem("desktop-stall", ActivityKind.Control, "No progress in ChatGPT.app", ActivityStatus.Waiting,
                                output = desktopStallNotice(threadId, started = turn != null))))
                        }
                        delay(100)
                        continue
                    }
                    heard = System.currentTimeMillis()
                    if (stalled) {
                        stalled = false
                        emit(AgentEvent.Activity(agent, AgentActivityItem("desktop-stall", ActivityKind.Control, "Progress resumed in ChatGPT.app", ActivityStatus.Completed)))
                    }
                    for (index in 0 until count) {
                        val byte = buffer[index]
                        if (byte == '\n'.code.toByte()) {
                            val record = runCatching { Json.parseToJsonElement(line.toString(StandardCharsets.UTF_8)).jsonObject }.getOrNull()
                            line.reset()
                            val payload = record?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "event_msg" }
                                ?.get("payload") as? JsonObject ?: continue
                            when (payload["type"]?.jsonPrimitive?.contentOrNull) {
                                "task_started" -> {
                                    turn = payload["turn_id"]?.jsonPrimitive?.contentOrNull
                                    turn?.let { emit(AgentEvent.TurnStarted(agent, it)) }
                                }
                                "item_completed" -> {
                                    val item = payload["item"] as? JsonObject
                                    if (item?.get("type")?.jsonPrimitive?.contentOrNull == "agentMessage")
                                        item["text"]?.jsonPrimitive?.contentOrNull?.let { answer.replace(0, answer.length, it) }
                                }
                                "thread_settings_applied" -> if (shortfall == null) codexDesktopShortfall(request.agent.tools, payload.objectOrEmpty("thread_settings"))?.let { found ->
                                    val failure = "ChatGPT.app runs Codex thread $threadId with $found. " +
                                        "Runs go to ChatGPT.app only while its Codex config sets approval_policy = \"never\" and the sandbox_mode they ask for."
                                    shortfall = failure
                                    emit(AgentEvent.Activity(agent, AgentActivityItem("desktop-permissions", ActivityKind.Control, "ChatGPT.app permissions fall short", ActivityStatus.Failed, output = failure)))
                                    threadId?.let { thread ->
                                        if (command(listOf(binary, "queue", "--thread", thread, "--message", DesktopStop))) command(listOf("open", "codex://threads/wake/$thread"))
                                    }
                                }
                                "token_count" -> payload.objectOrEmpty("info").objectOrEmpty("total_token_usage").toAppUsage()?.let {
                                    usage = it
                                    emit(AgentEvent.Usage(agent, it))
                                }
                                "task_complete" -> {
                                    val text = payload["last_agent_message"]?.jsonPrimitive?.contentOrNull ?: answer.toString()
                                    completed = AgentOutcome(agent, text, shortfall == null, failure = shortfall,
                                        session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) }, usage = usage, effort = effort)
                                    turn = null
                                }
                                "turn_aborted" -> {
                                    completed = AgentOutcome(agent, answer.toString(), false, interrupted = true,
                                        failure = shortfall ?: "Turn interrupted in ChatGPT.app",
                                        session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) }, usage = usage, effort = effort)
                                    turn = null
                                }
                                else -> Unit
                            }
                        } else line.write(byte.toInt())
                    }
                }
            }
        }
        return completed ?: AgentOutcome(agent, answer.toString(), false, failure = "Codex app rollout ended before completion",
            session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) }, usage = usage, effort = effort)
    }

    private fun findRollout(thread: String): File? {
        val sessions = home.resolve("sessions")
        if (!sessions.isDirectory) return null
        return runCatching {
            Files.walk(sessions.toPath()).use { paths ->
                paths.filter { it.fileName.toString().endsWith("-$thread.jsonl") }.findFirst().orElse(null)?.toFile()
            }
        }.getOrNull()
    }

    private suspend fun command(command: List<String>): Boolean = launch?.invoke(command) ?: withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(command).directory(directory)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment().putAll(request.environment) }.start()
        } catch (_: java.io.IOException) {
            return@withContext false
        }
        try {
            process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
        val active = turn ?: return CommandOutcome.Stale(expectedTurn, null)
        if (expectedTurn != null && expectedTurn != active) return CommandOutcome.Stale(expectedTurn, active)
        val thread = threadId ?: return CommandOutcome.Unsupported("Codex app thread has not started")
        return if (command(listOf(binary, "queue", "--thread", thread, "--message", text))) CommandOutcome.Accepted
        else CommandOutcome.Failed("Codex could not queue the follow-up")
    }

    override suspend fun resolve(requestId: String, decision: AgentDecision) =
        CommandOutcome.Unsupported("Codex app approvals are handled in ChatGPT.app")

    override suspend fun interrupt() = CommandOutcome.Unsupported("Codex app run cancellation is not supported")

    override fun close() = Unit
}

private fun codexDesktopAppAvailable(binary: String): Boolean = binary == "codex" &&
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
    listOf(File("/Applications/ChatGPT.app"), File(System.getProperty("user.home"), "Applications/ChatGPT.app"))
        .any(File::isDirectory)

private const val DesktopStallMillis = 20 * 60_000L

private const val DesktopStop = "Stop now and end your turn without running anything else. ChatGPT.app runs this thread with fewer permissions than this run needs, so its commands would wait for approvals nobody answers."

internal fun desktopStallNotice(thread: String?, started: Boolean): String = if (started)
    "Codex thread $thread has written nothing for ${DesktopStallMillis / 60_000} minutes. It may be waiting for an approval in ChatGPT.app, which this run cannot answer or interrupt."
else "Codex thread $thread has not started its turn ${DesktopStallMillis / 60_000} minutes after the prompt was queued. ChatGPT.app may not have picked the prompt up."

private fun codexHome(): File = System.getenv("CODEX_HOME")?.takeIf(String::isNotBlank)?.let(::File)
    ?: File(System.getProperty("user.home"), ".codex")

internal data class CodexDesktopDefaults(val sandbox: String?, val approvals: String?)

internal fun codexDesktopDefaults(config: File = codexHome().resolve("config.toml")): CodexDesktopDefaults {
    val top = runCatching { config.readLines() }.getOrDefault(emptyList()).takeWhile { !it.trimStart().startsWith("[") }
    fun value(key: String) = top.firstNotNullOfOrNull { Regex("""^\s*$key\s*=\s*"([^"]*)"""").find(it)?.groupValues?.get(1) }
    return CodexDesktopDefaults(value("sandbox_mode"), value("approval_policy"))
}

internal fun codexDesktopHonours(tools: ToolPolicy, defaults: CodexDesktopDefaults): Boolean =
    defaults.approvals == "never" && defaults.sandbox == tools.codexSandbox()

internal fun codexDesktopShortfall(tools: ToolPolicy, settings: JsonObject): String? {
    val approvals = settings["approval_policy"]?.jsonPrimitive?.contentOrNull ?: return null
    val profile = settings.objectOrEmpty("active_permission_profile")["id"]?.jsonPrimitive?.contentOrNull
    val sandbox = when (profile) {
        ":danger-full-access" -> "danger-full-access"
        ":workspace" -> "workspace-write"
        ":read-only" -> "read-only"
        else -> null
    }
    return buildList {
        if (sandbox != null && sandbox != tools.codexSandbox()) add("$profile permissions where it asked for ${tools.codexSandbox()}")
        if (approvals != "never") add("$approvals approvals")
    }.takeIf { it.isNotEmpty() }?.joinToString(" and ")
}

private fun JsonObject.toAppUsage(): AgentUsage? = AgentUsage(
    scope = UsageScope.ProviderSessionTotal,
    inputTokens = this["input_tokens"]?.jsonPrimitive?.longOrNull,
    cachedInputTokens = this["cached_input_tokens"]?.jsonPrimitive?.longOrNull,
    cacheWriteInputTokens = this["cache_write_input_tokens"]?.jsonPrimitive?.longOrNull,
    outputTokens = this["output_tokens"]?.jsonPrimitive?.longOrNull,
    reasoningOutputTokens = this["reasoning_output_tokens"]?.jsonPrimitive?.longOrNull,
)

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
                put("sandbox", request.agent.tools.codexSandbox())
                put("approvalPolicy", request.agent.tools.codexApprovals())
                request.agent.model?.let { put("model", it) }
            })
        } else {
            rpc.request("thread/start", buildJsonObject {
                put("cwd", request.workingDirectory)
                put("sandbox", request.agent.tools.codexSandbox())
                put("approvalPolicy", request.agent.tools.codexApprovals())
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
        var rateLimits: JsonObject? = null
        suspend fun usageLimit(error: JsonObject?): AgentUsageLimit? {
            if ((error?.get("codexErrorInfo") as? JsonPrimitive)?.contentOrNull != "usageLimitExceeded") return null
            val snapshot = withTimeoutOrNull(2_000) {
                try {
                    val response = rpc.request("account/rateLimits/read")
                    ((response["rateLimitsByLimitId"] as? JsonObject)?.get("codex") as? JsonObject
                        ?: response.objectOrEmpty("rateLimits")).takeIf { it.isNotEmpty() }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            } ?: rateLimits
            val resetsAt = listOf("primary", "secondary").mapNotNull { key ->
                val window = snapshot?.get(key) as? JsonObject
                window?.takeIf { (it["usedPercent"]?.jsonPrimitive?.intOrNull ?: 0) >= 100 }
                    ?.get("resetsAt")?.jsonPrimitive?.longOrNull
                    ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
            }.maxOrNull()
            return AgentUsageLimit(RuntimeKind.Codex, "usageLimitExceeded", resetsAt)
        }

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
                    is JsonRpcInbound.ServerRequest -> if (message.workspaceToolCall) rpc.respond(message.id, AcceptedToolCall) else {
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

                        "account/rateLimits/updated" -> message.params.objectOrEmpty("rateLimits").let { update ->
                            if (update["limitId"]?.jsonPrimitive?.contentOrNull in listOf(null, "codex"))
                                rateLimits = JsonObject(rateLimits.orEmpty() + update)
                        }

                        "thread/tokenUsage/updated" -> message.params.objectOrEmpty("tokenUsage").toUsage()?.let {
                            usage = it
                            send(AgentEvent.Usage(agent, it))
                        }

                        "turn/completed" -> {
                            val completed = message.params.objectOrEmpty("turn")
                            val answer = completed["items"]?.jsonArray.orEmpty()
                                .mapNotNull { it as? JsonObject }
                                .lastOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "agentMessage" }
                                ?.get("text")?.jsonPrimitive?.contentOrNull
                            val failed = completed["error"] as? JsonObject
                            terminal.complete(AgentOutcome(
                                agent = agent,
                                text = answer ?: finalText ?: text.toString(),
                                ok = failed == null && completed["status"]?.jsonPrimitive?.contentOrNull == "completed",
                                interrupted = completed["status"]?.jsonPrimitive?.contentOrNull == "interrupted",
                                failure = failed?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull,
                                session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) },
                                usage = usage,
                                effort = effort,
                                usageLimit = if (completed["status"]?.jsonPrimitive?.contentOrNull == "failed") usageLimit(failed) else null,
                            ))
                            turn = null
                            pending.clear()
                        }

                        "turn/failed", "error" -> if (message.params["willRetry"]?.jsonPrimitive?.booleanOrNull != true) {
                            val failed = message.params["error"] as? JsonObject ?: message.params
                            terminal.complete(AgentOutcome(agent, text.toString(), false,
                                failure = failed["message"]?.jsonPrimitive?.contentOrNull ?: "app-server reported an error",
                                session = threadId?.let { ProviderSession(RuntimeKind.Codex, it) }, usage = usage, effort = effort,
                                usageLimit = usageLimit(failed)))
                        }

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
            request.outputSchema?.let { put("outputSchema", it) }
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
