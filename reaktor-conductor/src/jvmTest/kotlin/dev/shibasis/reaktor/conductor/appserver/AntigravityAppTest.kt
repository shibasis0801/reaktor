package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AntigravityAppTest {
    private val agent = AgentId("gemini")
    private val project = AntigravityApp.Project("965c1e9f", "/Users/ovd/dev", true)

    @Test fun theDeepestProjectHoldingTheFolderWinsAndAWritableOneBreaksATie() {
        val projects = listOf(
            AntigravityApp.Project("dev-plain", "/Users/ovd/dev", true),
            AntigravityApp.Project("dev-git", "/Users/ovd/dev", true, git = true),
            AntigravityApp.Project("dev-locked", "/Users/ovd/dev", false, git = true),
            AntigravityApp.Project("bestbuds", "/Users/ovd/dev/bestbuds", true),
            AntigravityApp.Project("bestbuds-web", "/Users/ovd/dev/bestbuds-web", true),
        )
        assertEquals("bestbuds", AntigravityApp.choose(projects, "/Users/ovd/dev/bestbuds/plans")?.id)
        assertEquals("dev-git", AntigravityApp.choose(projects, "/Users/ovd/dev/reaktor")?.id)
        assertEquals("dev-git", AntigravityApp.choose(projects, "/Users/ovd/dev")?.id)
        assertNull(AntigravityApp.choose(projects, "/Users/ovd/other"))
    }

    @Test fun projectFoldersReadGitAndPlainFoldersAndSkipEverythingElse() {
        val project = Json.parseToJsonElement("""{"id":"p","projectResources":{"resources":[
            {"gitFolder":{"folderUri":"file:///Users/ovd/dev","allowWrite":true}},
            {"gitFolder":{"folderUri":"file:///Users/ovd/dev/reaktor"}},
            {"folderUri":"file:///Users/ovd/dev/bestbuds"},
            {"folderUri":"https://example.com/repo"}]}}""").jsonObject
        assertEquals(listOf(
            AntigravityApp.Project("p", File("/Users/ovd/dev").canonicalPath, true, git = true),
            AntigravityApp.Project("p", File("/Users/ovd/dev/reaktor").canonicalPath, false, git = true),
            AntigravityApp.Project("p", File("/Users/ovd/dev/bestbuds").canonicalPath, true),
        ), AntigravityApp.projectFolders("p", project))
        assertEquals(emptyList(), AntigravityApp.projectFolders("p", null))
    }

    @Test fun answersNameTheConversationThePlannerModelAndTheRunState() {
        val started = Json.parseToJsonElement("""{"response":{"messageId":"572d4a30-77a8-44d6-872a-37bd352ebdae","conversation":{"conversationId":"1cc44344-dcf9-44de-b8bf-f2f43f4f9a08"}}}""").jsonObject
        assertEquals("1cc44344-dcf9-44de-b8bf-f2f43f4f9a08", AntigravityApp.conversationId(started))
        assertEquals("195abc31-ebfa-4aff-a2c5-c97bc895de51", AntigravityApp.conversationId(Json.parseToJsonElement("""[{"cascade_id":"195abc31-ebfa-4aff-a2c5-c97bc895de51"}]""")))
        assertNull(AntigravityApp.conversationId(Json.parseToJsonElement("""{"response":{"messageId":"572d4a30-77a8-44d6-872a-37bd352ebdae","conversationId":"not-a-uuid"}}""").jsonObject))
        val metadata = Json.parseToJsonElement("""{"response":{"conversationMetadata":{"metadata":{"projectId":"p",
            "staticConfig":{"cascadeConfig":{"plannerConfig":{"planModel":"MODEL_PLACEHOLDER_M427"}}}}}}}""").jsonObject
        assertEquals("MODEL_PLACEHOLDER_M427", AntigravityApp.planModel(metadata))
        assertEquals("MODEL_PLACEHOLDER_M417", AntigravityApp.planModel(Json.parseToJsonElement("""{"metadata":{"staticConfig":{"cascadeConfig":
            {"plannerConfig":{"planModel":"MODEL_PLACEHOLDER_M417"}}}},"numSteps":3}""").jsonObject))
        assertNull(AntigravityApp.planModel(Json.parseToJsonElement("""{"metadata":{}}""").jsonObject))
        assertEquals("gemini-4-carbon-high", AntigravityApp.variant("Gemini 4 Carbon (High)"))
        assertEquals("gemini-3.8-flash-medium", AntigravityApp.variant("Gemini 3.8 Flash (Medium)"))
        val labels = AntigravityApp.modelLabels(Json.parseToJsonElement("""{"clientModelConfigs":[
            {"label":"Gemini 4 Carbon (High)","modelOrAlias":{"model":"MODEL_PLACEHOLDER_M427"}},
            {"label":"Gemini 4 Argon (High)","modelOrAlias":{"model":"MODEL_PLACEHOLDER_M417"}},
            {"label":"No model"}]}""").jsonObject)
        assertEquals(mapOf("MODEL_PLACEHOLDER_M427" to "Gemini 4 Carbon (High)", "MODEL_PLACEHOLDER_M417" to "Gemini 4 Argon (High)"), labels)
        assertEquals(AntigravityApp.Run(true, 84, null), AntigravityApp.runOf(Json.parseToJsonElement(
            """{"status":"CASCADE_RUN_STATUS_RUNNING","stepCount":84}""").jsonObject))
        assertEquals(AntigravityApp.Run(false, 32, "EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL"), AntigravityApp.runOf(Json.parseToJsonElement(
            """{"status":"CASCADE_RUN_STATUS_IDLE","stepCount":"32","latestExecutorTerminationReason":"EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL"}""").jsonObject))
        assertNull(AntigravityApp.runOf(null))
    }

    @Test fun aModelWithoutItsLevelTakesTheRequestedEffort() {
        assertEquals("gemini-4-argon-high", antigravityAppModel("gemini-4-argon", NativeEffort("high")))
        assertEquals("gemini-4-carbon-high", antigravityAppModel("gemini-4-carbon-high", NativeEffort("low")))
        assertEquals("gemini-4-carbon", antigravityAppModel("gemini-4-carbon", null))
    }

    @Test fun theLiveHubIsTheNewestOneWhoseProcessIsAlive() {
        val home = Files.createTempDirectory("agy-home").toFile()
        val daemon = home.resolve(".gemini/antigravity/daemon").apply { mkdirs() }
        daemon.resolve("hub_1.json").apply { writeText("""{"pid":11,"httpPort":1111,"csrfToken":"dead"}"""); setLastModified(3_000) }
        daemon.resolve("hub_2.json").apply { writeText("""{"pid":22,"httpPort":2222,"csrfToken":"older"}"""); setLastModified(1_000) }
        daemon.resolve("hub_3.json").apply { writeText("""{"pid":33,"httpPort":3333,"csrfToken":"live"}"""); setLastModified(2_000) }
        daemon.resolve("notes.txt").writeText("ignored")
        val app = AntigravityApp(home, home.resolve("Missing.app"), alive = { it != 11L })
        assertEquals(AntigravityApp.Hub(3333, "live"), app.hub())
        assertNull(AntigravityApp(home, home.resolve("Missing.app"), alive = { false }).hub())
        assertFalse(app.installed)
        assertEquals(home.resolve(".gemini/antigravity/brain/c/.system_generated/logs/transcript.jsonl"), app.transcript("c"))
    }

    @Test fun transcriptStepsBecomeLiveEventsAndOneOutcome() {
        val reader = AntigravityTranscriptEvents(agent)
        val rows = listOf(
            """{"step_index":0,"source":"USER","type":"USER_INPUT","status":"DONE","content":"<USER_REQUEST>Read it</USER_REQUEST>"}""",
            """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","thinking":"Plan the read","content":"Reading the marker.","input_tokens":100,"cache_read_tokens":40,"output_tokens":10,
                "tool_calls":[{"name":"read_file","args":{"Path":"/tmp/marker.txt"}},{"name":"run_command","args":{"CommandLine":"ls"}}]}""",
            """{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","content":"MARKER"}""",
            """{"step_index":3,"source":"MODEL","type":"GENERIC","status":"ERROR","error":"denied","content":"Encountered error"}""",
            """{"step_index":4,"source":"MODEL","type":"CHECKPOINT","status":"DONE","content":""}""",
            """{"step_index":5,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","content":"MARKER","input_tokens":120,"cache_read_tokens":60,"output_tokens":4}""",
            "not json",
        )
        val events = rows.flatMap(reader::line)
        assertEquals(AgentEvent.Reasoning(agent, "Plan the read", ReasoningFidelity.Thinking), events[0])
        assertEquals(AgentEvent.Delta(agent, "Reading the marker."), events[1])
        val tools = events.filterIsInstance<AgentEvent.Activity>().filter { it.item.kind == ActivityKind.Tool }
        assertEquals(listOf("read_file" to ActivityStatus.Started, "run_command" to ActivityStatus.Started,
            "read_file" to ActivityStatus.Completed, "run_command" to ActivityStatus.Failed), tools.map { it.item.title to it.item.status })
        assertEquals(listOf("agy-1-0", "agy-1-1", "agy-1-0", "agy-1-1"), tools.map { it.item.id })
        assertEquals("denied", tools.last().item.output)
        assertTrue(events.any { it is AgentEvent.Activity && it.item.kind == ActivityKind.Checkpoint })
        assertEquals(AgentEvent.Delta(agent, "\n\nMARKER"), events.last())
        assertTrue(reader.settled)
        assertTrue(reader.progressed)
        val outcome = reader.outcome(AntigravityApp.Run(false, 6, "EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL"), "c", project, "gemini-4-argon-high", NativeEffort("high"), false)
        assertTrue(outcome.ok, outcome.failure)
        assertEquals("MARKER", outcome.text)
        assertEquals(ProviderSession(RuntimeKind.Gemini, "c"), outcome.session)
        assertEquals(AgentUsage(inputTokens = 320, outputTokens = 14, cachedInputTokens = 100, scope = UsageScope.Turn), outcome.usage)
        assertEquals("Antigravity app", outcome.attributes["executorHarness"])
        assertEquals("965c1e9f", outcome.attributes["project"])
    }

    @Test fun cancelledErroredAndSilentTurnsSayWhy() {
        val cancelled = AntigravityTranscriptEvents(agent).apply { line("""{"step_index":1,"type":"PLANNER_RESPONSE","status":"DONE","content":"Half"}""") }
            .outcome(AntigravityApp.Run(false, 2, "EXECUTOR_TERMINATION_REASON_USER_CANCELED"), "c", project, "m", null, false)
        assertTrue(cancelled.interrupted)
        assertFalse(cancelled.ok)
        val errored = AntigravityTranscriptEvents(agent).apply { line("""{"step_index":1,"type":"ERROR_MESSAGE","status":"DONE","content":"No capacity available for model"}""") }
        assertTrue(errored.settled)
        assertFalse(errored.progressed)
        val failed = errored.outcome(AntigravityApp.Run(false, 2, "EXECUTOR_TERMINATION_REASON_ERROR"), "c", project, "m", null, false)
        assertEquals("No capacity available for model", failed.failure)
        val silent = AntigravityTranscriptEvents(agent).outcome(AntigravityApp.Run(false, 2, "EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL"), "c", project, "m", null, false)
        assertEquals("Antigravity ended the turn without an answer (EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL)", silent.failure)
        val budget = AntigravityTranscriptEvents(agent).outcome(null, "c", project, "m", null, false, limit = "budget")
        assertEquals("budget", budget.failure)
        assertNull(budget.usage)
    }

    @Test fun theTailReturnsOnlyWholeLinesAndKeepsSplitCharactersIntact() = runBlocking {
        val file = Files.createTempDirectory("agy-tail").toFile().resolve("logs/transcript.jsonl")
        TranscriptTail(file, 0).use { tail ->
            assertEquals(emptyList(), tail.lines())
            file.parentFile.mkdirs()
            val bytes = "{\"a\":\"één\"}\n{\"b\":".toByteArray()
            file.writeBytes(bytes.copyOfRange(0, 8))
            assertEquals(emptyList(), tail.lines())
            file.appendBytes(bytes.copyOfRange(8, bytes.size))
            assertEquals(listOf("{\"a\":\"één\"}"), tail.lines())
            file.appendText("2}\n\n")
            assertEquals(listOf("{\"b\":2}"), tail.lines())
            tail.await(10)
            assertEquals(emptyList(), tail.lines())
        }
        TranscriptTail(file, file.length()).use { assertEquals(emptyList(), it.lines()) }
    }
}
