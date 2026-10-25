package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TaskGraphChoicesTest {
    private val person = BoardActor(BoardActorKind.Person, "fixture")
    private val agent = BoardActor(BoardActorKind.Agent, "fixture", "codex:fixture")
    private val writers = listOf(TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write), TaskSeat(RuntimeKind.ClaudeCode, access = TaskAccess.Write))
    private val panel = TaskSeat(RuntimeKind.ClaudeCode, lenses = listOf("codex-correctness", "claude-security"))

    private fun draft(chooseTop: Boolean = false) = TaskGraphDraft("Alternatives", tasks = listOf(
        TaskGraphOp.Add("Solve", key = "solve", scope = listOf("Source.kt"), alternatives = TaskAlternatives(writers, "compile", panel)),
        TaskGraphOp.Add("Continue", kind = TaskKind.Gate, dependsOn = listOf(TaskDependency("solve")))
    ), envelope = TaskEnvelope(chooseTop = chooseTop))

    private fun fixture(body: (AgentWorkspace, File) -> Unit) {
        val directory = Files.createTempDirectory("task-choices-").toFile().canonicalFile
        val root = File(directory, "project").apply { mkdir() }
        try {
            File(root, "Source.kt").writeText("val baseline = 0\n")
            listOf(listOf("init", "--quiet"), listOf("add", "."), listOf("-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Base")).forEach { args ->
                val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { output }
            }
            val runtimes = listOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode).associateWith { kind ->
                object : AgentRuntime {
                    override val kind = kind
                    override fun run(request: AgentRequest) = emptyFlow<AgentEvent>()
                }
            }
            AgentWorkspace(root, File(directory, "data").toPath(), runtimes, discover = { ProviderCapability(it) }).use { body(it, root) }
        } finally { directory.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun prepare(owner: AgentWorkspace, root: File, chooseTop: Boolean = false): Pair<TaskGraphView, BoardTask> {
        val view = owner.board.propose(draft(chooseTop), person)
        owner.board.start(view.graph.id, person, view.graph.revision)
        val base = owner.worktrees.freeze(view.graph.id)
        owner.board.freezeBase(view.graph.id, base)
        val selection = view.tasks.single { it.graph?.choices?.isNotEmpty() == true }
        selection.graph!!.choices.entries.forEachIndexed { index, (writer, review) ->
            val tree = owner.worktrees.prepareFrom(writer, "fixture", base, emptyList())
            File(tree.roots.single().path, "Source.kt").writeText("val alternative = ${index + 1}\n")
            owner.board.advance(view.graph.id, writer) { value, plan -> value.copy(graph = plan.copy(state = TaskState.Succeeded, attempt = 1, worktreeId = tree.id)) }
            val candidate = owner.captureCandidate(writer)
            owner.board.advance(view.graph.id, writer) { value, plan -> value.copy(graph = plan.copy(outcome = TaskOutcome(attempt = 1, candidateId = candidate.id))) }
            owner.evidence.requireChecks(writer, listOf("compile"))
            owner.evidence.check(writer, AgentCandidateCheck("compile", candidate.id,
                CheckResult(listOf("compile"), CheckOutcome.Passed, revision = candidate.id, coverage = CheckCoverage.Complete), "kernel"))
            val check = owner.board.get(review).graph!!.dependsOn.single().task
            owner.board.advance(view.graph.id, check) { value, plan -> value.copy(graph = plan.copy(state = TaskState.Succeeded, attempt = 1,
                outcome = TaskOutcome(attempt = 1, candidateId = candidate.id, checks = listOf(TaskCheckResult("compile", true, "compile"))))) }
            owner.board.advance(view.graph.id, review) { value, plan -> value.copy(graph = plan.copy(state = TaskState.Succeeded, attempt = 1,
                resolvedSeat = owner.resolveSeat(plan.seat!!), outcome = TaskOutcome(attempt = 1, candidateId = candidate.id, verdict = "pass"))) }
        }
        owner.board.advance(view.graph.id, selection.id) { value, plan -> value.copy(graph = plan.copy(state = TaskState.Waiting)) }
        assertEquals("val baseline = 0\n", File(root, "Source.kt").readText())
        return owner.board.view(view.graph.id) to owner.board.get(selection.id)
    }

    @Test fun expandsIntoOrdinaryIsolatedWritersChecksPanelsAndOneSelection() = fixture { owner, _ ->
        val view = owner.board.propose(draft(), agent)
        assertEquals(8, view.tasks.size)
        val selection = view.tasks.single { it.graph?.choices?.isNotEmpty() == true }
        assertEquals(2, selection.graph!!.choices.size)
        assertEquals(listOf(selection.id), view.tasks.single { it.title == "Continue" }.graph!!.dependsOn.map { it.task })
        assertEquals(setOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode), view.graph.envelope.agents.toSet())
        assertEquals(listOf("Source.kt"), view.graph.envelope.writes)
        assertEquals(TaskGraphStatus.Proposed, view.graph.status)
        assertFalse(view.graph.envelope.chooseTop)
        assertTrue(view.choices.getValue(selection.id).all { !it.eligible })
    }

    @Test fun onlyPersonCanEnableAutomaticChoiceEvenWithFullAccess() = fixture { owner, _ ->
        val view = owner.board.propose(draft(), agent)
        assertFails { owner.board.patch(view.graph.id, TaskGraphPatch(listOf(TaskGraphOp.Envelope(view.graph.envelope.copy(chooseTop = true))), "Choose automatically", view.graph.revision), TaskGraphCaller(agent, lead = true)) }
        assertFalse(owner.board.graph(view.graph.id).envelope.chooseTop)
    }

    @Test fun rejectsInvalidBranchCountsAndWriteReviewPanels() = fixture { owner, _ ->
        val add = draft().tasks.first()
        listOf(add.copy(alternatives = TaskAlternatives(writers.take(1), "compile", panel)),
            add.copy(alternatives = TaskAlternatives(writers, "compile", panel.copy(access = TaskAccess.Write)))).forEach { invalid ->
            assertFails { owner.board.propose(TaskGraphDraft("Invalid", tasks = listOf(invalid)), person) }
        }
    }

    @Test fun exactChoiceFeedsOnlyTheChosenSourceAndDoesNotAcceptIt() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root)
        val ranks = view.choices.getValue(selection.id)
        assertTrue(ranks.all { it.eligible }, ranks.toString())
        assertEquals(ranks.map { it.task }.sorted(), ranks.map { it.task })
        val chosen = ranks.last()
        owner.reportTask(view.graph.id, selection.id, TaskReport.Succeeded,
            TaskOutcome(selectedTask = chosen.task, candidateId = chosen.candidate), TaskGraphCaller(person))
        val downstream = view.tasks.single { it.title == "Continue" }
        assertEquals(listOf(chosen.task), owner.board.sourceInputs(downstream.id).map { it.id })
        assertEquals("selected", owner.board.get(selection.id).graph!!.outcome!!.verdict)
        assertNull(owner.evidence.get(chosen.task).acceptedCandidate)
        assertEquals(2, selection.graph!!.choices.keys.flatMap { owner.worktrees.list(it) }.size)
        assertFails { owner.reportTask(view.graph.id, selection.id, TaskReport.Succeeded,
            TaskOutcome(selectedTask = ranks.first().task, candidateId = ranks.first().candidate), TaskGraphCaller(person)) }
        val reopened = AgentBoardStore(File(root.parentFile, "data/board").toPath())
        assertEquals(listOf(chosen.task), reopened.sourceInputs(downstream.id).map { it.id })
    }

    @Test fun onlyTheSelectedAlternativeCanBeAccepted() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root)
        val ranks = view.choices.getValue(selection.id)
        ranks.forEach { rank -> assertFails { owner.acceptTaskCandidate(view.graph.id, rank.task, rank.candidate!!, owner.board.graph(view.graph.id).revision, person) } }
        val chosen = ranks.first()
        owner.reportTask(view.graph.id, selection.id, TaskReport.Succeeded,
            TaskOutcome(selectedTask = chosen.task, candidateId = chosen.candidate), TaskGraphCaller(person))
        val other = ranks.last()
        assertFails { owner.acceptTaskCandidate(view.graph.id, other.task, other.candidate!!, owner.board.graph(view.graph.id).revision, person) }
        owner.acceptTaskCandidate(view.graph.id, chosen.task, chosen.candidate!!, owner.board.graph(view.graph.id).revision, person)
        assertEquals(chosen.candidate, owner.evidence.get(chosen.task).acceptedCandidate)
        assertNull(owner.evidence.get(other.task).acceptedCandidate)
    }

    @Test fun staleSourceAndStalePanelCannotBeSelected() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root)
        val chosen = view.choices.getValue(selection.id).first()
        assertFails { owner.reportTask(view.graph.id, selection.id, TaskReport.Succeeded,
            TaskOutcome(selectedTask = chosen.task, candidateId = "old"), TaskGraphCaller(person)) }
        val writer = owner.board.get(chosen.task).graph!!
        val tree = owner.worktrees.read(writer.worktreeId!!)
        File(tree.roots.single().path, "Source.kt").writeText("val changedAfterReview = true\n")
        assertFails { owner.reportTask(view.graph.id, selection.id, TaskReport.Succeeded,
            TaskOutcome(selectedTask = chosen.task, candidateId = chosen.candidate), TaskGraphCaller(person)) }
        assertEquals(TaskState.Waiting, owner.board.get(selection.id).graph!!.state)
        val review = selection.graph!!.choices.getValue(chosen.task)
        owner.board.advance(view.graph.id, review) { value, plan -> value.copy(graph = plan.copy(outcome = plan.outcome!!.copy(candidateId = "old"))) }
        assertFalse(owner.board.view(view.graph.id).choices.getValue(selection.id).first { it.task == chosen.task }.eligible)
    }

    @Test fun agentsAndDirectBoardReportsCannotBypassEvidence() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root)
        val rank = view.choices.getValue(selection.id).first()
        val outcome = TaskOutcome(selectedTask = rank.task, candidateId = rank.candidate)
        assertFails { owner.reportTask(view.graph.id, selection.id, TaskReport.Succeeded, outcome, TaskGraphCaller(agent)) }
        assertFails { owner.board.report(view.graph.id, selection.id, TaskReport.Succeeded, outcome, TaskGraphCaller(person)) }
        assertFails { owner.selectTask(view.graph.id, selection.id, null, null, BoardActor(BoardActorKind.Schedule, "coordinator")) }
    }

    @Test fun explicitAutomaticChoicePreservesCoordinatorProvenance() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root, chooseTop = true)
        val by = BoardActor(BoardActorKind.Schedule, "Task graph ${view.graph.id}")
        val selected = owner.selectTask(view.graph.id, selection.id, null, null, by)
        assertEquals(view.choices.getValue(selection.id).first().task, selected.graph!!.outcome!!.selectedTask)
        assertEquals(by, selected.history.last().by)
        assertNull(owner.evidence.get(selected.graph!!.outcome!!.selectedTask!!).acceptedCandidate)
    }

    @Test fun failedAlternativeSettlesWithoutBlockingAChosenSuccessfulBranch() = fixture { owner, _ ->
        val view = owner.board.propose(draft(), person)
        val selection = view.tasks.single { it.graph?.choices?.isNotEmpty() == true }
        val writer = selection.graph!!.choices.keys.first()
        val members = view.tasks.associateBy { it.id }.toMutableMap()
        fun state(id: String, state: TaskState) { members[id] = members.getValue(id).let { it.copy(graph = it.graph!!.copy(state = state)) } }
        state(writer, TaskState.Failed)
        val review = selection.graph!!.choices.getValue(writer)
        val check = members.getValue(review).graph!!.dependsOn.single().task
        assertEquals(TaskGraphRules.Readiness.Skip, TaskGraphRules.readiness(members.getValue(check), members))
        state(check, TaskState.Skipped)
        assertEquals(TaskGraphRules.Readiness.Skip, TaskGraphRules.readiness(members.getValue(review), members))
        members.keys.filter { it != writer }.forEach { state(it, TaskState.Succeeded) }
        assertEquals(TaskGraphStatus.Finished, TaskGraphRules.finished(members))
        state(view.tasks.single { it.title == "Continue" }.id, TaskState.Failed)
        assertEquals(TaskGraphStatus.Failed, TaskGraphRules.finished(members))
    }

    @Test fun rankingUsesConfirmedFindingsAndKeepsUnknownDiffSizeVisible() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root)
        val members = view.tasks.associateBy { it.id }
        val writer = selection.graph!!.choices.keys.first()
        val summary = view.summaries.getValue(writer)
        val finding = AgentFinding("finding", summary.candidate!!, "review", "verifier", "Issue", "Verified issue", "medium",
            confirmation = AgentFindingConfirmation("review", "verifier", "Confirmed"))
        val ranked = TaskChoices.rank(selection, members, view.summaries + (writer to summary.copy(findings = listOf(finding))))
        assertEquals(writer, ranked.last().task)
        assertEquals(1, ranked.last().confirmed)
        assertEquals(10, ranked.last().severity)
        val unknown = TaskChoices.rank(selection, members, view.summaries + (writer to summary.copy(added = null,
            limitations = listOf("Candidate diff unavailable; line counts unknown"))))
        assertEquals(writer, unknown.last().task)
        assertNull(unknown.last().lines)
        assertTrue(unknown.last().limitations.isNotEmpty())
        val receipt = summary.checks.single()
        val incomplete = TaskChoices.rank(selection, members, view.summaries + (writer to summary.copy(checks = listOf(
            receipt.copy(result = receipt.result.copy(coverage = CheckCoverage.Unparsed("fixture")))))))
        assertFalse(incomplete.single { it.task == writer }.eligible)
    }

    @Test fun downstreamRefusesASelectionWhoseWriterChangedAfterChoice() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root)
        val rank = view.choices.getValue(selection.id).first()
        owner.reportTask(view.graph.id, selection.id, TaskReport.Succeeded,
            TaskOutcome(selectedTask = rank.task, candidateId = rank.candidate), TaskGraphCaller(person))
        owner.board.advance(view.graph.id, rank.task) { value, plan -> value.copy(graph = plan.copy(
            outcome = plan.outcome!!.copy(candidateId = "changed"))) }
        assertFails { owner.board.sourceInputs(view.tasks.single { it.title == "Continue" }.id) }
    }

    @Test fun nativeOwnerBridgeRetainsExactSelection() = fixture { owner, root ->
        val (view, selection) = prepare(owner, root)
        val rank = view.choices.getValue(selection.id).last()
        AgentWorkspaceConnection.open(root, File(root.parentFile, "data").toPath(), runtimes = emptyMap(),
            discover = { ProviderCapability(it) }).use { connection -> kotlinx.coroutines.runBlocking {
            val selected = connection.reportTaskGraph(view.graph.id, selection.id, TaskReport.Succeeded,
                TaskOutcome(selectedTask = rank.task, candidateId = rank.candidate, attempt = selection.graph!!.attempt), person)
            assertEquals(rank.task, selected.graph!!.outcome!!.selectedTask)
            assertEquals(rank.candidate, selected.graph!!.outcome!!.candidateId)
            assertEquals(rank.task, connection.taskGraph(view.graph.id).tasks.single { it.id == selection.id }.graph!!.outcome!!.selectedTask)
        } }
    }

    @Test fun removingPendingAlternativesRetainsTheGroupAndCanBeUndone() = fixture { owner, _ ->
        val view = owner.board.propose(draft(), person)
        val selection = view.tasks.single { it.graph?.choices?.isNotEmpty() == true }
        val removed = owner.board.patch(view.graph.id, TaskGraphPatch(listOf(TaskGraphOp.Remove(selection.id)), "Cancel", view.graph.revision), TaskGraphCaller(person))
        assertEquals(TaskState.Cancelled, owner.board.get(selection.id).graph!!.state)
        assertEquals(8, owner.board.members(view.graph.id).size)
        assertTrue(owner.board.members(view.graph.id).filter { it.title != "Continue" }.all { it.graph?.state == TaskState.Cancelled })
        owner.board.patch(view.graph.id, TaskGraphPatch(removed.undo, "Revert", removed.graph.revision), TaskGraphCaller(person))
        assertEquals(TaskState.Pending, owner.board.get(selection.id).graph!!.state)
        assertEquals(2, owner.board.get(selection.id).graph!!.choices.size)
    }
}
