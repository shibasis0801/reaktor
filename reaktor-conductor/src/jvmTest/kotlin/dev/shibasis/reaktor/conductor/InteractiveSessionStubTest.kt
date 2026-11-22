package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InteractiveSessionStubTest {
    @Test fun measuresTheSecondTurnsFirstOutputWithAnIsolatedProcess() = runBlocking {
        val runtime = DelayedProcessRuntime()
        val agent = AgentSpec(AgentId("stub"), "Stub", RuntimeKind.Echo, "")
        val times = mutableListOf<Long>()
        repeat(3) { index ->
            val started = System.nanoTime()
            var first: Long? = null
            val outcome = runtime.awaitSession(AgentRequest(agent, "turn-${index + 1}", ".", executionId = "stub-thread:$index"), {}) { event ->
                if (event is AgentEvent.Delta && first == null) first = (System.nanoTime() - started) / 1_000_000
            }
            assertTrue(outcome.ok)
            assertEquals("turn-${index + 1}", outcome.text)
            times += requireNotNull(first)
        }
        assertEquals(runtime.starts, runtime.closes)
        println("HC_STUB_METRICS starts=${runtime.starts} closes=${runtime.closes} turn1Ms=${times[0]} turn2Ms=${times[1]} turn3Ms=${times[2]}")
    }
}

internal class DelayedProcessRuntime : InteractiveAgentRuntime {
    override val kind = RuntimeKind.Echo
    override val interactive = Qualification.qualified("InteractiveSessionStubTest")
    var starts = 0
        private set
    var closes = 0
        private set

    override fun run(request: AgentRequest): Flow<AgentEvent> = error("The stub is interactive")

    override suspend fun open(request: AgentRequest): AgentSession {
        val process = ProcessBuilder("python3", "-u", "-c", "import sys,time\ntime.sleep(2)\nfor line in sys.stdin:\n print(line.rstrip('\\n'), flush=True)").start()
        starts++
        return object : AgentSession {
            private var turn: String? = null
            private var closed = false
            override val activeTurn get() = turn
            override val events: Flow<AgentEvent> = flow {
                turn = "stub-turn"
                emit(AgentEvent.TurnStarted(request.agent.id, requireNotNull(turn)))
                val answer = withContext(Dispatchers.IO) {
                    process.outputStream.bufferedWriter().apply { write(request.prompt); newLine(); flush() }
                    requireNotNull(process.inputStream.bufferedReader().readLine())
                }
                answer.chunked(1_000).forEach { emit(AgentEvent.Delta(request.agent.id, it)) }
                turn = null
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, answer, true)))
            }
            override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Unsupported("stub steering")
            override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Stale(requestId, null)
            override suspend fun interrupt() = CommandOutcome.Unsupported("stub interruption")
            override fun close() {
                if (closed) return
                closed = true
                process.outputStream.close()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
                }
                process.inputStream.close()
                process.errorStream.close()
                closes++
            }
        }
    }
}
