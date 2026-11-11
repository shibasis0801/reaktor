package dev.shibasis.reaktor.conductor.gateway

import dev.shibasis.reaktor.conductor.AgentBudget
import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentSpec
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalModelLiveTest {
    @Test fun aLocalModelAnswersFromTheRepositoryItRead() = runBlocking {
        val workspace = System.getenv("REAKTOR_LOCAL_MODEL_LIVE")?.let(::File)?.takeIf(File::isDirectory) ?: return@runBlocking
        val events = LocalModelRuntime().run(AgentRequest(
            agent = AgentSpec(AgentId("scout"), "Scout", RuntimeKind.Local, "Answer briefly.", model = System.getenv("REAKTOR_LOCAL_MODEL"),
                budget = AgentBudget(timeoutMillis = 15 * 60 * 1000L)),
            prompt = "Where is RuntimeKind declared, and which runtime does Reaktor use for Cloudflare's open models? Name the files and lines.",
            workingDirectory = workspace.path,
        )).toList()
        val outcome = (events.last() as AgentEvent.Finished).outcome
        println("route=${outcome.attributes["route"]} model=${outcome.attributes["model"]} " +
            "tools=${events.filterIsInstance<AgentEvent.ToolUse>().map { "${it.tool}(${it.detail})" }} tokens=${outcome.usage}")
        println(outcome.text.ifBlank { outcome.failure.orEmpty() })
        assertTrue(outcome.ok, outcome.failure.orEmpty())
        assertTrue(events.any { it is AgentEvent.ToolUse })
    }
}
