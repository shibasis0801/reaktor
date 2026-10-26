package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.CliCapabilities
import dev.shibasis.reaktor.conductor.store.FileThreadStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** One admitted execution owner. Provider I/O never runs on the request/UI thread. */
class AgentWorkspace(
    private val root: File,
    private val directory: Path,
    runtimes: Map<RuntimeKind, AgentRuntime>,
    private val maxActive: Int = if (RuntimeKind.Gemini in runtimes) 3 else 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val discover: (RuntimeKind) -> ProviderCapability = CliCapabilities::probe,
    batchRuntimes: Map<RuntimeKind, AgentRuntime> = emptyMap(),
    private val harnessMcpConfig: String? = null,
    private val background: Boolean = false,
    private val workflowCheck: (suspend (String, String, WorkflowStage) -> WorkflowCheckResult)? = null,
    private val externalBusy: () -> Boolean = { false },
    /**
     * Which pool, if any, may plan for the composite seat without a person.
     *
     * Null by default, and the default is the whole point of this seat. It is the third option for
     * when Claude and Codex are exhausted, and it earns that by spending neither: ChatGPT chat
     * quota for the thinking, the Google allowance for the work. Planning it with
     * [RuntimeKind.Codex] would spend the pool whose exhaustion is the reason anyone reached for
     * this seat, so the fallback would stop working exactly when it is needed.
     *
     * Set it deliberately when a pool is known to be healthy and the unattended loop is worth more
     * than the quota — [HybridPlanner] never gets write grants either way.
     */
    private val plannerRuntime: RuntimeKind? = null,
    private val kernelRead: (suspend (String, JsonObject) -> JsonElement)? = null,
    private val scopeModules: (List<String>) -> List<String> = { emptyList() },
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
    val hybrid = HybridHandoffs(directory.resolve("handoffs"), evidence.artifacts) { runId, candidate ->
        evidence.recordCandidate(get(runId).threadId, candidate)
    }
    val entitlements = AgentEntitlements(directory.resolve("entitlements"))
    val profiles = AgentProfileStore(directory.resolve("profiles"))
    private val seatResolver = TaskSeatResolver(profiles::read) { provider -> capabilities.firstOrNull { it.runtime == provider } }
    fun resolveSeat(seat: TaskSeat): ResolvedTaskSeat = seatResolver.resolve(seat)
    val board = AgentBoardStore(directory.resolve("board"), resolveSeat = ::resolveSeat, scopeModules = scopeModules, summarize = ::summarize)
    internal fun summarize(task: BoardTask): TaskWorkSummary? {
        val plan = task.graph ?: return null
        if (plan.kind !in setOf(TaskKind.Agent, TaskKind.Merge) || plan.attempt == 0) return null
        val facts = evidence.get(task.id)
        val candidate = plan.outcome?.takeIf { it.attempt == plan.attempt }?.candidateId?.let { id -> facts.candidates.firstOrNull { it.id == id } }
        val diff = candidate?.diff?.let { ref -> runCatching { evidence.artifact(task.id, ref.id) }.getOrNull() }
        return TaskSummaries.of(task, facts, plan.runId?.let(activity::plan), diff)
    }
    @Volatile internal var graphs: TaskGraphRuntime? = null
    internal suspend fun readKernel(name: String, arguments: JsonObject): JsonElement =
        requireNotNull(kernelRead) { "This host has no kernel check reader" }.invoke(name, arguments)
    internal fun submission(id: String): AgentSubmission = synchronized(lock) {
        val saved = get(id)
        val file = directory.resolve("requests/$id.json")
        require(Files.size(file) <= 1_048_576)
        ConductorJson.decodeFromString(AgentSubmission.serializer(), Files.readString(file)).also { request ->
            require(digest(ConductorJson.encodeToString(AgentSubmission.serializer(), request)) == saved.requestFingerprint)
        }
    }
    internal val peer by lazy { AgentA2aWorkspace(this) }
    private fun requireEntitlements(request: AgentSubmission) = entitlements.requireAdmitted(request.workflow?.participants?.map { it.runtime }
        ?: (listOfNotNull(request.provider, request.partner?.provider) + if (request.councilHybrid) listOf(RuntimeKind.ChatGptGemini) else emptyList()))
    private val runtimes = withHybrid(runtimes)
    private val batchRuntimes = withHybrid(batchRuntimes)
    /**
     * Gives the composite seat its executor, and a planner only if one was asked for.
     *
     * The pool is read at the moment a turn opens rather than when the map is built, because pausing
     * a pool has to stop the next cycle, not the next process. A paused or absent planner leaves the
     * seat on the operator relay, which still works — ChatGPT chat has no programmatic surface, so a
     * person carrying the packet is the only thing that reaches that quota at all.
     */
    private fun withHybrid(values: Map<RuntimeKind, AgentRuntime>) = values[RuntimeKind.Gemini]?.let { gemini ->
        values + (RuntimeKind.ChatGptGemini to HybridRuntime(gemini, hybrid) {
            plannerRuntime?.let(values::get)
                ?.takeIf { runtime -> runtime.kind.entitlements().none { entitlements.get(it.entitlement).paused } }
                ?.let(::HybridPlanner)
        })
    } ?: values
    fun handoffs(runId: String): List<HybridHandoff> { get(runId); return hybrid.list(runId) }
    fun replyHandoff(runId: String, reply: HybridReply, via: HybridPlannerVia = HybridPlannerVia.Operator): HybridHandoff = synchronized(lock) {
        val run = get(runId)
        require(run.status != AgentRunStatus.Running && run.recovery != AgentRecovery.None) { "Wait for the council to pause before importing a handoff" }
        hybrid.reply(runId, reply, via = via)
    }

    /**
     * Runs a task's permitted acceptance checks now, outside any executor turn.
     *
     * The run has to be parked, for the same reason two builds should not share a daemon: the
     * executor is usually in the middle of one, and a second would contend for the very output
     * both are trying to measure.
     */
    fun checkHandoff(runId: String, handoffId: String, checks: List<HybridCheck>): java.util.concurrent.CompletableFuture<List<HybridCheckRun>> {
        synchronized(lock) {
            val run = get(runId)
            require(run.status != AgentRunStatus.Running) {
                "The executor is still working on this task. Await the cycle, then run the check against what it produced."
            }
        }
        // Started outside the lock and finished on its own thread: a check is a build, and the
        // workspace cannot stop serving everything else for the length of one.
        return hybrid.check(runId, handoffId, checks)
    }

    /** Pages an artifact this task's own handoffs recorded — the turn diffs, and nothing else. */
    fun handoffArtifact(runId: String, id: String, offset: Long = 0, limit: Int = 24000): AgentArtifactPage {
        get(runId)
        return hybrid.artifact(runId, id, offset, limit)
    }
    val checkpoints = AgentCheckpoints(directory.resolve("checkpoints"))
    val runbooks = AgentRunbooks(directory.resolve("runbooks"))
    val worktrees = AgentWorktrees(root, directory.resolve("worktrees"), evidence.artifacts)
    val localContext = AgentLocalContext(root, directory)
    val memory = AgentMemoryStore(root, directory.resolve("memory"), evidence.artifacts) { id ->
        if (Files.isRegularFile(directory.resolve("requests/$id.json"))) submission(id).task else null
    }
    fun remember(runId: String, eventId: String, scope: AgentMemoryScope = AgentMemoryScope.Workspace) = memory.remember(get(runId), requireNotNull(checkpoint(runId)), eventId, scope)
    private var draining = false

    fun drain(enabled: Boolean): Int = synchronized(lock) { draining = enabled; active.size + if (externalBusy()) 1 else 0 }
    fun acceptsWork(): Boolean = synchronized(lock) { !closed && !draining }
    fun <T> admitOperation(requireIdle: Boolean = false, action: () -> T): T = synchronized(lock) {
        check(acceptsWork()) { "Workspace is draining" }
        if (requireIdle) check(active.isEmpty() && !externalBusy()) { "Wait for active workspace work before applying" }
        action()
    }
    suspend fun applyWorktree(id: String, patch: String, revision: String): AgentWorktreeReview =
        withWorktreeFiles(id) { worktrees.apply(id, patch, revision) }

    internal suspend fun <T> withWorktreeFiles(id: String, action: () -> T): T {
        val files = worktrees.files(id)
        val radar = kernelRead?.invoke("agent.radar", buildJsonObject { putJsonArray("paths") { files.forEach { add(it.path) } } })?.jsonObject
        val busy = radar?.get("sessions")?.jsonArray.orEmpty().map { it.jsonObject }.filter {
            it["state"]?.jsonPrimitive?.content == "Working" && it["sharedFiles"]?.jsonArray.orEmpty().isNotEmpty()
        }
        require(busy.isEmpty()) { "Candidate files are busy: ${busy.joinToString { it["title"]?.jsonPrimitive?.content.orEmpty() }}" }
        return admitOperation {
            val paths = files.flatMap { listOf(it.path, it.relativeTo(root.canonicalFile).invariantSeparatorsPath) } +
                radar?.get("modules")?.jsonArray.orEmpty().flatMap { listOf(it.jsonPrimitive.content, ":" + it.jsonPrimitive.content.substringAfterLast('/')) }
            active.keys.forEach { run ->
                val request = submission(run)
                val scope = request.task?.let { board.get(it.task).scope }
                    ?: request.context?.entries?.filter { it.kind == "source" || it.kind == "file" }?.map { it.ref }.orEmpty()
                require(scope.isNotEmpty() && !TaskGraphRules.overlaps(scope, paths)) { "Running task $run touches candidate files or has no declared source scope" }
            }
            action()
        }
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
        require(runCatching { board.get(taskId).graph != null }.getOrDefault(false) ||
            Files.exists(threadPath(taskId)) || synchronized(lock) { records.values.any { it.threadId == taskId } }) { "Task not found in this workspace" }
        return evidence.get(taskId)
    }

    fun captureCandidate(taskId: String): AgentCandidate {
        taskEvidence(taskId)
        val plan = runCatching { board.get(taskId).graph }.getOrNull()
        val run = plan?.runId?.let(::get) ?: list(50).firstOrNull { record ->
            record.threadId == taskId || plan != null && submission(record.id).task?.let { it.task == taskId && it.attempt == plan.attempt } == true
        }
        val context = run?.context
        val subjects = context?.entries.orEmpty().filter { it.kind == "graph-subject" }
            .map { AgentGraphSubject(it.ref, context?.revision) }
        val isolated = plan?.worktreeId?.let(worktrees::read) ?: run?.id?.let(::worktreeFor)
        require((plan?.resolvedSeat?.seat ?: plan?.seat)?.access != TaskAccess.Write || isolated != null) { "The writing task has no prepared worktree yet" }
        return if (isolated == null) evidence.capture(taskId, subjects)
            else worktrees.capture(isolated.id, subjects).also { evidence.recordCandidate(taskId, it) }
    }

    fun addFinding(taskId: String, finding: AgentFinding): AgentTaskEvidence {
        taskEvidence(taskId)
        val run = get(finding.producerRunId)
        val request = submission(run.id)
        require(request.resolvedSeat?.lenses.isNullOrEmpty()) { "Panel findings are published only after independent verification; return them in the stage output" }
        require(finding.lenses.isEmpty() && finding.confirmation == null) { "Panel confirmation provenance is recorded by the coordinator" }
        val target = request.task
        val belongs = if (target == null) run.threadId == taskId else target.evidenceTask == taskId &&
            board.get(target.task).graph?.let { it.id == target.graph && it.attempt == target.attempt } == true &&
            (target.candidate == null || target.candidate == finding.candidateId)
        require(belongs && (finding.participant in run.participants || finding.participant == run.provider.name.lowercase())) {
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
        require((after.status == AgentRunStatus.Running || request.message != null && after.status == AgentRunStatus.Completed && after.recovery == AgentRecovery.None) && request.threadId == after.threadId)
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
            if (parent.status in setOf(AgentRunStatus.Running, AgentRunStatus.Limited) || parent.recovery != AgentRecovery.None) continue
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

    private fun requireSupportedEffort(provider: RuntimeKind, model: String?, effort: NativeEffort?) {
        resolveSeat(TaskSeat(agent = provider, model = model, effort = effort?.value))
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

    fun children(parentRunId: String): List<AgentRunRecord> = synchronized(lock) {
        get(parentRunId)
        Files.list(directory.resolve("runs")).use { paths -> paths
            .filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }
            .map { load(it.fileName.toString().removeSuffix(".json")) }
            .filter { it?.parentRunId == parentRunId }.toList().filterNotNull().sortedBy { it.startedAt }
        }
    }

    fun sourceRoots(runId: String, writing: Boolean = false): List<Path> = synchronized(lock) {
        check(!closed && acceptsWork()) { "Workspace is closed or draining" }
        val record = get(runId)
        require(record.status == AgentRunStatus.Running && runId in active) { "Source tools require an active run" }
        val request = submission(runId)
        validateTaskTarget(request, runId)
        if (writing) require(request.allowWrites && record.allowWrites) { "This run has read-only source access" }
        val tree = worktreeFor(runId)
        val source = tree?.roots?.single { it.source == root.canonicalPath }?.path?.let(::File) ?: root
        require(tree != null || record.isolation == AgentIsolation.Shared) { "Owned source worktree is missing" }
        val actual = source.toPath().toRealPath()
        require(record.workingDirectory?.let { File(it).canonicalFile.toPath() } == actual) { "Run source directory differs from its owned checkout" }
        listOf(actual) + tree?.roots.orEmpty().filter { it.source != root.canonicalPath }.map { File(it.path).toPath().toRealPath() }
    }

    internal fun worktreeFor(runId: String): AgentWorktree? {
        val request = submission(runId)
        val source = request.message?.run ?: runId
        val original = if (source == runId) request else submission(source)
        return original.task?.worktree?.let(worktrees::read)
            ?: worktrees.list(source).singleOrNull { it.participant == "workspace" }
    }

    private fun validateTaskTarget(request: AgentSubmission, runId: String? = null) {
        request.message?.let { validateMessageTarget(request, it) }
        val target = request.task ?: return
        val task = board.get(target.task)
        val plan = requireNotNull(task.graph)
        require(plan.id == target.graph && plan.attempt == target.attempt && plan.state == TaskState.Running) { "Task target names a stale attempt" }
        require(request.collaboration == AgentCollaboration.Single) { "Task targets require a pinned task seat" }
        val pinned = requireNotNull(plan.resolvedSeat)
        val panel = pinned.lenses.isNotEmpty()
        if (panel) require(request.context == null && request.threadId == null && !request.allowWrites) { "A review panel starts with fresh read-only context" }
        require(if (panel) plan.contract == WorkflowContract.Decision && target.candidate != null && request.workflow == TaskReviewPanel.definition(pinned, target.candidate) else request.workflow == null) { "Task workflow must match its pinned review panel" }
        require(request.resolvedSeat == pinned && request.allowWrites == (pinned.seat.access == TaskAccess.Write)) { "Task target does not match its pinned seat" }
        if (request.allowWrites) {
            require(target.evidenceTask == task.id && target.candidate == null)
            require(target.worktree == plan.worktreeId || target.worktree == null && runId != null &&
                worktreeFor(runId)?.id == plan.worktreeId) { "The writing checkout differs from its assigned run" }
            require(request.isolation == AgentIsolation.Worktree)
        } else if (target.candidate != null) {
            require(target.worktree != null)
            val members = board.members(target.graph).associateBy { it.id }
            val seen = mutableSetOf<String>()
            val pending = ArrayDeque(plan.dependsOn.map { it.task })
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (seen.add(next)) pending.addAll(members[next]?.graph?.dependsOn.orEmpty().map { it.task })
            }
            require(target.evidenceTask in seen) { "Review evidence is not an input of this task" }
            val input = requireNotNull(members[target.evidenceTask]?.graph)
            require(input.worktreeId == target.worktree && input.outcome?.candidateId == target.candidate) { "Review candidate is stale" }
            val candidate = evidence.get(target.evidenceTask).candidates.single { it.id == target.candidate }
            require(worktrees.read(target.worktree).roots.any { it.source == root.canonicalPath && File(it.path).canonicalPath == candidate.workspaceRoot })
        } else require(target.evidenceTask == task.id && target.worktree == null)
    }

    internal fun prepareTaskWorktree(runId: String, target: AgentTaskTarget): AgentWorktree = admitOperation { synchronized(board) {
        val task = board.get(target.task)
        val plan = requireNotNull(task.graph)
        require(plan.id == target.graph && plan.attempt == target.attempt && plan.state == TaskState.Running) { "The writing attempt changed before preparation" }
        val inputs = board.sourceInputs(task.id).map { input ->
            AgentWorktreeInput(input.id, requireNotNull(input.graph?.worktreeId), requireNotNull(input.graph?.outcome?.candidateId))
        }
        val graph = board.graph(plan.id)
        val base = graph.baseCommits.ifEmpty {
            board.freezeBase(plan.id, (target.worktree ?: inputs.firstOrNull()?.worktree)?.let { id ->
                worktrees.read(id).roots.associate { it.source to it.baseline }
            } ?: worktrees.freeze(plan.id))
        }
        val resolution = board.sourceInputs(task.id).singleOrNull()?.takeIf { input ->
            plan.kind == TaskKind.Merge && input.graph?.repairOf == task.id
        }
        val value = if (resolution != null) {
            val input = inputs.single()
            val current = worktrees.capture(input.worktree)
            require(current.complete && current.id == input.candidate) { "Resolution candidate changed" }
            worktrees.requireResolved(input.worktree)
            worktrees.attach(input.worktree, runId)
        } else if (target.worktree != null) {
            val prior = worktrees.read(target.worktree)
            require(prior.inputs == inputs.associate { it.task to it.candidate } &&
                prior.roots.associate { it.source to it.baseline } == base) { "The writing handoff inputs changed; start a fresh attempt" }
            inputs.forEach { input ->
                val candidate = worktrees.capture(input.worktree)
                require(candidate.complete && candidate.id == input.candidate) { "Input ${input.task} candidate is stale or incomplete" }
            }
            worktrees.attach(prior.id, runId)
        } else worktrees.prepareFrom(runId, "workspace", base, inputs, keepConflicts = plan.kind == TaskKind.Merge)
        board.advance(plan.id, task.id) { current, next ->
            require(next.attempt == target.attempt && next.state == TaskState.Running)
            current.copy(graph = next.copy(worktreeId = value.id))
        }
        value
    } }

    private fun runningTasks(): List<BoardTask> = board.graphs().flatMap { board.members(it.id) }
        .filter { it.graph?.let { plan -> plan.kind in setOf(TaskKind.Agent, TaskKind.Merge) && plan.state == TaskState.Running } == true }

    private fun ownedRuns(): List<Pair<String, AgentSubmission>> = active.keys.map { it to submission(it) }

    private fun slots(request: AgentSubmission): Int = if (request.councilHybrid) 3 else if (request.collaboration == AgentCollaboration.Single) 1 else 2

    private fun reservesSource(request: AgentSubmission): Boolean =
        (request.task != null || request.isolation != AgentIsolation.Worktree) &&
            (request.allowWrites || request.workflow?.participants?.any { it.tools.allowWrites } == true)

    private fun sourceScope(values: List<String>): List<String> {
        if (values.isEmpty() || values.any { it.trim() in setOf("", "*", ".", "./") }) return listOf("*")
        return values.flatMap { value ->
            val path = value.trim().replace('\\', '/').substringBefore('*').trimEnd('/')
            val modules = scopeModules(listOf(path))
            if (':' in path && !File(path).isAbsolute) {
                if (modules.isEmpty()) listOf("*") else modules.map { "module:$it" }
            } else {
                val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
                listOf(file.invariantSeparatorsPath) + modules.map { "module:$it" }
            }
        }.distinct()
    }

    private fun admission(paths: List<String>, writes: Boolean, requestedSlots: Int, task: String? = null): String? {
        if (closed || draining) return "Workspace is closed or draining"
        val running = runningTasks()
        val reserved = running.map { it.id }.toSet()
        val others = running.filter { it.id != task }
        val runs = ownedRuns().filter { (_, request) -> request.task?.task !in reserved && (task == null || request.task?.task != task) }
        if (others.size + runs.sumOf { slots(it.second) } + requestedSlots > maxActive) return "Agent capacity is busy; wait for an active attempt"
        if (!writes) return null
        val scope = sourceScope(paths)
        others.firstOrNull { other ->
            (other.graph?.kind == TaskKind.Merge || (other.graph?.resolvedSeat?.seat ?: other.graph?.seat)?.access == TaskAccess.Write) &&
                TaskGraphRules.overlaps(scope, sourceScope(other.scope))
        }?.let { return "Source scope is held by ${it.graph?.id}/${it.id} attempt ${it.graph?.attempt}" }
        runs.firstOrNull { (_, request) ->
            reservesSource(request) && TaskGraphRules.overlaps(scope, sourceScope(request.task?.let { board.get(it.task).scope } ?: request.scope))
        }?.let { return "Source scope is held by run ${it.first}" }
        return null
    }

    private fun taskAdmission(task: BoardTask, resolved: ResolvedTaskSeat?): String? {
        val plan = requireNotNull(task.graph)
        if (plan.kind == TaskKind.Merge) return admission(task.scope, true, 1, task.id)
        if (plan.kind != TaskKind.Agent) return null
        val agents = (listOfNotNull(resolved?.seat?.agent) + resolved?.lenses.orEmpty().mapNotNull { it.seat.agent }).distinct()
        val running = board.members(plan.id).filter { it.id != task.id && it.graph?.state == TaskState.Running }
        if (agents.any { agent -> running.count { other -> other.graph?.let { current ->
                current.kind == TaskKind.Agent && (agent == (current.resolvedSeat?.seat ?: current.seat)?.agent ||
                    current.resolvedSeat?.lenses.orEmpty().any { it.seat.agent == agent })
            } == true } >= board.graph(plan.id).envelope.perAgent }) return "The graph's per-agent capacity is busy"
        return admission(task.scope, resolved?.seat?.access == TaskAccess.Write, 1, task.id)
    }

    internal fun startTask(task: BoardTask, resolved: ResolvedTaskSeat?): BoardTask? = synchronized(lock) { synchronized(board) boardLock@{
        val graph = requireNotNull(task.graph).id
        val current = board.get(task.id)
        val plan = requireNotNull(current.graph)
        if (closed || draining || board.graph(graph).status != TaskGraphStatus.Running || current.scope != task.scope ||
            plan.seat != task.graph.seat || TaskGraphRules.readiness(current, board.members(graph).associateBy { it.id }) != TaskGraphRules.Readiness.Ready) return@boardLock null
        val refusal = taskAdmission(current, resolved)
        if (refusal != null) {
            if (plan.note != refusal) board.advance(graph, task.id) { value, next -> value.copy(graph = next.copy(note = refusal)) }
            return@boardLock null
        }
        board.advance(graph, task.id) { value, next ->
            value.copy(graph = next.copy(state = TaskState.Running, attempt = next.attempt + 1, note = null, outcome = null,
                held = false, run = null, runId = null, resolvedSeat = resolved, recovery = AgentRecovery.None, resumeAtReset = null))
        }
    } }

    internal fun claimTask(graph: String, task: String, caller: TaskGraphCaller): BoardTask = synchronized(lock) { synchronized(board) {
        val current = board.get(task)
        require(current.graph?.id == graph && caller.lead && current.graph.seat?.lead == true) { "Only the graph's lead session claims its tasks" }
        val resolved = resolveSeat(requireNotNull(current.graph.seat))
        val refusal = taskAdmission(current, resolved)
        require(refusal == null) { refusal.orEmpty() }
        board.claim(graph, task, caller)
    } }

    internal fun reportTask(graph: String, task: String, report: TaskReport, outcome: TaskOutcome, caller: TaskGraphCaller): BoardTask =
        synchronized(lock) { synchronized(board) {
            val current = board.get(task)
            val plan = requireNotNull(current.graph?.takeIf { it.id == graph })
            if (report == TaskReport.Running && plan.state != TaskState.Running) {
                require(plan.state == TaskState.Waiting && plan.attempt > 0) { "Retry a settled task before running it again" }
                val refusal = taskAdmission(current, plan.resolvedSeat ?: plan.seat?.let(::resolveSeat))
                require(refusal == null) { refusal.orEmpty() }
            }
            if (plan.choices.isNotEmpty() && report in setOf(TaskReport.Succeeded, TaskReport.Review)) {
                require(caller.person) { "Only a person chooses a candidate through taskgraph.report" }
                return@synchronized selectTask(graph, task, requireNotNull(outcome.selectedTask) { "Name selectedTask" },
                    requireNotNull(outcome.candidateId) { "Name the exact candidate you reviewed" }, caller.by, outcome.attempt)
            }
            board.report(graph, task, report, outcome, caller)
        } }

    internal fun selectTask(graph: String, task: String, selected: String?, candidate: String?, by: BoardActor, attempt: Int? = null): BoardTask =
        synchronized(lock) { synchronized(board) {
            val view = board.view(graph)
            val current = board.get(task)
            val plan = requireNotNull(current.graph?.takeIf { it.id == graph && it.choices.isNotEmpty() }) { "Choose a selection task" }
            require(view.graph.status in setOf(TaskGraphStatus.Running, TaskGraphStatus.Paused)) { "Start the graph before choosing a candidate" }
            require(plan.state in setOf(TaskState.Pending, TaskState.Waiting) && plan.recovery != AgentRecovery.NeedsReview) { "This selection has already settled or needs reconciliation" }
            require(attempt == null || attempt == plan.attempt) { "The selection attempt changed" }
            val automatic = by.kind == BoardActorKind.Schedule
            require(by.kind == BoardActorKind.Person || automatic && view.graph.envelope.chooseTop) { "The approved envelope must allow automatic selection" }
            val members = view.tasks.associateBy { it.id }
            require(plan.choices.values.all { members[it]?.graph?.state in TaskGraphRules.Settled }) { "Wait for every alternative's panel to settle" }
            val ranks = view.choices.getValue(task)
            val rank = if (automatic) ranks.firstOrNull { it.eligible } else ranks.firstOrNull { it.task == selected && it.candidate == candidate }
            requireNotNull(rank) { "No eligible candidate matches this selection" }
            require(rank.eligible) { rank.reasons.joinToString("; ") }
            val captured = captureCandidate(rank.task)
            require(captured.complete && captured.id == rank.candidate) { "The candidate changed; refresh its checks and review before choosing it" }
            val now = System.currentTimeMillis()
            board.advance(graph, task) { value, present ->
                value.copy(graph = present.copy(state = TaskState.Succeeded,
                    outcome = TaskOutcome(summary = ranks.joinToString("\n") { it.text() }.take(8_000), verdict = "selected", at = now,
                        attempt = present.attempt, candidateId = captured.id, selectedTask = rank.task, inputCandidates = mapOf(rank.task to captured.id)),
                    note = "Selected ${rank.task} · ${captured.id}"),
                    revision = value.revision + 1, history = (value.history + BoardEvent(now, by, "Selected ${rank.task} · ${captured.id}")).takeLast(50))
            }
        } }

    private fun admit(request: AgentSubmission, seed: ProtocolCheckpoint? = null, forkedFrom: String? = null): AgentRunRecord = synchronized(lock) {
        check(!closed) { "Workspace is closed" }
        check(!draining) { "Workspace is draining for a service upgrade" }
        require(request.requestId.isNotBlank() && request.requestId.length <= 200)
        require(request.prompt.isNotBlank() && request.prompt.length <= 100000)
        require(request.scope.size <= 100 && request.scope.all { it.isNotBlank() && it.length <= 500 })
        require(request.model == null || request.model.length in 1..256)
        require(request.provider in runtimes) { "Provider is not configured" }
        if (request.councilHybrid) {
            require(request.collaboration == AgentCollaboration.Council && request.workflow == null &&
                setOf(request.provider, request.partner?.provider) == setOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode)) {
                "The three-seat council consists of Codex, Claude Code, and ChatGPT + Gemini"
            }
            require(RuntimeKind.ChatGptGemini in runtimes) { "Gemini executor is unavailable" }
        }
        request.workflow?.let { workflow ->
            workflow.validate()
            require(request.collaboration == AgentCollaboration.Single && request.partner == null) { "A workflow supplies its own roster" }
            require(workflow.participants.all { it.runtime in runtimes }) { "Workflow provider unavailable" }
            workflow.participants.forEach { requireSupportedEffort(it.runtime, it.model, it.effort) }
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
            requireSupportedEffort(partner.provider, partner.model, partner.effort)
        }
        requireSupportedEffort(request.provider, request.model, request.effort)
        request.resolvedSeat?.let { resolved ->
            require(request.collaboration == AgentCollaboration.Single && (request.workflow == null || request.task != null && resolved.lenses.isNotEmpty()) &&
                resolved.seat.agent == request.provider && resolved.seat.model == request.model &&
                resolved.seat.effort == request.effort?.value && (resolved.seat.access == TaskAccess.Write) == request.allowWrites) {
                "The submission must match its pinned task seat"
            }
        }
        require(request.transport != AgentTransport.Batch || batchRuntimes.isNotEmpty() || runtimes[request.provider] !is InteractiveAgentRuntime) { "Batch transport is not configured" }
        require(request.transport != AgentTransport.Interactive || (runtimes[request.provider] as? InteractiveAgentRuntime)?.interactive?.usable == true) { "Interactive transport is not configured for this provider; use Automatic or Batch" }
        require(request.context == null || ConductorJson.encodeToString(ContextPacket.serializer(), request.context).length <= 24000)
        val id = digest(request.requestId)
        val fingerprint = digest(ConductorJson.encodeToString(AgentSubmission.serializer(), request))
        (records[id] ?: load(id))?.let {
            require(it.requestFingerprint == fingerprint) { "Request id already belongs to a different submission" }
            return@synchronized recover(it)
        }
        validateTaskTarget(request)
        request.parentRunId?.let { parentId ->
            val parent = get(parentId)
            require(parent.provider != request.provider && !request.allowWrites) { "Peer delegation requires another provider and read-only execution" }
            require(parent.parentRunId == null) { "Peer questions cannot recursively delegate" }
            require(children(parentId).size < 8) { "Peer exchange budget exhausted" }
        }
        requireEntitlements(request)
        require(request.task == null || ownedRuns().none { it.second.task?.let { target -> target.task == request.task.task && target.attempt == request.task.attempt } == true }) {
            "This task attempt already has an active run"
        }
        val refusal = admission(request.task?.let { board.get(it.task).scope } ?: request.scope, reservesSource(request), slots(request), request.task?.task ?: request.message?.task)
        require(refusal == null) { refusal.orEmpty() }
        val threadId = request.threadId ?: UUID.randomUUID().toString()
        val path = threadPath(threadId)
        require(request.threadId == null || Files.exists(path)) { "Conversation not found in this workspace" }
        require(active.keys.none { records[it]?.threadId == threadId }) { "Conversation already has an active turn" }
        require(records.values.none { it.threadId == threadId && it.recovery != AgentRecovery.None }) { "Resume or stop this task's interrupted turn before starting another" }
        val now = System.currentTimeMillis()
        val record = AgentRunRecord(id, threadId, request.provider, fingerprint, request.prompt.take(200),
            request.model, request.allowWrites, startedAt = now, updatedAt = now,
            collaboration = request.collaboration, partner = request.partner, councilHybrid = request.councilHybrid,
            workflow = request.workflow, workflowProgress = seed?.workflow, forkedFrom = forkedFrom, isolation = request.isolation,
            context = request.context,
            parentRunId = request.parentRunId,
            transport = if (useBatch(request)) AgentTransport.Batch else if ((runtimes[request.provider] as? InteractiveAgentRuntime)?.interactive?.usable == true) AgentTransport.Interactive else AgentTransport.Batch,
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
                if (records[id]?.status == AgentRunStatus.Running && records[id]?.attempt == record.attempt && !suspending) {
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
                active.remove(id, job)
                changes.value++
            }
        }
        changes.value++
        job.start()
    }

    private fun checkpointPath(id: String) = directory.resolve("checkpoints/${runPath(id).fileName}")
    internal fun checkpoint(id: String): ProtocolCheckpoint? = checkpointPath(id).takeIf(Files::exists)?.let {
        ConductorJson.decodeFromString(ProtocolCheckpoint.serializer(), Files.readString(it))
    }

    private fun recoverPending() {
        if (closed || draining || active.isNotEmpty()) return
        records.values.filter { it.recovery == AgentRecovery.Pending }.sortedBy { it.startedAt }.forEach { saved ->
            val reason = runCatching { when {
                submission(saved.id).message != null -> "Message delivery stopped without a terminal result; inspect its retained receipt before continuing"
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
        require(request.task == null || saved.recovery != AgentRecovery.NeedsReview) {
            "Reconcile this graph attempt in Hangar before resuming it"
        }
        requireEntitlements(request)
        val refusal = admission(request.task?.let { board.get(it.task).scope } ?: request.scope, reservesSource(request), slots(request), request.task?.task)
        require(refusal == null) { refusal.orEmpty() }
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
            recoveryReason = "Continuing from saved stage results; completed stages are reused", failure = null, pendingHandoff = null,
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

    internal fun reconcileRun(id: String, revision: Long, action: TaskRecoveryAction) = synchronized(lock) {
        val run = get(id)
        require(run.revision == revision && run.status == AgentRunStatus.Interrupted && run.recovery == AgentRecovery.NeedsReview && id !in active) {
            "The interrupted run changed; inspect it again"
        }
        update(id, true) { it.copy(recovery = AgentRecovery.None, recoveryReason = "Person chose ${action.name}; native outcome remains unknown") }
    }

    fun get(id: String): AgentRunRecord = synchronized(lock) {
        recover(records[id] ?: load(id) ?: error("Run not found"))
    }

    fun list(limit: Int = 20, offset: Int = 0): List<AgentRunRecord> = synchronized(lock) {
        require(limit in 1..50 && offset in 0..250)
        recent.drop(offset).take(limit).map(::get)
    }

    fun tasksPage(query: String = "", offset: Int = 0, limit: Int = 50): AgentTasksPage = synchronized(lock) {
        require(query.length <= 200 && offset in 0..200 && limit in 1..50)
        val search = dev.shibasis.reaktor.tooling.query.SearchQuery(query)
        val matches = recent.map(::get).distinctBy { it.threadId }.filter { run ->
            search.matches("${run.title} ${run.provider.name} ${run.taskStatus()} ${run.id}") { key, value, pattern ->
                val field = when (key) { "title" -> run.title; "provider" -> run.provider.name; "status" -> run.taskStatus(); "id" -> run.id; else -> null }
                field?.let { pattern?.containsMatchIn(it) ?: it.contains(value, ignoreCase = true) }
            }
        }
        AgentTasksPage(revision(), matches.drop(offset).take(limit), (offset + limit).takeIf { it < matches.size })
    }

    suspend fun awaitTasks(afterRevision: Long, query: String = "", offset: Int = 0, limit: Int = 50, timeoutMillis: Long = 30000): AgentTasksPage {
        require(afterRevision >= 0 && timeoutMillis in 0..30000)
        val current = tasksPage(query, offset, limit)
        if (current.revision != afterRevision || timeoutMillis == 0L) return current
        withTimeoutOrNull(timeoutMillis) { revisions().first { it != afterRevision } }
        return tasksPage(query, offset, limit)
    }

    private fun revision() = board.revision + changes.value

    internal fun revisions() = combine(board.changes, changes) { tasks, runs -> tasks + runs }

    internal fun freeSlots(): Int = synchronized(lock) {
        if (closed || draining) 0 else {
            val reserved = runningTasks().map { it.id }.toSet()
            (maxActive - reserved.size - ownedRuns().filter { it.second.task?.task !in reserved }.sumOf { slots(it.second) }).coerceAtLeast(0)
        }
    }

    internal fun latest(threadId: String): AgentRunRecord? = synchronized(lock) {
        recent.mapNotNull { records[it] ?: load(it) }.filter { it.threadId == threadId }.maxByOrNull { it.startedAt }
    }

    internal suspend fun runCheck(threadId: String, requestId: String, stage: WorkflowStage): WorkflowCheckResult =
        requireNotNull(workflowCheck) { "This host has no kernel check executor" }.invoke(threadId, requestId, stage)

    internal suspend fun settled(runId: String): AgentRunRecord {
        while (true) {
            val before = changes.value
            val current = get(runId)
            if (current.status != AgentRunStatus.Running && current.recovery != AgentRecovery.Pending && current.recovery != AgentRecovery.Resuming &&
                synchronized(lock) { runId !in active }) return current
            changes.first { it != before }
        }
    }

    internal fun sendMessage(draft: AgentMessage, caller: TaskGraphCaller): AgentMessage = synchronized(lock) {
        val previous = board.coordination(draft.graph).messages.any { it.author == caller.by && it.requestId == draft.requestId }
        val value = board.sendMessage(draft, caller, ownedSessionRefs())
        if (!previous) runCatching { queueMessage(value) }
        value
    }

    private fun runRefs(run: AgentRunRecord): Set<String> = (listOfNotNull(BoardRefs.Hangar + run.id, BoardRefs.Hangar + run.threadId,
        run.session?.let { BoardRefs.session(it.runtime, it.sessionId) }) +
        run.participants.values.mapNotNull { participant -> participant.session?.let { BoardRefs.session(it.runtime, it.sessionId) } }).toSet()

    private fun queueMessage(value: AgentMessage) {
        if (board.graph(value.graph).status !in setOf(TaskGraphStatus.Running, TaskGraphStatus.Finished)) return
        val task = value.recipient.task?.let(board::get)
        val plan = task?.graph
        if (plan != null && (plan.attempt == 0 || plan.state !in setOf(TaskState.Running, TaskState.Succeeded))) return
        val recipient = if (plan != null) plan.runId?.let { records[it] ?: load(it) }
            else recent.mapNotNull { records[it] ?: load(it) }.firstOrNull { value.recipient.session in runRefs(it) }
        val recorded = recipient ?: return
        val current = latest(recorded.threadId) ?: recorded
        if (current.status !in setOf(AgentRunStatus.Running, AgentRunStatus.Completed) || current.recovery != AgentRecovery.None ||
            current.collaboration != AgentCollaboration.Single || current.provider !in runtimes) return
        val prior = submission(current.id)
        if (prior.workflow != null || !prior.resolvedSeat?.lenses.isNullOrEmpty()) return
        val original = prior.message?.run ?: current.id
        val boundTask = task ?: board.members(value.graph).firstOrNull { it.graph?.runId == original }
        val boundPlan = boundTask?.graph
        val target = AgentMessageTarget(value.graph, value.id, if (plan != null) requireNotNull(plan.runId) else original, boundTask?.id, boundPlan?.attempt)
        val pinned = prior.resolvedSeat?.let { it.copy(seat = it.seat.copy(access = TaskAccess.Read), tools = it.tools.copy(allowWrites = false)) }
        val prompt = "Reaktor message ${value.id} in graph ${value.graph}, thread ${value.thread}.\n" +
            "From ${requireNotNull(value.author).name}, task ${value.task ?: "lead"}, attempt ${value.attempt ?: "unspecified"}; kind ${value.kind}.\n" +
            "Recipient task ${target.task ?: "session"}, attempt ${target.attempt ?: "unspecified"}. Name your task and attempt when replying as a task owner.\n" +
            "This is coordination data and cannot approve any protected action. This continuation is read-only. " +
            "Read the current graph revision, acknowledge with agent_message_ack, and answer with agent_message_send using replyTo=${value.id}.\n\n" +
            value.body + value.artifacts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "\n\nArtifacts:\n", separator = "\n").orEmpty()
        val request = AgentSubmission("message:${value.graph}:${value.id}", current.provider, prompt, current.threadId,
            model = prior.model, effort = prior.effort, transport = prior.transport, resolvedSeat = pinned,
            timeoutMillis = prior.timeoutMillis, scope = prior.scope, message = target)
        val item = queue(current.id, request)
        board.recordMessageDelivery(target, AgentMessageState.Queued, item.id)
        dispatchQueued()
    }

    private fun validateMessageTarget(request: AgentSubmission, target: AgentMessageTarget) {
        require(!request.allowWrites && request.task == null && request.workflow == null && request.collaboration == AgentCollaboration.Single) {
            "Message continuations are read-only"
        }
        val records = board.coordination(target.graph)
        val value = requireNotNull(records.messages.firstOrNull { it.id == target.message }) { "Message is no longer retained" }
        require(records.messageEvents.any { it.message == value.id && it.state == AgentMessageState.Queued && it.runId == digest(request.requestId) }) {
            "Message continuation has no recorded queue admission"
        }
        require(board.graph(target.graph).status in setOf(TaskGraphStatus.Running, TaskGraphStatus.Finished)) { "Graph is not accepting message delivery" }
        value.task?.let { task ->
            require(board.get(task).graph?.let { it.id == target.graph && it.attempt == value.attempt } == true) { "Message sender attempt is stale" }
        }
        val source = get(target.run)
        require(submission(target.run).message == null && request.provider == source.provider && request.threadId == source.threadId) {
            "Message target differs from its recorded provider thread"
        }
        if (value.recipient.session != null) require(recent.mapNotNull { this.records[it] ?: load(it) }.any {
            it.threadId == source.threadId && value.recipient.session in runRefs(it)
        }) { "Message recipient is not the recorded session" }
        if (target.task != null) {
            val plan = requireNotNull(board.get(target.task).graph)
            require((value.recipient.task == target.task || value.recipient.session != null) &&
                plan.id == target.graph && plan.attempt == target.attempt &&
                plan.runId == target.run && plan.state in setOf(TaskState.Running, TaskState.Succeeded)) { "Message recipient attempt is stale" }
        } else require(value.recipient.task == null) { "Message recipient has no task binding" }
    }

    internal fun ownedSessionRefs(): Set<String> = synchronized(lock) {
        recent.mapNotNull { records[it] ?: load(it) }.flatMap(::runRefs).toSet()
    }

    internal fun threadOf(ref: String): String? {
        val raw = ref.substringAfter(':')
        return when {
            ref.startsWith(BoardRefs.Hangar) && runCatching { UUID.fromString(raw).toString() == raw }.getOrDefault(false) -> raw
            ref.startsWith(BoardRefs.Hangar) && raw.matches(Regex("[a-f0-9]{64}")) -> synchronized(lock) { (records[raw] ?: load(raw))?.threadId }
            else -> synchronized(lock) { recent.mapNotNull { records[it] ?: load(it) }.firstOrNull { run ->
                run.session?.sessionId == raw || run.participants.values.any { it.session?.sessionId == raw }
            }?.threadId }
        }
    }

    fun boardPage(query: String = ""): BoardPage {
        val revision = revision()
        val tasks = board.tasks()
        val byId = tasks.associateBy { it.id }
        val threads = synchronized(lock) { recent.map(::get) }.groupBy { it.threadId }.mapValues { (_, runs) -> runs.maxBy { it.startedAt } }
        return BoardPage(revision, tasks.map { task ->
            task.entry(task.work.filter { it.startsWith(BoardRefs.Hangar) }.mapNotNull { ref -> threads[ref.removePrefix(BoardRefs.Hangar)]?.boardWork(ref) }, byId)
        }).matching(query)
    }

    fun boardEntry(id: String): BoardEntry = boardPage().entries.firstOrNull { it.task.id == id } ?: error("$id is not on the board")

    fun createBoardTask(draft: BoardDraft, by: BoardActor): BoardEntry = boardEntry(board.create(draft.copy(work = draft.work.onlyKnownThreads()), by).id)

    fun linkBoardWork(id: String, ref: String, linked: Boolean, by: BoardActor): BoardEntry =
        boardEntry(board.link(id, if (linked) listOf(ref).onlyKnownThreads().single() else ref, linked, by).id)

    private fun List<String>.onlyKnownThreads(): List<String> = onEach { ref ->
        val thread = ref.removePrefix(BoardRefs.Hangar)
        require(!ref.startsWith(BoardRefs.Hangar) || Files.exists(threadPath(thread)) || synchronized(lock) { records.values.any { it.threadId == thread } }) {
            "$ref is not a conversation in this workspace"
        }
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
            update(id, true) { it.copy(status = AgentRunStatus.Interrupted, recovery = AgentRecovery.None, recoveryReason = null, failure = "Stopped by the user", pendingHandoff = null,
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

    fun transcript(threadId: String, before: Int? = null, limit: Int = 20): AgentTranscript {
        require(limit in 1..50) { "Transcript page limit must be between 1 and 50" }
        val path = threadPath(threadId)
        require(Files.exists(path)) { "Conversation not found in this workspace" }
        val document = decodeThread(Files.readString(path))
        val end = before ?: document.events.size
        require(end in 0..document.events.size) { "Transcript cursor is outside this conversation" }
        var start = end
        var remaining = 256 * 1024
        while (start > 0 && end - start < limit) {
            val size = ConductorJson.encodeToString(ThreadEvent.serializer(), document.events[start - 1]).toByteArray(Charsets.UTF_8).size + 1
            if (size > remaining) {
                require(start < end) { "This complete event exceeds the transcript page transport budget" }
                break
            }
            remaining -= size
            start--
        }
        return AgentTranscript(threadId, document.events.subList(start, end), start > 0, start.takeIf { it > 0 })
    }

    private suspend fun execute(initial: AgentRunRecord, request: AgentSubmission) {
        // A paused turn can be resumed before this one finishes unwinding. Releasing by id alone
        // would drop the next attempt's job out of `active`, and a live run would read as stopped.
        val self = currentCoroutineContext()[Job]
        try {
            request.message?.let { validateMessageTarget(request, it) }
            FileThreadStore.open(threadPath(initial.threadId)).use { store ->
                val isolated = request.message?.let { worktreeFor(initial.id) } ?: if (request.allowWrites && request.task != null) prepareTaskWorktree(initial.id, request.task)
                    else request.task?.worktree?.let(worktrees::read)
                        ?: if (request.isolation == AgentIsolation.Worktree) worktrees.prepare(initial.id, "workspace") else null
                val workingRoot = isolated?.roots?.first { it.source == root.canonicalPath }?.path?.let(::File) ?: root
                val includedRoots = isolated?.roots?.filter { it.source != root.canonicalPath }?.map { it.path } ?: includedBuildRoots(root)
                update(initial.id, true) { it.copy(workingDirectory = workingRoot.canonicalPath) }
                val supplied = request.context
                val subjects = supplied?.entries.orEmpty().filter { it.kind == "graph-subject" }
                    .map { AgentGraphSubject(it.ref, supplied?.revision) }
                val evidenceTask = request.task?.evidenceTask ?: initial.threadId
                val candidate = request.task?.candidate?.let { id -> evidence.get(evidenceTask).candidates.single { it.id == id } }
                    ?: if (isolated == null) evidence.capture(evidenceTask, subjects)
                    else worktrees.capture(isolated.id, subjects).also { evidence.recordCandidate(evidenceTask, it) }
                if (request.task?.candidate != null) {
                    val current = worktrees.capture(requireNotNull(isolated).id, candidate.subjects)
                    require(current.complete && current.id == candidate.id) { "The review handoff candidate is stale or incomplete" }
                }
                val prior = evidence.get(evidenceTask)
                val context = ContextPacket(workspaceId = root.canonicalPath, principalId = supplied?.principalId ?: "local-operator",
                    source = "Reaktor agent workspace", observedAt = System.currentTimeMillis().toString(),
                    revision = supplied?.revision, freshness = "captured-before-turn", partial = !candidate.complete || supplied?.partial == true,
                    entries = listOf(ContextEntry("candidate:${candidate.id}", "candidate", "Source candidate",
                        "Task: $evidenceTask\nCandidate: ${candidate.id}\nBase commit: ${candidate.baseCommit}\nChanged paths: ${candidate.changedFiles.take(50).joinToString()}\n" +
                            "Use taskId=$evidenceTask with agent_candidate_capture, agent_task_graph and agent_artifact. Report findings through agent_finding against candidateId=${candidate.id}, producerRunId=${initial.id}. " +
                            "Evidence targets this exact checkout. Landing is a separate person-only operation.",
                        "Binds this attempt to local source evidence")) + supplied?.entries.orEmpty().take(20) +
                        prior.findings.filter { request.collaboration != AgentCollaboration.Compare && request.resolvedSeat?.lenses.isNullOrEmpty() && it.resolvedByCandidate == null }.take(10).map { ContextEntry(it.id, "finding", it.title,
                            it.detail.take(600), "Unresolved finding from ${it.participant} on ${it.candidateId}") },
                    omittedEntries = (supplied?.omittedEntries ?: 0) + maxOf(0, (supplied?.entries?.size ?: 0) - 20),
                    notices = candidate.notices + supplied?.notices.orEmpty()).bounded(22000)
                update(initial.id, true) { it.copy(context = context, candidateId = candidate.id) }
                val agentId = AgentId(request.provider.name.lowercase())
                fun spec(provider: RuntimeKind, model: String?, effort: NativeEffort?) = AgentSpec(
                    AgentId(provider.name.lowercase()), request.resolvedSeat?.name ?: provider.name, provider,
                    listOf("Follow the workspace's repository instructions.", request.resolvedSeat?.instructions.orEmpty()).filter(String::isNotBlank).joinToString("\n\n"), model = model, effort = effort,
                    tools = (request.resolvedSeat?.tools ?: ToolPolicy()).let { policy -> policy.copy(allowWrites = request.allowWrites,
                        additionalDirectories = (includedRoots + policy.additionalDirectories).distinct(), mcpConfig = policy.mcpConfig ?: harnessMcpConfig) },
                    budget = request.timeoutMillis?.let { AgentBudget(timeoutMillis = it) } ?: AgentBudget(),
                    attributes = (request.maxCycles?.let { mapOf("maxCycles" to it.toString()) }.orEmpty()) +
                        (request.allowedChecks.takeIf { it.isNotEmpty() }
                            ?.let { mapOf("allowedChecks" to it.joinToString("\u0001")) }.orEmpty()))
                val specs = request.workflow?.participants?.map { it.copy(tools = it.tools.copy(allowWrites = request.allowWrites && it.tools.allowWrites,
                    additionalDirectories = (includedRoots + it.tools.additionalDirectories).distinct(), mcpConfig = it.tools.mcpConfig ?: harnessMcpConfig)) }
                    ?: (listOf(spec(request.provider, request.model, request.effort)) +
                    listOfNotNull(request.partner?.let { spec(it.provider, it.model, it.effort) }) +
                    if (request.councilHybrid) listOf(spec(RuntimeKind.ChatGptGemini, null, null)) else emptyList())
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
                    request.message?.let { validateMessageTarget(request, it) }
                    val result = Conductor(if (useBatch(request)) batchRuntimes else runtimes, clock = System::currentTimeMillis, idFactory = { EventId(UUID.randomUUID().toString()) }).run(
                        document, request.prompt, protocol, workingRoot.canonicalPath,
                        context = context, resumeProviderSession = request.collaboration == AgentCollaboration.Single && request.workflow == null,
                        resumeFrom = restored,
                        executionPrefix = initial.id,
                        workingDirectoryFor = { directories.getValue(it) },
                        outputSchema = request.task?.candidate?.let(TaskReview::schema),
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
                            request.message?.let { target ->
                                val started = event is AgentEvent.TurnStarted || initial.transport == AgentTransport.Batch &&
                                    event is AgentEvent.Started && event.session != null
                                if (started) board.recordMessageDelivery(target, AgentMessageState.Delivered, initial.id,
                                    (event as? AgentEvent.TurnStarted)?.turnId)
                            }
                            recordActivity(initial.id, event)
                            update(initial.id, persistNow = event !is AgentEvent.Delta && event !is AgentEvent.Reasoning) { current ->
                                if (current.status != AgentRunStatus.Running) current else projectAgentEvent(current, event, specs.first { it.id == event.agent }.runtime)
                        } },
                    )
                    val answer = result.answer
                    val failures = result.added.filter { it.kind == EventKind.Failure }
                    val workflowFailed = request.workflow?.failed(lastWorkflow ?: WorkflowProgress())
                    if (request.task != null) validateTaskTarget(request, initial.id)
                    if (request.task?.candidate != null) {
                        val current = worktrees.capture(requireNotNull(isolated).id, candidate.subjects)
                        require(current.complete && current.id == candidate.id) { "The review candidate changed during the turn" }
                    }
                    val completedCandidate = if (request.allowWrites && failures.isEmpty() && workflowFailed != true &&
                        get(initial.id).status == AgentRunStatus.Running && get(initial.id).participants.values.none { it.status == AgentRunStatus.Interrupted }) {
                        (if (isolated == null) evidence.capture(evidenceTask, subjects) else worktrees.capture(isolated.id, subjects).also { evidence.recordCandidate(evidenceTask, it) })
                            .also { require(request.task == null || it.complete) { "Post-turn candidate coverage is incomplete: ${it.notices.joinToString()}" } }
                    } else null
                    update(initial.id, true) { if (it.status == AgentRunStatus.Interrupted && it.recovery == AgentRecovery.None) it else it.copy(status = if (it.participants.values.any { p -> p.status == AgentRunStatus.Interrupted }) AgentRunStatus.Interrupted else if (it.participants.values.any { p -> p.status == AgentRunStatus.Limited }) AgentRunStatus.Limited else if (workflowFailed ?: failures.isNotEmpty()) AgentRunStatus.Failed else AgentRunStatus.Completed,
                        usageLimit = it.participants.values.firstNotNullOfOrNull { p -> p.usageLimit },
                        pending = emptyList(), recovery = AgentRecovery.None, recoveryReason = null,
                        output = answer?.text.orEmpty().takeLast(12000), outputTruncated = answer?.text.orEmpty().length > 12000,
                        candidateId = completedCandidate?.id ?: it.candidateId,
                        usage = answer?.usage?.takeIf { request.collaboration == AgentCollaboration.Single },
                        reportedUsage = answer?.reportedUsage?.takeIf { request.collaboration == AgentCollaboration.Single },
                        failure = failures.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }?.take(2000)) }
                } catch (failure: Exception) {
                    if (failure is WorkflowPaused || failure is AgentHandoffRequired) throw failure
                    store.load()?.let { latest -> store.checkpoint(latest.append(ThreadEvent(EventId(UUID.randomUUID().toString()),
                        Author.Orchestrator("workspace"), EventKind.Failure,
                        if (failure is CancellationException) "Turn interrupted. Inspect workspace changes before continuing." else failure.message.orEmpty().take(2000),
                        parents = latest.heads().map { it.id }, createdAtEpochMillis = System.currentTimeMillis()))) }
                    throw failure
                }
            }
        } catch (failure: Exception) {
            if (failure is AgentHandoffRequired) {
                if (request.workflow != null) checkpoint(initial.id)?.takeIf { it.inFlight.isEmpty() }?.let { saved ->
                    val handoff = hybrid.read(failure.handoffId)
                    checkpoints.save(initial.id, saved.copy(sourceRevision = handoff.sourceRevision))
                }
                update(initial.id, true) { it.copy(status = AgentRunStatus.Interrupted, recovery = AgentRecovery.NeedsReview, pendingHandoff = failure.handoffId,
                    recoveryReason = "ChatGPT + Gemini is waiting for a ChatGPT handoff. Transfer the packet in Conversation, import the response, then continue.",
                    pending = emptyList(), participants = it.participants.mapValues { (_, p) ->
                        if (p.provider == RuntimeKind.ChatGptGemini) p.copy(status = AgentRunStatus.Interrupted, activeTurn = null, pending = emptyList()) else p }) }
                activity.append(initial.id, "chatgptgemini", initial.attempt, AgentActivityItem("handoff:${failure.handoffId}", ActivityKind.Control,
                    "Waiting for ChatGPT", ActivityStatus.Waiting, output = failure.handoffId))
                return
            }
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
            synchronized(lock) { self?.let { active.remove(initial.id, it) } ?: active.remove(initial.id); changes.value++ }
            synchronized(lock) { recoverPending() }
            dispatchQueued()
        }
    }

    private fun useBatch(request: AgentSubmission): Boolean = batchRuntimes.isNotEmpty() &&
        (request.transport == AgentTransport.Batch || (request.transport == AgentTransport.Automatic && request.collaboration != AgentCollaboration.Single))

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
        val (jobs, finishing) = synchronized(lock) {
            closed = true
            active.values.toList() to active.filter { (id, _) ->
                val record = records.getValue(id)
                record.workflow == null && record.collaboration != AgentCollaboration.Council &&
                    record.provider != RuntimeKind.ChatGptGemini && record.participants.isNotEmpty() &&
                    record.participants.values.all { it.status == AgentRunStatus.Completed || it.status == AgentRunStatus.Failed }
            }.values.toList()
        }
        runBlocking {
            withTimeoutOrNull(2000) { finishing.joinAll() }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
        scope.cancel()
    }

    fun suspendAndClose() { synchronized(lock) { suspending = true }; close() }

    private fun recordActivity(id: String, event: AgentEvent) {
        val item = activityProjection.item(id, event) ?: return
        activity.append(id, event.agent.value, synchronized(lock) { records.getValue(id).attempt }, item)
    }
}
