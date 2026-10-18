package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.HarnessActivity
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class AgentBoardTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val agent = BoardActor(BoardActorKind.Agent, "codex")
    private val temporary = File(System.getProperty("java.io.tmpdir"))
    private val made = mutableListOf<Path>()

    private fun directory(name: String): Path = Files.createTempDirectory(name).also(made::add)

    @AfterTest fun clean() = made.forEach { it.toFile().deleteTreeSafely(within = temporary) }

    @Test fun columnsFollowTheWorkLinkedToATask() {
        val clock = AtomicLong(1_000)
        val store = AgentBoardStore(directory("board-columns"), clock::get)
        store.create(BoardDraft("Ship the board", stage = BoardStage.Planning), person)
        fun column(vararg work: BoardWork) = store.get("T1").entry(work.toList(), emptyMap()).column
        assertEquals(BoardColumn.Planning, column())
        assertEquals(BoardColumn.Running, column(BoardWork("hangar:a", BoardWorkState.Executing, 1_100)))
        assertEquals(BoardColumn.NeedsYou, column(BoardWork("hangar:a", BoardWorkState.Executing, 1_100), BoardWork("claude:b", BoardWorkState.Asking, 1_200)))
        assertEquals(BoardColumn.NeedsYou, column(BoardWork("hangar:a", BoardWorkState.Stopped, 1_300)))
        assertEquals(BoardColumn.Planning, column(BoardWork("hangar:a", BoardWorkState.Stopped, 900)))
        clock.set(1_400)
        assertEquals("Kept in Planning", store.move("T1", BoardStage.Planning, person).history.last().change)
        assertEquals(BoardColumn.Planning, column(BoardWork("hangar:a", BoardWorkState.Stopped, 1_300)))
        store.move("T1", BoardStage.Review, agent)
        assertEquals(BoardColumn.Review, column(BoardWork("codex:c", BoardWorkState.Stopped, 1_500)))
        assertEquals(BoardColumn.Running, column(BoardWork("codex:c", BoardWorkState.Executing, 1_500)))
        val entry = store.get("T1").entry(listOf(BoardWork("claude:b", BoardWorkState.Asking, 1_200)), emptyMap())
        assertEquals(1_200, entry.since)
    }

    @Test fun linkedClaudeCodeCodexAndAntigravitySessionsPlaceTheirTasks() {
        fun task(id: String, stage: BoardStage, ref: String) =
            BoardTask(id, "Task $id", stage = stage, work = listOf(ref), createdBy = agent, createdAt = 1_000, updatedAt = 1_000, stageChangedAt = 1_000)
        fun session(runtime: RuntimeKind, id: String, activity: HarnessActivity, at: Long) = HarnessSession(runtime, id, "Session $id", "/workspace",
            startedAt = at - 100, lastActiveAt = at, origin = null, version = null, branch = null, model = null, effort = null, tokens = null,
            steps = emptyList(), transcript = "", activity = activity)
        val tasks = listOf(
            task("T1", BoardStage.Planning, "codex:working"),
            task("T2", BoardStage.Planning, "codex:asking"),
            task("T3", BoardStage.Planning, "claude:idle"),
            task("T4", BoardStage.Review, "claude:idle"),
            task("T5", BoardStage.Planning, "claude:gone"),
            task("T6", BoardStage.Planning, "agy:conversation"),
            task("T7", BoardStage.Planning, "claude:conversation"),
        )
        val byId = tasks.associateBy { it.id }
        val placed = BoardPage(7, tasks.map { it.entry(emptyList(), byId) }).placed(listOf(
            session(RuntimeKind.Codex, "working", HarnessActivity.Working, 5_000),
            session(RuntimeKind.Codex, "asking", HarnessActivity.NeedsYou, 6_000),
            session(RuntimeKind.ClaudeCode, "idle", HarnessActivity.Waiting, 7_000),
            session(RuntimeKind.Gemini, "conversation", HarnessActivity.Waiting, 8_000),
        ))
        assertEquals(mapOf("T1" to BoardColumn.Running, "T2" to BoardColumn.NeedsYou, "T3" to BoardColumn.NeedsYou, "T4" to BoardColumn.Review,
            "T5" to BoardColumn.Planning, "T6" to BoardColumn.NeedsYou, "T7" to BoardColumn.Planning), placed.entries.associate { it.task.id to it.column })
        assertEquals(4_900L, placed.entries.first { it.task.id == "T1" }.since)
        assertEquals(listOf("T1"), placed.matching("column:Running").entries.map { it.task.id })
        assertEquals(7L, placed.revision)
    }

    @Test fun onlyAPersonFinishesOrReopensATask() {
        val store = AgentBoardStore(directory("board-done"))
        store.create(BoardDraft("Land it"), agent)
        assertEquals(BoardStage.Review, store.move("T1", BoardStage.Review, agent).stage)
        assertTrue(assertFailsWith<IllegalArgumentException> { store.move("T1", BoardStage.Done, agent) }.message!!.startsWith("Only a person"))
        assertFailsWith<IllegalArgumentException> { store.move("T1", BoardStage.Done, BoardActor(BoardActorKind.Schedule, "nightly")) }
        assertEquals(BoardStage.Done, store.move("T1", BoardStage.Done, person).stage)
        assertFailsWith<IllegalArgumentException> { store.move("T1", BoardStage.Planning, agent) }
        assertEquals(BoardStage.Planning, store.move("T1", BoardStage.Planning, person).stage)
        assertFailsWith<IllegalArgumentException> { store.create(BoardDraft("Born done", stage = BoardStage.Done), person) }
        assertEquals(listOf("Created in Backlog", "Moved to Review", "Moved to Done", "Moved to Planning"), store.get("T1").history.map { it.change })
    }

    @Test fun blockersNameATaskOrAnOwnerAndNeverLoop() {
        val store = AgentBoardStore(directory("board-blockers"))
        store.create(BoardDraft("Migrate"), person)
        store.create(BoardDraft("Redeploy"), person)
        store.update("T1", BoardPatch(blocker = BoardBlocker.Task("T2")), agent)
        assertFailsWith<IllegalArgumentException> { store.update("T2", BoardPatch(blocker = BoardBlocker.Task("T1")), agent) }
        assertFailsWith<IllegalArgumentException> { store.update("T1", BoardPatch(blocker = BoardBlocker.Task("T1")), agent) }
        assertFailsWith<IllegalStateException> { store.update("T1", BoardPatch(blocker = BoardBlocker.Task("T9")), agent) }
        assertFailsWith<IllegalArgumentException> { store.update("T1", BoardPatch(blocker = BoardBlocker.Owner(" ", "redeploy")), agent) }
        fun entry() = store.tasks().associateBy { it.id }.let { tasks -> tasks.getValue("T1").entry(emptyList(), tasks) }
        assertEquals(BoardColumn.Backlog, entry().column)
        assertEquals("Waiting on T2: Redeploy", entry().blocked)
        store.move("T2", BoardStage.Done, person)
        assertNull(entry().blocked)
        val owned = store.update("T1", BoardPatch(blocker = BoardBlocker.Owner("Kedarnath", "redeploy the dev workers")), agent)
        assertEquals(BoardColumn.NeedsYou, entry().column)
        assertEquals("Waiting on Kedarnath to redeploy the dev workers", entry().blocked)
        assertFailsWith<IllegalArgumentException> { store.move("T1", BoardStage.Planning, agent, expected = owned.revision - 1) }
        assertEquals("Unblocked", store.update("T1", BoardPatch(unblock = true), person, expected = owned.revision).history.last().change)
        assertEquals(BoardColumn.Backlog, entry().column)
    }

    @Test fun recordsAreCheckedAndSurviveARestart() {
        val folder = directory("board-restart")
        val store = AgentBoardStore(folder)
        assertFailsWith<IllegalArgumentException> { store.create(BoardDraft(" "), person) }
        assertFailsWith<IllegalArgumentException> { store.create(BoardDraft("two\nlines"), person) }
        assertFailsWith<IllegalArgumentException> { store.create(BoardDraft("Bad link", work = listOf("terminal:1")), person) }
        assertFailsWith<IllegalArgumentException> { store.create(BoardDraft("Nobody"), BoardActor(BoardActorKind.Agent, "")) }
        store.create(BoardDraft("First", acceptance = listOf(" passes ", ""), work = listOf("claude:abc", "claude:abc", "agy:def")), person)
        store.create(BoardDraft("Second", parent = "T1"), agent)
        assertFailsWith<IllegalStateException> { store.create(BoardDraft("Orphan", parent = "T7"), agent) }
        assertEquals(listOf("passes"), store.get("T1").acceptance)
        assertEquals(listOf("claude:abc", "agy:def"), store.get("T1").work)
        val revision = store.revision
        val reopened = AgentBoardStore(folder)
        assertEquals(revision, reopened.revision)
        assertEquals(listOf("T2", "T1"), reopened.tasks().map { it.id })
        assertEquals("T3", reopened.create(BoardDraft("Third"), person).id)
        assertEquals("T1", reopened.get("T2").parent)
    }

    @Test fun theOwnerServesTheBoardAndItFollowsLinkedRuns() = runBlocking {
        val root = directory("board-root").toFile()
        val state = directory("board-state")
        val gate = CountDownLatch(1)
        val runtime = EchoRuntime(RuntimeKind.Codex) { gate.await(20, TimeUnit.SECONDS); "fixture reply" }
        AgentWorkspaceConnection.open(root, state, mapOf(runtime.kind to runtime), discover = { ProviderCapability(it) }).use { owner ->
            AgentWorkspaceConnection.open(root, state, allowStart = false).use { client ->
                val created = client.createBoardTask(BoardDraft("Ship the board", acceptance = listOf("Six columns"), stage = BoardStage.Planning), agent)
                assertEquals("T1", created.task.id)
                assertEquals(BoardColumn.Planning, created.column)
                val initial = owner.tasks()
                assertEquals(initial.revision, owner.board().revision)
                val changed = async { owner.waitTasks(initial.revision) }
                val edited = client.updateBoardTask("T1", BoardPatch(intent = "Six columns and an inbox"), agent)
                val woken = withTimeout(10_000) { changed.await() }
                assertNotEquals(initial.revision, woken.revision)
                assertEquals(owner.board().revision, woken.revision)
                val run = client.submit(AgentSubmission("board-run", runtime.kind, "build the board"))
                assertEquals(BoardColumn.Running, client.linkBoardWork("T1", "hangar:${run.threadId}", true, agent).column)
                assertEquals(edited.task.revision + 1, owner.boardTask("T1").task.revision)
                gate.countDown()
                var finished = run
                withTimeout(10_000) { while (finished.status == AgentRunStatus.Running) finished = client.wait(finished.id, finished.revision) }
                assertEquals(BoardColumn.NeedsYou, owner.boardTask("T1").column)
                assertEquals(BoardColumn.Review, client.moveBoardTask("T1", BoardStage.Review, agent).column)
                assertTrue(assertFailsWith<IllegalStateException> { client.moveBoardTask("T1", BoardStage.Done, agent) }.message!!.contains("Only a person"))
                assertFailsWith<IllegalStateException> { client.linkBoardWork("T1", "hangar:${UUID.randomUUID()}", true, agent) }
                val blocked = client.updateBoardTask("T1", BoardPatch(blocker = BoardBlocker.Owner("Kedarnath", "review the copy")), agent)
                assertEquals(BoardColumn.Review, blocked.column)
                assertEquals("Waiting on Kedarnath to review the copy", blocked.blocked)
                assertEquals(BoardColumn.Done, owner.moveBoardTask("T1", BoardStage.Done, person, blocked.task.revision).column)
                assertEquals(listOf("T1"), client.board("column:Done").entries.map { it.task.id })
                assertTrue(client.board("column:Review").entries.isEmpty())
            }
        }
        AgentWorkspaceConnection.open(root, state, mapOf(runtime.kind to runtime), discover = { ProviderCapability(it) }).use { owner ->
            assertEquals("T2", owner.createBoardTask(BoardDraft("Next"), person).task.id)
            assertEquals(listOf("T2", "T1"), owner.board().entries.map { it.task.id })
        }
    }
}
