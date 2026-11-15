package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunLogPromptMatchTest {
    @Test fun twoAskedPromptsMatchTheOneTurnAlreadyWritten() {
        val first = envelope("First request", "first")
        val second = envelope("Second request", "second")
        val log = native(" First\n request ")
        val turn = RunLogs.prompts(log, asked(first, second)).turns.single()
        assertEquals("First request", turn.prompt)
        assertEquals(WorkPrompts.of(first).context, turn.context)
    }

    @Test fun oneAskedPromptMatchesWithinThreeLoggedTurns() {
        val sent = envelope("Hangar request", "middle")
        val log = native("Vendor before", "Hangar request", "Vendor after")
        val result = RunLogs.prompts(log, asked(sent))
        assertSame(log.turns[0], result.turns[0])
        assertSame(log.turns[2], result.turns[2])
        assertEquals(WorkPrompts.of(sent).context, result.turns[1].context)
    }

    @Test fun aVendorTurnBetweenHangarTurnsStaysAsItWas() {
        val first = envelope("First request", "first")
        val last = envelope("Last request", "last")
        val log = native("First request", "Typed in the vendor app", "Last request")
        val result = RunLogs.prompts(log, asked(first, last))
        assertSame(log.turns[1], result.turns[1])
        assertEquals(listOf(WorkPrompts.of(first).context, null, WorkPrompts.of(last).context), result.turns.map { it.context })
    }

    @Test fun identicalPromptsMatchSuccessiveTurnsInOrder() {
        val first = envelope("Repeat the check", "first")
        val second = envelope("Repeat the check", "second")
        val log = native("Repeat the check", "Vendor question", "Repeat the check")
        val result = RunLogs.prompts(log, asked(first, second))
        assertEquals(WorkPrompts.of(first).context, result.turns[0].context)
        assertSame(log.turns[1], result.turns[1])
        assertEquals(WorkPrompts.of(second).context, result.turns[2].context)
    }

    @Test fun anEnvelopeQuotingAnEarlierQuestionDoesNotMatchThatQuestion() {
        val first = envelope("Find the port", "first")
        val second = envelope("Explain the route", "second", "Find the port")
        assertTrue(second.contains("Find the port"))
        val log = native("Vendor-only question", "Explain the route")
        val result = RunLogs.prompts(log, asked(first, second))
        assertSame(log.turns[0], result.turns[0])
        assertEquals(WorkPrompts.of(second).context, result.turns[1].context)
    }

    @Test fun aWindowOfEventsLeavesEarlierLoggedTurnsAlone() {
        val sent = envelope("Visible request", "visible")
        val log = native("Vendor before", "Earlier Hangar request", "Visible request")
        val result = RunLogs.prompts(log, asked(sent))
        assertSame(log.turns[0], result.turns[0])
        assertSame(log.turns[1], result.turns[1])
        assertEquals(WorkPrompts.of(sent).context, result.turns[2].context)
    }

    @Test fun equalCountsKeepThePositionalFallbackOnlyWhenNothingMatched() {
        val first = envelope("Canonical first", "first")
        val second = envelope("Canonical second", "second")
        val retained = WorkPrompts.of(envelope("Native first", "native")).context
        val log = native("Native first", "Native second").let { it.copy(turns = listOf(it.turns[0].copy(context = retained), it.turns[1])) }
        val result = RunLogs.prompts(log, asked(first, second))
        assertEquals(listOf("Canonical first", "Canonical second"), result.turns.map { it.prompt })
        assertSame(retained, result.turns[0].context)
        assertEquals(WorkPrompts.of(second).context, result.turns[1].context)
    }

    @Test fun aPartialMatchDoesNotPositionallyRewriteTheOtherTurn() {
        val first = envelope("Matching request", "first")
        val missing = envelope("Not written yet", "missing")
        val log = native("Matching request", "Vendor question")
        val result = RunLogs.prompts(log, asked(first, missing))
        assertEquals(WorkPrompts.of(first).context, result.turns[0].context)
        assertSame(log.turns[1], result.turns[1])
    }

    private fun native(vararg prompts: String) = WorkLog(prompts.mapIndexed { index, prompt ->
        WorkTurn("native-$index", prompt, index.toLong(), index.toLong(), emptyList())
    })

    private fun asked(vararg prompts: String) = prompts.mapIndexed { index, prompt ->
        ThreadEvent(EventId("asked-$index"), Author.Human(), EventKind.Prompt, prompt)
    }

    private fun envelope(task: String, seat: String, history: String? = null): String = DefaultContextCompiler().compile(CompileRequest(
        ThreadDocument(ThreadId("fixture-$seat"), "Fixture conversation", events = history?.let {
            listOf(ThreadEvent(EventId("history"), Author.Human(), EventKind.Prompt, it))
        }.orEmpty()),
        AgentSpec(AgentId("fixture-$seat"), "Fixture $seat", RuntimeKind.Codex, "Inspect the $seat fixture."),
        task, Visibility.Blind,
        context = ContextPacket(workspaceId = "fixture", principalId = "fixture", source = "fixture", entries = listOf(
            ContextEntry("fixture:$seat", "source", "Typed source", "Fixture ports", "Review"))),
    ))
}
