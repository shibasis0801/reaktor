package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.ClaudeCodeEventParser
import dev.shibasis.reaktor.conductor.cli.claudeArgv
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Claude Code held open, rather than run once and exited.
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
        // Interrupt and multi-turn input are qualified. Tool-permission routing is not: see resolve.
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
        val argv = claudeArgv(request, binary).toMutableList()
        argv.removeAll(listOf("-p", request.prompt))
        val flags = buildList {
            add(binary); add("-p")
            add("--input-format"); add("stream-json")
            // Anything that would prompt is routed to us rather than auto-denied.
            add("--permission-prompts"); add("host")
            addAll(argv.drop(1).filterNot { it == "-p" })
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
        val lines = Channel<String>(Channel.UNLIMITED)
        // Deliberately NOT a child of this channelFlow. A blocking stream read cannot be
        // cancelled, so a child would keep the flow from ever completing once the turn ends.
        val reader = scope.launch(Dispatchers.IO) {
            runCatching { process.inputStream.bufferedReader().forEachLine { lines.trySend(it) } }
            lines.close()
        }

        turn = "turn-${turns.incrementAndGet()}"
        write(buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") { addJsonObject { put("type", "text"); put("text", request.prompt) } }
            }
        })

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
                    message.toPending()?.let { send(AgentEvent.RequestPending(agent, it)) }
                    continue
                }
            }
            parser.onLine(line).forEach { send(it) }
            if (message?.get("type")?.jsonPrimitive?.contentOrNull == "result") break
        }

        turn = null
        // Closing the stream is what actually unblocks the reader; cancelling alone would not.
        runCatching { process.inputStream.close() }
        reader.cancel()
        lines.close()
        send(AgentEvent.Finished(agent, parser.finish(
            exitCode = if (process.isAlive) 0 else process.exitValue(),
            stderr = synchronized(stderr) { stderr.toString() },
        )))
    }

    override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
        val active = turn ?: return CommandOutcome.Stale(expectedTurn, null)
        if (expectedTurn != null && expectedTurn != active) return CommandOutcome.Stale(expectedTurn, active)
        write(buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
            }
        })
        return CommandOutcome.Accepted
    }

    /**
     * Tool-permission answers are modelled but not qualified: no `can_use_tool` request was
     * observed on 2.1.270 in the configuration this adapter uses, so the shape below is written
     * from the control-request envelope that was observed and stays unproven until one arrives.
     */
    override suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome {
        write(buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success")
                put("request_id", requestId)
                putJsonObject("response") {
                    put("behavior", if (decision is AgentDecision.Deny) "deny" else "allow")
                    (decision as? AgentDecision.Deny)?.reason?.let { put("message", it) }
                    (decision as? AgentDecision.Answer)?.let { put("message", it.text) }
                }
            }
        })
        return CommandOutcome.Accepted
    }

    override suspend fun interrupt(): CommandOutcome {
        if (turn == null) return CommandOutcome.Stale(null, null)
        val id = "reaktor-${turns.get()}-interrupt"
        val waiting = CompletableDeferred<JsonObject>()
        controls[id] = waiting
        write(buildJsonObject {
            put("type", "control_request")
            put("request_id", id)
            putJsonObject("request") { put("subtype", "interrupt") }
        })
        return withTimeoutOrNull(5000) {
            val response = waiting.await()
            if (response["subtype"]?.jsonPrimitive?.contentOrNull == "success") CommandOutcome.Accepted
            else CommandOutcome.Failed(response.toString().take(200))
        } ?: CommandOutcome.Failed("No control_response within 5s")
    }

    private fun write(message: JsonObject) = synchronized(writeLock) {
        runCatching { stdin.write(message.toString()); stdin.write("\n"); stdin.flush() }
        Unit
    }

    override fun close() {
        controls.values.forEach { it.cancel() }
        runCatching { stdin.close() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

private fun JsonObject.toPending(): PendingRequest? {
    val id = this["request_id"]?.jsonPrimitive?.contentOrNull ?: return null
    val ask = objectOrEmpty("request")
    return when (ask["subtype"]?.jsonPrimitive?.contentOrNull) {
        "can_use_tool" -> PendingRequest(
            id, RequestKind.Permission,
            "Use ${ask["tool_name"]?.jsonPrimitive?.contentOrNull ?: "a tool"}",
            scope = ask["input"]?.toString()?.take(400))
        else -> null
    }
}
