package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessCheck
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import dev.shibasis.reaktor.conductor.cli.HarnessStep
import dev.shibasis.reaktor.conductor.worklog.PlanState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentLedgerTest {
    private val day = 1_800_000_000_000L

    private fun session(id: String, model: String, effort: String, tokens: Long, done: Int, open: Int, at: Long = day) = HarnessSession(
        runtime = RuntimeKind.ClaudeCode, id = id, title = id, directory = "/w", startedAt = at - 60_000, lastActiveAt = at, origin = null, version = "2",
        branch = null, model = model, effort = effort, tokens = tokens,
        steps = listOf(HarnessStep(at, "Edit", null, false, "a.kt"), HarnessStep(at, "kernel", null, true), HarnessStep(at, "Edit", null, false, "a.kt")),
        transcript = "/t/$id.jsonl",
        checklist = List(done) { HarnessCheck("done $it", PlanState.Done) } + List(open) { HarnessCheck("open $it", PlanState.Pending) },
    )

    @Test fun aSessionBecomesOneRecordWithItsCostWorkAndOutcome() {
        val record = LedgerRecord.of(session("s1", "claude-opus-5-5", "max", 9_000, 3, 1), profile = "tester", thread = "hangar:t1")
        assertEquals("claudecode:s1", record.id)
        assertEquals(3, record.steps)
        assertEquals(1, record.doorCalls)
        assertEquals(1, record.files)
        assertEquals(4, record.checks)
        assertEquals(3, record.done)
        assertEquals("tester", record.profile)
        assertEquals(day - 60_000, record.startedAt)
    }

    @Test fun recordingKeepsTheLatestCopyOfEachSessionAndSkipsUnchangedOnes() {
        val folder = Files.createTempDirectory("ledger")
        try {
            val ledger = AgentLedger(folder)
            val first = LedgerRecord.of(session("s1", "claude-opus-5-5", "max", 9_000, 3, 1))
            val second = LedgerRecord.of(session("s2", "claude-sonnet-5-5", "high", 3_000, 2, 0, day + 86_400_000))
            assertEquals(2, ledger.record(listOf(first, second)))
            assertEquals(0, ledger.record(listOf(first, second)))
            val resumed = first.copy(tokens = 12_000, done = 4, endedAt = day + 86_400_000 + 5)
            assertEquals(1, ledger.record(listOf(resumed)))
            assertEquals(2, Files.list(folder).use { it.count() }.toInt())
            val read = AgentLedger(folder).read()
            assertEquals(listOf("claudecode:s1", "claudecode:s2"), read.map { it.id })
            assertEquals(12_000, read.first().tokens)
        } finally {
            folder.toFile().listFiles()?.forEach { Files.delete(it.toPath()) }
            Files.delete(folder)
        }
    }

    @Test fun standingsGroupSessionsByHarnessModelAndEffort() {
        val records = listOf(
            LedgerRecord.of(session("a", "claude-opus-5-5", "max", 10_000, 3, 1)),
            LedgerRecord.of(session("b", "claude-opus-5-5", "max", 20_000, 1, 1)),
            LedgerRecord.of(session("c", "claude-sonnet-5-5", "high", 4_000, 0, 0)),
            LedgerRecord("d", RuntimeKind.Codex, endedAt = day),
        )
        val standings = AgentLedger.standings(records)
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5"), standings.map { it.model })
        val opus = standings.first()
        assertEquals(2, opus.sessions)
        assertEquals(15_000, opus.tokensPerSession)
        assertEquals(4.0 / 6, opus.finished)
        assertNull(standings.last().finished)
    }
}
