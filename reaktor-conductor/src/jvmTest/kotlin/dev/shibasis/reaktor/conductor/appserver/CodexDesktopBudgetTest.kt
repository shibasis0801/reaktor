package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexDesktopBudgetTest {
    @Test fun aStartedTurnWithoutItsIdStillHasABoundedBudget() = runBlocking {
        val home = Files.createTempDirectory("codex-desktop-budget").toFile()
        try {
            val file = home.resolve("sessions/2026/11/18/rollout-fixture.jsonl").apply { parentFile.mkdirs(); writeText("{}\n") }
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, "", budget = AgentBudget(timeoutMillis = 200)),
                "Invented question", home.path, resume = ProviderSession(RuntimeKind.Codex, "fixture"))
            val session = CodexDesktopSession(request, "stub", this, home, launch = { command ->
                if (command.getOrNull(1) == "queue") file.appendText(
                    """{"type":"event_msg","payload":{"type":"task_started"}}""" + "\n" +
                    """{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"Invented answer"}}""" + "\n")
                true
            })
            val outcome = withTimeout(1500) { session.events.filterIsInstance<AgentEvent.Finished>().first().outcome }
            assertFalse(outcome.ok)
            assertTrue(outcome.failure.orEmpty().contains("budget"), "A malformed boundary did not receive a bounded failure")
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
