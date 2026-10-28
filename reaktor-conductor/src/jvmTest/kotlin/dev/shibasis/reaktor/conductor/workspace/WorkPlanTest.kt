package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessCheck
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import dev.shibasis.reaktor.conductor.worklog.PlanState
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class WorkPlanTest {
    private val person = BoardActor(BoardActorKind.Person, "owner (Hangar)")
    private val claude = BoardActor(BoardActorKind.Agent, "claude-code", "claude:s1")
    private val codex = BoardActor(BoardActorKind.Agent, "codex", "codex:c1")
    private val temporary = File(System.getProperty("java.io.tmpdir"))
    private val made = mutableListOf<Path>()

    private fun directory(name: String): Path = Files.createTempDirectory(name).also(made::add)

    @AfterTest fun clean() = made.forEach { it.toFile().deleteTreeSafely(within = temporary) }

    private fun draw(store: WorkPlanStore) = store.write("claude:s1", PlanPatch(title = "Unified agent work", ops = listOf(
        PlanOp.Add("Plan model", key = "model", refs = listOf("module:reaktor-conductor", "file:reaktor-conductor/src/WorkPlan.kt")),
        PlanOp.Add("Agent tools", key = "tools", after = listOf("model")),
        PlanOp.Add("Hangar views", key = "views", after = listOf("tools")),
        PlanOp.Add("Kanban lanes", key = "kanban", parent = "views"),
    )), claude)

    @Test fun anAgentDrawsItsPlanAndKeepsItsChecklist() {
        val store = WorkPlanStore(directory("plan-draw"))
        val drawn = draw(store)
        assertEquals(emptyList(), drawn.refused)
        assertEquals(listOf("model", "tools", "views", "kanban"), drawn.plan.items.map { it.id })
        assertEquals(1, drawn.plan.revision)
        assertEquals(setOf("kanban", "views"), drawn.plan.under("views"))
        val moved = store.write("claude:s1", PlanPatch(base = 1, ops = listOf(PlanOp.Status("model", PlanStatus.Done), PlanOp.Status("tools", PlanStatus.Doing))), claude)
        assertEquals(listOf("model → Done", "tools → In progress"), moved.plan.changes.last().summary)
        assertEquals(PlanStatus.Doing, WorkPlanStore(made.last()).plan("claude:s1")?.item("tools")?.status)
    }

    @Test fun aStaleWriteIsRefusedOnlyForTheItemSomeoneElseChanged() {
        val store = WorkPlanStore(directory("plan-stale"))
        draw(store)
        store.write("claude:s1", PlanPatch(base = 1, ops = listOf(PlanOp.Status("views", PlanStatus.Dropped))), person)
        val stale = store.write("claude:s1", PlanPatch(base = 1, ops = listOf(
            PlanOp.Status("views", PlanStatus.Doing), PlanOp.Status("model", PlanStatus.Done), PlanOp.Add("Docs", after = listOf("tools")),
        )), claude)
        assertEquals(listOf(0), stale.refused.map { it.op })
        assertContains(stale.refused.single().reason, "owner (Hangar)")
        assertEquals(PlanStatus.Dropped, stale.plan.item("views")?.status)
        assertEquals(PlanStatus.Done, stale.plan.item("model")?.status)
        assertEquals("p1", stale.plan.items.last().id)
        assertEquals(listOf("views → Won't do"), stale.news.single().summary)
    }

    @Test fun cyclesAreRefusedWherePartsAndDependenciesMeet() {
        val store = WorkPlanStore(directory("plan-cycles"))
        draw(store)
        val refused = store.write("claude:s1", PlanPatch(ops = listOf(
            PlanOp.Connect("views", "model"), PlanOp.Update("views", parent = "kanban"), PlanOp.Add("Bad link", refs = listOf("C:\\path")),
        )), claude).refused
        assertEquals(3, refused.size)
        assertContains(refused[0].reason, "cycle")
        assertContains(refused[1].reason, "own part")
        assertContains(refused[2].reason, "not a link")
    }

    @Test fun aSeatKeepsAnAttachedAgentInsideItsItemsAndOthersOutOfThem() {
        val store = WorkPlanStore(directory("plan-seat"))
        draw(store)
        val (_, seat) = store.attach("claude:s1") { PlanSeat("s1", "critic", listOf("views"), person, 2_000) }
        val inside = store.write("claude:s1", PlanPatch(seat = seat.id, ops = listOf(
            PlanOp.Status("kanban", PlanStatus.Doing), PlanOp.Add("DAG frames", key = "frames", parent = "views"), PlanOp.Status("model", PlanStatus.Done),
        )), codex)
        assertEquals(listOf(2), inside.refused.map { it.op })
        assertEquals("codex:c1", inside.plan.seat("s1")?.session)
        val stranger = store.write("claude:s1", PlanPatch(ops = listOf(PlanOp.Status("frames", PlanStatus.Done))),
            BoardActor(BoardActorKind.Agent, "gemini", "agy:g1"))
        assertContains(stranger.refused.single().reason, "seat s1")
        assertEquals(emptyList(), store.write("claude:s1", PlanPatch(ops = listOf(PlanOp.Status("frames", PlanStatus.Done))), claude).refused)
        assertFailsWith<IllegalArgumentException> { store.attach("claude:s1") { PlanSeat("s2", "critic", listOf("model"), codex, 3_000) } }
    }

    @Test fun anAgentStartsAPlanOnlyForItsOwnThread() {
        val store = WorkPlanStore(directory("plan-own"))
        assertFailsWith<IllegalArgumentException> { store.write("claude:other", PlanPatch(title = "Not mine"), claude) }
        assertEquals(1, store.write("hangar:t1", PlanPatch(title = "Hangar thread", ops = listOf(PlanOp.Add("Step"))), codex).plan.revision)
    }

    @Test fun otherWritersChangesReachALiveClaudeSessionsInbox() {
        val inbox = directory("plan-inbox")
        val store = WorkPlanStore(directory("plan-store"), inbox)
        draw(store)
        assertFalse(Files.exists(inbox.resolve("claude-s1.txt")))
        store.write("claude:s1", PlanPatch(ops = listOf(PlanOp.Status("views", PlanStatus.Dropped))), person)
        val text = Files.readString(inbox.resolve("claude-s1.txt"))
        assertContains(text, "plan.read")
        assertContains(text, "views → Won't do")
    }

    @Test fun statusWordsAgentsUseAreRead() {
        val ops = Json.decodeFromJsonElement(ListSerializer(PlanOp.serializer()),
            PlanJson.statuses(Json.parseToJsonElement("""[{"type":"status","item":"a","status":"in progress"},{"type":"status","item":"b","status":"won't do"}]""")))
        assertEquals(listOf(PlanStatus.Doing, PlanStatus.Dropped), ops.map { (it as PlanOp.Status).status })
    }

    @Test fun thePlanIsAReaktorGraphLinkedToWhatItTouches() {
        val store = WorkPlanStore(directory("plan-graph"))
        val plan = draw(store).plan
        val shape = PlanGraph.shape(listOf(plan))
        assertEquals(listOf("Unified agent work"), shape.scopes.filter { it.parent != null }.map { it.label })
        assertEquals(4, shape.nodes.count { it.kind == NodeKind.Workflow })
        assertEquals(setOf("reaktor-conductor", "WorkPlan.kt"), shape.nodes.filter { it.attributes["link"] != null }.map { it.label }.toSet())
        val ids = shape.nodes.associate { it.id to it.label }
        assertTrue(shape.wires.any { ids[it.consumer] == "Agent tools" && ids[it.provider] == "Plan model" })
        assertTrue(shape.wires.any { ids[it.consumer] == "Kanban lanes" && ids[it.provider] == "Hangar views" && it.consumerPort == "part of" })
        assertEquals("To do", shape.nodes.first { it.label == "Plan model" }.attributes["status"])
        assertTrue(shape.nodes.filter { it.kind == NodeKind.Workflow }.all { it.id == "${plan.thread}#${it.attributes["item"]}" })
        assertEquals(shape.nodes.map { it.id }, PlanGraph.shape(listOf(plan)).nodes.map { it.id })
        val provided = shape.nodes.flatMap { node -> node.ports.filter { it.provides }.map { it.key } }
        assertEquals(provided.distinct(), provided)
    }

    private val revamp = HarnessSession(RuntimeKind.ClaudeCode, "c8", "Revamp the pane", "/w", startedAt = 1, lastActiveAt = 5, origin = null, version = null, branch = null,
        model = null, effort = null, tokens = null, steps = emptyList(), transcript = "", files = listOf("modules/engine/Pane.kt", "modules/engine/Rail.kt"),
        checklist = listOf(HarnessCheck("Read the pane", PlanState.Done, listOf("modules/engine/Pane.kt")), HarnessCheck("Rewrite it", PlanState.Active)), checklistAt = 4)

    @Test fun aThreadWithoutAPlanIsDrawnFromItsOwnChecklist() {
        val plan = PlanMirror.of(revamp, PlanStatus.Done)
        assertEquals("claude:c8", plan.thread)
        assertEquals(listOf("read-the-pane", "rewrite-it"), plan.items.map { it.id })
        assertEquals(listOf(PlanStatus.Done, PlanStatus.Doing), plan.items.map { it.status })
        assertEquals(listOf("read-the-pane"), plan.item("rewrite-it")?.after)
        assertEquals(listOf("file:modules/engine/Pane.kt"), plan.item("read-the-pane")?.refs)
        assertEquals(4, plan.updatedAt)

        val bare = PlanMirror.of(revamp.copy(checklist = emptyList()), PlanStatus.Doing).items.single()
        assertEquals(Triple("Revamp the pane", PlanStatus.Doing, listOf("file:modules/engine/Pane.kt", "file:modules/engine/Rail.kt")), Triple(bare.title, bare.status, bare.refs))

        val store = WorkPlanStore(directory("plan-mirror"))
        val edited = store.write(plan.thread, PlanPatch(PlanMirror.ops(plan) + PlanOp.Status("rewrite-it", PlanStatus.Done), title = plan.title), person)
        assertEquals(emptyList(), edited.refused)
        assertEquals(listOf(PlanStatus.Done, PlanStatus.Done), edited.plan.items.map { it.status })
        assertEquals(listOf("read-the-pane"), edited.plan.item("rewrite-it")?.after)
    }

    @Test fun aLinkedFileReachesTheModuleThatOwnsIt() {
        val plan = PlanMirror.of(revamp, PlanStatus.Done)
        val shape = PlanGraph.shape(listOf(plan)) { ref -> if (ref.startsWith("file:modules/engine/")) listOf("module:bestbuds/modules/engine") else emptyList() }
        val module = shape.nodes.single { it.id == "claude:c8 ref module:bestbuds/modules/engine" }
        assertEquals("engine", module.label)
        assertTrue(shape.wires.any { it.provider == module.id && it.consumer == "claude:c8 ref file:modules/engine/Pane.kt" }, "${shape.wires}")
        assertTrue(shape.wires.any { it.provider == "claude:c8 ref file:modules/engine/Pane.kt" && it.consumer == "claude:c8#read-the-pane" })
    }

    @Test fun archivingMovesTaskGraphsThatAreNotRunning() {
        val clock = AtomicLong(1_000)
        val board = directory("plan-archive")
        val store = AgentBoardStore(board, clock::get)
        store.propose(TaskGraphDraft("Old", tasks = listOf(TaskGraphOp.Add("Work", seat = TaskSeat(RuntimeKind.Codex)))), codex)
        assertFailsWith<IllegalArgumentException> { store.archive(codex) }
        assertEquals(listOf("G1"), store.archive(person))
        assertEquals(emptyList(), store.graphs())
        assertTrue(Files.exists(board.resolve("archive/graphs/G1.json")))
        assertEquals(emptyList(), AgentBoardStore(board, clock::get).graphs())
    }
}
