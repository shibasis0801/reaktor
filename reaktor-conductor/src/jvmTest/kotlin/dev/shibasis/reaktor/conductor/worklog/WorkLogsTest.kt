package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessSessions
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkLogsTest {
    private lateinit var home: File
    private lateinit var root: String

    @BeforeTest fun machine() {
        home = Files.createTempDirectory("worklog-home").toFile().canonicalFile
        root = home.resolve("dev/bestbuds").apply { mkdirs() }.path
    }

    @AfterTest fun clean() {
        home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
    }

    @Test fun claudeTranscriptBecomesTurnsWithCommandsEditsPlansAndSubagents() {
        val file = home.resolve(".claude/projects/-dev-bestbuds/s1.jsonl").apply { parentFile.mkdirs() }
        file.writeText(lines(
            """{"type":"user","timestamp":"2026-10-17T10:00:00Z","message":{"role":"user","content":"Fix the login graph"}}""",
            """{"type":"assistant","timestamp":"2026-10-17T10:00:02Z","thinkingDurationMs":1200,"message":{"id":"m1","usage":{"input_tokens":10,"cache_read_input_tokens":900,"output_tokens":40},"content":[{"type":"thinking","thinking":"Look at the route first"}]}}""",
            """{"type":"assistant","timestamp":"2026-10-17T10:00:03Z","message":{"id":"m1","stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew :app:test","description":"Run the tests"}}]}}""",
            """{"type":"user","timestamp":"2026-10-17T10:00:13Z","toolUseResult":{"stdout":"BUILD FAILED","stderr":"","interrupted":false},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","is_error":true,"content":"Exit code 1\nBUILD FAILED"}]}}""",
            """{"type":"assistant","timestamp":"2026-10-17T10:00:14Z","message":{"id":"m2","content":[{"type":"tool_use","id":"t2","name":"Edit","input":{"file_path":"$root/app/Login.kt","old_string":"a","new_string":"b"}}]}}""",
            """{"type":"user","timestamp":"2026-10-17T10:00:15Z","toolUseResult":{"filePath":"$root/app/Login.kt","structuredPatch":[{"oldStart":4,"oldLines":1,"newStart":4,"newLines":2,"lines":["-a","+b","+c"]}]},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"ok"}]}}""",
            """{"type":"assistant","timestamp":"2026-10-17T10:00:16Z","message":{"id":"m3","content":[{"type":"tool_use","id":"t3","name":"mcp__ccd_turn__update_plan","input":{"items":[{"content":"Read","status":"in_progress"},{"content":"Fix","status":"pending"}]}},{"type":"tool_use","id":"t4","name":"mcp__ccd_turn__update_plan","input":{"advance":true}}]}}""",
            """{"type":"assistant","timestamp":"2026-10-17T10:00:17Z","message":{"id":"m4","content":[{"type":"tool_use","id":"t5","name":"Agent","input":{"description":"Survey callers","prompt":"Find callers"}}]}}""",
            """{"type":"user","timestamp":"2026-10-17T10:00:30Z","toolUseResult":{"agentId":"a1","status":"completed","resolvedModel":"claude-sonnet-5-5"},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t5","content":[{"type":"text","text":"Two callers"}]}]}}""",
            """{"type":"assistant","timestamp":"2026-10-17T10:00:31Z","message":{"id":"m5","stop_reason":"end_turn","content":[{"type":"text","text":"Fixed the login route."}]}}""",
        ))
        home.resolve(".claude/projects/-dev-bestbuds/s1/subagents").apply { mkdirs() }.let { folder ->
            folder.resolve("agent-a1.meta.json").writeText("""{"toolUseId":"t5","agentType":"Explore"}""")
            folder.resolve("agent-a1.jsonl").writeText(lines(
                """{"type":"user","timestamp":"2026-10-17T10:00:18Z","message":{"role":"user","content":"Find callers"}}""",
                """{"type":"assistant","timestamp":"2026-10-17T10:00:19Z","message":{"id":"n1","content":[{"type":"tool_use","id":"u1","name":"Grep","input":{"pattern":"login("}}]}}""",
            ))
        }

        assertEquals(file, WorkLogs.transcript(RuntimeKind.ClaudeCode, "s1", home))
        val log = WorkLogs.read(RuntimeKind.ClaudeCode, file, root)
        val turn = log.turns.single()
        assertEquals("Fix the login graph", turn.prompt)
        assertFalse(turn.open)
        assertEquals(WorkUsage(910, 40, null), turn.usage)
        val steps = turn.steps
        assertIs<WorkStep.Reasoning>(steps[0])
        val command = assertIs<WorkStep.Command>(steps[1])
        assertEquals(StepStatus.Failed, command.status)
        assertEquals(1, command.exitCode)
        assertEquals("BUILD FAILED", command.output)
        assertEquals(10_000L, command.millis)
        val edit = assertIs<WorkStep.Edit>(steps[2])
        assertEquals("app/Login.kt", edit.file)
        assertEquals(2, edit.added)
        assertEquals(4, edit.line)
        assertEquals(listOf(PlanState.Done, PlanState.Active), assertIs<WorkStep.Plan>(steps[4]).items.map { it.state })
        val agent = assertIs<WorkStep.Subagent>(steps[5])
        assertEquals("claude-sonnet-5-5", agent.model)
        assertEquals("Two callers", agent.result)
        assertEquals("Search", assertIs<WorkStep.Tool>(agent.steps.single()).name)
        assertEquals("Fixed the login route.", assertIs<WorkStep.Message>(steps.last()).text)
        assertEquals(listOf("app/Login.kt"), log.files)

        file.appendText(lines("""{"type":"user","timestamp":"2026-10-17T10:05:00Z","message":{"role":"user","content":"Now the logout"}}"""))
        assertEquals(listOf("Fix the login graph", "Now the logout"), WorkLogs.read(RuntimeKind.ClaudeCode, file, root).turns.map { it.prompt })
    }

    @Test fun codexRolloutBecomesTurnsWithDiffsAndToolCalls() {
        val file = home.resolve(".codex/sessions/2026/10/17/rollout-2026-10-17T10-00-00-x9.jsonl").apply { parentFile.mkdirs() }
        file.writeText(lines(
            """{"timestamp":"2026-10-17T10:00:00Z","type":"event_msg","payload":{"type":"task_started"}}""",
            """{"timestamp":"2026-10-17T10:00:00Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","content":[{"type":"text","text":"Wire the gateway"}]}}}""",
            """{"timestamp":"2026-10-17T10:00:01Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"Reasoning","summary_text":["Plan the route"],"raw_content":[]}}}""",
            """{"timestamp":"2026-10-17T10:00:05Z","type":"event_msg","payload":{"type":"item_completed","started_at_ms":1792231202000,"completed_at_ms":1792231205000,"item":{"type":"CommandExecution","id":"c1","command":["/bin/zsh","-lc","rg gateway"],"parsed_cmd":[{"cmd":"rg gateway"}],"aggregated_output":"no matches","exit_code":1,"status":"failed"}}}""",
            """{"timestamp":"2026-10-17T10:00:06Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"f1","status":"completed","changes":{"$root/gateway/Route.kt":{"type":"update","unified_diff":"@@ -1,2 +1,2 @@\n-old\n+new\n same\n"}}}}}""",
            """{"timestamp":"2026-10-17T10:00:07Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"McpToolCall","id":"m1","server":"reaktor","tool":"kernel","arguments":{"operation":"board.list"},"status":"completed","result":{"content":[{"type":"text","text":"3 tasks"}],"isError":false}}}}""",
            """{"timestamp":"2026-10-17T10:00:08Z","type":"response_item","payload":{"type":"function_call","name":"update_plan","call_id":"p1","arguments":"{\"plan\":[{\"step\":\"Route\",\"status\":\"completed\"},{\"step\":\"Test\",\"status\":\"in_progress\"}]}"}}""",
            """{"timestamp":"2026-10-17T10:00:09Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":5000,"output_tokens":300,"reasoning_output_tokens":100}}}}""",
            """{"timestamp":"2026-10-17T10:00:10Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","content":[{"type":"text","text":"Done."}]}}}""",
            """{"timestamp":"2026-10-17T10:00:11Z","type":"event_msg","payload":{"type":"task_complete"}}""",
        ))
        assertEquals(file, WorkLogs.transcript(RuntimeKind.Codex, "x9", home, Instant.parse("2026-10-17T12:00:00Z")))
        val turn = WorkLogs.read(RuntimeKind.Codex, file, root).turns.single()
        assertEquals("Wire the gateway", turn.prompt)
        assertFalse(turn.open)
        assertTrue(assertIs<WorkStep.Reasoning>(turn.steps[0]).summary)
        val command = assertIs<WorkStep.Command>(turn.steps[1])
        assertEquals("rg gateway", command.command)
        assertEquals(StepStatus.Failed, command.status)
        assertEquals(3_000L, command.millis)
        val edit = assertIs<WorkStep.Edit>(turn.steps[2])
        assertEquals("gateway/Route.kt", edit.file)
        assertEquals(listOf("-old", "+new", " same"), edit.hunks.single().lines)
        val tool = assertIs<WorkStep.Tool>(turn.steps[3])
        assertEquals("reaktor", tool.server)
        assertEquals("3 tasks", tool.output)
        assertEquals(PlanState.Active, assertIs<WorkStep.Plan>(turn.steps[4]).items.last().state)
        assertEquals(WorkUsage(5000, 300, 100), turn.usage)
    }

    @Test fun antigravityTranscriptPairsToolCallsWithTheirResults() {
        val file = home.resolve(".gemini/antigravity-cli/brain/g1/.system_generated/logs/transcript.jsonl").apply { parentFile.mkdirs() }
        file.writeText(lines(
            """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-10-17T10:00:00Z","content":"<USER_REQUEST>\nRename the screen\n</USER_REQUEST><ADDITIONAL_METADATA>x</ADDITIONAL_METADATA>"}""",
            """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-10-17T10:00:01Z","thinking":"Find it","tool_calls":[{"name":"run_command","args":{"CommandLine":"rg Screen","Cwd":"$root","toolSummary":"Search"}},{"name":"replace_file_content","args":{"TargetFile":"$root/ui/Screen.kt","TargetContent":"Old","ReplacementContent":"New","toolSummary":"Rename"}}]}""",
            """{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","created_at":"2026-10-17T10:00:03Z","content":"ui/Screen.kt:1"}""",
            """{"step_index":3,"source":"MODEL","type":"GENERIC","status":"DONE","created_at":"2026-10-17T10:00:04Z","content":"edited"}""",
            """{"step_index":4,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-10-17T10:00:05Z","content":"Renamed it."}""",
        ))
        home.resolve(".gemini/antigravity-cli/cache/last_conversations.json").apply { parentFile.mkdirs() }.writeText("""{"$root":"g1"}""")

        val turn = WorkLogs.read(RuntimeKind.Gemini, file, root).turns.single()
        assertEquals("Rename the screen", turn.prompt)
        assertFalse(turn.open)
        val command = assertIs<WorkStep.Command>(turn.steps[1])
        assertEquals("ui/Screen.kt:1", command.output)
        assertEquals(2_000L, command.millis)
        val edit = assertIs<WorkStep.Edit>(turn.steps[2])
        assertEquals(StepStatus.Done, edit.status)
        assertEquals(listOf("-Old", "+New"), edit.hunks.single().lines)
        assertEquals("Renamed it.", assertIs<WorkStep.Message>(turn.steps.last()).text)

        val now = Instant.parse("2026-10-17T10:01:00Z").toEpochMilli()
        file.setLastModified(now - 30_000)
        val session = HarnessSessions.read(File(root), home, now).single()
        assertEquals(RuntimeKind.Gemini, session.runtime)
        assertEquals("g1", session.id)
        assertEquals("Rename the screen", session.title)
        assertEquals(listOf("ui/Screen.kt"), session.files)
        assertEquals("agy --conversation g1", session.resumeCommand())
    }

    @Test fun codexRateLimitsComeFromTheLatestTokenCount() {
        val file = home.resolve("rollout.jsonl").apply {
            writeText(lines(
                """{"timestamp":"2026-10-17T10:00:00Z","type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":12.5,"window_minutes":300,"resets_at":1760700000},"secondary":null,"plan_type":"pro"}}}""",
                """{"timestamp":"2026-10-17T11:00:00Z","type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":40.0,"window_minutes":300,"resets_at":1760703600},"secondary":{"used_percent":8,"window_minutes":10080,"resets_at":1761000000},"plan_type":"pro"}}}""",
                """{"timestamp":"2026-10-17T11:01:00Z","type":"event_msg","payload":{"type":"agent_message","message":"done"}}""",
            ))
        }
        val limits = WorkLogs.limits(file)!!
        assertEquals(listOf(RateWindow(40.0, 300, 1_760_703_600_000), RateWindow(8.0, 10_080, 1_761_000_000_000)), limits.windows)
        assertEquals("pro", limits.plan)
        assertEquals(Instant.parse("2026-10-17T11:00:00Z").toEpochMilli(), limits.at)
        assertEquals(null, WorkLogs.limits(home.resolve("missing.jsonl")))
    }

    private fun lines(vararg records: String) = records.joinToString("\n", postfix = "\n")
}
