package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class CliCancellationTest {
    @Test
    fun stoppingCollectionReapsTheOwnedProcessBeforeReturning() = runBlocking {
        SupervisedProcessExecutor(terminationGraceMillis = 100).use { executor ->
            val agent = AgentSpec(AgentId("test"), "Test", RuntimeKind.Echo, "")
            val runtime = object : CliAgentRuntime(executor) {
                override val kind = RuntimeKind.Echo
                override fun argv(request: AgentRequest) = listOf("/bin/sh", "-c", "echo ${'$'}${'$'}; exec sleep 30")
                override fun parser(request: AgentRequest) = object : CliEventParser {
                    override fun onLine(line: String) = listOf(AgentEvent.Delta(agent.id, line.trim()))
                    override fun finish(exitCode: Int, stderr: String) = AgentOutcome(agent.id, "", false)
                }
            }
            val first = withTimeout(10000) { runtime.run(AgentRequest(agent, "test", ".")).first() }
            val pid = (first as AgentEvent.Delta).text.toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Cancelled harness was left running")
        }
    }
}
