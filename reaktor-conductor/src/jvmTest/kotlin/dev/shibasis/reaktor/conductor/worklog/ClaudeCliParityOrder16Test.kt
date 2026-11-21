package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.ClaudeCodeEventParser
import kotlinx.serialization.json.*
import kotlin.test.*

class ClaudeCliParityOrder16Test {
    private val agent = AgentId("fixture-claude")
    private fun compare(rows: List<String>, check: (List<AgentEvent>, AgentOutcome, List<AgentEvent>, AgentOutcome) -> Unit) {
        val old = ClaudeCodeEventParser(agent, EffortRecord.none)
        val shared = ClaudeLog(null, cli = true) { null }
        val oldEvents = rows.flatMap(old::onLine)
        val sharedEvents = rows.flatMap { shared.events(Json.parseToJsonElement(it).jsonObject, agent) }
        check(oldEvents, old.finish(0, ""), sharedEvents, shared.finish(agent, EffortRecord.none, 0, ""))
    }
    @Test fun anEmptySuccessfulResultIsStillSuccessful() = compare(listOf(
        """{"type":"result","subtype":"success","result":"","is_error":false,"session_id":"fixture"}""")) { _, old, _, shared ->
        assertTrue(old.ok)
        assertEquals(old.ok, shared.ok)
        assertEquals(old.text, shared.text)
        assertEquals(old.failure, shared.failure)
    }
    @Test fun subagentUsageDoesNotEnterTheParentsLiveUsage() = compare(listOf(
        """{"type":"assistant","message":{"id":"parent","usage":{"input_tokens":7,"output_tokens":2},"content":[{"type":"text","text":"Parent"}]}}""",
        """{"type":"assistant","parent_tool_use_id":"child","message":{"id":"subagent","usage":{"input_tokens":100,"output_tokens":30},"content":[{"type":"text","text":"Child"}]}}""",
        """{"type":"result","subtype":"success","result":"Parent","is_error":false}""")) { oldEvents, _, sharedEvents, _ ->
        assertEquals(oldEvents.filterIsInstance<AgentEvent.Usage>(), sharedEvents.filterIsInstance<AgentEvent.Usage>())
    }
    @Test fun aBlankTopLevelTextBlockStillEmitsItsDelta() = compare(listOf(
        """{"type":"assistant","message":{"id":"blank","content":[{"type":"text","text":"  "}]}}""",
        """{"type":"result","subtype":"success","result":"","is_error":false}""")) { oldEvents, _, sharedEvents, _ ->
        assertEquals(oldEvents.filterIsInstance<AgentEvent.Delta>(), sharedEvents.filterIsInstance<AgentEvent.Delta>())
    }
}
