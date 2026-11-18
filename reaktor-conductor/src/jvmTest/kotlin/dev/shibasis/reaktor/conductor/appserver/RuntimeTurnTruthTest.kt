package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RuntimeTurnTruthTest {
    @Test fun geminiKeepsEveryPlannerParagraphInItsTurnAnswer() {
        val reader = AntigravityTranscriptEvents(AgentId("gemini"))
        val deltas = (1..3).flatMap { n -> reader.line("""{"step_index":$n,"type":"PLANNER_RESPONSE","content":"Paragraph $n"}""") }
            .filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text }
        assertEquals("Paragraph 1\n\nParagraph 2\n\nParagraph 3", reader.answer)
        assertEquals(deltas, reader.answer)
    }

    @Test fun codexDesktopStreamsTheActualRolloutSchemaBeforeCompletion() = runBlocking {
        desktop { file, session ->
            val received = mutableListOf<AgentEvent>()
            val output = async { withTimeout(5000) { session.events.onEach { received += it }.toList() } }
            withTimeout(3000) { while (received.none { it is AgentEvent.TurnStarted }) yield() }
            file.event("""{"type":"agent_message_delta","delta":"First "}""")
            file.event("""{"type":"agent_message_delta","delta":"answer"}""")
            file.event("""{"type":"item_completed","item":{"id":"command","type":"CommandExecution","command":["sh","-lc","check"],"exit_code":0,"status":"completed"}}""")
            withTimeout(3000) { while (received.filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text } != "First answer") yield() }
            assertTrue(received.filterIsInstance<AgentEvent.Activity>().any { it.item.id == "command" })
            file.event("""{"type":"item_completed","item":{"id":"answer","type":"AgentMessage","content":[{"type":"text","text":"First answer"}]}}""")
            file.event("""{"type":"task_complete","turn_id":"ours"}""")
            assertEquals("First answer", output.await().filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        }
    }

    @Test fun successiveDesktopMessagesHaveABlankLineInLiveText() = runBlocking {
        desktop { file, session ->
            val received = mutableListOf<AgentEvent>()
            val output = async { withTimeout(5000) { session.events.onEach { received += it }.toList() } }
            withTimeout(3000) { while (session.activeTurn == null) yield() }
            file.event("""{"type":"item_completed","item":{"type":"AgentMessage","id":"first","text":"I read the file"}}""")
            file.event("""{"type":"item_completed","item":{"type":"AgentMessage","id":"second","text":"Here is the result"}}""")
            file.event("""{"type":"task_complete","turn_id":"ours","last_agent_message":"Here is the result"}""")
            val events = output.await()
            assertEquals("I read the file\n\nHere is the result", events.filterIsInstance<AgentEvent.Delta>().joinToString("") { it.text })
            assertEquals("Here is the result", events.filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        }
    }

    @Test fun anEarlierTurnCompletingAfterQueueIsNotThisRequestsAnswer() = runBlocking {
        desktop(earlier = true) { file, session ->
            val output = async { withTimeout(5000) { session.events.toList() } }
            withTimeout(3000) { while (!file.readText().contains("task_started")) yield() }
            file.event("""{"type":"task_complete","turn_id":"ours","last_agent_message":"Our answer"}""")
            assertEquals("Our answer", output.await().filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        }
    }

    @Test fun steeringNamesTheCurrentTurnBeforeQueueingInItsAppThread() = runBlocking {
        val launches = mutableListOf<List<String>>()
        desktop(launches = launches) { file, session ->
            val output = async { withTimeout(5000) { session.events.toList() } }
            withTimeout(3000) { while (session.activeTurn == null) yield() }
            val before = launches.size
            assertIs<CommandOutcome.Stale>(session.steer("Wrong turn", "previous"))
            assertEquals(before, launches.size)
            assertEquals(CommandOutcome.Accepted, session.steer("Steer this turn", "ours"))
            assertEquals(listOf("stub", "queue", "--thread", "fixture-thread", "--message", "Steer this turn"), launches.last())
            file.event("""{"type":"task_complete","turn_id":"ours","last_agent_message":"Our answer"}""")
            assertEquals("Our answer", output.await().filterIsInstance<AgentEvent.Finished>().single().outcome.text)
        }
    }

    private suspend fun desktop(earlier: Boolean = false, launches: MutableList<List<String>> = mutableListOf(), test: suspend CoroutineScope.(File, CodexDesktopSession) -> Unit) = coroutineScope {
        val home = Files.createTempDirectory("desktop-truth").toFile()
        try {
            val thread = "fixture-thread"
            val file = home.resolve("sessions/2026/11/17/rollout-$thread.jsonl").apply { parentFile.mkdirs(); writeText("{}\n") }
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Our question", home.path,
                resume = ProviderSession(RuntimeKind.Codex, thread))
            val queued = CompletableDeferred<Unit>()
            val session = CodexDesktopSession(request, "stub", this, home, launch = { command ->
                launches += command
                if (command.getOrNull(1) == "queue" && command.last() == "Our question") {
                    if (earlier) file.event("""{"type":"task_complete","turn_id":"previous","last_agent_message":"Previous answer"}""")
                    file.event("""{"type":"item_completed","item":{"type":"UserMessage","content":[{"type":"text","text":"Our question"}]}}""")
                    file.event("""{"type":"task_started","turn_id":"ours"}""")
                    queued.complete(Unit)
                }
                true
            })
            val run = async { test(file, session) }
            run.await()
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun File.event(payload: String) = appendText("""{"type":"event_msg","payload":$payload}""" + "\n")
}
