package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.async
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

    @Test fun aResumeWaitsForANewUserRecordBeforeStartingItsTurn() = kotlinx.coroutines.runBlocking {
        send("Earlier question")
        val events = mutableListOf<AgentEvent>()
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val runtime = ClaudeAppRuntime(app(), outside = Outside(), briefs = File(home, "briefs"))
        val session = runtime.open(AgentRequest(spec(true), "New question", work.path, resume = ProviderSession(RuntimeKind.ClaudeCode, "cli-1")))
        val job = async { session.events.onEach { event -> events += event; if (event is AgentEvent.Activity && event.item.id == "claude-app-send") waiting.complete(Unit) }.toList() }
        try {
            kotlinx.coroutines.withTimeout(2000) { waiting.await() }
            repeat(8) { kotlinx.coroutines.yield() }
            assertFalse(events.any { it is AgentEvent.Started }, "An existing session is not evidence that its new prompt was sent")
            File(home, ".claude/projects/-work/cli-1.jsonl").appendText("""{"type":"user","message":{"content":"New question"}}
{"type":"assistant","message":{"stop_reason":"end_turn","content":[{"type":"text","text":"New answer"}]}}
""")
            assertEquals("New answer", kotlinx.coroutines.withTimeout(4000) { job.await() }.filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        } finally { job.cancel(); session.close() }
    }

    @Test fun aKnownResumeWaitsForTheTranscriptWithoutReadingTheStoreOnATimer() = runBlocking {
        send("Earlier question")
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = ClaudeAppRuntime(app(), outside = Outside(), briefs = File(home, "briefs"))
            .open(AgentRequest(spec(true), "Next question", work.path, resume = ProviderSession(RuntimeKind.ClaudeCode, "cli-1")))
        val job = async { session.events.onEach { if (it is AgentEvent.Activity && it.item.id == "claude-app-send") waiting.complete(Unit) }.toList() }
        try {
            kotlinx.coroutines.withTimeout(2000) { waiting.await() }
            kotlinx.coroutines.delay(100)
            val recording = jdk.jfr.Recording().apply { enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO); start() }
            kotlinx.coroutines.delay(2200)
            recording.stop()
            val saved = home.resolve("resume-reads.jfr")
            recording.dump(saved.toPath()); recording.close()
            val reads = jdk.jfr.consumer.RecordingFile.readAllEvents(saved.toPath()).count {
                it.eventType.name == "jdk.FileRead" && it.getString("path")?.contains("claude-code-sessions") == true
            }
            assertEquals(0, reads, "A known session re-read its app store while waiting for the user's prompt")
            File(home, ".claude/projects/-work/cli-1.jsonl").appendText("""{"type":"user","message":{"content":"Next question"}}
{"type":"assistant","message":{"stop_reason":"end_turn","content":[{"type":"text","text":"New answer"}]}}
""")
            assertEquals("New answer", kotlinx.coroutines.withTimeout(2000) { job.await() }.filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        } finally { job.cancel(); session.close() }
    }

    @Test fun longBriefsAreOwnerOnlyAndRemovedAfterTheRun() = runBlocking {
        var saved: File? = null
        val secure = ClaudeApp(home, File(home, "Applications/Claude.app")) { url ->
            val message = sent(url)
            saved = File(message.removePrefix("Read the brief at ").removeSuffix(" and do what it says."))
            val permissions = Files.getPosixFilePermissions(saved!!.toPath())
            assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), permissions)
            send(message)
        }
        ClaudeAppRuntime(secure, outside = Outside(), briefs = File(home, "private-briefs"))
            .run(AgentRequest(spec(true), "x".repeat(20000), work.path)).toList()
        assertFalse(requireNotNull(saved).exists())
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
        var contents = ""
        val capture = ClaudeApp(home, File(home, "Applications/Claude.app")) { url ->
            launched += url
            val message = sent(url)
            contents = File(message.removePrefix("Read the brief at ").removeSuffix(" and do what it says.")).readText()
            send(message)
        }
        val events = ClaudeAppRuntime(capture, outside = Outside(), briefs = File(home, "briefs"))
            .run(AgentRequest(spec(writes = false), prompt, work.path)).toList()
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().single().outcome.ok)
        val sent = sent(launched.single())
        assertTrue(sent.startsWith("Read the brief at ${File(home, "briefs").path}/"))
        val brief = File(sent.removePrefix("Read the brief at ").removeSuffix(" and do what it says."))
        assertFalse(brief.exists())
        assertTrue(contents.startsWith("Inspect only: read, search and report."))
        assertTrue(contents.endsWith(prompt))
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
