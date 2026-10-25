package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.WorkflowContract

private fun AgentWorkspace.landingTask(graph: String, task: String, candidate: String, revision: Long, by: BoardActor): BoardTask {
    require(by.kind == BoardActorKind.Person && by.name.isNotBlank()) { "Only a person accepts or lands a graph candidate" }
    require(board.graph(graph).revision == revision) { "The graph changed; read its current revision" }
    val value = board.get(task)
    val plan = requireNotNull(value.graph?.takeIf { it.id == graph }) { "Task does not belong to this graph" }
    require(plan.state == TaskState.Succeeded && plan.outcome?.candidateId == candidate) { "The writing task has no settled outcome for this candidate" }
    require(plan.worktreeId != null && (plan.kind == TaskKind.Merge || (plan.resolvedSeat?.seat ?: plan.seat)?.access == TaskAccess.Write)) { "Only an isolated writing task can land" }
    val members = board.members(graph).associateBy { it.id }
    fun reaches(input: String): Boolean {
        val pending = ArrayDeque(listOf(input))
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (id == task) return true
            if (seen.add(id)) pending.addAll(members[id]?.graph?.dependsOn.orEmpty().map { it.task })
        }
        return false
    }
    require(members.values.any { review -> review.graph?.let {
        it.contract == WorkflowContract.Decision && it.state == TaskState.Succeeded &&
            it.outcome?.verdict == "pass" && it.outcome.candidateId == candidate && reaches(review.id)
    } == true }) { "A passing review Decision for this candidate is required" }
    require(captureCandidate(task).id == candidate) { "The task candidate changed" }
    return value
}

internal fun AgentWorkspace.acceptTaskCandidate(graph: String, task: String, candidate: String, revision: Long, by: BoardActor): TaskGraphView = admitOperation {
    landingTask(graph, task, candidate, revision, by)
    evidence.acceptPerson(task, candidate, by)
    board.advance(graph, task) { value, plan -> value.copy(revision = value.revision + 1,
        history = value.history + BoardEvent(System.currentTimeMillis(), by, "Accepted candidate $candidate"),
        graph = plan.copy(note = "Candidate $candidate accepted by ${by.name}")) }
    board.view(graph)
}

internal suspend fun AgentWorkspace.landTaskCandidate(graph: String, task: String, candidate: String, revision: Long, by: BoardActor): TaskGraphView {
    require(by.kind == BoardActorKind.Person) { "Only a person lands a graph candidate" }
    val current = board.get(task)
    if (current.stage == BoardStage.Done && current.graph?.id == graph && current.graph.outcome?.candidateId == candidate &&
        current.graph.note?.startsWith("Landed $candidate:") == true) return board.view(graph)
    val tree = requireNotNull(landingTask(graph, task, candidate, revision, by).graph?.worktreeId)
    return withWorktreeFiles(tree) {
        landingTask(graph, task, candidate, revision, by)
        val accepted = taskEvidence(task)
        require(accepted.acceptedCandidate == candidate && accepted.approverVerified) { "Accept this candidate through Hangar first" }
        evidence.acceptPerson(task, candidate, by)
        val review = worktrees.review(tree)
        val commits = worktrees.land(tree, review.patchDigest, review.sourceRevision, "Land $graph/$task: ${current.title}\n\nCandidate: $candidate\nAccepted by: ${by.name}")
        val detail = "Landed $candidate: " + commits.entries.joinToString { "${it.key} ${it.value}" }
        board.advance(graph, task) { value, plan -> value.copy(revision = value.revision + 1,
            history = value.history + BoardEvent(System.currentTimeMillis(), by, detail), graph = plan.copy(note = detail)) }
        worktrees.remove(tree)
        board.move(task, BoardStage.Done, by)
        board.view(graph)
    }
}

internal fun AgentWorkspace.resumeTaskAfterLimit(graph: String, task: String, revision: Long, attempt: Int, atReset: Boolean, by: BoardActor): TaskGraphView =
    admitOperation { synchronized(board) {
        require(by.kind == BoardActorKind.Person && by.name.isNotBlank()) { "Only a person schedules a usage-limit retry" }
        require(board.graph(graph).revision == revision) { "The graph changed; read its current revision" }
        require(board.graph(graph).status in setOf(TaskGraphStatus.Running, TaskGraphStatus.Paused)) { "The graph is closed" }
        val value = board.get(task)
        val plan = requireNotNull(value.graph?.takeIf { it.id == graph }) { "Task does not belong to this graph" }
        require(plan.kind == TaskKind.Agent && plan.state == TaskState.Limited && plan.attempt == attempt) { "The Limited attempt changed; inspect it again" }
        val run = get(requireNotNull(plan.runId))
        val target = submission(run.id).task
        require(run.status == AgentRunStatus.Limited && run.usageLimit == plan.outcome?.usageLimit &&
            target?.graph == graph && target.task == task && target.attempt == attempt) { "This run is not the Limited task attempt" }
        val reset = if (atReset) requireNotNull(run.usageLimit?.resetsAtEpochMillis) { "The provider did not report a reset time" } else null
        val detail = if (atReset) "${by.name} chose one retry at provider reset $reset; the graph must be running" else "${by.name} chose to keep waiting; scheduled retry cancelled"
        board.advance(graph, task) { current, currentPlan -> current.copy(revision = current.revision + 1,
            history = (current.history + BoardEvent(System.currentTimeMillis(), by, detail)).takeLast(50),
            graph = currentPlan.copy(resumeAtReset = reset, note = detail)) }
        board.view(graph)
    } }

internal fun AgentWorkspace.recoverTask(graph: String, task: String, revision: Long, attempt: Int, action: TaskRecoveryAction, by: BoardActor): TaskGraphView =
    admitOperation { synchronized(board) {
        require(by.kind == BoardActorKind.Person && by.name.isNotBlank()) { "Only a person reconciles an interrupted attempt" }
        require(board.graph(graph).revision == revision) { "The graph changed; read its current revision" }
        val value = board.get(task)
        val plan = requireNotNull(value.graph?.takeIf { it.id == graph }) { "Task does not belong to this graph" }
        require(plan.kind == TaskKind.Agent && plan.state == TaskState.Waiting && plan.recovery == AgentRecovery.NeedsReview &&
            plan.attempt == attempt) { "The interrupted attempt changed; inspect it again" }
        val run = get(requireNotNull(plan.runId))
        val target = submission(run.id).task
        require(run.status == AgentRunStatus.Interrupted && run.recovery == AgentRecovery.NeedsReview &&
            target?.graph == graph && target.task == task && target.attempt == attempt) { "This run is not the interrupted task attempt" }
        val writing = (plan.resolvedSeat?.seat ?: plan.seat)?.access == TaskAccess.Write
        val tree = plan.worktreeId?.takeIf { writing }
        val candidate = if (action == TaskRecoveryAction.KeepWork) {
            require(writing && plan.contract == WorkflowContract.Text && tree != null) { "Only an isolated writing task can keep work for review" }
            require(board.members(graph).any { review -> review.graph?.let {
                it.kind == TaskKind.Agent && it.contract == WorkflowContract.Decision && (it.resolvedSeat ?: it.seat?.let(::resolveSeat))?.seat?.access == TaskAccess.Read &&
                    it.dependsOn.any { dependency -> dependency.task == task }
            } == true }) { "Connect an independent review Decision after this task before keeping its work" }
            captureCandidate(task).also { require(it.complete) { "Candidate coverage is incomplete; keep the checkout" } }
        } else null
        val snapshot = if (action == TaskRecoveryAction.Abandon && tree != null)
            worktrees.retainSnapshot(tree).also { evidence.snapshot(task, it) } else null
        if (action == TaskRecoveryAction.Retry && writing) require(tree != null && worktrees.read(tree).roots.all { java.io.File(it.path).isDirectory }) { "The owned checkout is missing" }
        if (snapshot != null) worktrees.abandon(requireNotNull(tree), snapshot)
        reconcileRun(run.id, run.revision, action)
        val detail = when (action) {
            TaskRecoveryAction.Retry -> "Retry requested by ${by.name}; reuse the owned checkout"
            TaskRecoveryAction.KeepWork -> "Work kept by ${by.name} as candidate ${candidate?.id}; native outcome remains unknown; independent review and checks are required"
            TaskRecoveryAction.Abandon -> "Abandoned by ${by.name}; " + (snapshot?.let { "recoverable snapshot ${it.id}" } ?: "no owned writing checkout to remove")
        }
        val now = System.currentTimeMillis()
        board.advance(graph, task) { current, currentPlan ->
            current.copy(revision = current.revision + 1,
                stage = if (action == TaskRecoveryAction.KeepWork) BoardStage.Review else current.stage,
                history = (current.history + BoardEvent(now, by, detail)).takeLast(50),
                graph = currentPlan.copy(recovery = AgentRecovery.None, note = detail,
                    state = when (action) {
                        TaskRecoveryAction.Retry -> TaskState.Pending
                        TaskRecoveryAction.KeepWork -> TaskState.Succeeded
                        TaskRecoveryAction.Abandon -> TaskState.Cancelled
                    },
                    readySince = null,
                    worktreeId = currentPlan.worktreeId.takeUnless { action == TaskRecoveryAction.Abandon && writing },
                    outcome = if (candidate == null) currentPlan.outcome else (currentPlan.outcome ?: TaskOutcome()).copy(
                        summary = detail, candidateId = candidate.id, files = candidate.changedFiles, at = now, attempt = attempt, questions = emptyList())))
        }
        if (candidate != null) board.appendHandoff(TaskHandoff(graph, task, attempt,
            goal = value.intent.ifBlank { value.title }.take(4000), done = detail, candidate = candidate.id,
            changedPaths = candidate.changedFiles.take(100), sessionLogs = listOfNotNull(plan.outcome?.log)), by)
        board.view(graph)
    } }
