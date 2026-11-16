package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkPromptTest {
    @Test fun aRoleParagraphTypedOutsideAProfileRunStaysInTheBubble() {
        val typed = "Discuss this role.\n\nYour role, reviewer: be strict about naming"
        assertEquals(typed, WorkPrompts.of(typed).text)
        assertNull(WorkPrompts.of(typed).context)
        val sent = "You are Assistant.\n\n## Your task\n$typed\n"
        assertEquals(typed, WorkPrompts.of(sent).text)
        assertEquals(listOf("Instructions"), WorkPrompts.of(sent).context?.slices?.map { it.title })
    }

    @Test fun recordedClaudeLinesKeepTheEnvelopeOutsideTheTypedPrompt() = recorded("claude", RuntimeKind.ClaudeCode)
    @Test fun recordedCodexLinesKeepTheEnvelopeOutsideTheTypedPrompt() = recorded("codex", RuntimeKind.Codex)
    @Test fun recordedAntigravityLinesKeepTheEnvelopeOutsideTheTypedPrompt() = recorded("antigravity", RuntimeKind.Gemini)

    private fun recorded(fixture: String, runtime: RuntimeKind) {
        val directory = Files.createTempDirectory("work-prompt-fixture").toFile()
        try {
            val file = directory.resolve("transcript.jsonl")
            javaClass.getResourceAsStream("/worklog/chat-envelopes/$fixture.jsonl")!!.use { input -> file.outputStream().use(input::copyTo) }
            val log = RunLogs.prompts(WorkLogs.read(runtime, file, directory.path), emptyList(), profile = true)
            assertEquals(3, log.turns.size)
            assertEquals("Inspect the fixture graph.", log.turns[0].prompt)
            val context = assertNotNull(log.turns[0].context)
            assertEquals(4, context.slices.size)
            assertEquals(1, context.omittedEntries)
            assertEquals(1, context.notices.size)
            assertTrue(context.tokenEstimate > 0)
            assertTrue(context.sent.endsWith('\n'))
            assertTrue(context.sent.contains("## Your task"))
            assertTrue(context.slices.any { it.title == "Typed source" })
            assertEquals("Keep the next answer concise.", log.turns[1].prompt)
            assertEquals("Profile role", assertNotNull(log.turns[1].context).slices.single().title)
            assertEquals("## Your task\nKeep this literal heading in my message.", log.turns[2].prompt)
            assertNull(log.turns[2].context)
        } finally { directory.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun canonicalPromptsKeepTheNativeContextWhenTheyReplaceTheMachinePrompt() {
        val sent = "You are Reviewer.\n\nInspect the ports.\n\n## Your task\nA typed question\n\nYour role, Reviewer: Preserve source intent.\n"
        val parsed = WorkPrompts.of(sent, profile = true)
        val events = listOf(ThreadEvent(EventId("human"), Author.Human(), EventKind.Prompt, "A typed question"))
        val native = WorkLog(listOf(WorkTurn("native", parsed.text, 1, 2, emptyList(), context = parsed.context)))
        val rebuilt = RunLogs.prompts(native, events).turns.single()
        assertEquals("A typed question", rebuilt.prompt)
        assertTrue(rebuilt.context === parsed.context)
        val canonical = RunLogs.of(listOf(events.single().copy(text = sent)), emptyList(), false, profile = true).turns.single()
        assertEquals("A typed question", canonical.prompt)
        assertTrue(canonical.context?.sent == sent)
        assertEquals(2, canonical.context?.slices?.size)
    }

    @Test fun aLiteralTaskHeadingAndRoleMentionStayInTheTypedText() {
        val text = "## Your task\nDiscuss this example: Your role, Reviewer: explain a port."
        val parsed = WorkPrompts.of(text)
        assertEquals(text, parsed.text)
        assertNull(parsed.context)
    }

    @Test fun theTaskCanContainTheTaskHeadingWithoutLosingItsOwnText() {
        val typed = "Explain this heading:\n\n## Your task\nKeep it literal."
        val parsed = WorkPrompts.of("You are Reviewer.\n\n## Your task\n$typed\n")
        assertEquals(typed, parsed.text)
        assertEquals(1, parsed.context?.slices?.size)
    }
}
