package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.worklog.WorkLogs
import dev.shibasis.reaktor.conductor.worklog.WorkStep
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentEventProjectionTest {
    private val agent = AgentSpec(AgentId("stub"), "Stub", RuntimeKind.Echo, "")
    private fun run() = AgentRunRecord("stub-run", "stub-thread", RuntimeKind.Echo, "stub", "Stub", startedAt = 1, updatedAt = 1)

    @Test fun theSecondTurnKeepsEveryStreamedCharacterAndTheCompletedLog() = runBlocking {
        val runtime = DelayedProcessRuntime()
        val directory = Files.createTempDirectory("projection-stream-stub").toFile()
        try {
            val file = directory.resolve("rollout.jsonl")
            val answers = listOf("First answer", "0123456789".repeat(5_000))
            answers.forEachIndexed { turn, answer ->
                var state = run()
                var length = 0
                val outcome = runtime.awaitSession(AgentRequest(agent, answer, directory.path), {}) { event ->
                    state = projectAgentEvent(state, event, RuntimeKind.Echo)
                    if (event is AgentEvent.Delta) {
                        length += event.text.length
                        assertEquals(length, state.output.length)
                        assertEquals(length, state.participants.getValue(agent.id.value).output.length)
                        assertFalse(state.outputTruncated)
                        assertFalse(state.participants.getValue(agent.id.value).outputTruncated)
                    }
                }
                assertTrue(outcome.ok)
                assertEquals(answer, state.participants.getValue(agent.id.value).output)
                fun record(type: String, content: String) = buildJsonObject {
                    put("type", "event_msg")
                    putJsonObject("payload") {
                        put("type", "item_completed")
                        putJsonObject("item") { put("type", type); put("id", "$turn-$type"); put("content", content) }
                    }
                }.toString() + "\n"
                file.appendText(record("UserMessage", "Turn ${turn + 1}") + record("AgentMessage", answer))
            }
            val log = WorkLogs.read(RuntimeKind.Codex, file, directory.path)
            assertEquals(2, log.turns.size)
            assertEquals(answers, log.turns.map { it.steps.filterIsInstance<WorkStep.Message>().single().text })
            assertEquals(runtime.starts, runtime.closes)
        } finally {
            directory.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun reasoningIsLosslessForTheParticipantAndTheRun() {
        var state = run()
        val text = "reasoning ".repeat(5_000)
        text.chunked(997).forEach { chunk -> state = projectAgentEvent(state, AgentEvent.Reasoning(agent.id, chunk, ReasoningFidelity.Thinking), RuntimeKind.Echo) }
        assertEquals(text, state.reasoning)
        assertEquals(text, state.participants.getValue(agent.id.value).reasoning)
        assertFalse(state.reasoningTruncated)
        assertFalse(state.participants.getValue(agent.id.value).reasoningTruncated)
    }

    @Test fun aFinishedFailureKeepsItsWholeDiagnostic() {
        val failure = "diagnostic ".repeat(5_000)
        val state = projectAgentEvent(run(), AgentEvent.Finished(agent.id, AgentOutcome(agent.id, "", false, failure = failure)), RuntimeKind.Echo)
        assertEquals(failure, state.participants.getValue(agent.id.value).output)
        assertFalse(state.participants.getValue(agent.id.value).outputTruncated)
    }
}
