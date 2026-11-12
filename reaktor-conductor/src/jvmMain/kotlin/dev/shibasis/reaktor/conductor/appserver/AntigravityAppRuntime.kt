package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.*
import dev.shibasis.reaktor.conductor.telemetry.GeminiTelemetry
import dev.shibasis.reaktor.conductor.telemetry.rollupTelemetry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.UUID
import java.util.concurrent.TimeUnit

class AntigravityAppRuntime(
    private val app: AntigravityApp = AntigravityApp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val candidates: List<String> = defaultAntigravityModels,
    private val headless: InteractiveAgentRuntime = AntigravitySessionRuntime(scope = scope, candidates = candidates),
) : InteractiveAgentRuntime {
    override val kind: RuntimeKind = RuntimeKind.Gemini

    override val interactive = Qualification(advertised = true, configured = true, implemented = true,
        qualifiedBy = "AntigravityAppLiveTest")

    override fun run(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val session = runCatching { open(request) }.getOrElse { failure ->
            send(AgentEvent.Finished(request.agent.id, AgentOutcome(
                request.agent.id, "", false, failure = failure.message ?: "Could not start an Antigravity conversation")))
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
        antigravityGuard(request)
        headlessReason(request)?.let { return Headless(headless.open(request), request.agent.id, it) }
        val hub = app.connect()
        val project = withContext(Dispatchers.IO) { app.project(hub, directory) }
            ?: error("No Antigravity project holds ${directory.canonicalPath}. Open that folder in Antigravity once, then start the run again.")
        val resume = request.resume?.takeIf { it.runtime == RuntimeKind.Gemini }?.sessionId
        if (resume != null && withContext(Dispatchers.IO) { app.run(hub, resume) } == null)
            return Headless(headless.open(request), request.agent.id, "the conversation it continues was started headless")
        return AntigravityAppSession(app, hub, project, request, directory, resume, antigravityModelOrder(request.agent.model, candidates))
    }

    private fun headlessReason(request: AgentRequest): String? = when {
        !app.installed -> "the Antigravity app is not installed"
        antigravityPrompt(request).toByteArray().size > PromptLimit -> "its prompt is larger than the app's command line takes"
        else -> null
    }

    private companion object {
        val PromptLimit = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) 800_000 else 120_000
    }
}

internal fun antigravityAppModel(model: String, effort: NativeEffort?): String =
    if (effort == null || model.substringAfterLast('-') in setOf("low", "medium", "high")) model else "$model-${effort.value}"

private class Headless(private val session: AgentSession, private val agent: AgentId, private val reason: String) : AgentSession by session {
    override val events: Flow<AgentEvent> = flow {
        emit(AgentEvent.Activity(agent, AgentActivityItem("antigravity-headless", ActivityKind.Control,
            "Runs headless because $reason", ActivityStatus.Recorded)))
        emitAll(session.events)
    }
}

private class AntigravityAppSession(
    private val app: AntigravityApp,
    private val hub: AntigravityApp.Hub,
    private val project: AntigravityApp.Project,
    private val request: AgentRequest,
    private val directory: File,
    private val resume: String?,
    private val models: List<String>,
) : AgentSession {
    private val agent = request.agent.id
    private val turnId = "${UUID.randomUUID()}-turn-1"
    @Volatile private var turn: String? = null
    @Volatile private var conversation: String? = resume
    @Volatile private var interrupted = false
    @Volatile private var closed = false

    override val activeTurn: String? get() = turn

    override val events: Flow<AgentEvent> = channelFlow {
        turn = turnId
        send(AgentEvent.TurnStarted(agent, turnId))
        if (!request.agent.tools.allowWrites) send(AgentEvent.Activity(agent, AgentActivityItem("antigravity-inspect-only", ActivityKind.Control,
            "Inspect only by its brief; the Antigravity app does not enforce it", ActivityStatus.Recorded)))
        try {
            val outcome = if (resume != null) continueIn(resume) { send(it) } else ladder { send(it) }
            turn = null
            send(AgentEvent.Finished(agent, outcome))
        } finally {
            turn = null
        }
    }

    private suspend fun ladder(emit: suspend (AgentEvent) -> Unit): AgentOutcome {
        var last: AgentOutcome? = null
        for ((index, model) in models.withIndex()) {
            if (interrupted || closed) break
            val next = models.getOrNull(index + 1)
            val variant = antigravityAppModel(model, request.agent.effort)
            val id = withContext(Dispatchers.IO) { app.start(hub, project, directory, variant, title(), antigravityPrompt(request.withModel(model))) }
            conversation = id
            emit(AgentEvent.Started(agent, ProviderSession(RuntimeKind.Gemini, id)))
            emit(AgentEvent.Activity(agent, AgentActivityItem("antigravity-conversation-$index", ActivityKind.Control,
                "Antigravity conversation in ${project.folder}", ActivityStatus.Recorded, output = id)))
            val wanted = antigravityModelNames[variant]
            val served = withContext(Dispatchers.IO) { runCatching { app.model(hub, id) }.getOrNull() }
            if (wanted != null && served != null && served != wanted) {
                withContext(Dispatchers.IO) { runCatching { app.cancel(hub, id) } }
                last = AgentOutcome(agent, "", false, failure = "Antigravity ran $served instead of $wanted",
                    session = ProviderSession(RuntimeKind.Gemini, id))
                if (next == null) break
                emit(AgentEvent.Activity(agent, AgentActivityItem("antigravity-model-$index", ActivityKind.Control,
                    "Antigravity serves $served instead of $wanted; trying $next", ActivityStatus.Recorded)))
                continue
            }
            val reader = AntigravityTranscriptEvents(agent)
            val outcome = follow(id, 0L, 0, reader, variant, emit)
            if (next != null && !reader.progressed && !interrupted && !closed && outcome.outOfCapacity()) {
                emit(antigravityCapacitySwitch(agent, index, variant, next, outcome))
                last = outcome
                continue
            }
            return outcome
        }
        return last ?: AgentOutcome(agent, "", false, failure = "Interrupted", interrupted = true)
    }

    private suspend fun continueIn(id: String, emit: suspend (AgentEvent) -> Unit): AgentOutcome {
        val from = app.transcript(id).takeIf(File::isFile)?.length() ?: 0L
        val before = withContext(Dispatchers.IO) { app.run(hub, id)?.steps ?: 0 }
        withContext(Dispatchers.IO) { app.send(hub, project, directory, id, antigravityPrompt(request)) }
        emit(AgentEvent.Started(agent, ProviderSession(RuntimeKind.Gemini, id)))
        val model = request.agent.model?.let { antigravityAppModel(it, request.agent.effort) } ?: "the conversation's model"
        return follow(id, from, before, AntigravityTranscriptEvents(agent), model, emit)
    }

    private suspend fun follow(id: String, from: Long, before: Int, reader: AntigravityTranscriptEvents, model: String,
                               emit: suspend (AgentEvent) -> Unit): AgentOutcome {
        val tail = TranscriptTail(app.transcript(id), from)
        val deadline = System.currentTimeMillis() + request.agent.budget.timeoutMillis
        var seenRunning = false
        try {
            while (true) {
                tail.lines().forEach { line -> reader.line(line).forEach { emit(it) } }
                val run = withContext(Dispatchers.IO) { runCatching { app.run(hub, id) }.getOrNull() }
                if (run?.running == true) seenRunning = true
                if (run != null && !run.running && (seenRunning || run.steps > before + 1)) {
                    repeat(10) {
                        tail.lines().forEach { line -> reader.line(line).forEach { emit(it) } }
                        if (reader.settled) return reader.outcome(run, id, project, model, request.agent.effort, interrupted)
                        tail.await(500)
                    }
                    return reader.outcome(run, id, project, model, request.agent.effort, interrupted)
                }
                if (closed) return reader.outcome(run, id, project, model, request.agent.effort, true)
                if (System.currentTimeMillis() > deadline) {
                    withContext(Dispatchers.IO) { runCatching { app.cancel(hub, id) } }
                    return reader.outcome(run, id, project, model, request.agent.effort, false,
                        limit = "The turn reached its ${request.agent.budget.timeoutMillis} ms budget; Antigravity was asked to stop it")
                }
                tail.await(1_000)
            }
        } finally {
            tail.close()
        }
    }

    private fun title(): String {
        val line = request.prompt.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
        return "${request.agent.name} · $line".take(80)
    }

    override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
        val active = turn ?: return CommandOutcome.Stale(expectedTurn, null)
        if (expectedTurn != null && expectedTurn != active) return CommandOutcome.Stale(expectedTurn, active)
        val id = conversation ?: return CommandOutcome.Failed("The Antigravity conversation has not started yet")
        return withContext(Dispatchers.IO) {
            runCatching { app.send(hub, project, directory, id, text) }
                .fold({ CommandOutcome.Accepted }, { CommandOutcome.Failed(it.message ?: "Antigravity refused the message") })
        }
    }

    override suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome =
        CommandOutcome.Stale(requestId, null)

    override suspend fun interrupt(): CommandOutcome {
        if (turn == null) return CommandOutcome.Stale(null, null)
        interrupted = true
        val id = conversation ?: return CommandOutcome.Accepted
        return withContext(Dispatchers.IO) {
            runCatching { app.cancel(hub, id) }
                .fold({ CommandOutcome.Accepted }, { CommandOutcome.Failed(it.message ?: "Antigravity refused to stop") })
        }
    }

    override fun close() {
        closed = true
    }
}

internal class AntigravityTranscriptEvents(private val agent: AgentId) {
    private val pending = ArrayDeque<String>()
    private val names = mutableMapOf<String, String>()
    private val telemetry = GeminiTelemetry.SessionCursor()
    private var spoke = false

    var answer: String = ""
        private set
    var failure: String? = null
        private set
    var settled: Boolean = false
        private set
    var progressed: Boolean = false
        private set

    fun line(text: String): List<AgentEvent> {
        val row = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val index = row.string("step_index") ?: return emptyList()
        settled = false
        return when (row.string("type")) {
            "PLANNER_RESPONSE" -> buildList {
                progressed = true
                telemetry.ingest(row)
                row.string("thinking")?.trim()?.takeIf(String::isNotEmpty)?.let { add(AgentEvent.Reasoning(agent, it, ReasoningFidelity.Thinking)) }
                row.string("content")?.trim()?.takeIf(String::isNotEmpty)?.let {
                    add(AgentEvent.Delta(agent, if (spoke) "\n\n$it" else it))
                    spoke = true
                    answer = it
                }
                val calls = (row["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                calls.forEachIndexed { position, call ->
                    val id = "agy-$index-$position"
                    val name = call.string("name") ?: "Antigravity tool"
                    pending += id
                    names[id] = name
                    add(AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Tool, name, ActivityStatus.Started,
                        input = call["args"]?.toString()?.take(12_000))))
                }
                settled = calls.isEmpty()
            }
            "GENERIC" -> pending.removeFirstOrNull()?.let { id ->
                val failed = row.string("status") == "ERROR" || row.string("error") != null
                listOf(AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Tool, names[id] ?: "Antigravity tool",
                    if (failed) ActivityStatus.Failed else ActivityStatus.Completed,
                    output = (row.string("error") ?: row.string("content"))?.take(12_000))))
            }.orEmpty()
            "CHECKPOINT" -> listOf(AgentEvent.Activity(agent, AgentActivityItem("agy-$index", ActivityKind.Checkpoint,
                "Antigravity checkpoint", ActivityStatus.Completed)))
            "ERROR_MESSAGE" -> {
                val message = row.string("content")?.trim()?.takeIf(String::isNotEmpty) ?: "Antigravity reported an error"
                failure = message
                settled = true
                listOf(AgentEvent.Activity(agent, AgentActivityItem("agy-$index", ActivityKind.Control, "Antigravity error",
                    ActivityStatus.Failed, output = message.take(12_000))))
            }
            else -> emptyList()
        }
    }

    fun outcome(run: AntigravityApp.Run?, conversation: String, project: AntigravityApp.Project, model: String,
                effort: NativeEffort?, stopped: Boolean, limit: String? = null): AgentOutcome {
        val ended = run?.ended
        val interrupted = stopped || ended == "EXECUTOR_TERMINATION_REASON_USER_CANCELED"
        val ok = !interrupted && limit == null && failure == null && answer.isNotBlank() &&
            (ended == null || ended == "EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL")
        val reason = when {
            ok -> null
            limit != null -> limit
            interrupted -> "Interrupted"
            failure != null -> failure
            answer.isBlank() -> "Antigravity ended the turn without an answer (${ended ?: "no termination reason"})"
            else -> "Antigravity ended the turn with $ended"
        }
        val rollup = telemetry.records().map { it.copy(model = model).normalize() }.rollupTelemetry()
        val usage = if (rollup.requests > 0) AgentUsage(
            inputTokens = rollup.inputTokens,
            outputTokens = rollup.outputTokens,
            cachedInputTokens = rollup.cachedInputTokens,
            cacheWriteInputTokens = rollup.cacheWriteInputTokens,
            reasoningOutputTokens = rollup.reasoningOutputTokens,
            scope = UsageScope.Turn,
        ) else null
        return AgentOutcome(agent, answer, ok, failure = reason, session = ProviderSession(RuntimeKind.Gemini, conversation),
            usage = usage,
            effort = EffortRecord(requested = effort, resolved = effort), interrupted = interrupted,
            attributes = mapOf("executorHarness" to "Antigravity app", "entitlement" to "GoogleAgent", "model" to model,
                "conversation" to conversation, "project" to project.id))
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}

internal class TranscriptTail(private val file: File, private var offset: Long) : AutoCloseable {
    private var partial = ByteArray(0)
    private var watcher: WatchService? = null

    fun lines(): List<String> {
        if (!file.isFile) return emptyList()
        val length = file.length()
        if (length <= offset) return emptyList()
        val fresh = RandomAccessFile(file, "r").use { handle ->
            handle.seek(offset)
            ByteArray((length - offset).toInt()).also(handle::readFully)
        }
        offset = length
        val bytes = partial + fresh
        val end = bytes.lastIndexOf('\n'.code.toByte())
        if (end < 0) {
            partial = bytes
            return emptyList()
        }
        partial = bytes.copyOfRange(end + 1, bytes.size)
        return bytes.copyOfRange(0, end).decodeToString().split('\n').filter(String::isNotBlank)
    }

    suspend fun await(millis: Long) = withContext(Dispatchers.IO) {
        val directory = file.parentFile
        if (watcher == null && directory.isDirectory) {
            watcher = FileSystems.getDefault().newWatchService().also {
                directory.toPath().register(it, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
            }
        }
        val active = watcher
        if (active == null) delay(millis)
        else active.poll(millis, TimeUnit.MILLISECONDS)?.let { key ->
            key.pollEvents()
            key.reset()
        }
    }

    override fun close() {
        watcher?.close()
    }
}
