package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskGraphCheckTest {
    @Test fun checksPreviewWarningsWithoutAllocatingIdsWritingHistoryOrApprovingOutsideChanges() {
        val directory = Files.createTempDirectory("graph-check")
        try {
            val agent = BoardActor(BoardActorKind.Agent, "codex", "codex:lead")
            val store = AgentBoardStore(directory, scopeModules = { if (":engine" in it) listOf("a", "b", "c", "d") else emptyList() })
            val before = store.propose(TaskGraphDraft("Preview", tasks = listOf(TaskGraphOp.Add("Write source", scope = listOf(":engine"),
                seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)))), agent)
            val revision = store.revision
            val caller = TaskGraphCaller(agent, lead = true)
            val patch = TaskGraphPatch(listOf(TaskGraphOp.Add("Check source", kind = TaskKind.Check, check = ":engine:jvmTest",
                dependsOn = listOf(TaskDependency("T1"))), TaskGraphOp.Seat("T1", TaskSeat(RuntimeKind.ClaudeCode, access = TaskAccess.Write))),
                "Verify the writer", before.graph.revision)
            val checked = store.check("G1", patch, caller)
            assertTrue(checked.outside.isNotEmpty())
            assertEquals(setOf(TaskGraphWarningCode.BroadScope, TaskGraphWarningCode.UnclearAcceptance), checked.warnings.map { it.code }.toSet())
            assertEquals(before, store.view("G1"))
            assertEquals(revision, store.revision)
            assertEquals(before, AgentBoardStore(directory, scopeModules = { listOf("a", "b", "c", "d") }).view("G1"))
            assertFailsWith<TaskGraphOutside> { store.patch("G1", patch, caller) }
            val added = store.patch("G1", patch.copy(ops = patch.ops.take(1)), caller)
            assertEquals("T2", added.tasks.single().id)
            assertTrue("Plan warnings (advisory" in TaskGraphText.render(before))
        } finally {
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun warningsDoNotBlockAPersonStartingTheDisplayedRevision() {
        val directory = Files.createTempDirectory("graph-lint-start")
        try {
            val store = AgentBoardStore(directory)
            val person = BoardActor(BoardActorKind.Person, "Fixture")
            val graph = store.propose(TaskGraphDraft("Unverified", tasks = listOf(TaskGraphOp.Add("Write", scope = listOf(":engine"),
                seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)))), person)
            assertTrue(graph.warnings.isNotEmpty())
            assertEquals(TaskGraphStatus.Running, store.start(graph.graph.id, person, graph.graph.revision).status)
        } finally {
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
