package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexStatusParityTest {
    @Test fun theStatusReaderAndLogAgreeAcrossLongTurns() {
        val home = Files.createTempDirectory("codex-status-parity").toFile().canonicalFile
        val file = home.resolve(".codex/sessions/2026/11/17/rollout-fixture.jsonl").apply { parentFile.mkdirs() }
        val processes = object : HarnessProcesses {
            override fun alive(pid: Long) = false
            override fun openTranscripts() = setOf(file.path)
        }
        try {
            file.writeText("""{"type":"session_meta","payload":{"id":"fixture","cwd":"${home.path}"}}""" + "\n")
            val log = CodexLog(home.path)
            val rows = listOf(
                """{"timestamp":"2026-11-17T08:00:00Z","type":"event_msg","payload":{"type":"task_started","turn_id":"one"}}""",
                """{"type":"fixture-padding","text":"${"x".repeat(2 * 1024 * 1024)}"}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"answer","text":"The complete answer"}}}""",
                """{"timestamp":"2026-11-17T08:01:00Z","type":"event_msg","payload":{"type":"task_complete","last_agent_message":"The complete answer"}}""",
                """{"timestamp":"2026-11-17T08:02:00Z","type":"event_msg","payload":{"type":"task_started","turn_id":"two"}}""",
                """{"timestamp":"2026-11-17T08:03:00Z","type":"event_msg","payload":{"type":"turn_aborted"}}""")
            rows.forEach { row ->
                file.appendText(row + "\n")
                log.line(requireNotNull(parseRecord(row)))
                val session = HarnessSessions.read(home, home, file.lastModified(), processes).single()
                assertEquals(if (log.boundary == "task_started") HarnessActivity.Working else HarnessActivity.Waiting, session.activity)
                assertEquals(log.turnStartedAt.takeIf { log.boundary == "task_started" }, session.turnStartedAt)
            }
            assertTrue(log.log(false).turns.flatMap { it.steps }.any { it is WorkStep.Message && it.text == "The complete answer" })
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
