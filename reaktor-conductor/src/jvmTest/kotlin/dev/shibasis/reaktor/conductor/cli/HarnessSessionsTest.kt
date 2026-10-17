package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import dev.shibasis.reaktor.conductor.RuntimeKind
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessSessionsTest {
    private val now = Instant.parse("2026-10-02T07:00:00Z").toEpochMilli()
    private lateinit var home: File
    private lateinit var workspace: File

    @BeforeTest fun machine() {
        home = Files.createTempDirectory("harness-home").toFile()
        workspace = Files.createTempDirectory("harness-dev").toFile().resolve("bestbuds").apply { mkdirs() }.canonicalFile
        val ws = workspace.path
        val parent = workspace.parentFile.path
        claude("-in-workspace", "c1", ws, now - 30_000,
            """{"type":"user","cwd":"$ws","sessionId":"c1","version":"2.1.284","gitBranch":"main","entrypoint":"cli","timestamp":"2026-10-02T06:00:00Z","message":{"role":"user","content":"<command-name>/clear</command-name>"}}""",
            """{"type":"user","cwd":"$ws","sessionId":"c1","timestamp":"2026-10-02T06:00:01Z","message":{"role":"user","content":[{"type":"text","text":"Fix the agent pane\nand the AI pane"}]}}""",
            """{"type":"assistant","timestamp":"2026-10-02T06:59:00Z","message":{"model":"claude-opus-5-5","content":[{"type":"tool_use","name":"Edit","input":{"file_path":"$ws/modules/engine/Agent.kt"}},{"type":"tool_use","name":"mcp__reaktor__graph_query","input":{"query":"impact"}}]}}""",
            """{"type":"custom-title","customTitle":"Hangar agent pane","sessionId":"c1"}""")
        claude("-elsewhere", "c2", "/tmp/elsewhere", now - 30_000,
            """{"type":"user","cwd":"/tmp/elsewhere","sessionId":"c2","timestamp":"2026-10-02T06:00:00Z","message":{"role":"user","content":"Unrelated"}}""")
        claude("-stale", "c3", ws, now - 4 * 86_400_000L,
            """{"type":"user","cwd":"$ws","sessionId":"c3","timestamp":"2026-09-28T06:00:00Z","message":{"role":"user","content":"Old work"}}""")
        File(home, ".codex/session_index.jsonl").apply { parentFile.mkdirs() }
            .writeText("""{"id":"x1","thread_name":"Draft","updated_at":"2026-10-01T10:00:00Z"}""" + "\n" +
                """{"id":"x1","thread_name":"Wire the gateway","updated_at":"2026-10-02T06:00:00Z"}""" + "\n")
        File(home, ".codex/sessions/2026/10/01/rollout-2026-10-01T10-00-00-x1.jsonl").apply { parentFile.mkdirs() }.apply {
            writeText(listOf(
                """{"timestamp":"2026-10-01T10:00:00Z","type":"session_meta","payload":{"id":"x1","cwd":"$parent","originator":"Codex Desktop","cli_version":"0.159.0","timestamp":"2026-10-01T10:00:00Z"}}""",
                """{"timestamp":"2026-10-01T10:00:01Z","type":"turn_context","payload":{"cwd":"$parent","model":"gpt-6.1-sol","effort":"xhigh","workspace_roots":["$parent"]}}""",
                """{"timestamp":"2026-10-02T06:58:00Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"McpToolCall","server":"reaktor","tool":"system_topology","status":"completed"}}}""",
                """{"timestamp":"2026-10-02T06:58:30Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","changes":{"$ws/targets/botServer/wrangler.json":{"type":"update"}}}}}""",
                """{"timestamp":"2026-10-02T06:58:40Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","command":["/bin/zsh","-lc","./gradlew :engine:jvmTest\n--console=plain"]}}}""",
                """{"timestamp":"2026-10-02T06:58:50Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"total_tokens":31364}}}}""",
            ).joinToString("\n", postfix = "\n"))
            setLastModified(now - 90_000)
        }
    }

    @AfterTest fun clean() {
        home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        workspace.parentFile.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
    }

    @Test fun findsTheClaudeAndCodexSessionsWorkingOnThisWorkspace() {
        val sessions = HarnessSessions.read(workspace, home, now)
        assertEquals(listOf("c1", "x1"), sessions.map { it.id })

        val claude = sessions.first()
        assertEquals(RuntimeKind.ClaudeCode, claude.runtime)
        assertEquals("Hangar agent pane", claude.title)
        assertEquals("claude-opus-5-5", claude.model)
        assertEquals(listOf("modules/engine/Agent.kt"), claude.files)
        assertEquals(1, claude.doorCalls)
        assertEquals(listOf("Edit", "reaktor · graph_query"), claude.steps.map { it.tool })
        assertTrue(claude.live(now))
        assertEquals("claude --resume c1", claude.resumeCommand())

        val codex = sessions.last()
        assertEquals(RuntimeKind.Codex, codex.runtime)
        assertEquals("Wire the gateway", codex.title)
        assertEquals("gpt-6.1-sol", codex.model)
        assertEquals("xhigh", codex.effort)
        assertEquals(31364L, codex.tokens)
        assertEquals(1, codex.doorCalls)
        assertEquals(listOf("targets/botServer/wrangler.json"), codex.files)
        assertEquals("./gradlew :engine:jvmTest", codex.steps.last().detail)
        assertTrue(codex.live(now))
    }

    @Test fun aSessionWithNoTitleFallsBackToItsFirstRealPrompt() {
        File(home, ".claude/projects/-in-workspace/c1.jsonl").apply {
            writeText(readLines().filterNot { "custom-title" in it }.joinToString("\n", postfix = "\n"))
            setLastModified(now - 30_000)
        }
        assertEquals("Fix the agent pane", HarnessSessions.read(workspace, home, now).first { it.id == "c1" }.title)
    }

    @Test fun aContinuedClaudeSessionIsItsOwnSessionEvenWhenItStartsWithItsParentsHistory() {
        val ws = workspace.path
        claude("-in-workspace", "c4", ws, now - 10_000,
            """{"type":"user","cwd":"$ws","sessionId":"c1","timestamp":"2026-10-02T06:00:01Z","message":{"role":"user","content":"Fix the agent pane"}}""",
            """{"type":"user","cwd":"$ws","sessionId":"c4","timestamp":"2026-10-02T06:59:30Z","message":{"role":"user","content":"Carry on"}}""")
        val claude = HarnessSessions.read(workspace, home, now).filter { it.runtime == RuntimeKind.ClaudeCode }
        assertEquals(listOf("c4", "c1"), claude.map { it.id })
        assertEquals("claude --resume c4", claude.first().resumeCommand())
    }

    @Test fun transcriptsWrittenUnderOneSessionAreOneSessionFromTheLatestTranscript() {
        val latest = File(home, ".codex/sessions/2026/10/01/rollout-2026-10-01T12-00-00-x1.jsonl").apply {
            writeText("""{"timestamp":"2026-10-01T12:00:00Z","type":"session_meta","payload":{"id":"x1","cwd":"${workspace.parentFile.path}","timestamp":"2026-10-01T12:00:00Z"}}""" + "\n")
            setLastModified(now - 60_000)
        }
        val codex = HarnessSessions.read(workspace, home, now).filter { it.runtime == RuntimeKind.Codex }
        assertEquals(listOf("x1"), codex.map { it.id })
        assertEquals(latest.path, codex.single().transcript)
    }

    @Test fun aRunningClaudeSessionTakesItsStateAndNameFromTheLiveRegistry() {
        val ws = workspace.path
        claude("-in-workspace", "c5", ws, now - 600_000,
            """{"type":"user","cwd":"$ws","sessionId":"c5","entrypoint":"cli","timestamp":"2026-10-02T06:00:00Z","message":{"role":"user","content":"Port the board"}}""")
        registry(111, "c5", ws, "busy", "Board port", "claude-desktop")
        registry(222, "c1", ws, "idle", "Ignored when the transcript is titled", "cli")
        registry(333, "c9", workspace.parentFile.path, "idle", "Planning the week", "claude-desktop")
        registry(444, "c3", ws, "busy", "Dead process", "cli")
        File(home, ".claude/sessions/111.0123.key").writeText("never read")
        val sessions = HarnessSessions.read(workspace, home, now, processes(alive = setOf(111, 222, 333))).associateBy { it.id }

        assertEquals(HarnessActivity.Working, sessions.getValue("c5").activity)
        assertEquals("Board port", sessions.getValue("c5").title)
        assertEquals("claude-desktop", sessions.getValue("c5").origin)
        assertEquals(111L, sessions.getValue("c5").pid)
        assertEquals(HarnessActivity.Waiting, sessions.getValue("c1").activity)
        assertEquals("Hangar agent pane", sessions.getValue("c1").title)
        assertEquals("Planning the week", sessions.getValue("c9").title)
        assertEquals(HarnessActivity.Waiting, sessions.getValue("c9").activity)
        assertEquals("", sessions.getValue("c9").transcript)
        assertEquals(null, sessions["c3"])
    }

    @Test fun aClaudeSessionOutsideTheRegistryIsWorkingOnlyWhileItsTranscriptMoves() {
        val sessions = HarnessSessions.read(workspace, home, now, processes()).associateBy { it.id }
        assertEquals(HarnessActivity.Working, sessions.getValue("c1").activity)
        File(home, ".claude/projects/-in-workspace/c1.jsonl").setLastModified(now - 600_000)
        assertEquals(HarnessActivity.Ended, HarnessSessions.read(workspace, home, now, processes()).first { it.id == "c1" }.activity)
    }

    @Test fun aCodexThreadIsWorkingInATurnWaitingWhenOpenAndEndedWhenClosed() {
        val transcript = File(home, ".codex/sessions/2026/10/01/rollout-2026-10-01T10-00-00-x1.jsonl")
        fun state(open: Boolean, modified: Long = now - 600_000): HarnessActivity {
            transcript.setLastModified(modified)
            return HarnessSessions.read(workspace, home, now, processes(open = if (open) setOf(transcript.path) else emptySet()))
                .first { it.id == "x1" }.activity
        }
        assertEquals(HarnessActivity.Ended, state(open = false))
        assertEquals(HarnessActivity.Waiting, state(open = true))
        transcript.appendText("""{"timestamp":"2026-10-02T06:59:00Z","type":"event_msg","payload":{"type":"task_started","turn_id":"t2"}}""" + "\n")
        assertEquals(HarnessActivity.Working, state(open = true))
        assertEquals(HarnessActivity.Working, state(open = false, modified = now - 30_000))
        assertEquals(HarnessActivity.Ended, state(open = false))
        transcript.appendText("""{"timestamp":"2026-10-02T06:59:10Z","type":"event_msg","payload":{"type":"exec_approval_request","call_id":"a1"}}""" + "\n" +
            """{"timestamp":"2026-10-02T06:59:11Z","type":"event_msg","payload":{"type":"token_count","info":{}}}""" + "\n")
        assertEquals(HarnessActivity.NeedsYou, state(open = true))
        transcript.appendText("""{"timestamp":"2026-10-02T06:59:30Z","type":"event_msg","payload":{"type":"task_complete","turn_id":"t2"}}""" + "\n")
        assertEquals(HarnessActivity.Waiting, state(open = true))
    }

    @Test fun aTimelineReadsPromptsRepliesAndToolsFromEitherHarness() {
        val ws = workspace.path
        claude("-in-workspace", "c6", ws, now - 10_000,
            """{"type":"user","cwd":"$ws","sessionId":"c6","timestamp":"2026-10-02T06:00:00Z","message":{"role":"user","content":"<command-name>/clear</command-name>"}}""",
            """{"type":"user","cwd":"$ws","sessionId":"c6","timestamp":"2026-10-02T06:00:01Z","message":{"role":"user","content":[{"type":"text","text":"Add the board"}]}}""",
            """{"type":"assistant","sessionId":"c6","timestamp":"2026-10-02T06:00:05Z","message":{"content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"Reading the pane first."},{"type":"tool_use","name":"Edit","input":{"file_path":"$ws/modules/engine/Board.kt"}}]}}""",
            """{"type":"user","sessionId":"c6","timestamp":"2026-10-02T06:00:06Z","message":{"role":"user","content":[{"type":"tool_result","content":"ok"}]}}""",
            """{"type":"assistant","sessionId":"c6","isSidechain":true,"timestamp":"2026-10-02T06:00:07Z","message":{"content":[{"type":"text","text":"Subagent chatter"}]}}""")
        val claude = HarnessSessions.read(workspace, home, now, processes()).first { it.id == "c6" }
        val entries = HarnessSessions.timeline(claude, workspace).entries
        assertEquals(listOf(HarnessEntryKind.Prompt, HarnessEntryKind.Reply, HarnessEntryKind.Tool), entries.map { it.kind })
        assertEquals("Add the board", entries[0].text)
        assertEquals("modules/engine/Board.kt", entries[2].file)

        val codex = HarnessSessions.read(workspace, home, now, processes()).first { it.id == "x1" }
        File(codex.transcript).appendText(listOf(
            """{"timestamp":"2026-10-02T06:59:00Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","content":[{"type":"text","text":"Ship it"}]}}}""",
            """{"timestamp":"2026-10-02T06:59:05Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","content":[{"type":"Text","text":"Shipping."}]}}}""",
            """{"timestamp":"2026-10-02T06:59:06Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","command":["/bin/zsh"],"parsed_cmd":[{"type":"unknown","cmd":"./gradlew pr"}]}}}""",
        ).joinToString("\n", postfix = "\n"))
        val timeline = HarnessSessions.timeline(codex, workspace).entries
        assertEquals("Ship it", timeline.first { it.kind == HarnessEntryKind.Prompt }.text)
        assertEquals("Shipping.", timeline.first { it.kind == HarnessEntryKind.Reply }.text)
        assertEquals("./gradlew pr", timeline.last().detail)
        assertEquals("targets/botServer/wrangler.json", timeline.first { it.text == "edit" }.file)
    }

    @Test fun everyFileASessionEditedIsFoundAcrossItsWholeTranscriptAndAsItGrows() {
        val ws = workspace.path
        val output = """{"type":"user","sessionId":"c7","timestamp":"2026-10-02T06:10:00Z","message":{"role":"user","content":[{"type":"tool_result","content":"{\"name\":\"Edit\",\"file_path\":\"$ws/Quoted.kt\"} ${"x".repeat(4_000)}"}]}}"""
        claude("-in-workspace", "c7", ws, now - 10_000,
            """{"type":"user","cwd":"$ws","sessionId":"c7","timestamp":"2026-10-02T06:00:00Z","message":{"role":"user","content":"Long work"}}""",
            """{"type":"assistant","sessionId":"c7","timestamp":"2026-10-02T06:00:05Z","message":{"content":[{"type":"tool_use","name":"Write","input":{"file_path":"$ws/modules/early/E.kt"}},{"type":"tool_use","name":"Read","input":{"file_path":"$ws/modules/read/R.kt"}}]}}""",
            *Array(300) { output })
        val session = HarnessSessions.read(workspace, home, now, processes()).first { it.id == "c7" }
        assertEquals(emptyList<String>(), session.files)
        assertEquals(listOf("modules/early/E.kt"), HarnessSessions.edited(session, workspace).map { it.file })

        File(session.transcript).appendText(
            """{"type":"assistant","sessionId":"c7","timestamp":"2026-10-02T06:30:00Z","message":{"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"/elsewhere/reaktor/Late.kt"}}]}}""" + "\n" +
                """{"type":"assistant","sessionId":"c7","timestamp":"2026-10-02T06:40:00Z","message":{"content":[{"type":"tool_use","name":"MultiEdit","input":{"file_path":"$ws/modules/early/E.kt"}}]}}""" + "\n" +
                """{"type":"assistant","sessionId":"c7","timestamp":"2026-10-02T06:41:00Z","message":{"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"$ws/Partial.kt"}}]}}""")
        assertEquals(listOf("modules/early/E.kt", "/elsewhere/reaktor/Late.kt"), HarnessSessions.edited(session, workspace).map { it.file })
        File(session.transcript).appendText("\n")
        val edits = HarnessSessions.edited(session, workspace)
        assertEquals(listOf("modules/early/E.kt", "/elsewhere/reaktor/Late.kt", "Partial.kt"), edits.map { it.file })
        assertEquals(listOf("06:40", "06:30", "06:41").map { Instant.parse("2026-10-02T$it:00Z").toEpochMilli() }, edits.map { it.at })

        val codex = HarnessSessions.read(workspace, home, now, processes()).first { it.id == "x1" }
        assertEquals(listOf(HarnessEdit("targets/botServer/wrangler.json", Instant.parse("2026-10-02T06:58:30Z").toEpochMilli())), HarnessSessions.edited(codex, workspace))
    }

    private fun processes(alive: Set<Long> = emptySet(), open: Set<String> = emptySet()) = object : HarnessProcesses {
        override fun alive(pid: Long) = pid in alive
        override fun openTranscripts() = open
    }

    private fun registry(pid: Long, session: String, cwd: String, status: String, name: String, entrypoint: String) {
        File(home, ".claude/sessions/$pid.json").apply { parentFile.mkdirs() }.writeText(
            """{"pid":$pid,"sessionId":"$session","cwd":"$cwd","startedAt":"${now - 3_600_000}","version":"2.1.295","kind":"interactive","entrypoint":"$entrypoint","name":"$name","status":"$status","statusUpdatedAt":"${now - 5_000}"}""")
    }

    private fun claude(project: String, id: String, cwd: String, modified: Long, vararg lines: String) {
        File(home, ".claude/projects/$project/$id.jsonl").apply { parentFile.mkdirs() }.apply {
            writeText(lines.joinToString("\n", postfix = "\n"))
            setLastModified(modified)
        }
    }
}
