package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.CliCapabilities
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
                participants = saved.participants.mapValues { (_, participant) -> if (participant.status == AgentRunStatus.Running)
                    participant.copy(status = AgentRunStatus.Interrupted) else participant },
                failure = "Workspace owner stopped without a terminal result. Inspect changes before continuing.", updatedAt = System.currentTimeMillis(),
            ).also(::persist) else saved
        } }
    }

    // Probing runs `--version` and `--help` per provider, so it happens once and is reused. An
    // upgraded CLI is picked up by restarting the owner rather than by paying for a probe per call.
    private val capabilities: List<ProviderCapability> by lazy {
        runtimes.keys.map { CliCapabilities.probe(it) }
    }

    fun info() = AgentWorkspaceInfo(root.canonicalPath, runtimes.keys.toList(), maxActive,
        collaborations = if (maxActive >= 2 && runtimes.size >= 2) AgentCollaboration.entries else listOf(AgentCollaboration.Single),
        capabilities = capabilities)

    /**
     * Refuses an effort the provider does not advertise instead of quietly sending a different one.
     * A provider whose set cannot be enumerated accepts the value and records it as unverified.
     */
    private fun requireSupportedEffort(provider: RuntimeKind, effort: NativeEffort?) {
        val resolution = capabilities.firstOrNull { it.runtime == provider }?.effort.resolve(effort)
        if (resolution is EffortResolution.Unsupported) throw UnsupportedEffortException(resolution)
    }

    fun submit(request: AgentSubmission): AgentRunRecord = synchronized(lock) {
        check(!closed) { "Workspace is closed" }
        require(request.requestId.isNotBlank() && request.requestId.length <= 200)
        require(request.prompt.isNotBlank() && request.prompt.length <= 100000)
        require(request.model == null || request.model.length in 1..256)
        require(request.provider in runtimes) { "Provider is not configured" }
        require(request.collaboration in info().collaborations) { "Collaboration is not available in this workspace" }
        if (request.collaboration == AgentCollaboration.Single) {
            require(request.partner == null) { "Single-agent turns cannot have a partner" }
        } else {
            val partner = requireNotNull(request.partner) { "Choose a second provider" }
            require(partner.provider in runtimes && partner.provider != request.provider) { "Choose two different configured providers" }
            require(partner.model == null || partner.model.length in 1..256)
            require(!request.allowWrites) { "Collaborative turns request inspection; parallel editing requires owned worktrees" }
            requireSupportedEffort(partner.provider, partner.effort)
        }
        requireSupportedEffort(request.provider, request.effort)
        require(request.context == null || ConductorJson.encodeToString(ContextPacket.serializer(), request.context).length <= 24000)
        val id = digest(request.requestId)
        val fingerprint = digest(ConductorJson.encodeToString(AgentSubmission.serializer(), request))
        (records[id] ?: load(id))?.let {
            require(it.requestFingerprint == fingerprint) { "Request id already belongs to a different submission" }
            return@synchronized recover(it)
        }
        val slots = if (request.collaboration == AgentCollaboration.Single) 1 else 2
        require(active.keys.sumOf { if (records[it]?.collaboration == AgentCollaboration.Single) 1 else 2 } + slots <= maxActive) {
            "Agent capacity is busy; wait for an active run"
        }
        require(active.keys.none { records[it]?.allowWrites == true } && (!request.allowWrites || active.isEmpty())) {
            "Workspace edits require exclusive agent execution"
        }
        val threadId = request.threadId ?: UUID.randomUUID().toString()
        val path = threadPath(threadId)
        require(request.threadId == null || Files.exists(path)) { "Conversation not found in this workspace" }
        require(active.keys.none { records[it]?.threadId == threadId }) { "Conversation already has an active turn" }
        val now = System.currentTimeMillis()
        val record = AgentRunRecord(id, threadId, request.provider, fingerprint, request.prompt.take(200),
            request.model, request.allowWrites, startedAt = now, updatedAt = now,
            collaboration = request.collaboration, partner = request.partner,
            effort = request.effort?.let { EffortRecord(requested = it, resolved = it) } ?: EffortRecord.none)
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
                fun spec(provider: RuntimeKind, model: String?, effort: NativeEffort?) = AgentSpec(
                    AgentId(provider.name.lowercase()), provider.name, provider,
                    "Follow the workspace's repository instructions.", model = model, effort = effort,
                    tools = ToolPolicy(allowWrites = request.allowWrites))
                val specs = listOf(spec(request.provider, request.model, request.effort)) +
                    listOfNotNull(request.partner?.let { spec(it.provider, it.model, it.effort) })
                val saved = store.load() ?: ThreadDocument(ThreadId(initial.threadId), initial.title)
                val ids = specs.map { it.id }
                val invalidated = specs.filter { saved.agent(it.id)?.let { prior -> prior != it } == true }.map { it.id.value }
                val document = saved.copy(participants = saved.participants.filterNot { it.id in ids } + specs,
                    providerSessions = saved.providerSessions - invalidated.toSet())
                val protocol = when (request.collaboration) {
                    AgentCollaboration.Single -> Protocol.Ask(agentId)
                    AgentCollaboration.Compare -> Protocol.All(ids, blind = true)
                    AgentCollaboration.Council -> Protocol.Council(ids, synthesizer = agentId)
                }
                try {
                    val result = Conductor(runtimes, clock = System::currentTimeMillis, idFactory = { EventId(UUID.randomUUID().toString()) }).run(
                        document, request.prompt, protocol, root.canonicalPath,
                        context = request.context, resumeProviderSession = request.collaboration == AgentCollaboration.Single,
                        onCheckpoint = { checkpoint ->
                            store.checkpoint(if (request.collaboration == AgentCollaboration.Single) checkpoint else
                                checkpoint.copy(providerSessions = checkpoint.providerSessions - ids.map { it.value }.toSet()))
                            update(initial.id, true) { it.copy(turnUsage = checkpoint.copy(events = checkpoint.events.drop(saved.events.size)).usageSummary()) }
                        },
                        onEvent = { event -> update(initial.id, persistNow = event is AgentEvent.Started || event is AgentEvent.Finished) { current ->
                            val participant = current.participants[event.agent.value] ?: AgentParticipantRun(specs.first { it.id == event.agent }.runtime)
                            val next = when (event) {
                                is AgentEvent.Started -> participant.copy(status = AgentRunStatus.Running, session = event.session, output = "", outputTruncated = false, lastTool = null)
                                is AgentEvent.Delta -> participant.copy(output = (participant.output + event.text).takeLast(6000),
                                    outputTruncated = participant.outputTruncated || participant.output.length + event.text.length > 6000)
                                is AgentEvent.ToolUse -> participant.copy(lastTool = event.tool)
                                is AgentEvent.Reasoning -> participant
                                is AgentEvent.Finished -> participant.copy(status = if (event.outcome.ok) AgentRunStatus.Completed else AgentRunStatus.Failed,
                                    output = (event.outcome.failure ?: event.outcome.text).takeLast(6000),
                                    outputTruncated = (event.outcome.failure ?: event.outcome.text).length > 6000,
                                    session = event.outcome.session ?: participant.session)
                            }
                            val withParticipant = current.copy(participants = current.participants + (event.agent.value to next))
                            if (request.collaboration != AgentCollaboration.Single) withParticipant else when (event) {
                            is AgentEvent.Started -> withParticipant.copy(session = event.session)
                            is AgentEvent.Delta -> withParticipant.copy(output = (current.output + event.text).takeLast(12000),
                                outputTruncated = current.outputTruncated || current.output.length + event.text.length > 12000)
                            is AgentEvent.ToolUse -> withParticipant.copy(lastTool = event.tool)
                            is AgentEvent.Reasoning -> withParticipant
                            is AgentEvent.Finished -> withParticipant.copy(usage = event.outcome.usage, session = event.outcome.session ?: current.session)
                        } } },
                    )
                    val answer = result.answer
                    val failures = result.added.filter { it.kind == EventKind.Failure }
                    update(initial.id, true) { it.copy(status = if (failures.isNotEmpty()) AgentRunStatus.Failed else AgentRunStatus.Completed,
                        output = answer?.text.orEmpty().takeLast(12000), outputTruncated = answer?.text.orEmpty().length > 12000,
                        usage = answer?.usage?.takeIf { request.collaboration == AgentCollaboration.Single },
                        reportedUsage = answer?.reportedUsage?.takeIf { request.collaboration == AgentCollaboration.Single },
                        turnUsage = result.thread.copy(events = result.added).usageSummary(),
                        failure = failures.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }?.take(2000)) }
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
                participants = it.participants.mapValues { (_, participant) -> if (participant.status == AgentRunStatus.Running)
                    participant.copy(status = if (failure is CancellationException) AgentRunStatus.Interrupted else AgentRunStatus.Failed) else participant },
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
            participants = record.participants.mapValues { (_, participant) -> if (participant.status == AgentRunStatus.Running)
                participant.copy(status = AgentRunStatus.Interrupted) else participant },
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
