package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.*

class ClaudeAppTest {
    private val agent = AgentId("claude")
    private lateinit var home: File
    private lateinit var work: File
    private val launched = mutableListOf<String>()

    @BeforeTest fun machine() {
        home = Files.createTempDirectory("claude-home").toFile().canonicalFile
        work = Files.createTempDirectory("claude-work").toFile().canonicalFile
        File(home, "Applications/Claude.app").mkdirs()
    }

    @AfterTest fun clean() {
        home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        work.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
    }

    @Test fun aRunOpensInTheClaudeAppAndFollowsTheSessionOnceItIsSent() = runBlocking {
        val events = ClaudeAppRuntime(app(), outside = Outside(), briefs = File(home, "briefs"))
            .run(AgentRequest(spec(writes = true), "Review the agents pane", work.path)).toList()
        val outcome = events.filterIsInstance<AgentEvent.Finished>().single().outcome
        assertTrue(outcome.ok, outcome.failure)
        assertEquals("Looks fine", outcome.text)
        assertEquals(ProviderSession(RuntimeKind.ClaudeCode, "cli-1"), outcome.session)
        assertEquals("Claude app", outcome.attributes["executorHarness"])
        assertEquals("local_abc", outcome.attributes["session"])
        val waiting = events.filterIsInstance<AgentEvent.Activity>().first { it.item.id == "claude-app-send" }.item
        assertEquals("Send it in the Claude app", waiting.title)
        assertTrue(waiting.output.orEmpty().contains("claude-sonnet-5-5 · high"))
        assertEquals("claude://code/new?q=Review%20the%20agents%20pane&folder=${encode(work.path)}&source=reaktor", launched.single())
    }

    @Test fun anInspectOnlyBriefSaysSoAndALongOneGoesThroughAFile() = runBlocking {
        val prompt = "Audit the pane. " + "x".repeat(20_000)
        val events = ClaudeAppRuntime(app(), outside = Outside(), briefs = File(home, "briefs"))
            .run(AgentRequest(spec(writes = false), prompt, work.path)).toList()
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().single().outcome.ok)
        val sent = sent(launched.single())
        assertTrue(sent.startsWith("Read the brief at ${File(home, "briefs").path}/"))
        val brief = File(sent.removePrefix("Read the brief at ").removeSuffix(" and do what it says."))
        assertTrue(brief.readText().startsWith("Inspect only: read, search and report."))
        assertTrue(brief.readText().endsWith(prompt))
    }

    @Test fun steeringAndStoppingShowTheSessionInTheApp() = runBlocking {
        val runtime = ClaudeAppRuntime(app(), outside = Outside(), briefs = File(home, "briefs"))
        val session = runtime.open(AgentRequest(spec(writes = true), "Review the agents pane", work.path))
        session.events.toList()
        assertIs<CommandOutcome.Unsupported>(session.steer("one more thing", null))
        assertIs<CommandOutcome.Unsupported>(session.interrupt())
        assertEquals(listOf("claude://code/continue?session=local_abc", "claude://code/continue?session=local_abc"), launched.drop(1))
    }

    @Test fun withoutTheAppTheRunGoesOutsideItAndSaysWhy() = runBlocking {
        val events = ClaudeAppRuntime(ClaudeApp(home, File(home, "Missing.app")) { launched += it }, outside = Outside(), briefs = File(home, "briefs"))
            .run(AgentRequest(spec(writes = true), "Review", work.path)).toList()
        assertEquals("Runs outside the Claude app because the Claude app is not installed",
            events.filterIsInstance<AgentEvent.Activity>().first().item.title)
        assertEquals("outside", events.filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        assertEquals(emptyList(), launched)
    }

    private fun app() = ClaudeApp(home, File(home, "Applications/Claude.app")) { url ->
        launched += url
        if (url.startsWith("claude://code/new")) send(sent(url))
    }

    private fun send(prompt: String) {
        val now = System.currentTimeMillis()
        File(home, "Library/Application Support/Claude/claude-code-sessions/account/org/local_abc.json").apply { parentFile.mkdirs() }
            .writeText("""{"sessionId":"local_abc","cliSessionId":"cli-1","cwd":"${work.path}","createdAt":$now,"title":"Review"}""")
        File(home, ".claude/projects/-work/cli-1.jsonl").apply { parentFile.mkdirs() }.writeText(listOf(
            """{"type":"user","isSidechain":false,"message":{"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(prompt)}}}""",
            """{"type":"assistant","isSidechain":false,"message":{"id":"m1","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"Looks fine"}]}}""",
        ).joinToString("\n", postfix = "\n"))
        File(home, ".claude/sessions/4242.json").apply { parentFile.mkdirs() }
            .writeText("""{"pid":4242,"sessionId":"cli-1","status":"idle","statusUpdatedAt":$now}""")
    }

    private fun sent(url: String): String = URLDecoder.decode(url.substringAfter("q=").substringBefore("&"), StandardCharsets.UTF_8)

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun spec(writes: Boolean) = AgentSpec(agent, "Reviewer", RuntimeKind.ClaudeCode, "Review", model = "claude-sonnet-5-5",
        effort = NativeEffort("high"), tools = ToolPolicy(allowWrites = writes))

    private inner class Outside : InteractiveAgentRuntime {
        override val kind = RuntimeKind.ClaudeCode
        override val interactive = Qualification(true, true, true)
        override fun run(request: AgentRequest): Flow<AgentEvent> = flowOf(AgentEvent.Finished(agent, AgentOutcome(agent, "outside", true)))
        override suspend fun open(request: AgentRequest): AgentSession = object : AgentSession {
            override val events: Flow<AgentEvent> = flowOf(AgentEvent.Finished(agent, AgentOutcome(agent, "outside", true)))
            override val activeTurn: String? = null
            override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Stale(expectedTurn, null)
            override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Stale(requestId, null)
            override suspend fun interrupt() = CommandOutcome.Stale(null, null)
            override fun close() = Unit
        }
    }
}
