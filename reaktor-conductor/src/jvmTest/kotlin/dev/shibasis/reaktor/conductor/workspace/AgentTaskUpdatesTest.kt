package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class AgentTaskUpdatesTest {
    @Test fun secondClientChangesWakeTheTaskWaitAndServerSearchPagesBeyondTwenty() = runBlocking {
        val root = Files.createTempDirectory("task-updates-root").toFile()
        val directory = Files.createTempDirectory("task-updates-state")
        val runtime = EchoRuntime(RuntimeKind.Codex) { "fixture reply" }
        try {
            AgentWorkspaceConnection.open(root, directory, mapOf(runtime.kind to runtime), discover = { ProviderCapability(it) }).use { owner ->
                AgentWorkspaceConnection.open(root, directory, allowStart = false).use { client ->
                    val initial = owner.tasks()
                    val changed = async { owner.waitTasks(initial.revision) }
                    val run = client.submit(AgentSubmission("second-client", runtime.kind, "login graph task 0"))
                    assertTrue(withTimeout(1000) { changed.await() }.runs.any { it.id == run.id })
                    repeat(59) { number ->
                        var item = client.submit(AgentSubmission("task-${number + 1}", runtime.kind, "login graph task ${number + 1}"))
                        withTimeout(10000) { while (item.status == AgentRunStatus.Running) item = client.wait(item.id, item.revision) }
                    }
                    val first = owner.tasks("title:~login provider:codex -title:unrelated")
                    assertEquals(50, first.runs.size)
                    assertEquals(50, first.nextOffset)
                    val second = owner.tasks("title:~login provider:codex -title:unrelated", first.nextOffset!!)
                    assertEquals(10, second.runs.size)
                    assertNull(second.nextOffset)
                    assertEquals(60, (first.runs + second.runs).map { it.threadId }.toSet().size)
                    assertEquals(run.id, owner.tasks("id:${run.id}").runs.single().id)
                    var turn = client.submit(AgentSubmission("continued-one", runtime.kind, "continued login graph", threadId = run.threadId))
                    withTimeout(10000) { while (turn.status == AgentRunStatus.Running) turn = client.wait(turn.id, turn.revision) }
                    val firstTurn = turn
                    turn = client.submit(AgentSubmission("continued-two", runtime.kind, "continued login graph again", threadId = run.threadId))
                    withTimeout(10000) { while (turn.status == AgentRunStatus.Running) turn = client.wait(turn.id, turn.revision) }
                    assertEquals(listOf(turn.id, firstTurn.id), owner.list(2).map { it.id })
                    assertTrue(first.runs.all { it.output.isEmpty() && it.context == null && it.participants.values.all { child -> child.output.isEmpty() } })
                }
            }
        } finally {
            root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
            directory.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        }
    }
}
