package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser tests for harnesses Reaktor does not own.
 *
 * Every fixture marked CAPTURED is real output recorded on 2026-09-07 from `claude 2.1.183` and
 * `codex-cli 0.131.0`. That matters more than usual here: these formats belong to someone else, so
 * a parser written against a guessed schema is one that breaks silently on contact with the real
 * thing.
 */
class HarnessParserTest {
    @Test
    fun partialTextWithoutTheTerminalEnvelopeIsNotSuccess() {
        val codex = CodexEventParser(agent)
        codex.onLine("""{"type":"item.completed","item":{"type":"agent_message","text":"partial"}}""")
        val claude = ClaudeCodeEventParser(agent)
        claude.onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":"partial"}]}}""")
        assertFalse(codex.finish(0, "").ok)
        assertFalse(claude.finish(0, "").ok)
    }

    @Test
    fun providerCacheAccountingUsesTheSameInclusiveInputConvention() {
        val codex = CodexEventParser(agent)
        codexSuccess.forEach(codex::onLine)
        assertEquals(2432L, codex.finish(0, "").usage?.cachedInputTokens)
        assertEquals(0L, codex.finish(0, "").usage?.reasoningOutputTokens)
        val claude = ClaudeCodeEventParser(agent)
        claude.onLine("""{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{"input_tokens":11,"cache_read_input_tokens":80,"cache_creation_input_tokens":9,"output_tokens":7}}""")
        val usage = claude.finish(0, "").usage
        assertEquals(100L, usage?.inputTokens)
        assertEquals(80L, usage?.cachedInputTokens)
        assertEquals(9L, usage?.cacheWriteInputTokens)
        assertEquals(7L, usage?.outputTokens)
        val missing = ClaudeCodeEventParser(agent)
        missing.onLine("""{"type":"result","is_error":false,"result":"done","usage":{"input_tokens":11}}""")
        assertNull(missing.finish(0, "").usage?.inputTokens)
    }
    private val agent = AgentId("architect")

    // ---- Codex -----------------------------------------------------------------------------

    /** CAPTURED: a complete successful `codex exec --json` run. */
    private val codexSuccess = listOf(
        """{"type":"thread.started","thread_id":"01a0786e-2e14-7611-b13e-e9e32678f43e"}""",
        """{"type":"turn.started"}""",
        """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"pong"}}""",
        """{"type":"turn.completed","usage":{"input_tokens":20373,"cached_input_tokens":2432,"output_tokens":5,"reasoning_output_tokens":0}}""",
    )

    /** CAPTURED: the failure path, when the configured model is rejected upstream. */
    private val codexFailure = listOf(
        """{"type":"thread.started","thread_id":"01a0786a-259f-7923-87a2-d5b3dad5aa3c"}""",
        """{"type":"turn.started"}""",
        """{"type":"error","message":"The 'gpt-6-astra' model requires a newer version of Codex."}""",
        """{"type":"turn.failed","error":{"message":"The 'gpt-6-astra' model requires a newer version of Codex."}}""",
    )

    @Test
    fun codexReadsTheAnswerSessionAndUsage() {
        val parser = CodexEventParser(agent)
        val events = codexSuccess.flatMap(parser::onLine)

        val started = events.filterIsInstance<AgentEvent.Started>().single()
        assertEquals(RuntimeKind.Codex, started.session?.runtime)
        assertEquals("01a0786e-2e14-7611-b13e-e9e32678f43e", started.session?.sessionId)
        assertEquals(listOf("pong"), events.filterIsInstance<AgentEvent.Delta>().map { it.text })

        val outcome = parser.finish(exitCode = 0, stderr = "")
        assertTrue(outcome.ok)
        assertEquals("pong", outcome.text)
        assertNull(outcome.failure)
        assertEquals(20373L, outcome.usage?.inputTokens)
        assertEquals(5L, outcome.usage?.outputTokens)
    }

    @Test
    fun codexStreamsProgressButSavesOnlyTheLastCompletedMessage() {
        // Message shapes captured from codex-cli 0.154.0 on 2026-09-13.
        val parser = CodexEventParser(agent)
        val messages = listOf(
            """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"I’ll read the first line of `02-runtime-evidence.md`."}}""",
            """{"type":"item.completed","item":{"id":"item_2","type":"agent_message","text":"PHASE_PROBE_DONE"}}""",
        ).flatMap(parser::onLine)
        assertEquals(2, messages.filterIsInstance<AgentEvent.Delta>().size)
        assertFalse(parser.finish(0, "").ok)
        parser.onLine("""{"type":"turn.completed","usage":{"input_tokens":20,"output_tokens":10}}""")
        assertTrue(parser.finish(0, "").ok)
        assertEquals("PHASE_PROBE_DONE", parser.finish(0, "").text)
    }

    @Test
    fun codexReportsTheUpstreamFailureRatherThanTheExitCode() {
        val parser = CodexEventParser(agent)
        codexFailure.forEach(parser::onLine)
        val outcome = parser.finish(exitCode = 1, stderr = "noise on stderr")

        assertFalse(outcome.ok)
        assertTrue(outcome.failure!!.contains("newer version of Codex"))
        assertEquals("01a0786a-259f-7923-87a2-d5b3dad5aa3c", outcome.session?.sessionId)
    }

    // ---- Claude Code -----------------------------------------------------------------------

    /**
     * CAPTURED envelopes. A nested `claude` could not reach the operator's credentials from this
     * environment, so the recorded run is the auth-failure path. The envelope structure is real;
     * only the values differ from a successful turn, and the success case below reuses the exact
     * same shapes.
     */
    private val claudeInit =
        """{"type":"system","subtype":"init","session_id":"39b6dd65-cfc2-4ee7-8d76-61aabf4c66b9"}"""

    private val claudeAuthFailureResult =
        """{"type":"result","subtype":"success","is_error":true,"result":"Not logged in","session_id":"39b6dd65-cfc2-4ee7-8d76-61aabf4c66b9","total_cost_usd":0,"duration_ms":49}"""

    @Test
    fun claudeReadsSessionTextAndCost() {
        val parser = ClaudeCodeEventParser(agent)
        val events = listOf(
            claudeInit,
            """{"type":"assistant","message":{"content":[{"type":"text","text":"pong"}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"pong","session_id":"39b6dd65-cfc2-4ee7-8d76-61aabf4c66b9","total_cost_usd":0.0123,"duration_ms":1200,"usage":{"input_tokens":11,"output_tokens":3}}""",
        ).flatMap(parser::onLine)

        assertEquals(
            "39b6dd65-cfc2-4ee7-8d76-61aabf4c66b9",
            events.filterIsInstance<AgentEvent.Started>().single().session?.sessionId,
        )
        assertEquals(listOf("pong"), events.filterIsInstance<AgentEvent.Delta>().map { it.text })

        val outcome = parser.finish(exitCode = 0, stderr = "")
        assertTrue(outcome.ok)
        assertEquals("pong", outcome.text)
        assertEquals(0.0123, outcome.usage?.costUsd)
        assertEquals(1200L, outcome.usage?.durationMillis)
    }

    /**
     * Why the parser keys on `is_error` rather than `subtype`: this real line reports
     * `"subtype":"success"` and `"is_error":true` at the same time.
     */
    @Test
    fun claudeTreatsIsErrorAsAuthoritativeOverSubtype() {
        val parser = ClaudeCodeEventParser(agent)
        parser.onLine(claudeInit)
        parser.onLine(claudeAuthFailureResult)
        val outcome = parser.finish(exitCode = 1, stderr = "")

        assertFalse(outcome.ok)
        assertEquals("Not logged in", outcome.failure)
    }

    @Test
    fun claudeReportsToolUse() {
        val parser = ClaudeCodeEventParser(agent)
        val events = parser.onLine(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{}}]}}""",
        )
        assertEquals("Read", events.filterIsInstance<AgentEvent.ToolUse>().single().tool)
    }

    @Test
    fun claudeDoesNotRestartTheRunOnOtherSystemEvents() {
        val parser = ClaudeCodeEventParser(agent)
        assertEquals(1, parser.onLine(claudeInit).filterIsInstance<AgentEvent.Started>().size)
        assertTrue(parser.onLine("""{"type":"system","subtype":"status","status":"working"}""").isEmpty())
        assertTrue(parser.onLine("""{"type":"system","subtype":"future_notification"}""").isEmpty())
    }

    // ---- Both ------------------------------------------------------------------------------

    /**
     * These formats will grow event types without warning. An unrecognised line must cost one
     * ignored line, never a failed turn.
     */
    @Test
    fun unknownAndMalformedLinesAreIgnoredByBothParsers() {
        val noise = listOf(
            """{"type":"some_future_event","payload":{"a":1}}""",
            "not json at all",
            "",
            "   ",
            """{"broken":""",
        )
        assertTrue(noise.flatMap(ClaudeCodeEventParser(agent)::onLine).isEmpty())
        assertTrue(noise.flatMap(CodexEventParser(agent)::onLine).isEmpty())
    }

    @Test
    fun aRunThatProducedNoOutputFailsRatherThanReturningEmptySuccess() {
        assertFalse(ClaudeCodeEventParser(agent).finish(exitCode = 0, stderr = "").ok)
        assertFalse(CodexEventParser(agent).finish(exitCode = 0, stderr = "").ok)
    }
}
