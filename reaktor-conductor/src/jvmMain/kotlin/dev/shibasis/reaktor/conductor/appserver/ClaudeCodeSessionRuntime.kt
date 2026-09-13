package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.ClaudeCodeEventParser
import dev.shibasis.reaktor.conductor.cli.claudeArgv
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Interactive Claude Code control for one turn, with native session continuation on subsequent turns.
 *
 * `--input-format stream-json` keeps stdin live, so a session outlasts a turn. Verified against
 * 2.1.270 by driving it: a user message on stdin starts a turn, the same `stream-json` output the
 * batch adapter already parses comes back, the session stays alive after `result`, and a
 * `control_request` with subtype `interrupt` is answered with a `control_response`.
 *
 * One honest asymmetry with Codex: **Claude publishes no turn id.** Codex gives one and accepts it
 * as a steering precondition. Here the session numbers its own turns so stale steering is still
 * rejected, but the id is Reaktor's, not the provider's, and it is not interchangeable with one.
 */
class ClaudeCodeSessionRuntime(
    private val binary: String = "claude",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : InteractiveAgentRuntime {
    override val kind: RuntimeKind = RuntimeKind.ClaudeCode

    override val interactive = Qualification(
        advertised = true, configured = true, implemented = true,
        // Qualification of individual controls is recorded by the workspace host.
        qualifiedBy = "ClaudeCodeSessionLiveTest",
    )

    override fun run(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val session = runCatching { open(request) }.getOrElse { failure ->
            send(AgentEvent.Finished(request.agent.id, AgentOutcome(
                request.agent.id, "", false, failure = failure.message ?: "Could not start $binary")))
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

        // The batch argv already resolves model, effort, tools, MCP and resume the same way; only
        // the transport flags differ, so the flag logic stays in one place.
        val argv = claudeArgv(request, binary).drop(3)
        val flags = buildList {
            add(binary); add("-p")
            add("--input-format"); add("stream-json")
            // Anything that would prompt is routed to us rather than auto-denied.
            add("--permission-prompts"); add("host")
            add("--permission-prompt-tool"); add("stdio")
            addAll(argv)
        }

        val process = ProcessBuilder(flags).directory(directory).redirectErrorStream(false).start()
        return ClaudeCodeSession(request, process, scope)
    }
}

private class ClaudeCodeSession(
    private val request: AgentRequest,
    private val process: Process,
    private val scope: CoroutineScope,
) : AgentSession {
    private val agent = request.agent.id
    private val stdin: Writer = process.outputStream.writer()
    private val writeLock = Any()
    private val turns = AtomicLong()
    private val controls = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val stderr = StringBuilder()
    private val pending = ConcurrentHashMap<String, ClaudeRequest>()
    private val generation = java.util.UUID.randomUUID().toString()
    @Volatile private var interrupted = false

    @Volatile private var turn: String? = null
    override val activeTurn: String? get() = turn

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    synchronized(stderr) { if (stderr.length < 8000) stderr.append(it).append('\n') }
                }
            }
        }
    }

    override val events: Flow<AgentEvent> = channelFlow {
        val parser = ClaudeCodeEventParser(
            agent,
            request.agent.effort?.let { EffortRecord(requested = it, resolved = it) } ?: EffortRecord.none,
        )
        val lines = Channel<String>(256)
        // Deliberately NOT a child of this channelFlow. A blocking stream read cannot be
        // cancelled, so a child would keep the flow from ever completing once the turn ends.
        val reader = scope.launch(Dispatchers.IO) {
            runCatching { process.inputStream.bufferedReader().forEachLine { lines.trySendBlocking(it) } }
            lines.close()
        }

        val initialized = CompletableDeferred<JsonObject>()
        val initId = "$generation-initialize"
        controls[initId] = initialized
        write(buildJsonObject {
            put("type", "control_request"); put("request_id", initId)
            putJsonObject("request") { put("subtype", "initialize") }
        })
        val startup = launch {
            val response = withTimeout(60_000) { initialized.await() }
            check(response["subtype"]?.jsonPrimitive?.contentOrNull == "success") { "Claude initialization failed" }
            turn = "$generation-turn-${turns.incrementAndGet()}"
            send(AgentEvent.TurnStarted(agent, turn!!))
            writeUser(request.prompt)
        }

        for (line in lines) {
            val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            when (message?.get("type")?.jsonPrimitive?.contentOrNull) {
                // Answers to our own control requests never reach the transcript parser.
                "control_response" -> {
                    val response = message.objectOrEmpty("response")
                    val id = response["request_id"]?.jsonPrimitive?.contentOrNull
                    if (id != null) controls.remove(id)?.complete(response)
                    continue
                }
                // The CLI asking us for a tool decision.
                "control_request" -> {
                    val ask = claudeRequest(message, generation, turn)
                    if (ask != null) {
                        pending[ask.pending.id] = ask
                        send(AgentEvent.RequestPending(agent, ask.pending))
                    } else write(buildJsonObject {
                        put("type", "control_response")
                        putJsonObject("response") {
                            put("subtype", "error"); put("request_id", message["request_id"] ?: JsonNull)
                            put("error", "Unsupported control request")
                        }
                    })
                    continue
                }
            }
            if (message?.get("type")?.jsonPrimitive?.contentOrNull == "control_cancel_request") {
                val id = message["request_id"]?.jsonPrimitive?.contentOrNull
                pending.values.firstOrNull { it.id == id }?.let {
                    pending.remove(it.pending.id)
                    send(AgentEvent.RequestResolved(agent, it.pending.id, null))
                }
                continue
            }
            parser.onLine(line).forEach { send(it) }
            if (message?.get("type")?.jsonPrimitive?.contentOrNull == "result") break
        }

        turn = null
        startup.cancel()
        reader.cancel()
        lines.close()
        pending.clear()
        send(AgentEvent.Finished(agent, parser.finish(
            exitCode = if (process.isAlive) 0 else process.exitValue(),
            stderr = synchronized(stderr) { stderr.toString() },
        ).let { if (interrupted) it.copy(ok = false, interrupted = true, failure = "Interrupted") else it }))
    }

    override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
        val active = turn ?: return CommandOutcome.Stale(expectedTurn, null)
        if (expectedTurn != null && expectedTurn != active) return CommandOutcome.Stale(expectedTurn, active)
        return runCatching { writeUser(text); CommandOutcome.Accepted }
            .getOrElse { CommandOutcome.Failed(it.message ?: "Input could not be sent") }
    }

    override suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome = synchronized(pending) {
        val ask = pending[requestId] ?: return@synchronized CommandOutcome.Stale(requestId, null)
        if (ask.pending.turnId != turn) return@synchronized CommandOutcome.Stale(ask.pending.turnId, turn)
        runCatching {
            write(ask.response(decision))
            pending.remove(requestId)
            CommandOutcome.Accepted
        }.getOrElse { CommandOutcome.Failed(it.message ?: "Response could not be sent") }
    }

    override suspend fun interrupt(): CommandOutcome {
        if (turn == null) return CommandOutcome.Stale(null, null)
        val id = "$generation-${java.util.UUID.randomUUID()}-interrupt"
        val waiting = CompletableDeferred<JsonObject>()
        controls[id] = waiting
        return try {
            write(buildJsonObject {
                put("type", "control_request")
                put("request_id", id)
                putJsonObject("request") { put("subtype", "interrupt") }
            })
            withTimeoutOrNull(5000) {
                val response = waiting.await()
                if (response["subtype"]?.jsonPrimitive?.contentOrNull == "success") { interrupted = true; CommandOutcome.Accepted }
                else CommandOutcome.Failed(response.toString().take(200))
            } ?: CommandOutcome.Failed("No control_response within 5s")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            CommandOutcome.Failed(failure.message ?: "Interrupt could not be sent")
        } finally { controls.remove(id) }
    }

    private fun write(message: JsonObject) = synchronized(writeLock) {
        check(process.isAlive) { "Claude session is closed" }
        stdin.write(message.toString()); stdin.write("\n"); stdin.flush()
    }

    private fun writeUser(text: String) = write(buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
        }
    })

    override fun close() {
        controls.values.forEach { it.cancel() }
        stopNativeProcess(process)
        runCatching { stdin.close() }
        pending.clear()
    }
}
