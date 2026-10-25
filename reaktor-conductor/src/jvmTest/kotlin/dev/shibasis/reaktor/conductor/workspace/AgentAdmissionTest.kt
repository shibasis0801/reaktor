package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AgentAdmissionTest {
    private val person = BoardActor(BoardActorKind.Person, "fixture")

    private class Harness : AgentRuntime {
        override val kind = RuntimeKind.Codex
        val started = CompletableDeferred<Unit>()
        override fun run(request: AgentRequest) = flow {
            emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "admission")))
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private fun fixture(capacity: Int = 4, body: suspend (AgentWorkspace, File, Harness) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("admission-root").toFile()
        val data = Files.createTempDirectory("admission-data")
        val temporary = File(System.getProperty("java.io.tmpdir"))
        try {
            listOf("one", "two", "three").forEach { File(root, "modules/$it").mkdirs() }
            File(root, "modules/one/Source.kt").writeText("val one = 1\n")
            listOf(listOf("init", "--quiet"), listOf("add", "."),
                listOf("-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Baseline")).forEach { args ->
                val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { output }
            }
            val harness = Harness()
            AgentWorkspace(root, data, mapOf(harness.kind to harness), maxActive = capacity,
                discover = { ProviderCapability(it) }, scopeModules = { paths -> paths.flatMap { path ->
                    val canonical = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalPath
                    listOf("one", "two", "three").filter { path == ":$it" || canonical.startsWith(File(root, "modules/$it").canonicalPath + "/") ||
                        canonical == File(root, "modules/$it").canonicalPath }.map { "fixture/modules/$it" }
                }.distinct() }).use { body(it, root, harness) }
        } finally {
            root.deleteTreeSafely(within = temporary)
            data.toFile().deleteTreeSafely(within = temporary)
        }
    }

    private fun AgentWorkspace.task(scope: List<String>, access: TaskAccess = TaskAccess.Write, lead: Boolean = false): BoardTask {
        val actor = BoardActor(BoardActorKind.Agent, "lead", "codex:admission-lead")
        val proposed = board.propose(TaskGraphDraft("Admission", tasks = listOf(
            TaskGraphOp.Add("Work", scope = scope, seat = TaskSeat(if (lead) null else RuntimeKind.Codex, access = access, lead = lead))
        ), envelope = TaskEnvelope(perAgent = 4, writes = listOf("*"))), actor)
        board.start(proposed.graph.id, person, proposed.graph.revision)
        return board.members(proposed.graph.id).single()
    }

    private fun AgentWorkspace.start(task: BoardTask) = startTask(task, resolveSeat(requireNotNull(task.graph?.seat)))

    private fun AgentWorkspace.settle(task: BoardTask) {
        board.advance(requireNotNull(task.graph).id, task.id) { value, plan ->
            value.copy(graph = plan.copy(state = TaskState.Succeeded, outcome = TaskOutcome(summary = "Done", attempt = plan.attempt)))
        }
    }

    @Test fun disjointWritersRunAcrossGraphsAndAnotherFileInTheOwnedModuleWaitsUntilSettlement() = fixture { workspace, _, _ ->
        val first = workspace.task(listOf("modules/one/A.kt"))
        val second = workspace.task(listOf("modules/two/B.kt"))
        val third = workspace.task(listOf("modules/one/C.kt"))
        assertNotNull(workspace.start(first))
        assertNotNull(workspace.start(second))
        assertNull(workspace.start(third))
        assertEquals(TaskState.Pending, workspace.board.get(third.id).graph?.state)
        assertTrue(workspace.board.get(third.id).graph?.note.orEmpty().contains(first.id))
        workspace.settle(workspace.board.get(first.id))
        assertNotNull(workspace.start(workspace.board.get(third.id)))
    }

    @Test fun moduleAliasesCanonicalPathsAndSymlinksDescribeTheSameOwner() = fixture { workspace, root, _ ->
        val first = workspace.task(listOf(":one"))
        assertNotNull(workspace.start(first))
        val absolute = workspace.task(listOf(File(root, "modules/one/../one/Other.kt").path))
        assertNull(workspace.start(absolute))
        Files.createSymbolicLink(File(root, "linked").toPath(), File(root, "modules/one").toPath())
        val linked = workspace.task(listOf("linked/Other.kt"))
        assertNull(workspace.start(linked))
        workspace.settle(workspace.board.get(first.id))
        assertNotNull(workspace.start(workspace.board.get(linked.id)))
    }

    @Test fun declaredSharedInterfacesConflictEvenWhenTheSourceModulesDiffer() = fixture { workspace, _, _ ->
        val first = workspace.task(listOf("modules/one/A.kt", "api/shared.contract"))
        val second = workspace.task(listOf("modules/two/B.kt", "api/shared.contract"))
        assertNotNull(workspace.start(first))
        assertNull(workspace.start(second))
    }

    @Test fun standaloneWritersAndGraphTasksUseTheSameAdmissionInBothDirections() = fixture { workspace, _, harness ->
        val run = workspace.submit(AgentSubmission("standalone", RuntimeKind.Codex, "Hold source", allowWrites = true, scope = listOf(":one")))
        withTimeout(10000) { harness.started.await() }
        val blocked = workspace.task(listOf("modules/one/Other.kt"))
        assertNull(workspace.start(blocked))
        val admitted = workspace.task(listOf("modules/two/Other.kt"))
        assertNotNull(workspace.start(admitted))
        val isolated = workspace.submit(AgentSubmission("isolated", RuntimeKind.Codex, "Edit owned source",
            allowWrites = true, isolation = AgentIsolation.Worktree, scope = listOf(":one")))
        assertFailsWith<IllegalArgumentException> {
            workspace.submit(AgentSubmission("conflicting", RuntimeKind.Codex, "Write source", allowWrites = true, scope = listOf(":two")))
        }
        workspace.cancel(isolated.id)
        workspace.cancel(run.id)
        withTimeout(10000) { while (workspace.get(run.id).status == AgentRunStatus.Running) delay(10) }
        assertNotNull(workspace.start(workspace.board.get(blocked.id)))
    }

    @Test fun anUnspecifiedWriterOwnsTheWholeWorkspaceButReadersMayStillRun() = fixture { workspace, _, harness ->
        workspace.submit(AgentSubmission("unscoped", RuntimeKind.Codex, "Hold source", allowWrites = true))
        withTimeout(10000) { harness.started.await() }
        assertNull(workspace.start(workspace.task(listOf("modules/two/Other.kt"))))
        assertNotNull(workspace.start(workspace.task(listOf("modules/two/Other.kt"), TaskAccess.Read)))
    }

    @Test fun externalLeadClaimsAndRunningReportsCannotBypassAdmission() = fixture { workspace, _, _ ->
        val caller = TaskGraphCaller(BoardActor(BoardActorKind.Agent, "lead", "codex:admission-lead"), lead = true)
        val first = workspace.task(listOf(":one"), lead = true)
        val second = workspace.task(listOf("modules/one/Other.kt"), lead = true)
        val claimed = workspace.claimTask(requireNotNull(first.graph).id, first.id, caller)
        assertEquals(1, claimed.graph?.attempt)
        assertFailsWith<IllegalArgumentException> { workspace.claimTask(requireNotNull(second.graph).id, second.id, caller) }
        workspace.reportTask(first.graph.id, first.id, TaskReport.Blocked, TaskOutcome(summary = "Waiting"), caller)
        val next = workspace.claimTask(requireNotNull(second.graph).id, second.id, caller)
        assertEquals(TaskState.Running, next.graph?.state)
        assertFailsWith<IllegalArgumentException> {
            workspace.reportTask(first.graph.id, first.id, TaskReport.Running, TaskOutcome(summary = "Back"), caller)
        }
        workspace.settle(next)
        assertEquals(TaskState.Running, workspace.reportTask(first.graph.id, first.id, TaskReport.Running, TaskOutcome(summary = "Back"), caller).graph?.state)
    }

    @Test fun simultaneousGraphClaimsCannotOversubscribeCapacity() = fixture(capacity = 2) { workspace, _, _ ->
        val tasks = (1..8).map { workspace.task(listOf("unowned/$it/File.kt")) }
        val ready = CountDownLatch(tasks.size)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tasks.size)
        try {
            val futures = tasks.map { task -> executor.submit<BoardTask?> {
                ready.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                workspace.start(task)
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()
            val started = futures.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(2, started.size)
            val pending = tasks.filter { it.id !in started.map { value -> value.id } }
            workspace.settle(started.first())
            assertNotNull(workspace.start(workspace.board.get(pending.first().id)))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun aGraphReservationIsNotChargedTwiceWhenItsNativeRunStarts() = fixture(capacity = 1) { workspace, _, harness ->
        val task = workspace.task(listOf(":one"), access = TaskAccess.Read)
        val started = assertNotNull(workspace.start(task))
        val run = workspace.submit(AgentSubmission("graph", RuntimeKind.Codex, "Read the source",
            resolvedSeat = started.graph?.resolvedSeat,
            task = AgentTaskTarget(requireNotNull(started.graph).id, started.id, started.graph.attempt)))
        withTimeout(10000) { harness.started.await() }
        assertFailsWith<IllegalArgumentException> {
            workspace.submit(AgentSubmission("duplicate", RuntimeKind.Codex, "Read again",
                resolvedSeat = started.graph.resolvedSeat, task = AgentTaskTarget(started.graph.id, started.id, started.graph.attempt)))
        }
        assertFailsWith<IllegalArgumentException> { workspace.submit(AgentSubmission("outside", RuntimeKind.Codex, "Read source")) }
        workspace.settle(started)
        assertEquals(0, workspace.freeSlots())
        assertFailsWith<IllegalArgumentException> { workspace.submit(AgentSubmission("still-active", RuntimeKind.Codex, "Read source")) }
        workspace.cancel(run.id)
        assertEquals(1, workspace.freeSlots())
    }
}

