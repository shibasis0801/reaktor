package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentConnectionTaskChangesTest {
    @Test fun aSecondConnectionCollectsTheOwnersPushesWithoutATaskWait() = runBlocking {
        val method = assertNotNull(AgentWorkspaceConnection::class.java.methods.singleOrNull {
            it.name == "taskChanges" && it.parameterCount == 1
        }, "The connection must expose the in-process taskChanges adapter")
        val root = Files.createTempDirectory("connection-task-push").toFile()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Echo
            override fun run(request: dev.shibasis.reaktor.conductor.AgentRequest) = flow {
                emit(AgentEvent.Started(request.agent.id, null))
                awaitCancellation()
            }
        }
        try {
            lateinit var owner: AgentWorkspace
            val data = root.toPath().resolve("state")
            AgentWorkspaceConnection.open(root, data, mapOf(RuntimeKind.Echo to runtime), background = false,
                discover = { ProviderCapability(it) }, extraTools = { owner = it; emptyList() }).use { first ->
                AgentWorkspaceConnection.open(root, data, allowStart = false).use { second ->
                    @Suppress("UNCHECKED_CAST")
                    val changes = assertNotNull(method.invoke(second, "") as? Flow<AgentTasksPage>)
                    assertEquals(0, withTimeout(3000) { changes.first() }.runs.size)
                    val pushed = async { withTimeout(3000) { changes.first { it.runs.isNotEmpty() } } }
                    val run = owner.submit(AgentSubmission("push-fixture", RuntimeKind.Echo, "Push fixture"))
                    assertEquals(run.id, pushed.await().runs.single().id)
                    assertEquals(first.incarnation, second.incarnation)
                }
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }
}
