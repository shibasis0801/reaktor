package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class AntigravitySessionRuntime(
    private val binary: String = "agy",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val candidates: List<String> = defaultAntigravityModels,
) : InteractiveAgentRuntime {
    override val kind: RuntimeKind = RuntimeKind.Gemini

    override val interactive = Qualification(advertised = true, configured = true, implemented = true,
        qualifiedBy = "AntigravitySessionLiveTest")

    override fun run(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val session = runCatching { open(request) }.getOrElse { failure ->
            send(AgentEvent.Finished(request.agent.id, AgentOutcome(
                request.agent.id, "", false, failure = failure.message ?: "Could not start $binary")))
            return@channelFlow
        }
        try {
            session.events.collect { send(it) }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    override suspend fun open(request: AgentRequest): AgentSession {
        val directory = File(request.workingDirectory)
        require(directory.isDirectory) { "Working directory does not exist: ${directory.absolutePath}" }
        requireAntigravityGoogleAccount()
        antigravityGuard(request)
        return AntigravitySession(request, directory, binary, antigravityModelOrder(request.agent.model, candidates), scope)
    }
}

private class AntigravitySession(
    private val request: AgentRequest,
    private val directory: File,
    private val binary: String,
    private val models: List<String>,
    private val scope: CoroutineScope,
) : AgentSession {
    private val agent = request.agent.id
    private val turnId = "${UUID.randomUUID()}-turn-1"
    @Volatile private var current: AntigravityProcess? = null
    @Volatile private var turn: String? = null
    @Volatile private var interrupted = false
    @Volatile private var closed = false
    @Volatile private var children: List<NativeAgentState> = emptyList()

    override val activeTurn: String? get() = turn

    override fun nativeAgents(): List<NativeAgentState> = children

    override val events: Flow<AgentEvent> = channelFlow {
        turn = turnId
        send(AgentEvent.TurnStarted(agent, turnId))
        try {
            for ((index, model) in models.withIndex()) {
                val next = models.getOrNull(index + 1)
                val held = mutableListOf<AgentEvent>()
                var live = next == null
                val outcome = attempt(model) { event ->
                    (event as? AgentEvent.Activity)?.item?.nativeAgents?.takeIf { it.isNotEmpty() }?.let { children = it }
                    if (!live && event.progress) {
                        live = true
                        held.forEach { send(it) }
                        held.clear()
                    }
                    if (live) send(event) else held += event
                }
                if (!live && next != null && !interrupted && !closed && outcome.outOfCapacity()) {
                    withContext(Dispatchers.IO) { stopProcess() }
                    send(antigravityCapacitySwitch(agent, index, model, next, outcome))
                    continue
                }
                held.forEach { send(it) }
                turn = null
                send(AgentEvent.Finished(agent, outcome))
                break
            }
        } finally {
            turn = null
            withContext(NonCancellable + Dispatchers.IO) { stopProcess() }
        }
    }

    private suspend fun attempt(model: String, onEvent: suspend (AgentEvent) -> Unit): AgentOutcome {
        val parser = AntigravityEventParser(agent, request.agent.effort, request.agent.budget.timeoutMillis)
        if (interrupted || closed) return parser.finish(0, "").stopped()
        val agy = runCatching {
            AntigravityProcess(antigravitySessionArgv(request.withModel(model), binary), directory, request.environment, scope)
        }.getOrElse { return AgentOutcome(agent, "", false, failure = it.message ?: "Could not start $binary") }
        current = agy
        if (interrupted || closed) {
            withContext(Dispatchers.IO) { agy.stop() }
            return parser.finish(0, "").stopped()
        }
        runCatching { agy.send(antigravityUserMessage(antigravityPrompt(request))) }
        var answered = false
        for (line in agy.lines) {
            parser.onLine(line).forEach { onEvent(it) }
            if (line.isResult()) {
                answered = true
                break
            }
        }
        val exitCode = if (answered) 0 else withContext(Dispatchers.IO) { agy.exitCode() }
        val outcome = parser.finish(exitCode, agy.stderr)
        return if (interrupted) outcome.stopped() else outcome
    }

    override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
        val active = turn ?: return CommandOutcome.Stale(expectedTurn, null)
        if (expectedTurn != null && expectedTurn != active) return CommandOutcome.Stale(expectedTurn, active)
        return CommandOutcome.Unsupported("Antigravity steering")
    }

    override suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome =
        CommandOutcome.Stale(requestId, null)

    override suspend fun interrupt(): CommandOutcome {
        if (turn == null) return CommandOutcome.Stale(null, null)
        interrupted = true
        withContext(Dispatchers.IO) { stopProcess() }
        return CommandOutcome.Accepted
    }

    override fun close() {
        closed = true
        stopProcess()
        children = emptyList()
    }

    private fun stopProcess() {
        current?.stop()
    }

    private fun AgentOutcome.stopped() = copy(ok = false, interrupted = true, failure = "Interrupted")
}

private class AntigravityProcess(argv: List<String>, directory: File, environment: Map<String, String>, scope: CoroutineScope) {
    private val process: Process = ProcessBuilder(argv).directory(directory).redirectErrorStream(false)
        .apply { environment().putAll(environment) }.start()
    private val stdin = process.outputStream.writer()
    private val errors = StringBuilder()
    val lines = Channel<String>(256)

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    synchronized(errors) { if (errors.length < 8000) errors.append(it).append('\n') }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { process.inputStream.bufferedReader().forEachLine { lines.trySendBlocking(it) } }
            lines.close()
        }
    }

    val stderr: String get() = synchronized(errors) { errors.toString() }

    fun send(message: JsonObject) = synchronized(stdin) {
        stdin.write(message.toString())
        stdin.write("\n")
        stdin.flush()
    }

    fun exitCode(): Int = if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else -1

    fun stop() {
        runCatching { synchronized(stdin) { stdin.close() } }
        stopNativeProcess(process)
    }
}

private val AgentEvent.progress: Boolean
    get() = this is AgentEvent.Delta || this is AgentEvent.Reasoning || this is AgentEvent.ToolUse ||
        (this is AgentEvent.Activity && item.kind in setOf(ActivityKind.Tool, ActivityKind.Agent))

private fun String.isResult(): Boolean =
    runCatching { Json.parseToJsonElement(this).jsonObject["event"]?.jsonPrimitive?.contentOrNull == "result" }.getOrDefault(false)
