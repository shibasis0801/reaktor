package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class HybridHandoffs(private val directory: Path, private val artifacts: LocalAgentArtifacts,
    private val recordCandidate: (String, AgentCandidate) -> Unit = { _, _ -> }) {
    init { privateDirectory(directory) }
    private fun path(id: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid handoff id" }
        return directory.resolve("$id.json")
    }
    @Synchronized fun read(id: String): HybridHandoff = ConductorJson.decodeFromString(HybridHandoff.serializer(), Files.readString(path(id)))
    @Synchronized fun list(runId: String): List<HybridHandoff> = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }.map { read(it.fileName.toString().removeSuffix(".json")) }
            .filter { it.runId == runId }.toList().sortedBy { it.executionId }
    }
    private fun save(value: HybridHandoff): HybridHandoff {
        atomicWrite(path(value.id), ConductorJson.encodeToString(HybridHandoff.serializer(), value)); return value
    }
    @Synchronized fun prepare(request: AgentRequest, maxCycles: Int? = null, allowedChecks: List<String> = emptyList()): HybridHandoff {
        val execution = requireNotNull(request.executionId) { "Hybrid turns require a durable council stage identity" }
        val id = digest(execution)
        if (Files.exists(path(id))) return read(id).also {
            require(it.agent == request.agent.id && it.workspaceRoot == File(request.workingDirectory).canonicalPath)
        }
        // A real repository is rarely fully readable — binaries, vendored pods and build outputs run
        // past the capture budget — and refusing the turn for that would leave this seat unusable
        // where the other two work. The digest advances per file either way, so it still detects a
        // changed or added file; what it cannot see is stated in the packet rather than assumed.
        val candidate = SourceCandidates(File(request.workingDirectory), artifacts).capture()
        return save(HybridHandoff(id, execution.substringBefore(':'), execution, request.agent.id,
            candidate.workspaceRoot, candidate.sourceDigest, request.prompt.take(32000), subjectRefs = request.subjectRefs.take(100),
            partial = !candidate.complete || request.prompt.length > 32000, coverage = coverage(candidate),
            // Once per task, not per turn: prepare returns the stored handoff on every later call.
            grantAdvisory = runCatching {
                dev.shibasis.reaktor.conductor.cli.AntigravityGrants.advisory(
                    File(request.workingDirectory), request.agent.tools.allowWrites)
            }.getOrNull(),
            allowedChecks = allowedChecks,
            maxCycles = maxCycles?.coerceIn(1, 40) ?: DEFAULT_MAX_CYCLES))
    }
    /** What the snapshot could not read, bounded, and honest about its own bound. */
    private fun coverage(candidate: AgentCandidate): List<String> = candidate.notices.take(20) +
        listOfNotNull("and ${candidate.notices.size - 20} further gaps this list does not show".takeIf { candidate.notices.size > 20 }) +
        listOfNotNull(("${candidate.fingerprintedBySize} large binary files are in this revision by length rather " +
            "than by content: one replaced by a different build of exactly the same size would not move it")
            .takeIf { candidate.fingerprintedBySize > 0 })

    private fun verifySource(value: HybridHandoff) {
        val current = SourceCandidates(File(value.workspaceRoot), artifacts).capture()
        require(current.sourceDigest == value.sourceRevision) {
            "Source changed after this handoff. Stop and start a fresh council turn with current graph context."
        }
    }

    /**
     * Applies one planner reply.
     *
     * A reviewing reply has two exits, and which one it takes is the planner's call: [HybridNext.Finish]
     * ends the seat's contribution, [HybridNext.Execute] closes the pass and opens the next one with
     * the reply as its instruction. That second edge is the whole loop — without it the seat plans
     * once, executes once, and stops whether or not the work is done.
     *
     * [HybridHandoff.maxCycles] overrides the planner. A loop that decides its own termination has
     * no termination.
     */
    @Synchronized fun reply(runId: String, reply: HybridReply, plannerSession: ProviderSession? = null,
        via: HybridPlannerVia = HybridPlannerVia.Operator): HybridHandoff {
        val old = read(reply.handoffId)
        require(old.runId == runId) { "Handoff belongs to another run" }
        if (reply == old.plan || reply == old.review) return old
        require(old.revision == reply.revision && old.phase == reply.phase) { "Handoff changed; copy the current packet before replying" }
        require(reply.phase in listOf(HybridPhase.Planning, HybridPhase.Reviewing))
        require(reply.text.isNotBlank() && reply.text.length <= 24000 && reply.acceptanceCriteria.size <= 20 && reply.acceptanceCriteria.all { it.length in 1..1000 })
        verifySource(old)
        val carried = old.copy(plannerSession = plannerSession ?: old.plannerSession, plannerVia = via)
        return save(when {
            // A planner that reads the packet and concludes nothing should be run says so, rather
            // than being made to invent an instruction in order to reach an exit.
            old.phase == HybridPhase.Planning && reply.decision() == HybridNext.Execute ->
                carried.copy(plan = reply, phase = HybridPhase.Executing, revision = old.revision + 1,
                    executionAttempts = 0)

            old.phase == HybridPhase.Planning ->
                carried.copy(review = reply, phase = HybridPhase.Completed, revision = old.revision + 1)

            // A pass that died in the harness is closed like any other and simply not charged, so
            // a denied permission costs the task a retry rather than a development iteration. The
            // second clause is what still guarantees termination when every pass dies that way.
            reply.decision() == HybridNext.Execute &&
                old.chargedCycles + (if (charge(old.observation)) 1 else 0) < old.maxCycles &&
                old.cycles.size + 1 < old.maxCycles * HARNESS_HEADROOM ->
                carried.copy(
                    // The pass's on-demand runs close with it, so the cycle record holds every
                    // check that was taken while it was open and the next pass starts with none.
                    cycles = old.cycles + HybridCycle(old.cycles.size, requireNotNull(old.plan) { "A review without a plan" },
                        old.observation?.let { it.copy(checks = it.checks + old.checks) },
                        counted = charge(old.observation)),
                    plan = reply.copy(phase = HybridPhase.Planning),
                    observation = null,
                    checks = emptyList(),
                    executionAttempts = 0,
                    phase = HybridPhase.Executing,
                    revision = old.revision + 1,
                )

            else -> {
                // Finishing turns a claim into the record. Either a check Reaktor ran passed, or the
                // reply says why none could — anything else would let "it works" be unfalsifiable.
                // Only where a gate exists: an operator who permitted no checks opted out of it,
                // and demanding an admission there would be ceremony rather than evidence.
                //
                // A baseline pass is excluded on purpose: it says the repository was green before
                // this task touched it, which is the opposite of evidence that the work is good.
                require(old.allowedChecks.isEmpty() || old.proven() || !reply.unverified.isNullOrBlank()) {
                    "This task permits acceptance checks (" + old.allowedChecks.joinToString(", ") +
                        ") and none has passed since something was changed. Either run or attach a check that proves " +
                        "the work, or set 'unverified' on this reply to state what could not be verified and why."
                }
                carried.copy(review = reply, phase = HybridPhase.Completed, revision = old.revision + 1)
            }
        })
    }
    /**
     * Names the harness reason a turn produced nothing, when that is what happened.
     *
     * Deliberately narrow. A model that tried the work and got it wrong has to cost a cycle — that
     * is what the budget is for — so only causes that stopped the turn before it reached the code
     * are recognised, and everything else is charged.
     */
    private fun harnessCause(outcome: AgentOutcome): String? {
        val failure = outcome.failure.orEmpty()
        return when {
            outcome.ok -> null
            outcome.interrupted -> "interrupted"
            failure.contains("could not obtain required permissions") -> "permission"
            failure.contains("budget ends exactly like this") -> "budget"
            failure.contains("produced no completed answer") -> "no result"
            else -> null
        }
    }

    /**
     * Whether a finished pass should spend one of the task's cycles.
     *
     * Only a turn that reached the code counts. A permission the executor never had, a budget that
     * elapsed mid-build, an interrupt: none of those told the loop anything about the repository,
     * and charging them turns a ten-iteration budget into ten attempts at the plumbing. Anything
     * that changed a file or produced a check result is charged whether or not it succeeded —
     * failing at the actual work is exactly what the budget is for.
     */
    private fun charge(observation: HybridObservation?): Boolean = observation == null ||
        observation.harness == null || observation.changedPaths.isNotEmpty() ||
        observation.checks.any { it.exitCode != null }

    /**
     * Runs acceptance checks now, without spending an executor turn on them.
     *
     * Verification was the loop's most expensive habit: the only way to learn whether the build was
     * green was to plan a cycle, ask the executor to run the command, and have it paste the output
     * back — two model turns and a cycle of budget for an exit code Reaktor can read itself. These
     * runs are the same evidence and cost neither.
     *
     * Runs taken before anything has changed are recorded as baselines, which is what later lets a
     * failure be classified instead of argued over.
     */
    fun check(runId: String, handoffId: String, requested: List<HybridCheck>): CompletableFuture<List<HybridCheckRun>> {
        require(requested.isNotEmpty() && requested.size <= 8) { "Name between one and eight checks to run" }
        val (open, baseline) = synchronized(this) {
            val handoff = read(handoffId)
            require(handoff.runId == runId) { "Handoff belongs to another run" }
            require(handoff.phase != HybridPhase.Executing) {
                "The executor is running on this task; a check now would compete with it for the build. Await the cycle first."
            }
            if (!inFlight.add(runId)) error("A check is already running on this task; wait for it before starting another")
            handoff to (handoff.completedCycles == 0)
        }
        val result = CompletableFuture<List<HybridCheckRun>>()
        // Off the lock and off the caller's thread. A real acceptance check is a build: holding
        // either for fifteen minutes would stall every other handoff in the workspace, and a
        // connector that has to keep an HTTP request open that long simply gives up instead.
        Thread({
            try {
                val runs = HybridChecks.run(File(open.workspaceRoot), requested, open.allowedChecks)
                synchronized(this) {
                    // Re-read: the pass may have moved on while a long build was running, and the
                    // classification has to be against what is stored now rather than a stale copy.
                    val current = read(handoffId)
                    val classified = runs.map { classify(current, it, baseline) }
                    save(current.copy(
                        checks = (current.checks + classified).takeLast(MAX_RECORDED_CHECKS),
                        baselines = current.baselines +
                            classified.filter { it.baseline && it.exitCode != null }.associate { it.command to it.ok },
                    ))
                    result.complete(classified)
                }
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            } finally {
                synchronized(this) { inFlight.remove(runId) }
            }
        }, "reaktor-hybrid-check").apply { isDaemon = true }.start()
        return result
    }

    /** Tasks with a check running. One at a time: two builds in one tree measure each other. */
    private val inFlight = mutableSetOf<String>()

    /** Places one run against what the same command did before this task changed anything. */
    private fun classify(handoff: HybridHandoff, run: HybridCheckRun, baseline: Boolean): HybridCheckRun = run.copy(
        baseline = baseline,
        classification = when {
            baseline -> CheckClassification.Baseline
            run.ok || run.exitCode == null -> null
            handoff.baselines[run.command] == true -> CheckClassification.Regression
            handoff.baselines[run.command] == false -> CheckClassification.PreExisting
            else -> CheckClassification.Unknown
        },
        at = System.currentTimeMillis(),
    )

    /**
     * Reads one artifact this handoff itself recorded.
     *
     * The id is looked up in the handoff rather than trusted from the caller, so a planner that can
     * name a task can read that task's diffs and nothing else.
     */
    @Synchronized fun artifact(runId: String, id: String, offset: Long = 0, limit: Int = 24000): AgentArtifactPage {
        val handoffs = list(runId)
        val observations = handoffs.flatMap { it.cycles.mapNotNull(HybridCycle::observation) + listOfNotNull(it.observation) }
        val ref = observations.firstNotNullOfOrNull { it.diffArtifact?.takeIf { ref -> ref.id == id } }
            ?: error("Artifact is not attached to this task")
        return artifacts.read(ref, offset, limit)
    }

    /**
     * Verifies nothing moved since the plan was written, and records the tree as it stands now.
     *
     * Returns the updated handoff: the fingerprint has to be taken here, immediately before the
     * executor runs, because the planner's own turn sits between [prepare] and this moment.
     *
     * The one case where a changed tree is not a reason to stop is a turn that was interrupted after
     * it had already written. The handoff still says Executing with no observation, which means this
     * process handed a plan to an executor and never recorded what came back — so the difference is
     * almost certainly the executor's own half-finished work, and refusing would make the run
     * unrecoverable exactly when recovery is the point. It re-baselines instead, and says so in the
     * packet rather than quietly accepting it, because the planner is about to reason about a tree
     * nobody has reported on.
     */
    @Synchronized fun beforeExecution(value: HybridHandoff): HybridHandoff {
        val current = SourceCandidates(File(value.workspaceRoot), artifacts)
        val fresh = current.capture()
        // Only a restart may re-baseline. A first start has to find the tree the planner saw.
        val resuming = value.executionAttempts > 0 && value.phase == HybridPhase.Executing && value.observation == null
        if (fresh.sourceDigest != value.sourceRevision && !resuming) {
            error("Source changed after this handoff. Stop and start a fresh council turn with current graph context.")
        }
        val rebaselined = fresh.sourceDigest != value.sourceRevision
        return save(
            value.copy(
                manifest = current.dirtyManifest(),
                sourceRevision = fresh.sourceDigest,
                executionAttempts = value.executionAttempts + 1,
                coverage = if (!rebaselined) value.coverage else value.coverage +
                    ("An earlier attempt at this cycle was interrupted after it had already changed files. " +
                        "The tree was re-baselined from ${value.sourceRevision.take(12)} to " +
                        "${fresh.sourceDigest.take(12)}; whatever that attempt wrote is present but was never " +
                        "reported, so read the files before trusting any earlier description of them."),
            ),
        )
    }
    @Synchronized fun observed(id: String, outcome: AgentOutcome): HybridHandoff {
        val old = read(id)
        require(old.phase == HybridPhase.Executing && old.observation == null)
        val source = SourceCandidates(File(old.workspaceRoot), artifacts)
        val after = source.dirtyManifest()
        val candidate = source.capture(old.subjectRefs.map { AgentGraphSubject(it) })
        recordCandidate(old.runId, candidate)
        // Fall back to the candidate's own list only when a fingerprint is missing, and say so:
        // "these files differ from HEAD" and "this turn changed these" must not be confused.
        val turnChanged = if (old.manifest.available && after.available) old.manifest.changedInto(after)
            else candidate.changedFiles
        val attribution = if (old.manifest.available && after.available) emptyMap() else
            mapOf("changedPathsScope" to "sinceLastCommit")
        // The diff the reviewer reads is this turn's, not the checkout's. A whole-tree diff in a
        // repository carrying other work is unreviewable — the change is somewhere inside dozens of
        // unrelated deletions — and a reviewer who cannot see what a task did falls back to
        // believing the executor's description of it.
        val scoped = source.diffOf(turnChanged)
        val diffArtifact = scoped.text.takeIf { it.isNotBlank() }
            ?.let { runCatching { artifacts.put(it, "turn-diff") }.getOrNull() }
        val excerpt = diffArtifact?.let { artifacts.read(it, limit = 12000) }
        // Run the plan's own acceptance checks here, so the exit codes in the report were produced
        // by Reaktor rather than described by the party being judged.
        val baseline = old.completedCycles == 0 && turnChanged.isEmpty()
        val checks = HybridChecks.run(File(old.workspaceRoot), old.plan?.checks.orEmpty(), old.allowedChecks)
            .map { classify(old, it, baseline) }
        return save(old.copy(manifest = after, phase = HybridPhase.Reviewing, revision = old.revision + 1, sourceRevision = candidate.sourceDigest,
            coverage = coverage(candidate) + scoped.notices,
            baselines = old.baselines + checks.filter { it.baseline && it.exitCode != null }.associate { it.command to it.ok },
            observation = HybridObservation(outcome.text.take(18000), outcome.ok, outcome.failure, outcome.session, outcome.usage,
                candidate.id, candidate.sourceDigest, turnChanged.take(100), diffArtifact,
                !candidate.complete || outcome.text.length > 18000 || turnChanged.size > 100 || excerpt?.truncated == true,
                excerpt?.text, outcome.attributes + attribution, checks,
                scope = if (attribution.isEmpty()) ChangeScope.Turn else ChangeScope.SinceLastCommit,
                harness = harnessCause(outcome))))
    }
}

/**
 * How many on-demand runs one open pass keeps. Enough to iterate; not enough to become the packet.
 */
private const val MAX_RECORDED_CHECKS = 12

/**
 * The composite ChatGPT + Gemini seat.
 *
 * With a planner this runs unattended: ChatGPT plans, Gemini executes, ChatGPT reads the result and
 * either asks for the next thing or declares the work done, until it finishes or the cycle cap stops
 * it. Without one it falls back to the operator relay, where each of those steps is a copy and a
 * paste.
 *
 * [planner] is resolved per turn rather than held, because whether an automatic planner exists is a
 * live fact: its entitlement can be paused between one turn and the next, and a seat that kept a
 * stale answer would quietly spend a pool the operator had closed. Falling back to the relay is the
 * correct behaviour there — the seat still works, it just asks a person for the half it can no
 * longer buy.
 *
 * The split of authority is the point and it holds in both modes: the planner never gets write
 * grants, so the half that reasons about the repository is never the half that changes it.
 */
internal class HybridRuntime(
    private val gemini: AgentRuntime,
    private val handoffs: HybridHandoffs,
    private val planner: () -> HybridPlanner? = { null },
) : InteractiveAgentRuntime {
    override val kind = RuntimeKind.ChatGptGemini
    override val interactive = (gemini as? InteractiveAgentRuntime)?.interactive ?: Qualification.unavailable

    override fun run(request: AgentRequest): Flow<AgentEvent> = planner()
        ?.let { active -> automatic(request, active) }
        ?: flow { awaitSession(request, {}, onEvent = { emit(it) }) }

    override suspend fun open(request: AgentRequest): AgentSession {
        planner()?.let { return automaticSession(request, it) }
        val handoff = handoffs.prepare(request, request.agent.attributes["maxCycles"]?.toIntOrNull(),
            request.agent.attributes["allowedChecks"]?.split('\u0001')?.filter { it.isNotBlank() }.orEmpty())
        when (handoff.phase) {
            HybridPhase.Planning, HybridPhase.Reviewing -> throw AgentHandoffRequired(handoff.id)
            HybridPhase.Completed -> return completedSession(request, handoff)
            HybridPhase.Executing -> Unit
        }
        val ready = handoffs.beforeExecution(handoff)
        val executorRequest = executorRequest(request, ready)
        val session = (gemini as? InteractiveAgentRuntime)?.open(executorRequest)
        return object : AgentSession {
            override val activeTurn get() = session?.activeTurn
            override fun nativeAgents() = session?.nativeAgents().orEmpty()
            override suspend fun steer(text: String, expectedTurn: String?) = session?.steer(text, expectedTurn) ?: CommandOutcome.Unsupported("Gemini batch steering")
            override suspend fun resolve(requestId: String, decision: AgentDecision) = session?.resolve(requestId, decision) ?: CommandOutcome.Unsupported("Gemini batch permission")
            override suspend fun interrupt() = session?.interrupt() ?: CommandOutcome.Unsupported("Gemini batch interrupt")
            override val events: Flow<AgentEvent> = flow {
                var outcome: AgentOutcome? = null
                (session?.events ?: gemini.run(executorRequest)).collect {
                    if (it is AgentEvent.Finished) outcome = it.outcome else emit(it)
                }
                handoffs.observed(handoff.id, requireNotNull(outcome) { "Gemini ended without a durable result; inspect its activity before retrying" })
                throw AgentHandoffRequired(handoff.id)
            }
            override fun close() { session?.close() }
        }
    }

    /** The unattended loop, as a session so the workspace can stop it between cycles. */
    private fun automaticSession(request: AgentRequest, planner: HybridPlanner): AgentSession = object : AgentSession {
        @Volatile private var stopped = false
        override val activeTurn: String? = null
        override val events: Flow<AgentEvent> = automatic(request, planner) { stopped }
        override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Unsupported("ChatGPT + Gemini plans each cycle itself")
        override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Stale(requestId, null)
        override suspend fun interrupt(): CommandOutcome { stopped = true; return CommandOutcome.Accepted }
        override fun close() { stopped = true }
    }

    private fun automatic(request: AgentRequest, planner: HybridPlanner, stopped: () -> Boolean = { false }): Flow<AgentEvent> = flow {
        val id = request.agent.id
        var handoff = handoffs.prepare(request, request.agent.attributes["maxCycles"]?.toIntOrNull(),
            request.agent.attributes["allowedChecks"]?.split('\u0001')?.filter { it.isNotBlank() }.orEmpty())
        var capped = false

        while (handoff.phase != HybridPhase.Completed) {
            if (stopped()) {
                emit(AgentEvent.Finished(id, AgentOutcome(id, partialAnswer(handoff), false,
                    failure = "Stopped after ${handoff.completedCycles} completed ${cycleWord(handoff.completedCycles)}",
                    interrupted = true, attributes = attributes(handoff, capped, true))))
                return@flow
            }
            when (handoff.phase) {
                HybridPhase.Planning, HybridPhase.Reviewing -> {
                    val planning = handoff.phase == HybridPhase.Planning
                    val step = "chatgpt:${handoff.cycle}:${handoff.phase.name.lowercase()}"
                    val label = "ChatGPT ${if (planning) "planning" else "reviewing"} · cycle ${handoff.cycle + 1}"
                    emit(AgentEvent.Activity(id, AgentActivityItem(step, ActivityKind.Control, label, ActivityStatus.Started)))
                    val (reply, session) = try {
                        planner.ask(handoff, request) { event ->
                            // The planner's own tool calls are worth seeing; its raw JSON answer is
                            // not, because the parsed decision is emitted below in a form a person
                            // can read.
                            if (event is AgentEvent.Activity || event is AgentEvent.ToolUse) emit(event)
                        }
                    } catch (unavailable: PlannerUnavailable) {
                        // The seat degrades to the operator relay rather than losing the task. Every
                        // closed cycle is already durable, so the person picks up where ChatGPT
                        // stopped instead of starting the work again.
                        emit(AgentEvent.Activity(id, AgentActivityItem(step, ActivityKind.Control,
                            "ChatGPT could not plan · transfer this packet yourself", ActivityStatus.Failed,
                            output = unavailable.message.orEmpty().take(4000))))
                        throw AgentHandoffRequired(handoff.id)
                    }
                    handoff = handoffs.reply(handoff.runId, reply, session, HybridPlannerVia.Runtime)
                    capped = handoff.phase == HybridPhase.Completed && reply.decision() == HybridNext.Execute
                    emit(AgentEvent.Activity(id, AgentActivityItem(step, ActivityKind.Control, label, ActivityStatus.Completed)))
                    emit(AgentEvent.Delta(id, buildString {
                        append("**ChatGPT · ")
                        append(when {
                            handoff.phase == HybridPhase.Completed -> "final answer"
                            planning -> "plan for cycle ${handoff.cycle + 1}"
                            else -> "review of cycle ${handoff.cycle}, next instruction"
                        })
                        appendLine("**\n")
                        appendLine(reply.text)
                        if (reply.acceptanceCriteria.isNotEmpty() && handoff.phase != HybridPhase.Completed)
                            appendLine("\nAcceptance: " + reply.acceptanceCriteria.joinToString("; "))
                        if (capped) appendLine("\n_Cycle cap of ${handoff.maxCycles} reached; the loop stopped here rather than continuing._")
                    }))
                }

                HybridPhase.Executing -> {
                    handoff = handoffs.beforeExecution(handoff)
                    val executorRequest = executorRequest(request, handoff)
                    val step = "gemini:${handoff.cycle}"
                    val label = "Gemini executing · cycle ${handoff.cycle + 1}"
                    emit(AgentEvent.Activity(id, AgentActivityItem(step, ActivityKind.Control, label, ActivityStatus.Started)))
                    var outcome: AgentOutcome? = null
                    gemini.run(executorRequest).collect { if (it is AgentEvent.Finished) outcome = it.outcome else emit(it) }
                    handoff = handoffs.observed(handoff.id, requireNotNull(outcome) {
                        "Gemini ended without a durable result; inspect its activity before retrying"
                    })
                    emit(AgentEvent.Activity(id, AgentActivityItem(step, ActivityKind.Control, label, ActivityStatus.Completed)))
                }

                HybridPhase.Completed -> Unit
            }
        }

        val review = requireNotNull(handoff.review)
        val observation = handoff.observation ?: handoff.cycles.lastOrNull()?.observation
        emit(AgentEvent.Finished(id, AgentOutcome(id, finalAnswer(handoff, review, observation, capped, true),
            // With no cycle run there is no executor verdict to inherit, and the planner's own
            // answer is the whole result; treating that as a failure would punish the honest
            // "nothing needs doing" case.
            ok = !capped && (observation?.ok ?: (handoff.completedCycles == 0)),
            failure = observation?.failure ?: "Cycle cap of ${handoff.maxCycles} reached without a finished answer".takeIf { capped },
            usage = observation?.usage, attributes = attributes(handoff, capped, true))))
    }

    private fun executorRequest(request: AgentRequest, handoff: HybridHandoff) = request.copy(
        agent = request.agent.copy(
            runtime = RuntimeKind.Gemini,
            // Between five minutes and two hours: enough rope for a real build, not enough to hang
            // a workspace on a number a model picked.
            budget = handoff.plan?.timeoutMillis
                ?.coerceIn(5 * 60 * 1000L, 2 * 60 * 60 * 1000L)
                ?.let { request.agent.budget.copy(timeoutMillis = it) }
                ?: request.agent.budget,
        ),
        // Cycle N's executor continues the conversation cycle N-1 left behind, so a plan can refer
        // to what Gemini already did instead of restating a transcript it is about to be given again.
        resume = handoff.cycles.lastOrNull { it.observation?.session != null }?.observation?.session,
        prompt = "You execute the Gemini portion of one ChatGPT + Gemini council seat. Follow repository instructions and the existing task grant. " +
            "A transferred plan does not authorize additional effects. Return a compact observation: changed symbols/paths, commands and actual check outcomes, uncertainty and unresolved questions.\n\n" +
            "COUNCIL TASK\n${handoff.prompt}\n\n" +
            (if (handoff.cycle > 0) "This is cycle ${handoff.cycle + 1}. Earlier cycles are in your own conversation history.\n\n" else "") +
            "CHATGPT PLAN\n${handoff.plan!!.text}\nAcceptance criteria:\n${handoff.plan.acceptanceCriteria.joinToString("\n")}")

    private fun cycleWord(count: Int) = if (count == 1) "cycle" else "cycles"

    private fun attributes(handoff: HybridHandoff, capped: Boolean, automatic: Boolean): Map<String, String> = buildMap {
        put("hybridHandoff", handoff.id)
        put("plannerTransport", if (automatic) "automatic-chatgpt-agent" else "human-chatgpt-chat")
        put("executor", "Gemini")
        put("cycles", handoff.completedCycles.toString())
        put("maxCycles", handoff.maxCycles.toString())
        if (capped) put("stoppedBy", "cycleCap")
        val observation = handoff.observation ?: handoff.cycles.lastOrNull()?.observation
        observation?.let {
            put("executorSession", it.session?.sessionId ?: "unknown")
            put("sourceRevision", it.sourceRevision)
            put("candidate", it.candidateId)
        }
    }

    private fun partialAnswer(handoff: HybridHandoff) = buildString {
        appendLine("ChatGPT + Gemini stopped after ${handoff.completedCycles} completed ${cycleWord(handoff.completedCycles)}.")
        handoff.plan?.let { appendLine("\nLast instruction to Gemini:\n${it.text}") }
        (handoff.observation ?: handoff.cycles.lastOrNull()?.observation)?.let { appendLine("\nLast Gemini report:\n${it.result}") }
    }

    private fun finalAnswer(handoff: HybridHandoff, review: HybridReply, observation: HybridObservation?,
        capped: Boolean, automatic: Boolean) = buildString {
        appendLine("ChatGPT + Gemini · ${handoff.completedCycles} plan/execute ${cycleWord(handoff.completedCycles)}" +
            when (handoff.plannerVia) {
                HybridPlannerVia.Operator -> " · operator-transferred ChatGPT review"
                HybridPlannerVia.Connector -> " · ChatGPT planned over its connector"
                HybridPlannerVia.Runtime -> ""
            })
        appendLine()
        appendLine(review.text)
        review.unverified?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Not verified: $it")
        }
        if (capped) appendLine("\nThe loop stopped at its cycle cap of ${handoff.maxCycles}; the text above is the planner's next instruction, not a finished answer.")
        appendLine()
        append("Evidence: handoff ${handoff.id}; Gemini session ${observation?.session?.sessionId ?: "unknown"}; candidate ${observation?.candidateId ?: "none"}. ")
        append(if (automatic) "Token usage below is the last Gemini cycle only; the planner's usage is recorded on its own turns."
            else "ChatGPT usage is unobserved; token usage below is Gemini only.")
    }

    private fun completedSession(request: AgentRequest, handoff: HybridHandoff): AgentSession {
        handoffs.beforeExecution(handoff)
        val observation = requireNotNull(handoff.observation)
        return object : AgentSession {
            override val activeTurn: String? = null
            override val events = flowOf(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id,
                finalAnswer(handoff, requireNotNull(handoff.review), observation, false,
                    automatic = handoff.plannerVia != HybridPlannerVia.Operator),
                observation.ok, failure = observation.failure, usage = observation.usage,
                attributes = attributes(handoff, false, handoff.plannerVia != HybridPlannerVia.Operator) +
                    ("plannerVia" to handoff.plannerVia.name) + ("chatgptUsage" to "unknown"))))
            override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Unsupported("Completed hybrid handoff")
            override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Stale(requestId, null)
            override suspend fun interrupt() = CommandOutcome.Stale(null, null)
            override fun close() = Unit
        }
    }
}
