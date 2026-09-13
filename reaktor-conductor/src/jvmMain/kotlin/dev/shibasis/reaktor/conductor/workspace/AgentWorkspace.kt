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
    private val discover: (RuntimeKind) -> ProviderCapability = CliCapabilities::probe,
    private val batchRuntimes: Map<RuntimeKind, AgentRuntime> = emptyMap(),
    private val harnessMcpConfig: String? = null,
    private val background: Boolean = false,
    private val workflowCheck: (suspend (String, String, WorkflowStage) -> WorkflowCheckResult)? = null,
    private val externalBusy: () -> Boolean = { false },
) : AutoCloseable {
    private val lock = Any()
    private val changes = MutableStateFlow(0L)
    private val active = mutableMapOf<String, Job>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, AgentSession>>()
    private val records = mutableMapOf<String, AgentRunRecord>()
    private val index = directory.resolve("recent.json")
    private var recent = emptyList<String>()
    private var closed = false
    private var suspending = false
    private val lastPersisted = mutableMapOf<String, Long>()
    private val serviceStartedAt = System.currentTimeMillis()
    val activity = AgentActivityStore(directory.resolve("activity"))
    private val activityProjection = AgentActivityProjection()
    private val queueFile = directory.resolve("queue.json")
    private var queued: List<AgentQueuedTurn> = if (Files.exists(queueFile))
        ConductorJson.decodeFromString(ListSerializer(AgentQueuedTurn.serializer()), Files.readString(queueFile)) else emptyList()
    val evidence = AgentEvidenceStore(root, directory.resolve("evidence"))
    val checkpoints = AgentCheckpoints(directory.resolve("checkpoints"))
    val runbooks = AgentRunbooks(directory.resolve("runbooks"))
    val worktrees = AgentWorktrees(root, directory.resolve("worktrees"), evidence.artifacts)
    val localContext = AgentLocalContext(root, directory)
    val memory = AgentMemoryStore(root, directory.resolve("memory"), evidence.artifacts)
    fun remember(runId: String, eventId: String) = memory.remember(get(runId), requireNotNull(checkpoint(runId)), eventId)
    private var draining = false

    fun drain(enabled: Boolean): Int = synchronized(lock) { draining = enabled; active.size + if (externalBusy()) 1 else 0 }
    fun acceptsWork(): Boolean = synchronized(lock) { !closed && !draining }
    fun <T> admitOperation(action: () -> T): T = synchronized(lock) { check(acceptsWork()) { "Workspace is draining" }; action() }
    fun applyWorktree(id: String, patch: String, revision: String): AgentWorktreeReview = synchronized(lock) {
        require(active.isEmpty() && !externalBusy()) { "Wait for active workspace work before applying" }
        worktrees.apply(id, patch, revision)
    }
    fun compactActivity(olderThanDays: Int = 30): Long = synchronized(lock) {
        require(olderThanDays in 1..3650)
        val cutoff = System.currentTimeMillis() - olderThanDays * 86400000L
        Files.list(directory.resolve("runs")).use { files -> files.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }.toList() }
            .mapNotNull { load(it.fileName.toString().removeSuffix(".json")) }
            .filter { it.id !in active && it.status != AgentRunStatus.Running && it.recovery == AgentRecovery.None && it.updatedAt < cutoff }
            .sumOf { activity.archive(it.id) }
    }
    fun pruneRuntimeImages(olderThanDays: Int = 30): Long = synchronized(lock) {
        require(active.isEmpty() && !externalBusy()) { "Wait for running work before pruning service images" }
        pruneServiceImages(directory, olderThanDays)
    }

    fun taskEvidence(taskId: String): AgentTaskEvidence {
        require(Files.exists(threadPath(taskId)) || synchronized(lock) { records.values.any { it.threadId == taskId } }) { "Task not found in this workspace" }
        return evidence.get(taskId)
    }

    fun captureCandidate(taskId: String): AgentCandidate {
        taskEvidence(taskId)
        val context = list(50).firstOrNull { it.threadId == taskId }?.context
        val subjects = context?.entries.orEmpty().filter { it.kind == "graph-subject" }
            .map { AgentGraphSubject(it.ref, context?.revision) }
        return evidence.capture(taskId, subjects)
    }

    fun addFinding(taskId: String, finding: AgentFinding): AgentTaskEvidence {
        taskEvidence(taskId)
        val run = get(finding.producerRunId)
        require(run.threadId == taskId && (finding.participant in run.participants || finding.participant == run.provider.name.lowercase())) {
            "Finding producer does not belong to this task"
        }
        return evidence.finding(taskId, finding)
    }

    init {
        require(root.isDirectory && maxActive in 1..8)
        privateDirectory(directory)
        privateDirectory(directory.resolve("runs"))
        privateDirectory(directory.resolve("threads"))
        privateDirectory(directory.resolve("requests"))
        privateDirectory(directory.resolve("checkpoints"))
        if (Files.exists(index)) recent = ConductorJson.decodeFromString(ListSerializer(String.serializer()), Files.readString(index))
        require(recent.size <= 200) { "Invalid recent-run index" }
        // Admission can crash after saving a run but before updating the recent index.
        val diskIds = Files.list(directory.resolve("runs")).use { paths ->
            paths.map { it.fileName.toString().removeSuffix(".json") }.filter { it.matches(Regex("[a-f0-9]{64}")) }.toList()
        }
        recent = (recent + diskIds).distinct().mapNotNull(::load).sortedByDescending { it.startedAt }.take(200).map { it.id }
        atomicWrite(index, ConductorJson.encodeToString(ListSerializer(String.serializer()), recent))
        recent.forEach { id -> load(id)?.let { saved ->
            records[id] = if (saved.status == AgentRunStatus.Running) saved.copy(
                status = AgentRunStatus.Interrupted, revision = saved.revision + 1,
                recovery = if (background && saved.pending.isEmpty() && saved.participants.values.none { it.pending.isNotEmpty() }) AgentRecovery.Pending else AgentRecovery.NeedsReview,
                recoveryReason = "Previous owner exited; inspecting saved execution state",
                pending = emptyList(),
                participants = saved.participants.mapValues { (_, participant) -> if (participant.status == AgentRunStatus.Running)
                    participant.copy(status = AgentRunStatus.Interrupted, activeTurn = null, pending = emptyList()) else participant },
                failure = "Workspace owner stopped without a terminal result. Inspect changes before continuing.", updatedAt = System.currentTimeMillis(),
            ).also(::persist) else saved
        } }
        queued = queued.map { item -> if (item.state == "waiting") {
            if (load(item.id) != null) item.copy(state = "dispatched", runId = item.id)
            else if (background) item else item.copy(state = "blocked", error = "Owner restarted; queued work requires resubmission")
        } else item }
        persistQueue()
    }

    fun recoverInBackground() { if (background) scope.launch { synchronized(lock) { recoverPending(); dispatchQueued() } } }

    fun queue(afterRunId: String, request: AgentSubmission): AgentQueuedTurn = synchronized(lock) {
        val id = digest(request.requestId)
        queued.firstOrNull { it.id == id }?.let {
            require(it.submission == request) { "Queued request id already has different content" }
            return@synchronized it
        }
        val after = get(afterRunId)
        require(after.status == AgentRunStatus.Running && request.threadId == after.threadId)
        require(request.provider in runtimes && request.prompt.isNotBlank() && request.prompt.length <= 100000)
        require(queued.count { it.state == "waiting" } < 8) { "Queue is full" }
        val predecessor = queued.lastOrNull { it.submission.threadId == request.threadId && it.state == "waiting" }?.id ?: afterRunId
        AgentQueuedTurn(id, predecessor, request).also { queued = queued.takeLast(99) + it; persistQueue() }
    }
    fun queueItems(taskId: String): List<AgentQueuedTurn> = synchronized(lock) { queued.filter { it.submission.threadId == taskId } }
    fun cancelQueued(id: String): List<AgentQueuedTurn> = synchronized(lock) {
        require(queued.any { it.id == id && it.state == "waiting" }) { "Queue entry is no longer waiting" }
        queued = queued.map { if (it.id == id) it.copy(state = "cancelled") else it }
        persistQueue(); dispatchQueued(); queued
    }
    private fun persistQueue() = atomicWrite(queueFile, ConductorJson.encodeToString(ListSerializer(AgentQueuedTurn.serializer()), queued))
    private fun dispatchQueued() = synchronized(lock) {
        if (closed || draining || active.isNotEmpty()) return@synchronized
        for (item in queued.filter { it.state == "waiting" }) {
            val parent = records[item.afterRunId] ?: load(item.afterRunId)
            if (parent == null) {
                if (queued.any { it.id == item.afterRunId && it.state in listOf("cancelled", "blocked") }) {
                    queued = queued.map { if (it.id == item.id) it.copy(state = "blocked", error = "Previous queued turn was not dispatched") else it }
                    persistQueue()
                }
                continue
            }
            if (parent.status == AgentRunStatus.Running || parent.recovery != AgentRecovery.None) continue
            val updated = if (parent.status != AgentRunStatus.Completed) item.copy(state = "blocked", error = "Previous turn did not complete")
            else runCatching { submit(item.submission) }.fold(
                { item.copy(state = "dispatched", runId = it.id) },
                { item.copy(state = "blocked", error = it.message) })
            queued = queued.map { if (it.id == item.id) updated else it }
            persistQueue()
            if (active.isNotEmpty()) break
        }
    }

    private val capabilities by lazy { workspaceCapabilities(runtimes, discover) }

    fun info() = AgentWorkspaceInfo(root.canonicalPath, runtimes.keys.toList(), maxActive,
        collaborations = if (maxActive >= 2 && runtimes.size >= 2) AgentCollaboration.entries else listOf(AgentCollaboration.Single),
        capabilities = capabilities,
        sessionMode = if (runtimes.values.any { it is InteractiveAgentRuntime }) "Interactive Single; automatic collaboration uses batch when available" else "Batch turns",
        transports = buildList {
            add(AgentTransport.Automatic)
            if (runtimes.values.any { it is InteractiveAgentRuntime }) add(AgentTransport.Interactive)
            if (batchRuntimes.isNotEmpty() || runtimes.values.any { it !is InteractiveAgentRuntime }) add(AgentTransport.Batch)
        }, service = AgentServiceInfo(ProcessHandle.current().pid(), serviceStartedAt, background, if (background) System.getenv("REAKTOR_AGENT_SUPERVISOR") else null))

    private fun requireSupportedEffort(provider: RuntimeKind, effort: NativeEffort?) {
        val resolution = capabilities.firstOrNull { it.runtime == provider }?.effort.resolve(effort)
        if (resolution is EffortResolution.Unsupported) throw UnsupportedEffortException(resolution)
    }

    fun attach(runId: String): AgentAttachment = synchronized(lock) {
        val record = records[runId] ?: load(runId) ?: throw IllegalArgumentException("Run not found in this workspace")
        val live = sessions[runId].orEmpty()
        AgentAttachment(
            run = record,
            live = record.status == AgentRunStatus.Running && active.containsKey(runId),
            // Only a participant whose session is still held can be answered or steered.
            answerable = live.filterValues { it.activeTurn != null }.keys.toList().sorted(),
            activeTurns = live.mapNotNull { (id, session) -> session.activeTurn?.let { id to it } }.toMap(),
            nativeAgents = live.mapValues { it.value.nativeAgents() },
            pending = (record.pending + record.participants.values.flatMap { it.pending }).distinctBy { it.id },
        )
    }

    suspend fun resolve(runId: String, agent: String, requestId: String, decision: AgentDecision): CommandOutcome =
        withSession(runId, agent) { it.resolve(requestId, decision) }
            .also { if (it is CommandOutcome.Accepted) update(runId, true) { record -> record.withRequestResolved(agent, requestId) } }

    suspend fun steer(runId: String, agent: String, text: String, expectedTurn: String?): CommandOutcome {
        require(text.isNotBlank() && text.length <= 100000)
        return withSession(runId, agent) { it.steer(text, expectedTurn) }
    }

    suspend fun interrupt(runId: String, agent: String): CommandOutcome =
        withSession(runId, agent) { it.interrupt() }

    suspend fun controlNative(runId: String, agent: String, child: String, expectedTurn: String, text: String?): CommandOutcome {
        require(text == null || text.length in 1..100000)
        return withSession(runId, agent) { it.controlNative(child, expectedTurn, text) }
    }

    fun controls(runId: String): Map<String, Boolean> = sessions[runId]?.mapValues { true }.orEmpty()

    private suspend fun withSession(runId: String, agent: String, body: suspend (AgentSession) -> CommandOutcome): CommandOutcome {
        val session = sessions[runId]?.get(agent)
            ?: return CommandOutcome.Unsupported(
                if (records[runId] == null && load(runId) == null) "unknown run" else "interactive session")
        return body(session)
    }

    fun submit(request: AgentSubmission): AgentRunRecord = admit(request)

    private fun admit(request: AgentSubmission, seed: ProtocolCheckpoint? = null, forkedFrom: String? = null): AgentRunRecord = synchronized(lock) {
        check(!closed) { "Workspace is closed" }
        check(!draining) { "Workspace is draining for a service upgrade" }
        require(request.requestId.isNotBlank() && request.requestId.length <= 200)
        require(request.prompt.isNotBlank() && request.prompt.length <= 100000)
        require(request.model == null || request.model.length in 1..256)
        require(request.provider in runtimes) { "Provider is not configured" }
        request.workflow?.let { workflow ->
            workflow.validate()
            require(request.collaboration == AgentCollaboration.Single && request.partner == null) { "A workflow supplies its own roster" }
            require(workflow.participants.all { it.runtime in runtimes }) { "Workflow provider unavailable" }
            workflow.participants.forEach { requireSupportedEffort(it.runtime, it.effort) }
            require(workflow.stages.none { it.action == WorkflowAction.Check } || workflowCheck != null) { "This host has no kernel check executor" }
            require(request.isolation != AgentIsolation.Worktree || workflow.stages.none { it.action == WorkflowAction.Check }) { "Kernel checks currently target the source workspace; apply an isolated patch before checking it" }
        }
        require(request.collaboration in info().collaborations) { "Collaboration is not available in this workspace" }
        if (request.collaboration == AgentCollaboration.Single) {
            require(request.partner == null) { "Single-agent turns cannot have a partner" }
        } else {
            val partner = requireNotNull(request.partner) { "Choose a second provider" }
            require(partner.provider in runtimes && partner.provider != request.provider) { "Choose two different configured providers" }
            require(partner.model == null || partner.model.length in 1..256)
            require(!request.allowWrites || request.isolation == AgentIsolation.Worktree) { "Parallel editing requires owned worktrees" }
            requireSupportedEffort(partner.provider, partner.effort)
        }
        requireSupportedEffort(request.provider, request.effort)
        require(request.transport != AgentTransport.Batch || batchRuntimes.isNotEmpty() || runtimes[request.provider] !is InteractiveAgentRuntime) { "Batch transport is not configured" }
        require(request.transport != AgentTransport.Interactive || runtimes[request.provider] is InteractiveAgentRuntime) { "Interactive transport is not configured" }
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
        require(active.keys.none { records[it]?.allowWrites == true && records[it]?.isolation == AgentIsolation.Shared } &&
            (!request.allowWrites || request.isolation == AgentIsolation.Worktree || active.isEmpty())) {
            "Workspace edits require exclusive agent execution"
        }
        val threadId = request.threadId ?: UUID.randomUUID().toString()
        val path = threadPath(threadId)
        require(request.threadId == null || Files.exists(path)) { "Conversation not found in this workspace" }
        require(active.keys.none { records[it]?.threadId == threadId }) { "Conversation already has an active turn" }
        require(records.values.none { it.threadId == threadId && it.recovery != AgentRecovery.None }) { "Resume or stop this task's interrupted turn before starting another" }
        val now = System.currentTimeMillis()
        val record = AgentRunRecord(id, threadId, request.provider, fingerprint, request.prompt.take(200),
            request.model, request.allowWrites, startedAt = now, updatedAt = now,
            collaboration = request.collaboration, partner = request.partner,
            workflow = request.workflow, workflowProgress = seed?.workflow, forkedFrom = forkedFrom, isolation = request.isolation,
            context = request.context,
            transport = if (useBatch(request)) AgentTransport.Batch else if (runtimes[request.provider] is InteractiveAgentRuntime) AgentTransport.Interactive else AgentTransport.Batch,
            effort = request.effort?.let { EffortRecord(requested = it, resolved = it) } ?: EffortRecord.none)
        atomicWrite(directory.resolve("requests/$id.json"), ConductorJson.encodeToString(AgentSubmission.serializer(), request))
        seed?.let { checkpoints.save(id, it.copy(thread = it.thread.copy(id = ThreadId(threadId)))) }
        persist(record)
        records[id] = record
        recent = (listOf(id) + recent).distinct().take(200)
        atomicWrite(index, ConductorJson.encodeToString(ListSerializer(String.serializer()), recent))
        startRun(record, request)
        record
    }

    private fun startRun(record: AgentRunRecord, request: AgentSubmission) {
        val id = record.id
        val threadId = record.threadId
        val path = threadPath(threadId)
        val now = System.currentTimeMillis()
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(record, request) }
        active[id] = job
        records.keys.retainAll(recent.toSet() + active.keys)
        job.invokeOnCompletion {
            synchronized(lock) {
                if (records[id]?.status == AgentRunStatus.Running && !suspending) {
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
    }

    private fun checkpointPath(id: String) = directory.resolve("checkpoints/${runPath(id).fileName}")
    private fun checkpoint(id: String): ProtocolCheckpoint? = checkpointPath(id).takeIf(Files::exists)?.let {
        ConductorJson.decodeFromString(ProtocolCheckpoint.serializer(), Files.readString(it))
    }

    private fun recoverPending() {
        if (closed || draining || active.isNotEmpty()) return
        records.values.filter { it.recovery == AgentRecovery.Pending }.sortedBy { it.startedAt }.forEach { saved ->
            val reason = runCatching { when {
                saved.attempt >= 3 -> "Recovery paused after three attempts; inspect the repeated failure"
                saved.pending.isNotEmpty() || saved.participants.values.any { it.pending.isNotEmpty() } -> "A provider was waiting for a decision when its owner exited"
                activity.unresolved(saved.id, saved.attempt).isNotEmpty() -> "A tool was in flight when the owner exited; inspect its effects before resuming"
                checkpoint(saved.id)?.inFlight?.isNotEmpty() == true -> "The native stage ended without a durable result. Inspect its session and effects before retrying that stage"
                checkpoint(saved.id)?.workflow?.stages?.values?.any { it.status == WorkflowStageStatus.Running || it.status == WorkflowStageStatus.Waiting } == true -> "A workflow stage needs review before continuing; inspect its saved results and any kernel receipt"
                else -> null
            } }.getOrElse { "Saved execution state could not be read: ${it.message.orEmpty().take(300)}" }
            if (reason != null) update(saved.id, true) { it.copy(recovery = AgentRecovery.NeedsReview, recoveryReason = reason) }
            else {
                runCatching { resume(saved.id) }.onFailure { failure ->
                    update(saved.id, true) { it.copy(recovery = AgentRecovery.NeedsReview, recoveryReason = "Recovery could not start: ${failure.message.orEmpty().take(300)}") }
                }
                if (active.isNotEmpty()) return
            }
        }
    }

    private fun settleTerminal(id: String) {
        val finishing = synchronized(lock) { active[id]?.takeIf { records[id]?.status != AgentRunStatus.Running } }
        if (finishing != null) runBlocking { withTimeout(10000) { finishing.join() } }
    }

    fun resume(id: String, expectedRevision: Long? = null): AgentRunRecord {
        settleTerminal(id)
        return synchronized(lock) {
        check(!closed && !draining) { "Workspace is closed or draining" }
        val saved = get(id)
        if (expectedRevision != null) require(saved.revision == expectedRevision) { "Run changed since review; inspect the current checkpoint before continuing" }
        if (saved.status == AgentRunStatus.Running) return@synchronized saved
        require(saved.recovery != AgentRecovery.None) { "This run is not awaiting recovery" }
        require(active.isEmpty()) { "Wait for active workspace work before recovering this run" }
        val request = ConductorJson.decodeFromString(AgentSubmission.serializer(), Files.readString(directory.resolve("requests/$id.json")))
        require(digest(ConductorJson.encodeToString(AgentSubmission.serializer(), request)) == saved.requestFingerprint)
        val checkpoint = checkpoint(id)
        val currentRoot = saved.workingDirectory?.let(::File) ?: root
        checkpoint?.sourceRevision?.takeIf { request.workflow != null }?.let { expected ->
            require(SourceCandidates(currentRoot, evidence.artifacts).capture().sourceDigest == expected) {
                "Source changed since the saved checkpoint. Fork from the beginning to recompute evidence, or restore the reviewed source before resuming."
            }
        }
        val gates = checkpoint?.workflow?.stages.orEmpty().filterValues { it.status == WorkflowStageStatus.Waiting }.keys
        require(gates.isEmpty() || expectedRevision != null) { "Review the current run and provide expectedRevision to continue a workflow gate" }
        if (checkpoint != null && gates.isNotEmpty()) checkpoints.save(id, checkpoint.copy(workflow = checkpoint.workflow.copy(approvedGates = checkpoint.workflow.approvedGates + gates)))
        val next = saved.copy(status = AgentRunStatus.Running, recovery = AgentRecovery.Resuming, attempt = saved.attempt + 1,
            recoveryReason = "Continuing from saved stage results; completed stages are reused", failure = null,
            pending = emptyList(), participants = saved.participants.mapValues { it.value.copy(pending = emptyList(), activeTurn = null) },
            revision = saved.revision + 1, updatedAt = System.currentTimeMillis())
        persist(next); records[id] = next
        activity.append(id, "workspace", next.attempt, AgentActivityItem("recovery-${next.attempt}", ActivityKind.Recovery, "Resuming saved work", output = next.recoveryReason))
        startRun(next, request)
        next
    } }

    fun fork(id: String, checkpointId: String, requestId: String, fromStage: String? = null): AgentRunRecord {
        settleTerminal(id)
        return synchronized(lock) {
        val origin = "$id/$checkpointId:${fromStage ?: "all"}"
        (records[digest(requestId)] ?: load(digest(requestId)))?.let {
            require(it.forkedFrom == origin) { "Fork request id already has different content" }
            return@synchronized recover(it)
        }
        val original = get(id)
        require(original.status != AgentRunStatus.Running) { "Pause or finish the source run before forking" }
        val saved = checkpoints.read(id, checkpointId)
        val request = ConductorJson.decodeFromString(AgentSubmission.serializer(), Files.readString(directory.resolve("requests/$id.json")))
        require(request.workflow != null) { "Checkpoint forks need a workflow definition" }
        val definition = request.workflow
        require(fromStage == null || definition.stages.any { it.id == fromStage })
        val invalidated = if (fromStage == null) definition.stages.map { it.id }.toMutableSet() else mutableSetOf(fromStage)
        repeat(definition.stages.size) { definition.edges.filter { it.from in invalidated }.forEach { invalidated += it.to } }
        val retained = saved.workflow.stages.filter { it.key !in invalidated && it.value.status in listOf(WorkflowStageStatus.Completed, WorkflowStageStatus.Skipped) }
        require(saved.inFlight.isEmpty()) { "Choose a checkpoint without an in-flight native action" }
        val revision = SourceCandidates(root, evidence.artifacts).capture().sourceDigest
        if (retained.isNotEmpty()) require(request.isolation == AgentIsolation.Shared && revision == saved.sourceRevision) { "Source moved; fork from the beginning" }
        val seed = saved.copy(completed = saved.completed.filterKeys { key -> retained.keys.any { key == "graph:$it" } }, inFlight = emptySet(),
            thread = saved.thread.copy(providerSessions = emptyMap()), workflow = WorkflowProgress(retained), sourceRevision = revision)
        admit(request.copy(requestId = requestId, threadId = null), seed, origin)
    } }

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
        val job = synchronized(lock) {
            val saved = get(id)
            if (saved.status != AgentRunStatus.Running && saved.recovery == AgentRecovery.None) return saved
            update(id, true) { it.copy(status = AgentRunStatus.Interrupted, recovery = AgentRecovery.None, recoveryReason = null, failure = "Stopped by the user",
                pending = emptyList(), participants = it.participants.mapValues { (_, participant) -> participant.copy(
                    status = if (participant.status == AgentRunStatus.Running) AgentRunStatus.Interrupted else participant.status,
                    activeTurn = null, pending = emptyList()) }) }
            sessions.remove(id)
            active[id]
        }
        job?.cancelAndJoin()
        dispatchQueued()
        return get(id)
    }

    fun transcript(threadId: String): AgentTranscript {
        val path = threadPath(threadId)
        require(Files.exists(path)) { "Conversation not found in this workspace" }
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
                val isolated = if (request.isolation == AgentIsolation.Worktree) worktrees.prepare(initial.id, "workspace") else null
                val workingRoot = isolated?.roots?.first { it.source == root.canonicalPath }?.path?.let(::File) ?: root
                update(initial.id, true) { it.copy(workingDirectory = workingRoot.canonicalPath) }
                val supplied = request.context
                val subjects = supplied?.entries.orEmpty().filter { it.kind == "graph-subject" }
                    .map { AgentGraphSubject(it.ref, supplied?.revision) }
                val candidate = if (isolated == null) evidence.capture(initial.threadId, subjects) else SourceCandidates(workingRoot, evidence.artifacts).capture(subjects)
                val prior = evidence.get(initial.threadId)
                val context = ContextPacket(workspaceId = root.canonicalPath, principalId = supplied?.principalId ?: "local-operator",
                    source = "Reaktor agent workspace", observedAt = System.currentTimeMillis().toString(),
                    revision = supplied?.revision, freshness = "captured-before-turn", partial = !candidate.complete || supplied?.partial == true,
                    entries = listOf(ContextEntry("candidate:${candidate.id}", "candidate", "Source candidate",
                        "Task: ${initial.threadId}\nCandidate: ${candidate.id}\nBase commit: ${candidate.baseCommit}\nChanged paths: ${candidate.changedFiles.take(50).joinToString()}\n" +
                            (if (isolated == null) "Use agent_candidate_capture after editing, agent_task_graph for findings/checks and agent_artifact for the exact diff. Report findings through agent_finding with producerRunId=${initial.id}."
                             else "This candidate belongs to the isolated checkout. Use agent_worktrees(runId=${initial.id}) and agent_worktree_review for its task-only diff. Generic candidate/check tools refer to the original workspace; do not claim they verify this worktree. Applying the exact reviewed patch is a separate operation."),
                        "Binds this attempt to local source evidence")) + supplied?.entries.orEmpty().take(20) +
                        prior.findings.filter { it.resolvedByCandidate == null }.take(10).map { ContextEntry(it.id, "finding", it.title,
                            it.detail.take(600), "Unresolved finding from ${it.participant} on ${it.candidateId}") },
                    omittedEntries = (supplied?.omittedEntries ?: 0) + maxOf(0, (supplied?.entries?.size ?: 0) - 20),
                    notices = candidate.notices + supplied?.notices.orEmpty()).bounded()
                update(initial.id, true) { it.copy(context = context, candidateId = candidate.id) }
                val agentId = AgentId(request.provider.name.lowercase())
                fun spec(provider: RuntimeKind, model: String?, effort: NativeEffort?) = AgentSpec(
                    AgentId(provider.name.lowercase()), provider.name, provider,
                    "Follow the workspace's repository instructions.", model = model, effort = effort,
                    tools = ToolPolicy(allowWrites = request.allowWrites, mcpConfig = harnessMcpConfig))
                val specs = request.workflow?.participants?.map { it.copy(tools = ToolPolicy(allowWrites = request.allowWrites && it.tools.allowWrites, mcpConfig = harnessMcpConfig)) }
                    ?: (listOf(spec(request.provider, request.model, request.effort)) +
                    listOfNotNull(request.partner?.let { spec(it.provider, it.model, it.effort) }))
                val restored = checkpoint(initial.id)
                val saved = restored?.thread ?: store.load() ?: ThreadDocument(ThreadId(initial.threadId), initial.title)
                val ids = specs.map { it.id }
                val invalidated = specs.filter { saved.agent(it.id)?.let { prior -> prior != it } == true }.map { it.id.value }
                val document = saved.copy(participants = saved.participants.filterNot { it.id in ids } + specs,
                    providerSessions = saved.providerSessions - invalidated.toSet())
                val protocol = request.workflow?.let { Protocol.Graph(it.copy(participants = specs)) } ?: when (request.collaboration) {
                    AgentCollaboration.Single -> Protocol.Ask(agentId)
                    AgentCollaboration.Compare -> Protocol.All(ids, blind = true)
                    AgentCollaboration.Council -> Protocol.Council(ids, synthesizer = agentId)
                }
                val directories = if (request.isolation == AgentIsolation.Worktree && request.collaboration != AgentCollaboration.Single) specs.associate { agent ->
                    agent.id to worktrees.prepare(initial.id, agent.id.value).roots.first { it.source == root.canonicalPath }.path
                } else specs.associate { it.id to workingRoot.canonicalPath }
                var lastWorkflow = restored?.workflow
                var sourceRevision = restored?.sourceRevision ?: candidate.sourceDigest
                val inheritedEvents = initial.forkedFrom?.let { origin ->
                    checkpoints.read(origin.substringBefore('/'), origin.substringAfter('/').substringBefore(':')).thread.events.map { it.id }.toSet()
                }.orEmpty()
                try {
                    val result = Conductor(if (useBatch(request)) batchRuntimes else runtimes, clock = System::currentTimeMillis, idFactory = { EventId(UUID.randomUUID().toString()) }).run(
                        document, request.prompt, protocol, workingRoot.canonicalPath,
                        context = context, resumeProviderSession = request.collaboration == AgentCollaboration.Single && request.workflow == null,
                        resumeFrom = restored,
                        workingDirectoryFor = { directories.getValue(it) },
                        runCheck = { stage -> requireNotNull(workflowCheck).invoke(initial.threadId, "${initial.id}:${stage.id}", stage) },
                        onProgress = { progress ->
                            if (request.workflow != null && progress.workflow != lastWorkflow && progress.workflow.stages.values.none { it.status == WorkflowStageStatus.Running })
                                sourceRevision = SourceCandidates(workingRoot, evidence.artifacts).capture().sourceDigest
                            lastWorkflow = progress.workflow
                            checkpoints.save(initial.id, progress.copy(sourceRevision = sourceRevision))
                            update(initial.id, true) { it.copy(workflowProgress = progress.workflow.takeIf { request.workflow != null },
                                turnUsage = progress.thread.copy(events = progress.completed.values.filter { it.id !in inheritedEvents }).usageSummary()) }
                        },
                        onSession = { agentId, session ->
                            sessions.computeIfAbsent(initial.id) { java.util.concurrent.ConcurrentHashMap() }[agentId.value] = session
                        },
                        onSessionClosed = { agentId, session ->
                            sessions[initial.id]?.remove(agentId.value, session)
                            update(initial.id, true) { record -> record.copy(
                                participants = record.participants.mapValues { (id, participant) ->
                                    if (id == agentId.value) participant.copy(activeTurn = null, pending = emptyList()) else participant
                                }, pending = if (request.collaboration == AgentCollaboration.Single) emptyList() else record.pending) }
                        },
                        onCheckpoint = { checkpoint ->
                            store.checkpoint(if (request.collaboration == AgentCollaboration.Single) checkpoint else
                                checkpoint.copy(providerSessions = checkpoint.providerSessions - ids.map { it.value }.toSet()))
                        },
                        onEvent = { event ->
                            recordActivity(initial.id, event)
                            update(initial.id, persistNow = event !is AgentEvent.Delta && event !is AgentEvent.Reasoning) { current ->
                                if (current.status != AgentRunStatus.Running) current else projectAgentEvent(current, event, specs.first { it.id == event.agent }.runtime)
                        } },
                    )
                    val answer = result.answer
                    val failures = result.added.filter { it.kind == EventKind.Failure }
                    val workflowFailed = request.workflow?.failed(lastWorkflow ?: WorkflowProgress())
                    update(initial.id, true) { if (it.status == AgentRunStatus.Interrupted && it.recovery == AgentRecovery.None) it else it.copy(status = if (it.participants.values.any { p -> p.status == AgentRunStatus.Interrupted }) AgentRunStatus.Interrupted else if (workflowFailed ?: failures.isNotEmpty()) AgentRunStatus.Failed else AgentRunStatus.Completed,
                        pending = emptyList(), recovery = AgentRecovery.None, recoveryReason = null,
                        output = answer?.text.orEmpty().takeLast(12000), outputTruncated = answer?.text.orEmpty().length > 12000,
                        usage = answer?.usage?.takeIf { request.collaboration == AgentCollaboration.Single },
                        reportedUsage = answer?.reportedUsage?.takeIf { request.collaboration == AgentCollaboration.Single },
                        failure = failures.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }?.take(2000)) }
                } catch (failure: Exception) {
                    if (failure is WorkflowPaused) throw failure
                    store.load()?.let { latest -> store.checkpoint(latest.append(ThreadEvent(EventId(UUID.randomUUID().toString()),
                        Author.Orchestrator("workspace"), EventKind.Failure,
                        if (failure is CancellationException) "Turn interrupted. Inspect workspace changes before continuing." else failure.message.orEmpty().take(2000),
                        parents = latest.heads().map { it.id }, createdAtEpochMillis = System.currentTimeMillis()))) }
                    throw failure
                }
            }
        } catch (failure: Exception) {
            if (failure is WorkflowPaused) {
                update(initial.id, true) { it.copy(status = AgentRunStatus.Interrupted, recovery = AgentRecovery.NeedsReview,
                    recoveryReason = "${failure.message}. Review the stage results and source, then continue this gate.", pending = emptyList()) }
                activity.append(initial.id, "workspace", initial.attempt, AgentActivityItem("gate:${failure.stageId}", ActivityKind.Control,
                    failure.message.orEmpty(), ActivityStatus.Waiting))
                return
            }
            update(initial.id, true) { if (it.status == AgentRunStatus.Interrupted && it.recovery == AgentRecovery.None) it else it.copy(status = if (suspending) AgentRunStatus.Running else if (failure is CancellationException) AgentRunStatus.Interrupted else AgentRunStatus.Failed,
                recovery = if (suspending) AgentRecovery.Pending else AgentRecovery.None,
                pending = emptyList(),
                participants = it.participants.mapValues { (_, participant) -> if (participant.status == AgentRunStatus.Running)
                    participant.copy(status = if (failure is CancellationException) AgentRunStatus.Interrupted else AgentRunStatus.Failed, activeTurn = null, pending = emptyList()) else participant },
                failure = if (failure is CancellationException) "Interrupted by the workspace owner" else failure.message.orEmpty().take(2000)) }
        } finally {
            sessions.remove(initial.id)
            activityProjection.finished(initial.id)
            synchronized(lock) { active.remove(initial.id); changes.value++ }
            synchronized(lock) { recoverPending() }
            dispatchQueued()
        }
    }

    private fun useBatch(request: AgentSubmission): Boolean = batchRuntimes.isNotEmpty() &&
        (request.transport == AgentTransport.Batch || (request.transport == AgentTransport.Automatic && request.collaboration != AgentCollaboration.Single))

    private fun ContextPacket.bounded(): ContextPacket {
        var packet = copy(notices = notices.take(12).map { it.take(240) },
            partial = partial || notices.size > 12 || notices.any { it.length > 240 })
        while (ConductorJson.encodeToString(ContextPacket.serializer(), packet).length > 22000 && packet.entries.isNotEmpty()) {
            packet = packet.copy(entries = packet.entries.dropLast(1), omittedEntries = packet.omittedEntries + 1, partial = true)
        }
        return packet
    }

    private fun update(id: String, persistNow: Boolean, change: (AgentRunRecord) -> AgentRunRecord) = synchronized(lock) {
        val current = records.getValue(id)
        val next = change(current).copy(revision = current.revision + 1, updatedAt = System.currentTimeMillis())
        if (persistNow || next.updatedAt - (lastPersisted[id] ?: 0) >= 1000) { persist(next); lastPersisted[id] = next.updatedAt }
        records[id] = next
        changes.value++
    }

    private fun recover(record: AgentRunRecord): AgentRunRecord =
        if (record.status == AgentRunStatus.Running && record.id !in active && record.recovery != AgentRecovery.Pending) record.copy(status = AgentRunStatus.Interrupted,
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

    fun suspendAndClose() { synchronized(lock) { suspending = true }; close() }

    private fun recordActivity(id: String, event: AgentEvent) {
        val item = activityProjection.item(id, event) ?: return
        activity.append(id, event.agent.value, synchronized(lock) { records.getValue(id).attempt }, item)
    }
}
