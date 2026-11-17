package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.worklog.ClaudeLog
import dev.shibasis.reaktor.conductor.worklog.parseRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import dev.shibasis.reaktor.conductor.worklog.WorkLogs

class ClaudeAppRuntime(
    private val app: ClaudeApp = ClaudeApp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val outside: InteractiveAgentRuntime = ClaudeCodeSessionRuntime(scope = scope),
    private val briefs: File = File(System.getProperty("user.home"), ".reaktor/briefs"),
) : InteractiveAgentRuntime {
    override val kind: RuntimeKind = RuntimeKind.ClaudeCode

    override val interactive = Qualification(advertised = true, configured = true, implemented = true, qualifiedBy = "ClaudeAppTest")

    override fun run(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val session = runCatching { open(request) }.getOrElse { failure ->
            send(AgentEvent.Finished(request.agent.id, AgentOutcome(
                request.agent.id, "", false, failure = failure.message ?: "Could not open a Claude app session")))
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
        if (!app.installed) return OutsideApp(outside.open(request), request.agent.id, "the Claude app is not installed")
        return ClaudeAppSession(app, request, directory.canonicalFile, briefs)
    }
}

private class OutsideApp(private val session: AgentSession, private val agent: AgentId, private val reason: String) : AgentSession by session {
    override val events: Flow<AgentEvent> = flow {
        emit(AgentEvent.Activity(agent, AgentActivityItem("claude-outside-app", ActivityKind.Control,
            "Runs outside the Claude app because $reason", ActivityStatus.Recorded)))
        emitAll(session.events)
    }
}

private class ClaudeAppSession(
    private val app: ClaudeApp,
    private val request: AgentRequest,
    private val directory: File,
    private val briefs: File,
) : AgentSession {
    private val agent = request.agent.id
    private val turnId = "${UUID.randomUUID()}-turn-1"
    private val resume = request.resume?.takeIf { it.runtime == RuntimeKind.ClaudeCode }?.sessionId
    @Volatile private var turn: String? = null
    @Volatile private var session: ClaudeApp.Session? = null
    @Volatile private var closed = false
    private var briefFile: File? = null

    override val activeTurn: String? get() = turn

    override val events: Flow<AgentEvent> = channelFlow {
        try {
        val since = System.currentTimeMillis()
        val known = withContext(Dispatchers.IO) { app.sessions().map { it.id }.toSet() }
        val earlier = resume?.let { withContext(Dispatchers.IO) { app.session(it) } }
        val from = resume?.let { withContext(Dispatchers.IO) { app.transcript(it)?.length() } } ?: 0L
        withContext(Dispatchers.IO) {
            when {
                earlier != null -> app.show(earlier)
                resume != null -> app.adopt(resume)
                else -> app.start(directory, sent)
            }
        }
        send(AgentEvent.Activity(agent, waiting()))
        val opened = awaitSession(since, known, from, maxOf(request.agent.budget.timeoutMillis, SendWindow))
        if (opened == null) {
            send(AgentEvent.Finished(agent, AgentOutcome(agent, "", false, interrupted = closed,
                failure = if (closed) "Interrupted" else "Nobody sent it in the Claude app in time")))
            return@channelFlow
        }
        session = opened
        turn = turnId
        send(AgentEvent.Started(agent, ProviderSession(RuntimeKind.ClaudeCode, opened.cli)))
        send(AgentEvent.TurnStarted(agent, turnId))
        send(AgentEvent.Activity(agent, AgentActivityItem("claude-app-session", ActivityKind.Control,
            "Claude app session · ${opened.title ?: opened.id}", ActivityStatus.Recorded, output = opened.id)))
        val outcome = try { follow(opened, from) { send(it) } } finally { turn = null }
        send(AgentEvent.Finished(agent, outcome))
        } finally { briefFile?.delete(); briefFile = null }
    }

    private val sent: String by lazy { brief() }

    private fun brief(): String {
        val text = if (request.agent.tools.allowWrites) request.prompt else "$InspectOnly\n\n${request.prompt}"
        if (text.length <= ClaudeApp.PromptLimit) return text
        Files.createDirectories(briefs.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.setPosixFilePermissions(briefs.toPath(), PosixFilePermissions.fromString("rwx------"))
        val file = Files.createTempFile(briefs.toPath(), "brief-", ".md",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toFile()
        briefFile = file
        file.writeText(text)
        return "Read the brief at ${file.path} and do what it says."
    }

    private fun waiting(): AgentActivityItem {
        val model = listOfNotNull(request.agent.model, request.agent.effort?.value).joinToString(" · ").ifBlank { null }
        val output = when {
            resume != null -> "Hangar cannot type into a Claude app session. Send this there and Hangar follows the turn:\n\n${request.prompt.take(4_000)}"
            model != null -> "The Claude app opened a new session in ${directory.path} with this brief. Pick $model there and press send."
            else -> "The Claude app opened a new session in ${directory.path} with this brief. Press send there."
        }
        val title = if (resume != null) "Continue it in the Claude app" else "Send it in the Claude app"
        return AgentActivityItem("claude-app-send", ActivityKind.Control, title, ActivityStatus.Waiting, output = output)
    }

    private suspend fun awaitSession(since: Long, known: Set<String>, from: Long, window: Long): ClaudeApp.Session? {
        val deadline = since + window
        val opening = sent.trim().take(Opening)
        if (resume != null) {
            val opened = withContext(Dispatchers.IO) { app.session(resume) } ?: return null
            val transcript = withContext(Dispatchers.IO) { app.transcript(opened.cli) } ?: return null
            val tail = TranscriptTail(transcript, from)
            try {
                while (!closed) {
                    val submitted = withContext(Dispatchers.IO) { WorkLogs.claudeTurn(transcript, directory.path)?.promptOffset?.let { it > from } == true }
                    if (submitted) return opened
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0 || !tail.await(remaining)) return null
                }
            } finally { tail.close() }
            return null
        }
        while (!closed && System.currentTimeMillis() < deadline) {
            val found = withContext(Dispatchers.IO) {
                app.sessions(since - Slack).filter { it.id !in known && File(it.directory).canonicalPath == directory.path }
                    .let { fresh -> fresh.firstOrNull { firstPrompt(it)?.startsWith(opening) == true } ?: fresh.singleOrNull() }
            }
            if (found != null) return found
            delay(1_000)
        }
        return null
    }

    private fun firstPrompt(session: ClaudeApp.Session): String? = app.transcript(session.cli)?.let { file ->
        WorkLogs.claude(file, directory.path) { it.firstPrompt }
    }

    private suspend fun follow(opened: ClaudeApp.Session, from: Long, emit: suspend (AgentEvent) -> Unit): AgentOutcome {
        val reader = ClaudeTranscriptEvents(agent, EffortRecord(requested = request.agent.effort))
        val deadline = System.currentTimeMillis() + request.agent.budget.timeoutMillis
        var file = withContext(Dispatchers.IO) { app.transcript(opened.cli) }
        while (file == null && !closed && System.currentTimeMillis() < deadline) {
            delay(500)
            file = withContext(Dispatchers.IO) { app.transcript(opened.cli) }
        }
        val transcript = file ?: return reader.outcome(opened, closed, "The Claude app session wrote no transcript")
        val tail = TranscriptTail(transcript, from)
        var offset = from
        var busy = false
        try {
            while (true) {
                withContext(Dispatchers.IO) { WorkLogs.claudeTurn(transcript, directory.path, offset) }?.let { snapshot ->
                    offset = snapshot.offset
                    reader.accept(snapshot)
                    snapshot.events.forEach { emit(it.forAgent(agent)) }
                }
                val status = withContext(Dispatchers.IO) { app.status(opened.cli) }
                if (status == "busy") busy = true
                if (reader.ended || busy && status == "idle") {
                    withContext(Dispatchers.IO) { WorkLogs.claudeTurn(transcript, directory.path, offset) }?.let { snapshot ->
                        reader.accept(snapshot)
                        snapshot.events.forEach { emit(it.forAgent(agent)) }
                    }
                    return reader.outcome(opened, false)
                }
                if (closed) return reader.outcome(opened, true)
                if (System.currentTimeMillis() > deadline)
                    return reader.outcome(opened, false, "The turn passed its ${request.agent.budget.timeoutMillis} ms budget; it keeps running in the Claude app")
                tail.await((deadline - System.currentTimeMillis()).coerceAtLeast(1))
            }
        } finally {
            tail.close()
        }
    }

    override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
        session?.let { withContext(Dispatchers.IO) { app.show(it) } }
        return CommandOutcome.Unsupported("Claude app sessions take messages in the Claude app; Hangar opened it there")
    }

    override suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome = CommandOutcome.Stale(requestId, null)

    override suspend fun interrupt(): CommandOutcome {
        session?.let { withContext(Dispatchers.IO) { app.show(it) } }
        return CommandOutcome.Unsupported("Stop it in the Claude app; Hangar opened it there")
    }

    override fun close() {
        closed = true
        briefFile?.delete()
        briefFile = null
    }

    private companion object {
        const val SendWindow = 30 * 60 * 1000L
        const val Slack = 5_000L
        const val Opening = 120
        const val InspectOnly = "Inspect only: read, search and report. Change no file and run no command that changes anything."
    }
}

internal class ClaudeTranscriptEvents(private val agent: AgentId, private val effort: EffortRecord) {
    private val parser = ClaudeLog(null) { null }
    private var snapshot: WorkLogs.ClaudeSnapshot? = null
    val answer: String get() = snapshot?.answer ?: parser.answer
    val ended: Boolean get() = snapshot?.ended ?: parser.ended
    val answered: Boolean get() = ended && answer.isNotBlank()

    fun line(text: String): List<AgentEvent> = parseRecord(text)?.let { parser.events(it, agent) }.orEmpty()
    fun accept(value: WorkLogs.ClaudeSnapshot) { snapshot = value }

    fun outcome(session: ClaudeApp.Session, stopped: Boolean, limit: String? = null): AgentOutcome {
        val body = answer
        val ok = !stopped && limit == null && body.isNotBlank()
        return AgentOutcome(agent, body, ok,
            failure = when {
                ok -> null
                limit != null -> limit
                stopped -> "Interrupted"
                body.isBlank() -> "The Claude app session ended the turn without an answer"
                else -> "The Claude app session did not finish the turn"
            }, session = ProviderSession(RuntimeKind.ClaudeCode, session.cli), usage = snapshot?.usage ?: parser.usage, effort = effort, interrupted = stopped,
            attributes = mapOf("executorHarness" to "Claude app", "session" to session.id, "conversation" to session.cli))
    }
}

private fun AgentEvent.forAgent(agent: AgentId): AgentEvent = when (this) {
    is AgentEvent.Started -> copy(agent = agent)
    is AgentEvent.Delta -> copy(agent = agent)
    is AgentEvent.Reasoning -> copy(agent = agent)
    is AgentEvent.Activity -> copy(agent = agent)
    is AgentEvent.ToolUse -> copy(agent = agent)
    is AgentEvent.Usage -> copy(agent = agent)
    else -> this
}
