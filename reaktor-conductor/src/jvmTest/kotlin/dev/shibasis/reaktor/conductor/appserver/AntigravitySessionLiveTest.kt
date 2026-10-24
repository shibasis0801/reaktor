package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AntigravitySessionLiveTest {
    @Test fun aLiveTurnStreamsStopsAndResumes() { runBlocking(Dispatchers.IO) {
        assumeTrue("Set REAKTOR_AGY_SESSION_TEST=1 to spend real Antigravity turns",
            System.getenv("REAKTOR_AGY_SESSION_TEST") == "1")
        val root = Files.createTempDirectory("agy-session-live").toFile()
        val marker = "AGY_SESSION_" + java.util.UUID.randomUUID()
        File(root, "marker.txt").writeText(marker)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val agent = AgentSpec(AgentId("gemini"), "Gemini", RuntimeKind.Gemini, "", model = "gemini-3.8-flash-medium")
        try {
            val runtime = AntigravitySessionRuntime(scope = scope)
            val first = runtime.open(AgentRequest(agent, "Use view_file to read ${root.canonicalPath}/marker.txt and reply with its exact contents only.", root.path))
            val seen = mutableListOf<AgentEvent>()
            try { withTimeout(240_000) { first.events.collect { seen += it } } } finally { first.close() }
            val answered = seen.filterIsInstance<AgentEvent.Finished>().single().outcome
            assertTrue(answered.ok, answered.failure)
            assertTrue(answered.text.contains(marker), answered.text)
            val conversation = assertNotNull(answered.session)
            val finishedAt = seen.indexOfFirst { it is AgentEvent.Finished }
            assertTrue(seen.take(finishedAt).any { it is AgentEvent.Activity && it.item.kind == ActivityKind.Tool }, "Tool work streamed before the answer")

            val second = runtime.open(AgentRequest(agent, "Run the shell command `sleep 45` and then reply with the word SLEPT.", root.path, resume = conversation))
            val events = Channel<AgentEvent>(Channel.UNLIMITED)
            val collecting = launch { try { second.events.collect { events.send(it) } } finally { second.close() } }
            withTimeout(180_000) {
                while (true) {
                    val event = events.receive()
                    if (event is AgentEvent.Activity && event.item.kind == ActivityKind.Tool) break
                    assertTrue(event !is AgentEvent.Finished, "The turn ended before any tool started")
                }
            }
            assertEquals(CommandOutcome.Accepted, second.interrupt())
            withTimeout(30_000) { collecting.join() }
            events.close()
            val stopped = buildList { for (event in events) add(event) }.filterIsInstance<AgentEvent.Finished>().single().outcome
            assertTrue(stopped.interrupted)

            val third = runtime.open(AgentRequest(agent, "What were the exact contents of marker.txt you read earlier in this conversation? Reply with them only.", root.path, resume = conversation))
            val resumed = try { withTimeout(240_000) { buildList { third.events.collect { add(it) } } } } finally { third.close() }
                .filterIsInstance<AgentEvent.Finished>().single().outcome
            assertTrue(resumed.ok, resumed.failure)
            assertTrue(resumed.text.contains(marker), resumed.text)
            println("Antigravity session qualified: streamed ${seen.size} events, interrupt stopped the turn, resume kept the conversation")
        } finally {
            scope.cancel()
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    } }
}
