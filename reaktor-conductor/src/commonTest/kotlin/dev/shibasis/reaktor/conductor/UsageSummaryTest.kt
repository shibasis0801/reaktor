package dev.shibasis.reaktor.conductor

import kotlin.test.*

class UsageSummaryTest {
    @Test fun resumedCodexCountersAreDifferencedAndMissingBaselinesStayUnknown() {
        val first = AgentUsage(inputTokens = 21471, outputTokens = 33, cachedInputTokens = 2432, reasoningOutputTokens = 26, scope = UsageScope.ProviderSessionTotal)
        val resumed = AgentUsage(inputTokens = 43016, outputTokens = 50, cachedInputTokens = 23296, reasoningOutputTokens = 35, scope = UsageScope.ProviderSessionTotal)
        val delta = resumed.forTurn(first, freshSession = false)
        assertEquals(21545L, delta.inputTokens)
        assertEquals(17L, delta.outputTokens)
        assertEquals(20864L, delta.cachedInputTokens)
        assertEquals(9L, delta.reasoningOutputTokens)
        assertEquals(UsageScope.ProviderSessionDelta, delta.scope)
        assertNull(resumed.forTurn(null, freshSession = false).inputTokens)
        assertNull(first.forTurn(resumed, freshSession = false).inputTokens)
        assertEquals(21471L, first.forTurn(null, freshSession = true).inputTokens)
    }
    @Test
    fun unknownUsageStaysUnknownAndReasoningIsNotAddedToOutput() {
        val agent = AgentSpec(AgentId("a"), "a", RuntimeKind.Echo, "")
        val document = ThreadDocument(ThreadId("t"), "t", listOf(agent)).appendAll(listOf(
            ThreadEvent(EventId("a"), Author.Agent(agent.id), EventKind.Proposal, "answer",
                usage = AgentUsage(inputTokens = 100, outputTokens = 30, cachedInputTokens = 80,
                    cacheWriteInputTokens = 5, reasoningOutputTokens = 20)),
            ThreadEvent(EventId("b"), Author.Agent(agent.id), EventKind.Failure, "interrupted"),
        ))
        val summary = decodeThread(document.encode()).usageSummary()
        assertEquals(UsageMetric(20, 1), summary.freshInput)
        assertEquals(UsageMetric(30, 1), summary.output)
        assertEquals(UsageMetric(20, 1), summary.reasoningOutput)
        assertEquals(UsageMetric(5, 1), summary.cacheWriteInput)
    }
}
