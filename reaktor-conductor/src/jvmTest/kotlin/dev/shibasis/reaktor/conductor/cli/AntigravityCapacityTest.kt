package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Model routing, against a stand-in that answers like the service does.
 *
 * A real `agy` cannot be made to run out of capacity on demand, and that is the one behaviour worth
 * pinning: the live loop died on a full model and reported the task as failed, when another model
 * on the same allowance was answering the whole time.
 */
class AntigravityCapacityTest {

    @Test fun afullModelStepsDownToTheNextOneAndOnlyTheAnsweringTurnIsTranscribed() = runBlocking {
        val root = Files.createTempDirectory("agy-capacity").toFile()
        val binary = fakeAgy(root, full = setOf("gemini-3.1-pro-high", "gemini-3.8-flash-high"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = SupervisedProcessExecutor(scope = scope)
        try {
            val events = AntigravityRuntime(executor, binary.absolutePath).run(request(root)).toList()
            val outcome = (events.last() as AgentEvent.Finished).outcome
            assertTrue(outcome.ok, outcome.failure)
            assertEquals("gemini-3.7-flash-high", outcome.attributes["model"], "It settled on the first model with capacity")

            val switches = events.filterIsInstance<AgentEvent.Activity>().filter { it.item.id.startsWith("antigravity-capacity") }
            assertEquals(2, switches.size, "Both refusals are visible rather than silent")
            assertTrue(switches.first().item.title.contains("gemini-3.1-pro-high is at capacity"), switches.first().item.title)

            // The turns that never ran must not leave a transcript behind them.
            assertEquals(1, events.count { it is AgentEvent.Started })
            assertEquals(1, events.count { it is AgentEvent.Finished })
        } finally { scope.cancel(); executor.close(); root.deleteRecursively() }
        Unit
    }

    @Test fun anOrdinaryFailureIsTheAnswerAndIsNotRetriedOnAnotherModel() = runBlocking {
        val root = Files.createTempDirectory("agy-failure").toFile()
        val binary = fakeAgy(root, full = emptySet(), fail = "The build did not compile")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = SupervisedProcessExecutor(scope = scope)
        try {
            val events = AntigravityRuntime(executor, binary.absolutePath).run(request(root)).toList()
            val outcome = (events.last() as AgentEvent.Finished).outcome
            assertFalse(outcome.ok)
            assertTrue(outcome.failure.orEmpty().contains("did not compile"), outcome.failure.orEmpty())
            assertTrue(events.none { it is AgentEvent.Activity && it.item.id.startsWith("antigravity-capacity") },
                "A turn that failed on its merits has an answer; spending the allowance again would buy the same one")
            assertEquals(1, File(root, "calls.txt").readLines().size)
        } finally { scope.cancel(); executor.close(); root.deleteRecursively() }
        Unit
    }

    @Test fun theSeatNamesAModelRatherThanInheritingAStaleOne() {
        val argv = antigravityArgv(request(File("/tmp")).withPreferred())
        assertTrue("--model" in argv, "An unnamed model becomes whatever the CLI falls back to")
        assertEquals(defaultAntigravityModels.first(), argv[argv.indexOf("--model") + 1])
        assertTrue(defaultAntigravityModels.first().contains("pro"), "The default is a working tier, not the cheapest one")
    }

    private fun AgentRequest.withPreferred() = copy(agent = agent.copy(model = defaultAntigravityModels.first()))

    private fun request(root: File) = AgentRequest(
        AgentSpec(AgentId("hybrid"), "Hybrid", RuntimeKind.Gemini, ""), "Inspect the graph", root.absolutePath)

    /** Answers on agy's NDJSON contract, refusing the models named in [full] the way the service does. */
    private fun fakeAgy(root: File, full: Set<String>, fail: String? = null): File {
        val script = File(root, "fake-agy.sh")
        script.writeText(
            """
            #!/bin/bash
            model=""
            while [ ${'$'}# -gt 0 ]; do
              if [ "${'$'}1" = "--model" ]; then model="${'$'}2"; fi
              shift
            done
            echo "${'$'}model" >> "${root.absolutePath}/calls.txt"
            echo '{"event":"init","conversation_id":"c1","init":{"model":"'"${'$'}model"'"}}'
            case " ${full.joinToString(" ")} " in
              *" ${'$'}model "*)
                echo '{"event":"result","result":{"conversation_id":"c1","status":"ERROR","response":"","error":"API error (attempt 1): UNAVAILABLE (code 503): No capacity available for model '"${'$'}model"' on the server"}}'
                ;;
              *)
                ${if (fail != null) """echo '{"event":"result","result":{"conversation_id":"c1","status":"ERROR","response":"","error":"$fail"}}'"""
                  else """echo '{"event":"result","result":{"conversation_id":"c1","status":"SUCCESS","response":"done","usage":{"input_tokens":1,"output_tokens":1}}}'"""}
                ;;
            esac
            exit 0
            """.trimIndent(),
        )
        script.setExecutable(true)
        return script
    }
}
