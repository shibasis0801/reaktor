package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.store.FileThreadStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** One admitted execution owner. Provider I/O never runs on the request/UI thread. */
class AgentWorkspace(
    private val root: File,
    private val directory: Path,
    private val runtimes: Map<RuntimeKind, AgentRuntime>,
    private val maxActive: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val lock = Any()
    private val changes = MutableStateFlow(0L)
    private val active = mutableMapOf<String, Job>()
    private val records = mutableMapOf<String, AgentRunRecord>()
    private val index = directory.resolve("recent.json")
    private var recent = emptyList<String>()
    private var closed = false

    init {
        require(root.isDirectory && maxActive in 1..8)
        privateDirectory(directory)
        privateDirectory(directory.resolve("runs"))
        privateDirectory(directory.resolve("threads"))
        privateDirectory(directory.resolve("requests"))
        if (Files.exists(index)) recent = ConductorJson.decodeFromString(ListSerializer(String.serializer()), Files.readString(index))
        require(recent.size <= 200) { "Invalid recent-run index" }
        recent.forEach { id -> load(id)?.let { saved ->
            records[id] = if (saved.status == AgentRunStatus.Running) saved.copy(
                status = AgentRunStatus.Interrupted, revision = saved.revision + 1,
                failure = "Workspace owner stopped without a terminal result. Inspect changes before continuing.", updatedAt = System.currentTimeMillis(),
            ).also(::persist) else saved
        } }
    }

    fun info() = AgentWorkspaceInfo(root.canonicalPath, runtimes.keys.toList(), maxActive)

    fun submit(request: AgentSubmission): AgentRunRecord = synchronized(lock) {
        check(!closed) { "Workspace is closed" }
        require(request.requestId.isNotBlank() && request.requestId.length <= 200)
        require(request.prompt.isNotBlank() && request.prompt.length <= 100000)
        require(request.model == null || request.model.length in 1..256)
        require(request.provider in runtimes) { "Provider is not configured" }
        require(request.context == null || ConductorJson.encodeToString(ContextPacket.serializer(), request.context).length <= 24000)
        val id = digest(request.requestId)
        val fingerprint = digest(ConductorJson.encodeToString(AgentSubmission.serializer(), request))
        (records[id] ?: load(id))?.let {
            require(it.requestFingerprint == fingerprint) { "Request id already belongs to a different submission" }
            return@synchronized recover(it)
        }
        require(active.size < maxActive) { "Agent capacity is busy; wait for an active run" }
        require(active.keys.none { records[it]?.allowWrites == true } && (!request.allowWrites || active.isEmpty())) {
            "Workspace edits require exclusive agent execution"
        }
        val threadId = request.threadId ?: UUID.randomUUID().toString()
        val path = threadPath(threadId)
        require(request.threadId == null || Files.exists(path)) { "Conversation not found in this workspace" }
        require(active.keys.none { records[it]?.threadId == threadId }) { "Conversation already has an active turn" }
        val now = System.currentTimeMillis()
        val record = AgentRunRecord(id, threadId, request.provider, fingerprint, request.prompt.take(200),
            request.model, request.allowWrites, startedAt = now, updatedAt = now)
        atomicWrite(directory.resolve("requests/$id.json"), ConductorJson.encodeToString(AgentSubmission.serializer(), request))
        persist(record)
        records[id] = record
        recent = (listOf(id) + recent).distinct().take(200)
        atomicWrite(index, ConductorJson.encodeToString(ListSerializer(String.serializer()), recent))
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(record, request) }
        active[id] = job
        records.keys.retainAll(recent.toSet() + active.keys)
        job.invokeOnCompletion {
            synchronized(lock) {
                if (records[id]?.status == AgentRunStatus.Running) {
                    FileThreadStore.open(path).use { store ->
                        val saved = store.load() ?: ThreadDocument(ThreadId(threadId), record.title)
                        val prompt = ThreadEvent(EventId(UUID.randomUUID().toString()), Author.Human(), EventKind.Prompt,
                            request.prompt, parents = saved.heads().map { it.id }, createdAtEpochMillis = now)
                        val interrupted = ThreadEvent(EventId(UUID.randomUUID().toString()), Author.Orchestrator("workspace"), EventKind.Failure,
                            "Turn interrupted before dispatch. Inspect workspace changes before continuing.", parents = listOf(prompt.id), createdAtEpochMillis = System.currentTimeMillis())
                        store.checkpoint(saved.append(prompt).append(interrupted))
                    }
                    update(id, true) { running -> running.copy(status = AgentRunStatus.Interrupted, failure = "Run stopped before a terminal result") }
                }
                active.remove(id)
                changes.value++
            }
        }
        changes.value++
        job.start()
        record
    }

    fun get(id: String): AgentRunRecord = synchronized(lock) {
        recover(records[id] ?: load(id) ?: error("Run not found"))
    }

    fun list(limit: Int = 20): List<AgentRunRecord> = synchronized(lock) {
        require(limit in 1..50)
        recent.take(limit).map(::get)
    }

    suspend fun awaitChange(id: String, afterRevision: Long, timeoutMillis: Long): AgentRunRecord {
        require(afterRevision >= 0 && timeoutMillis in 0..30000)
        val before = changes.value
        val current = get(id)
        if (current.revision > afterRevision || current.status != AgentRunStatus.Running || timeoutMillis == 0L) return current
        withTimeoutOrNull(timeoutMillis) {
            changes.first { it > before && (get(id).revision > afterRevision || get(id).status != AgentRunStatus.Running) }
        }
        return get(id)
    }

    suspend fun cancel(id: String): AgentRunRecord {
        val job = synchronized(lock) { get(id); active[id] }
        job?.cancelAndJoin()
        return get(id)
    }

    fun transcript(threadId: String): AgentTranscript {
        val path = threadPath(threadId)
        require(Files.exists(path)) { "Conversation not found in this workspace" }
        // Atomic checkpoints can be read while the owner holds the writer lock.
        val document = decodeThread(Files.readString(path))
        var remaining = 24000
        val selected = document.events.takeLast(20).asReversed().map { event ->
            val text = event.text.take(remaining.coerceAtMost(6000))
            remaining -= text.length
            event.copy(text = text)
        }.asReversed()
        return AgentTranscript(threadId, selected, selected != document.events)
    }

    private suspend fun execute(initial: AgentRunRecord, request: AgentSubmission) {
        try {
            FileThreadStore.open(threadPath(initial.threadId)).use { store ->
                val agentId = AgentId(request.provider.name.lowercase())
                val spec = AgentSpec(agentId, request.provider.name, request.provider, "Follow the workspace's repository instructions.",
                    model = request.model, tools = ToolPolicy(allowWrites = request.allowWrites))
                val saved = store.load() ?: ThreadDocument(ThreadId(initial.threadId), initial.title)
                val priorSpec = saved.agent(agentId)
                val document = saved.copy(participants = saved.participants.filterNot { it.id == agentId } + spec,
                    providerSessions = if (priorSpec != null && priorSpec != spec) saved.providerSessions - agentId.value else saved.providerSessions)
                try {
                    val result = Conductor(runtimes, clock = System::currentTimeMillis, idFactory = { EventId(UUID.randomUUID().toString()) }).run(
                        document, request.prompt, Protocol.Ask(agentId), root.canonicalPath,
                        context = request.context, resumeProviderSession = true, onCheckpoint = store::checkpoint,
                        onEvent = { event -> update(initial.id, persistNow = event is AgentEvent.Started) { current -> when (event) {
                            is AgentEvent.Started -> current.copy(session = event.session)
                            is AgentEvent.Delta -> current.copy(output = (current.output + event.text).takeLast(12000),
                                outputTruncated = current.outputTruncated || current.output.length + event.text.length > 12000)
                            is AgentEvent.ToolUse -> current.copy(lastTool = event.tool)
                            is AgentEvent.Finished -> current.copy(usage = event.outcome.usage, session = event.outcome.session ?: current.session)
                        } } },
                    )
                    val answer = result.answer
                    update(initial.id, true) { it.copy(status = if (answer?.kind == EventKind.Failure) AgentRunStatus.Failed else AgentRunStatus.Completed,
                        output = answer?.text.orEmpty().takeLast(12000), outputTruncated = answer?.text.orEmpty().length > 12000,
                        usage = answer?.usage, reportedUsage = answer?.reportedUsage,
                        failure = answer?.takeIf { event -> event.kind == EventKind.Failure }?.text?.take(2000)) }
                } catch (failure: Exception) {
                    store.load()?.let { latest -> store.checkpoint(latest.append(ThreadEvent(EventId(UUID.randomUUID().toString()),
                        Author.Orchestrator("workspace"), EventKind.Failure,
                        if (failure is CancellationException) "Turn interrupted. Inspect workspace changes before continuing." else failure.message.orEmpty().take(2000),
                        parents = latest.heads().map { it.id }, createdAtEpochMillis = System.currentTimeMillis()))) }
                    throw failure
                }
            }
        } catch (failure: Exception) {
            update(initial.id, true) { it.copy(status = if (failure is CancellationException) AgentRunStatus.Interrupted else AgentRunStatus.Failed,
                failure = if (failure is CancellationException) "Interrupted by the workspace owner" else failure.message.orEmpty().take(2000)) }
        } finally {
            synchronized(lock) { active.remove(initial.id); changes.value++ }
        }
    }

    private fun update(id: String, persistNow: Boolean, change: (AgentRunRecord) -> AgentRunRecord) = synchronized(lock) {
        val current = records.getValue(id)
        val next = change(current).copy(revision = current.revision + 1, updatedAt = System.currentTimeMillis())
        if (persistNow) persist(next)
        records[id] = next
        changes.value++
    }

    private fun recover(record: AgentRunRecord): AgentRunRecord =
        if (record.status == AgentRunStatus.Running && record.id !in active) record.copy(status = AgentRunStatus.Interrupted,
            revision = record.revision + 1, failure = "Owner stopped without a terminal result; inspect changes before continuing.").also { persist(it); records[it.id] = it }
        else record

    private fun runPath(id: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid run id" }
        return directory.resolve("runs/$id.json")
    }
    private fun threadPath(id: String): Path {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid conversation id" }
        return directory.resolve("threads/$id.json")
    }
    private fun load(id: String): AgentRunRecord? = runPath(id).let { path ->
        if (Files.exists(path)) ConductorJson.decodeFromString(AgentRunRecord.serializer(), Files.readString(path)) else null
    }
    private fun persist(record: AgentRunRecord) = atomicWrite(runPath(record.id), ConductorJson.encodeToString(AgentRunRecord.serializer(), record))

    override fun close() {
        val jobs = synchronized(lock) { closed = true; active.values.toList() }
        runBlocking { jobs.forEach { it.cancel() }; jobs.joinAll() }
        scope.cancel()
    }
}
