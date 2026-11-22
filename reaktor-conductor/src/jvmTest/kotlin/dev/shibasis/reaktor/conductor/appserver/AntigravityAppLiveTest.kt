package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityApp
import dev.shibasis.reaktor.conductor.cli.antigravityModelNames
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import kotlin.test.*

class AntigravityAppLiveTest {
    @Test fun aLiveConversationShowsInTheAppAnswersContinuesAndStops() { runBlocking(Dispatchers.IO) {
        assumeTrue("Set REAKTOR_AGY_APP_TEST=1 to spend real Antigravity turns in the app",
            System.getenv("REAKTOR_AGY_APP_TEST") == "1")
        val app = AntigravityApp()
        assumeTrue("Antigravity is not installed", app.installed)
        val root = File(System.getProperty("user.dir"), "build/agy-app-live/${UUID.randomUUID()}").apply { mkdirs() }
        val marker = "AGY_APP_" + UUID.randomUUID()
        File(root, "marker.txt").writeText(marker)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = System.getenv("REAKTOR_AGY_APP_MODEL") ?: "gemini-4-argon-high"
        val agent = AgentSpec(AgentId("gemini"), "Reaktor live test", RuntimeKind.Gemini, "", model = model,
            tools = ToolPolicy(allowWrites = true))
        try {
            val runtime = AntigravityAppRuntime(app, scope)
            val first = runtime.open(AgentRequest(agent, "Read ${root.canonicalPath}/marker.txt with your file tool and reply with its exact contents only.", root.path))
            val seen = mutableListOf<AgentEvent>()
            try { withTimeout(300_000) { first.events.collect { seen += it } } } finally { first.close() }
            val answered = seen.filterIsInstance<AgentEvent.Finished>().single().outcome
            assertTrue(answered.ok, answered.failure)
            assertTrue(answered.text.contains(marker), answered.text)
            val conversation = assertNotNull(answered.session)
            assertTrue(seen.none { it is AgentEvent.Activity && it.item.id == "antigravity-headless" }, "Ran in the app")
            assertTrue(seen.any { it is AgentEvent.Activity && it.item.kind == ActivityKind.Tool }, "Tool work streamed before the answer")
            val hub = app.connect()
            assertNotNull(app.run(hub, conversation.sessionId), "The app lists the conversation")
            assertEquals(antigravityModelNames[model], app.model(hub, conversation.sessionId))

            val second = runtime.open(AgentRequest(agent, "Run the shell command `sleep 60` and then reply with the word SLEPT.", root.path, resume = conversation))
            val events = Channel<AgentEvent>(Channel.UNLIMITED)
            val collecting = launch { try { second.events.collect { events.send(it) } } finally { second.close() } }
            withTimeout(180_000) {
                while (true) {
                    val event = events.receive()
                    if (event is AgentEvent.Activity && event.item.kind == ActivityKind.Tool && event.item.status == ActivityStatus.Started) break
                    if (event is AgentEvent.Finished) fail("The turn finished before its command started: ${event.outcome.failure}")
                }
            }
            assertEquals(CommandOutcome.Accepted, second.interrupt())
            val stopped = withTimeout(120_000) {
                var finished: AgentOutcome? = null
                while (finished == null) finished = (events.receive() as? AgentEvent.Finished)?.outcome
                finished
            }
            assertTrue(stopped.interrupted, stopped.failure)
            assertEquals(conversation, stopped.session)
            collecting.join()
        } finally {
            scope.cancel()
            root.deleteTreeSafely(within = File(System.getProperty("user.dir"), "build"))
        }
    } }
}
