package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentUsage
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class HarnessUsageTest {
    private lateinit var home: File
    private val since = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private val until = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli()
    private val ledger = HarnessUsageLedger()

    @BeforeTest fun setup() { home = Files.createTempDirectory("usage-home").toFile() }
    @AfterTest fun clean() { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }

    @Test fun claudeSplitMessagesAndCopiedHistoryCountOnceAndIncludeCacheTokens() {
        val first = claude("m1", usage = """{"input_tokens":2,"cache_read_input_tokens":80,"cache_creation_input_tokens":18,"output_tokens":3}""")
        val final = claude("m1", usage = """{"input_tokens":2,"cache_read_input_tokens":80,"cache_creation_input_tokens":18,"output_tokens":9,"output_tokens_details":{"thinking_tokens":5}}""")
        transcript(".claude/projects/p/c1.jsonl", first, final)
        transcript(".claude/projects/p/c2.jsonl", final)
        val report = ledger.read(home, since, until)
        val row = ledger.rows(since, until).single()
        assertEquals(AgentUsage(inputTokens = 100, cachedInputTokens = 80, cacheWriteInputTokens = 18,
            outputTokens = 9, reasoningOutputTokens = 5), row.usage)
        assertEquals(1, report["usage"]!!.jsonObject["turns"]!!.jsonPrimitive.int)
        assertFalse(row.json().toString().contains("secret prompt"))
    }

    @Test fun claudeSubagentsAndMissingFieldsStayVisible() {
        transcript(".claude/projects/p/c1/subagents/agent-a.jsonl", claude("child", usage = """{"input_tokens":2,"output_tokens":3}"""))
        transcript(".claude/projects/p/c1.jsonl", claude("synthetic", model = "<synthetic>"), claude("missing"))
        val report = ledger.read(home, since, until)
        val rows = ledger.rows(since, until)
        assertEquals(2, rows.size)
        assertTrue(rows.single { it.requestId == "child" }.subagent)
        assertNull(rows.single { it.requestId == "child" }.usage.inputTokens)
        assertEquals(2, report["usage"]!!.jsonObject["input"]!!.jsonObject["unknownTurns"]!!.jsonPrimitive.int)
        assertEquals(2, report["groups"]!!.jsonArray.size)
    }

    @Test fun claudeChildrenHaveDistinctSessionIdentitiesWithinTheirParent() {
        transcript(".claude/projects/p/c1/subagents/agent-a.jsonl", claude("child-a"))
        transcript(".claude/projects/p/c1/subagents/agent-b.jsonl", claude("child-b"))
        val report = ledger.read(home, since, until)
        assertEquals(setOf("c1:agent-a", "c1:agent-b"), ledger.rows(since, until).map { it.sessionId }.toSet())
        assertEquals(2, report["groups"]!!.jsonArray.single().jsonObject["sessions"]!!.jsonPrimitive.int)
    }

    @Test fun codexDuplicateSnapshotsUsePerRequestUsageRatherThanCumulativeTotals() {
        transcript(".codex/sessions/2026/10/01/x.jsonl", meta(), count(100, 10, 100, 10),
            count(100, 10, 100, 10, at = "2026-10-01T02:01:00Z"), count(250, 30, 150, 20))
        transcript(".codex/archived_sessions/x.jsonl", meta(), count(100, 10, 100, 10), count(250, 30, 150, 20))
        val report = ledger.read(home, since, until)
        assertEquals(listOf(100L, 150L), ledger.rows(since, until).map { it.usage.inputTokens })
        assertEquals(250L, report["usage"]!!.jsonObject["input"]!!.jsonObject["reported"]!!.jsonPrimitive.long)
        assertEquals(30L, report["usage"]!!.jsonObject["output"]!!.jsonObject["reported"]!!.jsonPrimitive.long)
    }

    @Test fun aForkExcludesReplayedHistoryAndInheritedCounters() {
        transcript(".codex/sessions/x.jsonl", meta(fork = true, at = "2026-10-02T00:00:00Z"),
            count(1000, 80, 1000, 80), count(1120, 85, 120, 5, at = "2026-10-02T01:00:00Z"))
        ledger.read(home, since, until)
        val row = ledger.rows(since, until).single()
        assertEquals(120L, row.usage.inputTokens)
        assertEquals(5L, row.usage.outputTokens)
        assertFalse(row.subagent)
    }

    @Test fun aForkWithoutAReplayOrLastRequestDoesNotInventABaseline() {
        transcript(".codex/sessions/x.jsonl", meta(fork = true), count(1000, 80, null, null))
        ledger.read(home, since, until)
        val row = ledger.rows(since, until).single()
        assertNull(row.usage.inputTokens)
        assertNull(row.usage.outputTokens)
    }

    @Test fun counterDeltasIncludeAPreWindowBaselineAndResetsStayUnknown() {
        transcript(".codex/sessions/x.jsonl", meta(at = "2026-09-29T00:00:00Z"),
            count(100, 10, null, null, at = "2026-09-30T23:00:00Z"),
            count(250, 30, null, null), count(20, 2, null, null, at = "2026-10-02T00:00:00Z"),
            count(50, 5, null, null, at = "2026-10-03T00:00:00Z"))
        ledger.read(home, since, until)
        assertEquals(listOf(150L, null, 30L), ledger.rows(since, until).map { it.usage.inputTokens })
    }

    @Test fun dateBoundariesAndCwdScopeDoNotAttributeParentDirectoriesToAProject() {
        transcript(".claude/projects/p/c1.jsonl", claude("start", at = "2026-10-01T00:00:00Z"),
            claude("end", at = "2026-10-08T00:00:00Z"), claude("old", at = "2026-09-30T23:59:59Z"),
            claude("parent", cwd = home.parentFile.path), claude("sibling", cwd = home.path + "-other"))
        ledger.read(home, since, until, home)
        assertEquals(listOf("start"), ledger.rows(since, until, home.path).map { it.requestId })
    }

    @Test fun cwdScopeMatchesSymlinkPathsAndPreservesTheRecordedDirectory() {
        val workspace = File(home, "workspace").apply { mkdirs() }
        val alias = File(home, "workspace-alias")
        Files.createSymbolicLink(alias.toPath(), workspace.toPath())
        File(workspace, "child").mkdirs()
        val recorded = File(alias, "child").path
        transcript(".claude/projects/p/c1.jsonl", claude("linked", cwd = recorded),
            claude("outside", cwd = File(home, "workspace-sibling").path),
            claude("invalid", cwd = "bad\\u0000path"))
        val report = ledger.read(home, since, until, workspace)
        assertEquals(0, report["malformedLines"]!!.jsonPrimitive.int)
        assertEquals(1, report["usage"]!!.jsonObject["turns"]!!.jsonPrimitive.int)
        assertEquals(recorded, ledger.rows(since, until, workspace.canonicalPath).single().directory)
        assertEquals("linked", ledger.rows(since, until, alias.path).single().requestId)
    }

    @Test fun malformedRecordsAndUnidentifiableRequestsAreCounted() {
        transcript(".claude/projects/p/c1.jsonl", "not json", claude("bad-time", at = "broken"), claude("ok"))
        val report = ledger.read(home, since, until)
        assertEquals(1, report["malformedLines"]!!.jsonPrimitive.int)
        assertEquals(1, report["unidentifiableRequests"]!!.jsonPrimitive.int)
        assertEquals(1, ledger.rows(since, until).size)
    }

    @Test fun allowanceIsTheLatestAccountSnapshotPerLimitAndCanUseEitherSlot() {
        val rate = """{"limit_id":"codex","secondary":{"window_minutes":10080,"used_percent":40.0,"resets_at":1791609619}}"""
        val later = rate.replace("40.0", "45.0").replace("secondary", "primary").replace("1791609619", "1792214419")
        transcript(".codex/sessions/x.jsonl", meta(), count(100, 10, 100, 10, rate = rate),
            count(100, 10, 100, 10, at = "2026-10-02T00:00:00Z", rate = later))
        val report = ledger.read(home, since, until)
        assertEquals(45.0, report["codexWeeklyAllowance"]!!.jsonArray.single().jsonObject["usedPercent"]!!.jsonPrimitive.double)
        assertEquals(1, ledger.rows(since, until).size)
        val empty = ledger.read(home, since, until, home)
        assertEquals(0, empty["usage"]!!.jsonObject["turns"]!!.jsonPrimitive.int)
        assertEquals(1, empty["codexWeeklyAllowance"]!!.jsonArray.size)
    }

    @Test fun rescansAreDeterministicAndRemovedHistoryDoesNotRemainInMemory() {
        val path = ".claude/projects/p/c1.jsonl"
        val first = claude("first", usage = """{"input_tokens":2,"cache_read_input_tokens":8,"cache_creation_input_tokens":0,"output_tokens":3}""")
        transcript(path, first)
        val before = ledger.read(home, since, until)
        assertEquals(before, ledger.read(home, since, until))
        transcript(path, first, claude("second"))
        assertEquals(2, ledger.read(home, since, until)["usage"]!!.jsonObject["turns"]!!.jsonPrimitive.int)
        transcript(path)
        assertEquals(0, ledger.read(home, since, until)["usage"]!!.jsonObject["turns"]!!.jsonPrimitive.int)
        assertTrue(ledger.rows(since, until).isEmpty())
    }

    @Test fun symlinkedFilesAndDirectoriesCannotImportOutsideHistory() {
        val outside = File(home, "outside").apply { mkdirs() }
        val history = File(outside, "history.jsonl").apply { writeText(claude("outside")) }
        val root = File(home, ".claude/projects/p").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "linked.jsonl").toPath(), history.toPath())
        Files.createSymbolicLink(File(root, "linked-directory").toPath(), outside.toPath())
        transcript(".claude/projects/p/local.jsonl", claude("local"))
        val report = ledger.read(home, since, until)
        assertEquals(listOf("local"), ledger.rows(since, until).map { it.requestId })
        assertEquals(1, report["filesRead"]!!.jsonPrimitive.int)
    }

    @Test fun negativeProviderFieldsRemainUnknownRatherThanReducingReportedUsage() {
        transcript(".claude/projects/p/c1.jsonl", claude("invalid", usage =
            """{"input_tokens":-1,"cache_read_input_tokens":8,"cache_creation_input_tokens":0,"output_tokens":-3}"""))
        val report = ledger.read(home, since, until)
        val row = ledger.rows(since, until).single()
        assertNull(row.usage.inputTokens)
        assertNull(row.usage.outputTokens)
        assertEquals(8L, row.usage.cachedInputTokens)
        assertEquals(0L, report["usage"]!!.jsonObject["input"]!!.jsonObject["reported"]!!.jsonPrimitive.long)
        assertEquals(1, report["usage"]!!.jsonObject["input"]!!.jsonObject["unknownTurns"]!!.jsonPrimitive.int)
    }

    @Test fun copiedCountersAfterAResetKeepDistinctRequestsWithTheSameTotal() {
        val lines = arrayOf(meta(), count(100, 10, 100, 10),
            count(20, 2, 20, 2, at = "2026-10-02T00:00:00Z"),
            count(100, 10, 80, 8, at = "2026-10-03T00:00:00Z"))
        transcript(".codex/sessions/x.jsonl", *lines)
        transcript(".codex/archived_sessions/x.jsonl", *lines)
        val report = ledger.read(home, since, until)
        assertEquals(listOf(100L, 20L, 80L), ledger.rows(since, until).map { it.usage.inputTokens })
        assertEquals(200L, report["usage"]!!.jsonObject["input"]!!.jsonObject["reported"]!!.jsonPrimitive.long)
    }

    @Test fun requestIdentifiersFromDifferentProvidersNeverDeduplicateEachOther() {
        transcript(".codex/sessions/x.jsonl", meta(), count(100, 10, 100, 10))
        ledger.read(home, since, until)
        val id = ledger.rows(since, until).single().requestId
        transcript(".claude/projects/p/c1.jsonl", claude(id))
        ledger.read(home, since, until)
        assertEquals(2, ledger.rows(since, until).size)
    }

    @Test fun aCopiedPartialMessageCannotEraseCompletedUsage() {
        val complete = claude("copied", usage =
            """{"input_tokens":2,"cache_read_input_tokens":8,"cache_creation_input_tokens":0,"output_tokens":3}""")
        transcript(".claude/projects/p/a.jsonl", complete)
        transcript(".claude/projects/p/b.jsonl", claude("copied"))
        ledger.read(home, since, until)
        assertEquals(AgentUsage(inputTokens = 10, cachedInputTokens = 8, cacheWriteInputTokens = 0, outputTokens = 3),
            ledger.rows(since, until).single().usage)
    }

    private fun transcript(path: String, vararg lines: String) = File(home, path).apply {
        parentFile.mkdirs(); writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun claude(id: String, at: String = "2026-10-01T02:00:00Z", usage: String = "null",
        cwd: String = home.path, model: String = "claude-opus-5-5") =
        """{"type":"assistant","timestamp":"$at","sessionId":"c1","cwd":"$cwd","message":{"id":"$id","model":"$model","usage":$usage,"content":[{"type":"text","text":"secret prompt"}]}}"""

    private fun meta(fork: Boolean = false, at: String = "2026-10-01T00:00:00Z") =
        """{"type":"session_meta","payload":{"id":"x1","cwd":"/dev/project","timestamp":"$at"${if (fork) ",\"forked_from_id\":\"parent\"" else ""}}}"""

    private fun count(input: Long, output: Long, lastInput: Long?, lastOutput: Long?,
        at: String = "2026-10-01T02:00:00Z", rate: String = "null"): String {
        val total = """{"input_tokens":$input,"output_tokens":$output,"cached_input_tokens":0}"""
        val last = if (lastInput == null) "null" else """{"input_tokens":$lastInput,"output_tokens":$lastOutput,"cached_input_tokens":0}"""
        return """{"type":"event_msg","timestamp":"$at","payload":{"type":"token_count","info":{"total_token_usage":$total,"last_token_usage":$last},"rate_limits":$rate}}"""
    }
}
