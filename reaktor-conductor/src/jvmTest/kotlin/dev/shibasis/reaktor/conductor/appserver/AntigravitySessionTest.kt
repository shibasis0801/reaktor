package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.workspace.workspaceCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AntigravitySessionTest {
    private val temporary = File(System.getProperty("java.io.tmpdir"))

    @Test fun aTurnShowsItsWorkWhileItRunsAndTakesNoSteering() = session(Finish.OnSignal) { root, runtime ->
        val session = runtime.open(request(root))
        val seen = Channel<AgentEvent>(Channel.UNLIMITED)
        val collecting = launch { session.events.collect { seen.send(it) } }
        val early = mutableListOf<AgentEvent>()
        withTimeout(20_000) { while (early.none { it is AgentEvent.Delta }) early += seen.receive() }
        assertTrue(early.none { it is AgentEvent.Finished })
        assertTrue(early.any { it is AgentEvent.Activity && it.item.kind == ActivityKind.Tool && it.item.title == "view_file" })
        assertEquals(1, early.count { it is AgentEvent.TurnStarted })
        val turn = assertNotNull(session.activeTurn)
        assertEquals(CommandOutcome.Unsupported("Antigravity steering"), session.steer("Also check the tests", turn))
        assertIs<CommandOutcome.Stale>(session.steer("Also check the tests", "turn-999"))
        assertEquals(listOf("c2"), session.nativeAgents().map { it.id })

        File(root, "go").createNewFile()
        withTimeout(20_000) { collecting.join() }
        seen.close()
        val events = early + buildList { for (event in seen) add(event) }
        val outcome = events.filterIsInstance<AgentEvent.Finished>().single().outcome
        assertTrue(outcome.ok, outcome.failure)
        assertEquals("Read it", outcome.text)
        assertEquals("c1", outcome.session?.sessionId)
        assertNull(session.activeTurn)
        assertIs<CommandOutcome.Stale>(session.interrupt())
        session.close()

        val message = Json.parseToJsonElement(File(root, "messages.txt").readLines().single()).jsonObject
        assertEquals("user", message["event"]?.jsonPrimitive?.content)
        val text = message["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("Workspace: ") && text.endsWith("Read marker.txt"), text)
        val argv = File(root, "argv.txt").readLines()
        assertTrue("--print=" in argv)
        assertEquals("stream-json", argv[argv.indexOf("--input-format") + 1])
        assertEquals("stream-json", argv[argv.indexOf("--output-format") + 1])
        assertEquals("gemini-4-argon-high", argv[argv.indexOf("--model") + 1])
        assertNoneAlive(root)
    }

    @Test fun interruptingEndsTheTurnAndItsProcess() = session(Finish.Never) { root, runtime ->
        val session = runtime.open(request(root))
        val seen = Channel<AgentEvent>(Channel.UNLIMITED)
        val collecting = launch { session.events.collect { seen.send(it) } }
        withTimeout(20_000) { while (seen.receive() !is AgentEvent.Delta) Unit }
        assertEquals(CommandOutcome.Accepted, session.interrupt())
        withTimeout(20_000) { collecting.join() }
        seen.close()
        val outcome = buildList { for (event in seen) add(event) }.filterIsInstance<AgentEvent.Finished>().single().outcome
        assertTrue(outcome.interrupted)
        assertFalse(outcome.ok)
        assertEquals("Interrupted", outcome.failure)
        assertNull(session.activeTurn)
        session.close()
        assertNoneAlive(root)
    }

    @Test fun aFullModelStepsDownBeforeAnythingIsShown() =
        session(Finish.Immediately, full = setOf("gemini-4-argon-high", "gemini-3.8-flash-high")) { root, runtime ->
            val events = withTimeout(30_000) { runtime.run(request(root)).toList() }
            val outcome = events.filterIsInstance<AgentEvent.Finished>().single().outcome
            assertTrue(outcome.ok, outcome.failure)
            assertEquals("gemini-4-argon-medium", outcome.attributes["model"])
            val switches = events.filterIsInstance<AgentEvent.Activity>().filter { it.item.id.startsWith("antigravity-capacity") }
            assertEquals(listOf("gemini-4-argon-high is at capacity; trying gemini-3.8-flash-high",
                "gemini-3.8-flash-high is at capacity; trying gemini-4-argon-medium"), switches.map { it.item.title })
            assertEquals(1, events.count { it is AgentEvent.Started })
            assertEquals(1, events.count { it is AgentEvent.TurnStarted })
            assertEquals(listOf("gemini-4-argon-high", "gemini-3.8-flash-high", "gemini-4-argon-medium"), File(root, "calls.txt").readLines())
            assertNoneAlive(root)
        }

    @Test fun aRefusalAfterTheWorkStartedIsTheAnswer() = session(Finish.CapacityAfterWork) { root, runtime ->
        val events = withTimeout(30_000) { runtime.run(request(root)).toList() }
        val outcome = events.filterIsInstance<AgentEvent.Finished>().single().outcome
        assertFalse(outcome.ok)
        assertTrue(outcome.failure.orEmpty().contains("No capacity available"), outcome.failure)
        assertTrue(events.none { it is AgentEvent.Activity && it.item.id.startsWith("antigravity-capacity") })
        assertEquals(1, File(root, "calls.txt").readLines().size)
        assertNoneAlive(root)
    }

    @Test fun antigravityIsInteractiveAndOffersStartAndInterrupt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val runtimes = AgentRuntimes.interactive(scope)
            assertIs<AntigravitySessionRuntime>(runtimes[RuntimeKind.Gemini])
            val gemini = workspaceCapabilities(runtimes) { ProviderCapability(it, version = "1.4.7") }.single { it.runtime == RuntimeKind.Gemini }
            assertTrue(gemini.session.usable)
            assertEquals(setOf("start", "interrupt"), gemini.controls.keys)
        } finally { scope.cancel() }
    }

    private enum class Finish { Immediately, OnSignal, Never, CapacityAfterWork }

    private fun session(finish: Finish, full: Set<String> = emptySet(), body: suspend CoroutineScope.(File, AntigravitySessionRuntime) -> Unit) = runBlocking(Dispatchers.IO) {
        val root = Files.createTempDirectory("agy-session").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            body(root, AntigravitySessionRuntime(fakeAgy(root, finish, full).absolutePath, scope))
        } finally {
            scope.cancel()
            root.deleteTreeSafely(within = temporary)
        }
    }

    private fun request(root: File) = AgentRequest(AgentSpec(AgentId("gemini"), "Gemini", RuntimeKind.Gemini, ""), "Read marker.txt", root.absolutePath)

    private fun assertNoneAlive(root: File) {
        val pids = File(root, "pids.txt").readLines().map { it.trim().toLong() }
        assertTrue(pids.isNotEmpty())
        val alive = pids.filter { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
        assertTrue(alive.isEmpty(), "Still running: $alive")
    }

    private fun fakeAgy(root: File, finish: Finish, full: Set<String>): File {
        val step = { index: Int, body: String -> """echo '{"event":"step_update","step_update":{"conversation_id":"c1","step_index":$index,$body}}'""" }
        val work = listOf(
            step(1, """"state":"DONE","step_type":"tool","tool_name":"view_file","tool_info":{"parameters":{"AbsolutePath":"marker.txt"}}"""),
            step(2, """"state":"ACTIVE","step_type":"subagent","subagent_info":{"subagents":[{"conversation_id":"c2","role":"reviewer"}]}"""),
            step(3, """"state":"ACTIVE","step_type":"agent_response","text_delta":"Read it""""),
        ).joinToString("\n")
        val success = """echo '{"event":"result","result":{"conversation_id":"c1","status":"SUCCESS","response":"Read it","usage":{"input_tokens":3,"output_tokens":2}}}'"""
        val refusal = """echo '{"event":"result","result":{"conversation_id":"c1","status":"ERROR","response":"","error":"API error (attempt 1): UNAVAILABLE (code 503): No capacity available for model '"${'$'}model"' on the server"}}'"""
        val ending = when (finish) {
            Finish.Immediately -> "$work\n$success"
            Finish.OnSignal -> "$work\nwhile [ ! -f \"${root.absolutePath}/go\" ]; do sleep 0.05; done\n$success"
            Finish.Never -> "$work\nsleep 60"
            Finish.CapacityAfterWork -> "$work\n$refusal"
        }
        val script = File(root, "fake-agy.sh")
        script.writeText(
            """
            |#!/bin/bash
            |model=""
            |for arg in "${'$'}@"; do printf '%s\n' "${'$'}arg" >> "${root.absolutePath}/argv.txt"; done
            |while [ ${'$'}# -gt 0 ]; do
            |  if [ "${'$'}1" = "--model" ]; then model="${'$'}2"; fi
            |  shift
            |done
            |echo "${'$'}model" >> "${root.absolutePath}/calls.txt"
            |echo ${'$'}${'$'} >> "${root.absolutePath}/pids.txt"
            |IFS= read -r message
            |printf '%s\n' "${'$'}message" >> "${root.absolutePath}/messages.txt"
            |echo '{"event":"init","conversation_id":"c1","init":{"model":"'"${'$'}model"'"}}'
            |case " ${full.joinToString(" ")} " in
            |  *" ${'$'}model "*)
            |    $refusal
            |    ;;
            |  *)
            ${ending.lines().joinToString("\n") { "|    $it" }}
            |    ;;
            |esac
            |while IFS= read -r more; do printf '%s\n' "${'$'}more" >> "${root.absolutePath}/messages.txt"; done
            """.trimMargin(),
        )
        script.setExecutable(true)
        return script
    }
}
