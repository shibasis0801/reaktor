package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlin.test.*

class PlanAttachmentAdmissionTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val thread = "hangar:attachment"

    private fun fixture(configured: Boolean = true, test: suspend (AgentWorkspace, List<String>) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("plan-attachment").toFile()
        val release = CompletableDeferred<Unit>()
        val prompts = CopyOnWriteArrayList<String>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow {
                prompts += request.prompt
                release.await()
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "done", true)))
            }
        }
        try {
            AgentWorkspace(root, root.toPath().resolve("state"), if (configured) mapOf(runtime.kind to runtime) else emptyMap(),
                maxActive = 4, background = false, discover = { ProviderCapability(it) }).use { workspace ->
                workspace.profiles.save(AgentProfile("writer", "Writer", RuntimeKind.Echo, access = TaskAccess.Write), owner)
                workspace.plans.write(thread, PlanPatch(title = "Attachment", ops = listOf(
                    PlanOp.Add("Area", key = "area", kind = PlanKind.Component),
                    PlanOp.Add("First", key = "first", parent = "area", refs = listOf("file:src/first.kt")),
                    PlanOp.Add("Second", key = "second", refs = listOf("file:src/second.kt")),
                )), owner)
                try { test(workspace, prompts) } finally { release.complete(Unit) }
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }

    @Test fun unavailableProviderNeverPersistsASeat() = fixture(configured = false) { workspace, prompts ->
        val before = workspace.plans.read(thread).plan
        repeat(3) {
            assertFailsWith<IllegalArgumentException> { workspace.attachPlan(thread, listOf("first"), "writer", "", owner) }
            assertEquals(before, workspace.plans.read(thread).plan)
        }
        assertTrue(prompts.isEmpty())
    }

    @Test fun invalidItemsAndAgentCallersFailBeforeAdmission() = fixture { workspace, prompts ->
        val before = workspace.plans.read(thread).plan
        for (items in listOf(emptyList(), listOf("missing"))) {
            assertFailsWith<IllegalArgumentException> { workspace.attachPlan(thread, items, "writer", "", owner) }
        }
        assertFailsWith<IllegalArgumentException> {
            workspace.attachPlan(thread, listOf("first"), "writer", "", BoardActor(BoardActorKind.Agent, "Codex", "codex:proof"))
        }
        assertEquals(before, workspace.plans.read(thread).plan)
        assertTrue(prompts.isEmpty())
    }

    @Test fun unrelatedCoveredFilesCanRunTogetherAndConflictsLeaveNoSeat() = fixture { workspace, _ ->
        val first = workspace.attachPlan(thread, listOf("area", "area"), "writer", "", owner)
        val second = workspace.attachPlan(thread, listOf("second"), "writer", "", owner)
        assertEquals(listOf("area"), first.seat.items)
        assertEquals("s1", first.seat.id)
        assertEquals("s2", second.seat.id)
        assertEquals(first.run.id, first.seat.run)
        assertEquals(second.run.id, second.seat.run)
        assertEquals(listOf("src/first.kt"), workspace.submission(first.run.id).scope)
        assertEquals(listOf("src/second.kt"), workspace.submission(second.run.id).scope)
        val before = workspace.plans.read(thread).plan
        repeat(3) {
            val refused = assertFailsWith<IllegalArgumentException> { workspace.attachPlan(thread, listOf("first"), "writer", "", owner) }
            assertTrue(refused.message.orEmpty().contains("Source scope"))
            assertEquals(before, workspace.plans.read(thread).plan)
        }
        val persisted = workspace.plans.read(thread).plan
        assertTrue(persisted.seats.all { it.agent != null })
        val stored = persisted.copy(seats = persisted.seats.map { it.copy(agent = null) })
        assertEquals(listOf(first.seat, second.seat), stored.seats)
        assertEquals(stored, second.plan)
    }

    @Test fun concurrentAttachmentsChooseDistinctBoundSeats() = fixture { workspace, _ ->
        val attached = coroutineScope {
            listOf("first", "second").map { item -> async(Dispatchers.Default) {
                workspace.attachPlan(thread, listOf(item), "writer", "", owner)
            } }.awaitAll()
        }
        assertEquals(setOf("s1", "s2"), attached.map { it.seat.id }.toSet())
        assertEquals(2, attached.map { it.run.id }.toSet().size)
        assertEquals(attached.map { it.seat }.toSet(), workspace.plans.read(thread).plan.seats.map { it.copy(agent = null) }.toSet())
        assertTrue(attached.all { it.seat.run == it.run.id })
    }

    @Test fun missingFileRefsKeepTheWholeWorkspaceReservation() = fixture { workspace, _ ->
        workspace.plans.write(thread, PlanPatch(ops = listOf(PlanOp.Add("Unscoped", key = "unscoped"))), owner)
        val attached = workspace.attachPlan(thread, listOf("unscoped"), "writer", "", owner)
        assertTrue(workspace.submission(attached.run.id).scope.isEmpty())
        val before = workspace.plans.read(thread).plan
        assertFailsWith<IllegalArgumentException> { workspace.attachPlan(thread, listOf("first"), "writer", "", owner) }
        assertEquals(before, workspace.plans.read(thread).plan)
    }
}
