package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessCheck
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import dev.shibasis.reaktor.conductor.cli.HarnessStep
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
        store.write("claude:s1", PlanPatch(base = 1, ops = listOf(PlanOp.Status("views", PlanStatus.Dropped, reason = "Use the existing view"))), person)
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
        store.write("claude:s1", PlanPatch(ops = listOf(PlanOp.Status("views", PlanStatus.Dropped, reason = "Use the existing view"))), person)
        val text = Files.readString(inbox.resolve("claude-s1.txt"))
        assertContains(text, "plan.read")
        assertContains(text, "views → Won't do")
    }

    @Test fun codexThreadsHearOfOthersChangesButNotOfTheirOwn() {
        val inbox = directory("plan-codex-inbox")
        val store = WorkPlanStore(directory("plan-codex-store"), inbox)
        val unnamed = BoardActor(BoardActorKind.Agent, "codex")
        store.write("codex:c9", PlanPatch(title = "Parser", ops = listOf(PlanOp.Add("Map it", key = "map"))), unnamed)
        store.write("codex:c9", PlanPatch(ops = listOf(PlanOp.Status("map", PlanStatus.Doing))), unnamed)
        assertFalse(Files.exists(inbox.resolve("codex-c9.txt")))
        store.write("codex:c9", PlanPatch(ops = listOf(PlanOp.Status("map", PlanStatus.Done))), person)
        assertContains(Files.readString(inbox.resolve("codex-c9.txt")), "map → Done")
    }

    @Test fun anItemAnotherAgentHasInProgressStaysTheirsUntilTheyLetItGo() {
        val store = WorkPlanStore(directory("plan-claims"))
        store.write("hangar:launch", PlanPatch(title = "Hangar launch", ops = listOf(
            PlanOp.Add("Data pane", key = "data", kind = PlanKind.Component),
            PlanOp.Add("Group stores by type", key = "rail", parent = "data"),
            PlanOp.Add("File tree", key = "tree", parent = "data"),
        )), claude)
        store.write("hangar:launch", PlanPatch(ops = listOf(PlanOp.Status("tree", PlanStatus.Doing))), codex)
        val taken = store.write("hangar:launch", PlanPatch(ops = listOf(
            PlanOp.Status("tree", PlanStatus.Done), PlanOp.Update("tree", detail = "mine now"), PlanOp.Add("Icons", key = "icons", parent = "tree"),
            PlanOp.Status("rail", PlanStatus.Doing),
        )), claude)
        assertEquals(listOf("tree", "tree", "icons"), taken.refused.map { it.item })
        assertTrue(taken.refused.all { "in progress by codex" in it.reason })
        assertEquals(PlanStatus.Doing, taken.plan.item("rail")?.status)
        assertEquals(listOf("rail"), store.write("hangar:launch", PlanPatch(ops = listOf(PlanOp.Status("rail", PlanStatus.Done))), codex).refused.map { it.item })
        store.write("hangar:launch", PlanPatch(ops = listOf(PlanOp.Status("tree", PlanStatus.Done))), codex)
        assertEquals(emptyList(), store.write("hangar:launch", PlanPatch(ops = listOf(PlanOp.Update("tree", detail = "Next: icons"))), claude).refused)
        assertEquals(emptyList(), store.write("hangar:launch", PlanPatch(ops = listOf(PlanOp.Status("rail", PlanStatus.Todo))), person).refused)
    }

    @Test fun aMessageIsKeptInThePlansLogAndReachesTheOthersOnIt() {
        val inbox = directory("plan-message-inbox")
        val store = WorkPlanStore(directory("plan-message-store"), inbox)
        store.write("hangar:launch", PlanPatch(title = "Hangar launch", ops = listOf(PlanOp.Add("Data pane", key = "data"))), claude)
        store.write("hangar:launch", PlanPatch(ops = listOf(PlanOp.Status("data", PlanStatus.Doing)), message = "Taking the data pane;\n  the file tree lands first"), codex)
        assertEquals("Says: Taking the data pane; the file tree lands first", store.read("hangar:launch").plan.changes.last().summary.last())
        assertContains(Files.readString(inbox.resolve("claude-s1.txt")), "codex: data → In progress; Says: Taking the data pane; the file tree lands first")
        assertFalse(Files.exists(inbox.resolve("codex-c1.txt")))
        val said = store.write("hangar:launch", PlanPatch(message = "Done for tonight"), claude)
        assertEquals(listOf("Says: Done for tonight"), said.plan.changes.last().summary)
        assertContains(Files.readString(inbox.resolve("codex-c1.txt")), "Done for tonight")
    }

    @Test fun aThreadsInboxIsTakenOnceAndKeptAsRead() {
        val inbox = ThreadInbox(directory("thread-inbox"))
        inbox.write("codex:c1", "- r2 · owner: Edited a\n", opening = "Your plan changed:\n")
        inbox.write("codex:c1", "- r3 · owner: Edited b\n", opening = "Your plan changed:\n")
        assertTrue(inbox.waiting(BoardRefs.Codex))
        assertFalse(inbox.waiting(BoardRefs.Claude))
        assertEquals("Your plan changed:\n- r2 · owner: Edited a\n- r3 · owner: Edited b", inbox.take("codex:c1"))
        assertNull(inbox.take("codex:c1"))
        assertFalse(inbox.waiting(BoardRefs.Codex))
        assertContains(Files.readString(inbox.directory.resolve("codex-c1.txt.read")), "r3 · owner")
        assertNull(ThreadInbox(directory("thread-inbox-none").resolve("missing")).take("claude:s1"))
    }

    @Test fun aLentNoteStaysWaitingUntilItIsSettled() {
        val inbox = ThreadInbox(directory("thread-lend"))
        inbox.write("codex:c1", "first\n")
        assertEquals("first", inbox.lend("codex:c1"))
        assertTrue(inbox.waiting(BoardRefs.Codex))
        inbox.write("codex:c1", "second\n")
        assertEquals("first\nsecond", inbox.lend("codex:c1"))
        inbox.settle("codex:c1")
        assertFalse(inbox.waiting(BoardRefs.Codex))
        assertNull(inbox.lend("codex:c1"))
        assertEquals("first\nsecond\n", Files.readString(inbox.directory.resolve("codex-c1.txt.read")))
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
        assertEquals(listOf("Unified agent work"), shape.scopes.filter { it.parent == "plans" }.map { it.label })
        assertEquals(setOf("Hangar views", "In Reaktor"), shape.scopes.filter { it.parent == plan.thread }.map { it.label }.toSet())
        assertEquals(4, shape.nodes.count { it.kind == NodeKind.Workflow })
        assertEquals(setOf("reaktor-conductor", "WorkPlan.kt"), shape.nodes.filter { it.attributes["link"] != null }.map { it.label }.toSet())
        assertTrue(shape.nodes.filter { it.attributes["link"] != null }.all { it.scope == "${plan.thread} code" })
        assertEquals("""atlas_read {"path":"reaktor-conductor/src/WorkPlan.kt","part":"outline"}""", shape.nodes.single { it.label == "WorkPlan.kt" }.attributes["explore"])
        val ids = shape.nodes.associate { it.id to it.label }
        assertTrue(shape.wires.any { ids[it.consumer] == "Agent tools" && ids[it.provider] == "Plan model" })
        assertEquals(setOf("Hangar views", "Kanban lanes"), shape.nodes.filter { it.scope == "${plan.thread} parts views" }.map { it.label }.toSet())
        assertEquals("To do", shape.nodes.first { it.label == "Plan model" }.attributes["status"])
        assertTrue(shape.nodes.filter { it.kind == NodeKind.Workflow }.all { it.id == "${plan.thread}#${it.attributes["item"]}" })
        assertEquals(shape.nodes.map { it.id }, PlanGraph.shape(listOf(plan)).nodes.map { it.id })
        val provided = shape.nodes.flatMap { node -> node.ports.filter { it.provides }.map { it.key } }
        assertEquals(provided.distinct(), provided)
    }

    @Test fun aDesignHasComponentsWhosePartsCarryTheStatus() {
        val store = WorkPlanStore(directory("plan-design"))
        val ops = Json.decodeFromString(ListSerializer(PlanOp.serializer()), """[
            {"type":"add","key":"store","kind":"component","title":"Plan store","refs":["module:reaktor-conductor"]},
            {"type":"add","key":"api","kind":"Service","title":"Plan operations"},
            {"type":"add","key":"kinds","title":"Item kinds","parent":"store","status":"Done"},
            {"type":"add","key":"relations","title":"Relations","parent":"store","after":["kinds"]},
            {"type":"add","key":"store-test","kind":"test","title":"Store tests","parent":"store","after":["relations"]},
            {"type":"add","key":"ops","title":"relate and unrelate","parent":"api"},
            {"type":"relate","from":"api","to":"store","label":" writes  "}
        ]""")
        val drawn = store.write("claude:s1", PlanPatch(title = "Real designs", ops = ops), claude)
        assertEquals(emptyList(), drawn.refused)
        val plan = drawn.plan
        assertEquals(listOf(PlanKind.Component, PlanKind.Component, PlanKind.Task, PlanKind.Task, PlanKind.Check, PlanKind.Task), plan.items.map { it.kind })
        assertEquals(listOf(PlanRelation("store", "writes")), plan.item("api")?.relations)
        assertEquals("api writes store", plan.changes.last().summary.last())
        assertEquals(PlanStatus.Doing, plan.state("store"))
        assertEquals(PlanStatus.Todo, plan.state("api"))
        assertEquals(mapOf(PlanStatus.Done to 1, PlanStatus.Todo to 3), plan.summary().statuses)
        assertEquals(2, plan.summary().components)

        val finished = store.write("claude:s1", PlanPatch(base = plan.revision, ops = listOf(
            PlanOp.Status("relations", PlanStatus.Done), PlanOp.Status("store-test", PlanStatus.Dropped, reason = "Covered by the integration flow"), PlanOp.Update("ops", kind = PlanKind.Decision),
            PlanOp.Relate("api", "store", "reads"), PlanOp.Relate("api", "api", "calls"), PlanOp.Relate("api", "gone", "calls"),
        )), claude)
        assertEquals(listOf(4, 5), finished.refused.map { it.op })
        assertEquals(PlanStatus.Done, finished.plan.state("store"))
        assertEquals(PlanKind.Decision, finished.plan.item("ops")?.kind)
        assertEquals(listOf(PlanRelation("store", "reads")), finished.plan.item("api")?.relations)

        val shape = PlanGraph.shape(listOf(finished.plan))
        assertEquals(setOf("Plan store", "Plan operations", "In Reaktor"), shape.scopes.filter { it.parent == "claude:s1" }.map { it.label }.toSet())
        assertEquals("Component", shape.nodes.first { it.id == "claude:s1#store" }.attributes["kind"])
        assertEquals("Done", shape.nodes.first { it.id == "claude:s1#store" }.attributes["status"])
        assertEquals("2 of 2 done", shape.nodes.first { it.id == "claude:s1#store" }.attributes["parts"])
        assertTrue(shape.wires.any { it.consumer == "claude:s1#api" && it.provider == "claude:s1#store" && it.consumerPort == "reads" })

        val removed = store.write("claude:s1", PlanPatch(ops = listOf(PlanOp.Remove("store"), PlanOp.Unrelate("api", "store"))), person).plan
        assertEquals(emptyList(), removed.item("api")?.relations)
        assertEquals(null, removed.item("kinds")?.parent)
        assertEquals(PlanKind.Component, WorkPlanStore(made.last()).plan("claude:s1")?.item("api")?.kind)
    }

    @Test fun hangarRemindsALiveSessionWhoseDesignFallsBehindItsEdits() {
        val now = 10 * 60 * 60_000L
        fun session(vararg edits: Pair<String, Long>) = HarnessSession(RuntimeKind.ClaudeCode, "s1", "Work", "/w", startedAt = now - 40 * 60_000, lastActiveAt = now - 1_000,
            origin = null, version = null, branch = null, model = null, effort = null, tokens = null,
            steps = edits.map { (file, at) -> HarnessStep(at, "Edit", null, false, file) }, transcript = "")
        val store = WorkPlanStore(directory("plan-nudge-store"), clock = { now - 40 * 60_000 })
        val design = store.write("claude:s1", PlanPatch(title = "Design", ops = listOf(
            PlanOp.Add("Plan store", key = "store", kind = PlanKind.Component, refs = listOf("module:reaktor-conductor")),
            PlanOp.Add("Item kinds", key = "kinds", parent = "store", refs = listOf("symbol:WorkPlan.state")),
        )), claude).plan
        val flat = design.copy(items = listOf("a", "b", "c", "d").map { PlanItem(it, it) })
        val working = design.copy(items = design.items.map { if (it.id == "kinds") it.copy(status = PlanStatus.Doing) else it })
        fun kind(plan: WorkPlan?, vararg edits: Pair<String, Long>) = PlanNudges.of("claude:s1", plan, session(*edits), now)?.kind

        assertEquals(PlanNudges.Draw, kind(null, "modules/app/A.kt" to now - 60_000))
        assertNull(kind(null, "/Users/me/.claude/projects/x/memory/note.md" to now - 60_000))
        assertEquals(PlanNudges.Flat, kind(flat))
        assertNull(kind(flat.copy(items = flat.items.map { if (it.id == "a") it else it.copy(parent = "a") })))
        assertEquals(PlanNudges.Doing, kind(design, "reaktor-conductor/src/WorkPlanStore.kt" to now - 60_000))
        assertEquals(PlanNudges.Links, kind(working, "modules/app/A.kt" to now - 60_000, "modules/app/B.kt" to now - 50_000))
        assertEquals(PlanNudges.Stale, kind(working, "reaktor-conductor/src/WorkPlan.kt" to now - 60_000))
        assertNull(kind(working.copy(updatedAt = now - 5 * 60_000), "reaktor-conductor/src/WorkPlan.kt" to now - 60_000))

        val inbox = directory("plan-nudge-inbox")
        val nudges = PlanNudges(inbox)
        assertEquals(listOf(PlanNudges.Doing), nudges.check(listOf(session("reaktor-conductor/src/WorkPlan.kt" to now - 60_000)), mapOf(design.thread to design), now).map { it.kind })
        assertContains(Files.readString(inbox.resolve("claude-s1.txt")), "nothing on your plan “Design” is in progress")
        assertEquals(emptyList(), nudges.check(listOf(session("modules/app/A.kt" to now)), emptyMap(), now + 60_000))
        assertEquals(listOf(PlanNudges.Draw), nudges.check(listOf(session("modules/app/A.kt" to now + 20 * 60_000).copy(lastActiveAt = now + 20 * 60_000)), emptyMap(), now + 20 * 60_000).map { it.kind })
        val codex = session("modules/app/A.kt" to now + 30 * 60_000).copy(runtime = RuntimeKind.Codex, id = "c1", lastActiveAt = now + 40 * 60_000)
        assertEquals(listOf("codex:c1"), nudges.check(listOf(codex), emptyMap(), now + 40 * 60_000).map { it.thread })
        assertContains(Files.readString(inbox.resolve("codex-c1.txt")), "no plan yet")
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
