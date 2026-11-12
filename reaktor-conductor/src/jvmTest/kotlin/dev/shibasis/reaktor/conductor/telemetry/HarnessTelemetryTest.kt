package dev.shibasis.reaktor.conductor.telemetry

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.UsageScope
import dev.shibasis.reaktor.conductor.workspace.ModelFamily
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessTelemetryTest {
    private val temporary = File(System.getProperty("java.io.tmpdir"))

    @Test fun sealedHierarchyCoversAllThreeModelFamiliesAndSerializesPolymorphically() {
        assertEquals(listOf(ClaudeTelemetry, CodexTelemetry, GeminiTelemetry), AgentTelemetry.all)
        assertEquals(ClaudeTelemetry, AgentTelemetry.of(ModelFamily.Claude))
        assertEquals(CodexTelemetry, AgentTelemetry.of(ModelFamily.Codex))
        assertEquals(GeminiTelemetry, AgentTelemetry.of(ModelFamily.Gemini))
        assertEquals(ClaudeTelemetry, AgentTelemetry.of(RuntimeKind.ClaudeCode))
        assertEquals(CodexTelemetry, AgentTelemetry.of(RuntimeKind.Codex))
        assertEquals(GeminiTelemetry, AgentTelemetry.of(RuntimeKind.Gemini))
        assertNull(AgentTelemetry.of(RuntimeKind.Local))
        assertNull(AgentTelemetry.of(ModelFamily.DeepSeek))

        val records: List<ProviderTelemetry> = listOf(
            ProviderTelemetry.Claude(requestId = "c1", model = "claude-opus-4-6", freshInputTokens = 200, cacheReadTokens = 1800, cacheCreationTokens = 500, outputTokens = 100, thinkingTokens = 40, costUsd = 0.012),
            ProviderTelemetry.Codex(requestId = "x1", model = "gpt-5.4", inclusiveInputTokens = 4000, cachedInputTokens = 3000, outputTokens = 200, reasoningOutputTokens = 80, contextTokens = 4000),
            ProviderTelemetry.Gemini(requestId = "g1", model = "gemini-4-argon-high", stepIndex = 3, freshInputTokens = 500, cacheReadTokens = 9500, outputTokens = 150, thinkingTokens = 60),
        )
        records.forEach { original ->
            val encoded = ConductorJson.encodeToString(ProviderTelemetry.serializer(), original)
            val decoded = ConductorJson.decodeFromString(ProviderTelemetry.serializer(), encoded)
            assertEquals(original, decoded)
        }
    }

    @Test fun claudeCodexAndGeminiNormalizeToIdenticalInclusiveInputAndContextConventions() {
        val claude = ProviderTelemetry.Claude(
            requestId = "m1",
            model = "claude-opus-4-6",
            freshInputTokens = 1_000,
            cacheReadTokens = 8_000,
            cacheCreationTokens = 1_000,
            outputTokens = 500,
            thinkingTokens = 200,
            costUsd = 0.031,
        ).normalize()
        assertEquals(10_000L, claude.inputTokens)
        assertEquals(1_000L, claude.freshInputTokens)
        assertEquals(8_000L, claude.cachedInputTokens)
        assertEquals(1_000L, claude.cacheWriteInputTokens)
        assertEquals(10_000L, claude.contextTokens)
        assertEquals(1_000_000L, claude.contextWindowTokens)
        assertEquals(1.0, claude.contextUtilizationPercent!!, 1e-6)
        assertEquals(0.031, claude.costUsd)
        assertNotNull(claude.estimatedCostUsd)

        val codex = ProviderTelemetry.Codex(
            requestId = "x1",
            model = "gpt-5.4",
            inclusiveInputTokens = 27_200,
            cachedInputTokens = 20_000,
            outputTokens = 400,
            reasoningOutputTokens = 150,
            contextTokens = 13_600,
        ).normalize()
        assertEquals(27_200L, codex.inputTokens)
        assertEquals(7_200L, codex.freshInputTokens)
        assertEquals(20_000L, codex.cachedInputTokens)
        assertEquals(13_600L, codex.contextTokens)
        assertEquals(272_000L, codex.contextWindowTokens)
        assertEquals(5.0, codex.contextUtilizationPercent!!, 1e-6)
        assertNull(codex.costUsd)
        assertNotNull(codex.estimatedCostUsd)

        val gemini = ProviderTelemetry.Gemini(
            requestId = "g1",
            model = "gemini-4-argon-high",
            freshInputTokens = 2_000,
            cacheReadTokens = 98_000,
            outputTokens = 600,
            thinkingTokens = 250,
        ).normalize()
        assertEquals(100_000L, gemini.inputTokens)
        assertEquals(2_000L, gemini.freshInputTokens)
        assertEquals(98_000L, gemini.cachedInputTokens)
        assertEquals(100_000L, gemini.contextTokens)
        assertEquals(2_000_000L, gemini.contextWindowTokens)
        assertEquals(5.0, gemini.contextUtilizationPercent!!, 1e-6)
        assertEquals(0.98, gemini.cacheHitRatio!!, 1e-6)
    }

    @Test fun claudeSessionCursorDeduplicatesStreamingSnapshotsAndEmitsExactDeltas() {
        val cursor = ClaudeTelemetry.SessionCursor("claude-sonnet-4-6")
        val first = json("""{"type":"assistant","timestamp":"2026-11-12T10:00:00Z","costUSD":0.01,"message":{"id":"msg_1","model":"claude-sonnet-4-6","usage":{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":50,"output_tokens":12}}}""")
        val second = json("""{"type":"assistant","timestamp":"2026-11-12T10:00:02Z","costUSD":0.025,"message":{"id":"msg_1","model":"claude-sonnet-4-6","usage":{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":50,"output_tokens":42,"output_tokens_details":{"thinking_tokens":18}}}}""")
        val compact = json("""{"type":"system","subtype":"compact_boundary","timestamp":"2026-11-12T10:00:05Z"}""")
        val limit = json("""{"type":"rate_limit_event","timestamp":"2026-11-12T10:00:06Z","rate_limit_info":{"status":"allowed_warning","resetsAt":1794500000,"isUsingOverage":true}}""")

        val step1 = assertNotNull(cursor.ingest(first))
        assertEquals(1_050L, step1.delta.inputTokens)
        assertEquals(12L, step1.delta.outputTokens)

        val step2 = assertNotNull(cursor.ingest(second))
        assertEquals(0L, step2.delta.inputTokens)
        assertEquals(30L, step2.delta.outputTokens)
        assertEquals(18L, step2.delta.reasoningOutputTokens)
        assertTrue(abs((step2.delta.costUsd ?: 0.0) - 0.015) < 1e-9)
        assertEquals(1_050L, step2.merged.strictInclusiveInputTokens)
        assertEquals(42L, step2.merged.outputTokens)

        val compacted = assertNotNull(cursor.ingest(compact))
        assertTrue(compacted.delta.compacted)

        assertNull(cursor.ingest(limit))
        assertEquals("allowed_warning", cursor.latestRateLimits?.status)
        assertEquals(true, cursor.latestRateLimits?.overage)
    }

    @Test fun codexAppServerAndRestAndRolloutFormatsNormalizeTogether() {
        val appServer = json("""{"total":{"inputTokens":18000,"cachedInputTokens":14000,"cacheWriteInputTokens":500,"outputTokens":600,"reasoningOutputTokens":220,"totalTokens":18600},"last":{"inputTokens":6200,"cachedInputTokens":5800,"outputTokens":140,"reasoningOutputTokens":50}}""")
        val parsed = assertNotNull(CodexTelemetry.parseAppServerTokenUsage(appServer, model = "gpt-6-astra", effort = "high"))
        assertEquals(UsageScope.ProviderSessionTotal, parsed.scope)
        assertEquals(18_000L, parsed.inclusiveInputTokens)
        assertEquals(6_200L, parsed.contextTokens)
        assertEquals(400_000L, parsed.normalize().contextWindowTokens)

        val rest = json("""{"prompt_tokens":1200,"prompt_tokens_details":{"cached_tokens":900},"completion_tokens":80,"completion_tokens_details":{"reasoning_tokens":30}}""")
        val apiParsed = assertIs<ProviderTelemetry.Codex>(CodexTelemetry.parseApiUsage(rest, model = "o3"))
        assertEquals(1_200L, apiParsed.inclusiveInputTokens)
        assertEquals(300L, apiParsed.freshInputTokens)
        assertEquals(900L, apiParsed.cachedInputTokens)
        assertEquals(80L, apiParsed.outputTokens)
        assertEquals(30L, apiParsed.reasoningOutputTokens)
    }

    @Test fun geminiHandlesBothAntigravityFreshInputAndDirectRestInclusiveInput() {
        val headless = json("""{"event":"result","result":{"status":"SUCCESS","usage":{"input_tokens":15,"cache_read_tokens":85,"output_tokens":12,"thinking_tokens":5}}}""")
        val agyParsed = assertIs<ProviderTelemetry.Gemini>(GeminiTelemetry.parseRecord(headless, fallbackModel = "gemini-4-carbon-high"))
        assertEquals(UsageScope.ProviderSessionTotal, agyParsed.scope)
        assertEquals(100L, agyParsed.toAgentUsage().inputTokens)
        assertEquals(85L, agyParsed.toAgentUsage().cachedInputTokens)
        assertEquals(5L, agyParsed.toAgentUsage().reasoningOutputTokens)

        val rest = json("""{"modelVersion":"gemini-2.5-pro","usageMetadata":{"promptTokenCount":5000,"cachedContentTokenCount":4200,"candidatesTokenCount":300,"thoughtsTokenCount":110}}""")
        val restParsed = assertIs<ProviderTelemetry.Gemini>(GeminiTelemetry.parseRecord(rest))
        val sample = restParsed.normalize()
        assertEquals(5_000L, sample.inputTokens)
        assertEquals(800L, sample.freshInputTokens)
        assertEquals(4_200L, sample.cachedInputTokens)
        assertEquals(300L, sample.outputTokens)
        assertEquals(110L, sample.reasoningOutputTokens)
    }

    @Test fun scannerAttributesAntigravityBrainTranscriptsAndSubagentsToWorkspace() {
        val home = Files.createTempDirectory("telemetry-scan").toFile()
        try {
            val workspace = File(home, "work/project").apply { mkdirs() }
            val parentId = "11111111-2222-3333-4444-555555555555"
            val childId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            File(home, ".gemini/antigravity/cache").apply { mkdirs() }
                .resolve("last_conversations.json")
                .writeText("""{"${workspace.canonicalPath}":"$parentId"}""")
            val parentLog = File(home, ".gemini/antigravity/brain/$parentId/.system_generated/logs/transcript.jsonl")
                .apply { parentFile.mkdirs() }
            parentLog.writeText(
                listOf(
                    """{"step_index":0,"type":"USER_INPUT","created_at":"2026-11-10T10:00:00Z","content":"<USER_REQUEST>Audit</USER_REQUEST>"}""",
                    """{"step_index":1,"type":"PLANNER_RESPONSE","created_at":"2026-11-10T10:00:05Z","input_tokens":200,"cache_read_tokens":800,"output_tokens":50,"tool_calls":[{"name":"invoke_subagent","args":{"Role":"Researcher"}}]}""",
                    """{"step_index":2,"type":"GENERIC","created_at":"2026-11-10T10:00:06Z","content":"Subagent started with conversationId: $childId"}""",
                ).joinToString("\n", postfix = "\n")
            )
            val childLog = File(home, ".gemini/antigravity/brain/$childId/.system_generated/logs/transcript.jsonl")
                .apply { parentFile.mkdirs() }
            childLog.writeText(
                listOf(
                    """{"step_index":0,"type":"USER_INPUT","created_at":"2026-11-10T10:00:07Z","content":"Subagent prompt"}""",
                    """{"step_index":1,"type":"PLANNER_RESPONSE","created_at":"2026-11-10T10:00:12Z","input_tokens":100,"cache_read_tokens":300,"output_tokens":25}""",
                ).joinToString("\n", postfix = "\n")
            )
            val since = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
            val until = Instant.parse("2026-11-12T00:00:00Z").toEpochMilli()
            val scan = HarnessTelemetryScanner.scan(home, since, until, workspace)
            assertEquals(2, scan.requests.size)
            val parentRow = scan.requests.single { !it.subagent }
            val childRow = scan.requests.single { it.subagent }
            assertEquals(parentId, parentRow.sessionId)
            assertEquals("$parentId:$childId", childRow.sessionId)
            assertEquals(1_000L, parentRow.usage.inputTokens)
            assertEquals(800L, parentRow.usage.cachedInputTokens)
            assertEquals(400L, childRow.usage.inputTokens)
            assertEquals(300L, childRow.usage.cachedInputTokens)
            val rollup = scan.requests.map { it.sample }.rollupTelemetry()
            assertEquals(1_400L, rollup.inputTokens)
            assertEquals(300L, rollup.freshInputTokens)
            assertEquals(1_100L, rollup.cachedInputTokens)
            assertEquals(1_000L, rollup.peakContextTokens)
        } finally {
            home.deleteTreeSafely(within = temporary)
        }
    }

    @Test fun isoEpochParserMatchesJavaTimeInstantAcrossOffsetsAndFractions() {
        val cases = listOf(
            "1970-01-01T00:00:00Z",
            "2024-02-29T23:59:59.999Z",
            "2026-11-12T12:20:56+05:30",
            "2026-11-12T06:50:56.123456789Z",
            "2026-01-01T00:00:00-07:00",
            "2026-10-01T02:00:00.5+00:00",
        )
        for (raw in cases) {
            assertEquals(Instant.parse(raw).toEpochMilli(), parseIsoEpochMillis(raw), raw)
        }
        assertNull(parseIsoEpochMillis("broken"))
        assertNull(parseIsoEpochMillis("2026-02-29T00:00:00Z"))
        assertNull(parseIsoEpochMillis(null))
    }

    private fun json(raw: String): JsonObject = ConductorJson.parseToJsonElement(raw) as JsonObject
}
