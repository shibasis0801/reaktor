package dev.shibasis.reaktor.conductor.cli

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
        home.deleteRecursively()
        workspace.parentFile.deleteRecursively()
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

    private fun claude(project: String, id: String, cwd: String, modified: Long, vararg lines: String) {
        File(home, ".claude/projects/$project/$id.jsonl").apply { parentFile.mkdirs() }.apply {
            writeText(lines.joinToString("\n", postfix = "\n"))
            setLastModified(modified)
        }
    }
}
