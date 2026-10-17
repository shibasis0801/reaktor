package dev.shibasis.reaktor.conductor.gateway

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

class GatewayLiveTest {
    @Test fun anOpenModelAnswersFromTheRepositoryItRead() = runBlocking {
        val workspace = System.getenv("REAKTOR_GATEWAY_LIVE")?.let(::File)?.takeIf(File::isDirectory) ?: return@runBlocking
        val events = GatewayRuntime().run(AgentRequest(
            agent = AgentSpec(AgentId("scout"), "Scout", RuntimeKind.Gateway, "Answer briefly.", model = System.getenv("REAKTOR_GATEWAY_MODEL")),
            prompt = "Which Cloudflare worker in this repository has an AI binding and calls Llama Guard? Name the file and line.",
            workingDirectory = workspace.path,
        )).toList()
        val outcome = (events.last() as AgentEvent.Finished).outcome
        println("route=${outcome.attributes["route"]} model=${outcome.attributes["model"]} neurons=${outcome.attributes["neurons"]} " +
            "tools=${events.filterIsInstance<AgentEvent.ToolUse>().map { "${it.tool}(${it.detail})" }} tokens=${outcome.usage}")
        println(outcome.text.ifBlank { outcome.failure.orEmpty() })
        assertTrue(outcome.ok, outcome.failure.orEmpty())
        assertTrue(events.any { it is AgentEvent.ToolUse })
    }
}
