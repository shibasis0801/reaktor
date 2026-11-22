package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AgentTaskPushRateTest {
    @Test fun streamingTextDoesNotRebuildTheTaskRailOnEveryDelta() = runBlocking {
        val root = Files.createTempDirectory("task-push-rate").toFile()
        val releases = Channel<Unit>(Channel.UNLIMITED)
        val pages = Channel<AgentTasksPage>(Channel.UNLIMITED)
        val count = AtomicInteger()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: AgentRequest) = flow {
                emit(AgentEvent.Started(request.agent.id, null))
                repeat(20) { releases.receive(); emit(AgentEvent.Delta(request.agent.id, "word ")) }
                releases.receive()
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "word ".repeat(20), true)))
            }
        }
        try {
            AgentWorkspace(root, root.resolve("state").toPath(), mapOf(runtime.kind to runtime),
                discover = { ProviderCapability(it) }, background = false).use { workspace ->
                val collector = launch { workspace.taskChanges().collect { count.incrementAndGet(); pages.send(it) } }
                try {
                    withTimeout(3000) { pages.receive() }
                    val run = workspace.submit(AgentSubmission("rate", runtime.kind, "Stream words"))
                    withTimeout(3000) { while (pages.receive().runs.isEmpty()) Unit }
                    releases.send(Unit)
                    withTimeout(3000) { while (!pages.receive().runs.single().outputTruncated) Unit }
                    val before = count.get()
                    repeat(19) { index ->
                        releases.send(Unit)
                        withTimeout(3000) { while (workspace.get(run.id).output.length < (index + 2) * 5) delay(5) }
                        delay(15)
                    }
                    assertEquals(before, count.get(), "Text-only deltas do not wake the task rail")
                    releases.send(Unit)
                    withTimeout(3000) { while (pages.receive().runs.single().status != AgentRunStatus.Completed) Unit }
                } finally { collector.cancelAndJoin() }
            }
        } finally { releases.close(); pages.close(); root.deleteTreeSafely(root.parentFile) }
    }
}
