package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.WorkflowContract
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class TaskGraphTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val lead = BoardActor(BoardActorKind.Agent, "claude", "claude:lead-session")
    private val codex = BoardActor(BoardActorKind.Agent, "codex", "codex:worker-session")
    private val stranger = BoardActor(BoardActorKind.Agent, "agy", "agy:other")
    private val temporary = File(System.getProperty("java.io.tmpdir"))
    private val made = mutableListOf<Path>()
    private val clock = AtomicLong(1_000)

    private fun store(): AgentBoardStore = AgentBoardStore(Files.createTempDirectory("taskgraph").also(made::add), clock::incrementAndGet)

    @AfterTest fun clean() = made.forEach { it.toFile().deleteTreeSafely(within = temporary) }

    private val build = TaskGraphOp.Add("Build the parser", key = "build", acceptance = listOf("Parses the sample"), scope = listOf(":engine"),
        seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write))
    private val review = TaskGraphOp.Add("Review the parser", key = "review", seat = TaskSeat(RuntimeKind.ClaudeCode), contract = WorkflowContract.Decision,
        dependsOn = listOf(TaskDependency("build")))
    private val check = TaskGraphOp.Add("Run engine checks", key = "check", kind = TaskKind.Check, check = "engine-tests", dependsOn = listOf(TaskDependency("build")))
    private val land = TaskGraphOp.Add("Land it", key = "land", kind = TaskKind.Gate,
        dependsOn = listOf(TaskDependency("review", TaskCondition.Pass), TaskDependency("check")))

    private fun proposed(store: AgentBoardStore) = store.propose(TaskGraphDraft("Parser", "A parser for the sample", listOf(build, review, check, land)), lead)

    private fun caller(store: AgentBoardStore, by: BoardActor, own: Set<String> = emptySet()) =
        TaskGraphCaller(by, lead = store.graph("G1").lead == by.session, own = own)

    @Test fun aProposalBecomesPlannedBoardTasksWithAnEnvelopeAroundThem() {
        val store = store()
        val view = proposed(store)
        assertEquals(TaskGraphStatus.Proposed, view.graph.status)
        assertEquals("claude:lead-session", view.graph.lead)
        assertEquals(listOf("T1", "T2", "T3", "T4"), view.tasks.map { it.id })
        assertTrue(view.tasks.all { it.stage == BoardStage.Planning && it.graph?.id == "G1" })
        assertEquals(listOf(TaskDependency("T2", TaskCondition.Pass), TaskDependency("T3")), store.get("T4").graph?.dependsOn)
        assertEquals(listOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode), view.graph.envelope.agents)
        assertEquals(listOf(":engine"), view.graph.envelope.writes)
        assertEquals("Waiting on T1", store.get("T2").blockedBy(view.tasks.associateBy { it.id }))
        val text = TaskGraphText.render(store.view("G1"))
        assertTrue("G1 · Parser · Proposed · revision 1" in text)
        assertTrue("T4 Land it · Gate · Pending · after T2 if Pass, T3" in text)
        assertEquals(2, proposed(store).graph.number)
    }

    @Test fun changesInsideTheEnvelopeApplyAndOutsideItWaitForAPerson() {
        val store = store()
        proposed(store)
        val revision = store.graph("G1").revision
        val split = TaskGraphPatch(listOf(TaskGraphOp.Add("Write the parser tests", parent = "T1", seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write), scope = listOf(":engine/src/test"))),
            "Tests deserve their own task", revision)
        val applied = store.patch("G1", split, caller(store, codex, setOf("T1")))
        assertEquals(listOf("T5"), applied.tasks.map { it.id })
        assertEquals("codex:worker-session", store.get("T5").createdBy.session)
        val widening = TaskGraphPatch(listOf(TaskGraphOp.Seat("T2", TaskSeat(RuntimeKind.Gemini))), "Antigravity reviews instead", store.graph("G1").revision)
        val outside = assertFailsWith<TaskGraphOutside> { store.patch("G1", widening, caller(store, lead)) }
        assertTrue(outside.reasons.single().startsWith("Gemini is not one of the envelope's agents"))
        assertEquals(outside.reasons, store.outside("G1", widening, caller(store, lead)))
        store.patch("G1", widening, caller(store, lead), approvedBy = "owner")
        assertEquals(RuntimeKind.Gemini, store.get("T2").graph?.seat?.agent)
        assertTrue(store.graph("G1").changes.last().summary.last().startsWith("Outside the envelope, allowed by owner: Gemini is not one"))
        val writing = TaskGraphPatch(listOf(TaskGraphOp.Add("Touch the kernel", parent = "T1", scope = listOf(":kernel"), seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write))),
            "Needs a kernel hook", store.graph("G1").revision)
        assertTrue(assertFailsWith<TaskGraphOutside> { store.patch("G1", writing, caller(store, codex, setOf("T1"))) }.reasons.single().startsWith(":kernel is outside"))
        assertFailsWith<IllegalArgumentException> { store.patch("G1", widening.copy(expectedRevision = store.graph("G1").revision), caller(store, codex, setOf("T1"))) }
        assertFailsWith<IllegalArgumentException> { store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Add("Mine", parent = "T2")), "x", store.graph("G1").revision), caller(store, stranger)) }
        assertFailsWith<IllegalArgumentException> { store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Add("Mine")), "x"), caller(store, codex, setOf("T1"))) }
    }

    @Test fun aChangeBasedOnAnOldRevisionFailsWhereSomeoneElseChangedTheTask() {
        val store = store()
        proposed(store)
        val read = store.graph("G1").revision
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Update("T1", intent = "Use the new grammar")), "", null), TaskGraphCaller(person))
        val stale = TaskGraphPatch(listOf(TaskGraphOp.Update("T1", intent = "Use the old grammar")), "My plan", read)
        assertFailsWith<TaskGraphConflict> { store.patch("G1", stale, caller(store, codex, setOf("T1"))) }
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Update("T2", intent = "Look at errors first")), "Errors matter", read), caller(store, lead))
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Add("Document the parser", acceptance = listOf("Every rule has an example"), seat = TaskSeat(RuntimeKind.Codex))), ""), TaskGraphCaller(person))
        assertFailsWith<IllegalArgumentException> { store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Update("T5", acceptance = listOf("Anything"))), "Easier", store.graph("G1").revision), caller(store, codex, setOf("T5"))) }
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Update("T5", intent = "Start from the grammar file")), "Found the source", store.graph("G1").revision), caller(store, codex, setOf("T5")))
        assertEquals("Start from the grammar file", store.get("T5").intent)
        assertFailsWith<IllegalArgumentException> { store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Update("T5", title = "Lead's now")), "x", store.graph("G1").revision), caller(store, lead)) }
        assertFailsWith<IllegalArgumentException> { store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Update("T1", title = "Mine now")), "x"), caller(store, lead)) }
    }

    @Test fun cyclesAreRefusedAndStartedTasksAreCancelledNotRemoved() {
        val store = store()
        proposed(store)
        assertTrue(assertFailsWith<IllegalStateException> { store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Connect("T4", "T1")), "loop"), TaskGraphCaller(person)) }
            .message!!.startsWith("This change would make a cycle"))
        store.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(state = TaskState.Running)) }
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Remove("T1")), "Not needed"), TaskGraphCaller(person))
        assertEquals(TaskState.Cancelled, store.get("T1").graph?.state)
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Remove("T3")), "Checks later"), TaskGraphCaller(person))
        assertFailsWith<IllegalStateException> { store.get("T3") }
        assertEquals(listOf(TaskDependency("T2", TaskCondition.Pass)), store.get("T4").graph?.dependsOn)
        val removal = store.graph("G1").changes.last()
        store.revert("G1", removal.revision, person)
        val restored = store.members("G1").single { it.title == "Run engine checks" }
        assertTrue(store.get("T4").graph!!.dependsOn.any { it.task == restored.id })
    }

    @Test fun readinessWaitsForEveryDependencyAndFollowsDecisionBranches() {
        val store = store()
        proposed(store)
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Add("Fix what review found", key = "fix", seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write), scope = listOf(":engine"),
            dependsOn = listOf(TaskDependency("T2", TaskCondition.Repair)))), "Repair branch"), TaskGraphCaller(person))
        fun ready() = TaskGraphRules.ready(store.members("G1").associateBy { it.id }).map { it.id }
        assertEquals(listOf("T1"), ready())
        fun settle(id: String, state: TaskState, verdict: String? = null) =
            store.advance("G1", id) { task, plan -> task.copy(graph = plan.copy(state = state, outcome = TaskOutcome(verdict = verdict))) }
        settle("T1", TaskState.Succeeded)
        assertEquals(setOf("T2", "T3"), ready().toSet())
        settle("T2", TaskState.Succeeded, "pass")
        assertEquals(listOf("T3"), ready())
        val members = store.members("G1").associateBy { it.id }
        assertEquals(TaskGraphRules.Readiness.Skip, TaskGraphRules.readiness(members.getValue("T5"), members))
        settle("T3", TaskState.Failed)
        assertEquals(listOf("T4"), ready())
        assertTrue(TaskGraphRules.unhandledFailure(store.get("T3"), store.members("G1").associateBy { it.id }))
    }

    @Test fun onlyAPersonStartsAGraphAndOnlyAPersonPassesAGate() {
        val store = store()
        proposed(store)
        assertFailsWith<IllegalArgumentException> { store.start("G1", lead) }
        assertFailsWith<IllegalArgumentException> { store.start("G1", person, expected = 0) }
        val started = store.start("G1", person, store.graph("G1").revision)
        assertEquals(TaskGraphStatus.Running, started.status)
        assertEquals(started.revision, started.approvedRevision)
        store.patch("G1", TaskGraphPatch(listOf(TaskGraphOp.Update("T1", intent = "More")), ""), TaskGraphCaller(person))
        assertEquals(1, store.graph("G1").changedSinceApproval)
        store.advance("G1", "T4") { task, plan -> task.copy(graph = plan.copy(state = TaskState.Waiting)) }
        assertFailsWith<IllegalArgumentException> { store.report("G1", "T4", TaskReport.Succeeded, TaskOutcome(), caller(store, lead)) }
        assertEquals("accepted", store.report("G1", "T4", TaskReport.Succeeded, TaskOutcome(summary = "Ship it"), TaskGraphCaller(person)).graph?.outcome?.verdict)
        assertFailsWith<IllegalArgumentException> { store.pause("G1", caller(store, codex, setOf("T1")), "stop") }
        assertEquals(TaskGraphStatus.Paused, store.pause("G1", caller(store, lead), "Wait for me").status)
        assertEquals(TaskGraphStatus.Cancelled, store.cancel("G1", person).status)
        assertTrue(store.members("G1").none { it.graph?.state in TaskGraphRules.Open })
    }

    @Test fun anAgentReportsOnlyOnItsOwnTaskAndTheLeadClaimsItsTasks() {
        val store = store()
        store.propose(TaskGraphDraft("Lead work", tasks = listOf(TaskGraphOp.Add("Explore", key = "a", seat = TaskSeat(lead = true)),
            TaskGraphOp.Add("Write", seat = TaskSeat(RuntimeKind.Codex), dependsOn = listOf(TaskDependency("a"))))), lead)
        store.start("G1", person)
        val claimed = store.claim("G1", "T1", caller(store, lead))
        assertEquals(TaskState.Running, claimed.graph?.state)
        assertEquals(listOf("claude:lead-session"), claimed.work)
        assertFailsWith<IllegalArgumentException> { store.claim("G1", "T2", caller(store, lead)) }
        assertFailsWith<IllegalStateException> { store.report("G1", "T1", TaskReport.Review, TaskOutcome(), caller(store, codex)) }
        val reported = store.report("G1", "T1", TaskReport.Review, TaskOutcome(summary = "Mapped the code"), caller(store, lead))
        assertEquals(TaskState.Succeeded, reported.graph?.state)
        assertEquals(BoardStage.Review, reported.stage)
        assertEquals(listOf("T2"), TaskGraphRules.ready(store.members("G1").associateBy { it.id }).map { it.id })
    }
}
