package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.WorkflowContract

private fun AgentWorkspace.landingTask(graph: String, task: String, candidate: String, revision: Long, by: BoardActor): BoardTask {
    require(by.kind == BoardActorKind.Person && by.name.isNotBlank()) { "Only a person accepts or lands a graph candidate" }
    require(board.graph(graph).revision == revision) { "The graph changed; read its current revision" }
    val value = board.get(task)
    val plan = requireNotNull(value.graph?.takeIf { it.id == graph }) { "Task does not belong to this graph" }
    require(plan.state == TaskState.Succeeded && plan.outcome?.candidateId == candidate) { "The writing task has no settled outcome for this candidate" }
    require(plan.worktreeId != null && (plan.resolvedSeat?.seat ?: plan.seat)?.access == TaskAccess.Write) { "Only an isolated writing task can land" }
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
