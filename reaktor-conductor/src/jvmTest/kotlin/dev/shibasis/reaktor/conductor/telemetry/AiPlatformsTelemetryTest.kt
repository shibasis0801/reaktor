package dev.shibasis.reaktor.conductor.telemetry

import dev.shibasis.reaktor.cloud.cloudflare.AiPlatforms
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AiPlatformsTelemetryTest {
    private val home: File = Files.createTempDirectory("ai-platforms-telemetry").toFile()
    private val now = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()

    @AfterTest
    fun cleanup() {
        home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
    }

    @Test
    fun localReadersUseUnifiedCodecsThroughServiceLoaderWithoutFakeTokenHeuristics() {
        val stamp1 = "2026-11-12T10:00:00Z"
        val stamp2 = "2026-11-12T10:05:00Z"

        val claudeSession = File(home, ".claude/projects/demo/c1.jsonl").apply { parentFile.mkdirs() }
        claudeSession.writeText(
            listOf(
                """{"type":"assistant","sessionId":"c1","timestamp":"$stamp1","message":{"id":"msg_1","model":"claude-opus-4-6","usage":{"input_tokens":100,"cache_read_input_tokens":800,"cache_creation_input_tokens":100,"output_tokens":20}}}""",
                """{"type":"assistant","sessionId":"c1","timestamp":"$stamp1","message":{"id":"msg_1","model":"claude-opus-4-6","usage":{"input_tokens":100,"cache_read_input_tokens":800,"cache_creation_input_tokens":100,"output_tokens":55}}}""",
            ).joinToString("\n", postfix = "\n"),
        )
        claudeSession.setLastModified(now - 60_000L)

        val codexSession = File(home, ".codex/sessions/2026/11/12/rollout-x1.jsonl").apply { parentFile.mkdirs() }
        codexSession.writeText(
            listOf(
                """{"timestamp":"$stamp1","type":"session_meta","payload":{"id":"x1","cwd":"/tmp/demo"}}""",
                """{"timestamp":"$stamp1","type":"turn_context","payload":{"model":"gpt-5.4","effort":"xhigh"}}""",
                """{"timestamp":"$stamp2","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":2000,"cached_input_tokens":1500,"output_tokens":120,"reasoning_output_tokens":40},"last_token_usage":{"input_tokens":2000,"cached_input_tokens":1500,"output_tokens":120,"reasoning_output_tokens":40}},"rate_limits":{"plan_type":"pro","primary":{"used_percent":22.0,"window_minutes":300,"resets_at":1794500000},"secondary":{"used_percent":48.0,"window_minutes":10080,"resets_at":1794900000}}}}""",
            ).joinToString("\n", postfix = "\n"),
        )
        codexSession.setLastModified(now - 60_000L)

        val subId = "11111111-1111-1111-1111-111111111111"
        val geminiCliBrain = File(home, ".gemini/antigravity-cli/brain/g-root/.system_generated/logs/transcript.jsonl").apply { parentFile.mkdirs() }
        geminiCliBrain.writeText(
            listOf(
                """{"step_index":0,"type":"USER_INPUT","created_at":"$stamp1","content":"Run"}""",
                """{"step_index":1,"type":"PLANNER_RESPONSE","created_at":"$stamp2","model":"gemini-2.5-pro","input_tokens":300,"cache_read_tokens":1700,"output_tokens":90,"thinking_tokens":30,"tool_calls":[{"name":"invoke_subagent","result":{"conversationId":"$subId"}}]}""",
            ).joinToString("\n", postfix = "\n"),
        )
        geminiCliBrain.setLastModified(now - 60_000L)

        val geminiSubBrain = File(home, ".gemini/antigravity-cli/brain/$subId/.system_generated/logs/transcript.jsonl").apply { parentFile.mkdirs() }
        geminiSubBrain.writeText(
            """{"step_index":0,"type":"PLANNER_RESPONSE","created_at":"$stamp2","model":"gemini-2.5-flash","input_tokens":100,"cache_read_tokens":400,"output_tokens":25}""" + "\n",
        )
        geminiSubBrain.setLastModified(now - 60_000L)

        val untokenizedIdeBrain = File(home, ".gemini/antigravity/brain/g-ide/.system_generated/logs/transcript.jsonl").apply { parentFile.mkdirs() }
        untokenizedIdeBrain.writeText(
            """{"step_index":0,"type":"PLANNER_RESPONSE","created_at":"$stamp2","thinking":"long thought without token metadata","content":"answer without token metadata"}""" + "\n",
        )
        untokenizedIdeBrain.setLastModified(now - 60_000L)

        val claude = AiPlatforms.readLocalClaudeTelemetry(File(home, ".claude"), days = 7, now = now)
        assertEquals(1, claude.sessionCount7d)
        assertEquals(1L, claude.requests7d)
        assertEquals(1_000L, claude.inputTokens7d)
        assertEquals(800L, claude.cacheReadTokens7d)
        assertEquals(100L, claude.cacheCreationTokens7d)
        assertEquals(55L, claude.outputTokens7d)

        val codex = AiPlatforms.readLocalCodexTelemetry(File(home, ".codex"), days = 7, now = now)
        assertEquals(1, codex.sessionCount7d)
        assertEquals(1L, codex.requests7d)
        assertEquals(2_000L, codex.inputTokens7d)
        assertEquals(1_500L, codex.cachedTokens7d)
        assertEquals(120L, codex.outputTokens7d)
        assertEquals(40L, codex.reasoningTokens7d)
        assertEquals("pro", codex.planType)
        assertEquals(22.0, codex.primaryWindow?.usedPercent)
        assertEquals(48.0, codex.secondaryWindow?.usedPercent)

        val gemini = AiPlatforms.readLocalGeminiTelemetry(File(home, ".gemini"), days = 7, now = now)
        assertEquals(2, gemini.conversationCount7d)
        assertEquals(3L, gemini.requests7d)
        assertEquals(2_500L, gemini.inputTokens7d)
        assertEquals(2_100L, gemini.cachedTokens7d)
        assertEquals(115L, gemini.outputTokens7d)
        assertEquals(30L, gemini.thinkingTokens7d)
    }
}
