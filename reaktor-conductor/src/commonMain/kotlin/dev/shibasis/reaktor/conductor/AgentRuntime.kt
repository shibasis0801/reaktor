package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One turn's worth of work for one agent. The prompt is already compiled; see [ContextCompiler]. */
data class AgentRequest(
    val agent: AgentSpec,
    val prompt: String,
    val workingDirectory: String,
    val resume: ProviderSession? = null,
)

/**
 * What a harness reports while it works. Deliberately small: agents exchange results, not their
 * internal monologue, so a downstream stage receives a proposal rather than a transcript.
 */
sealed interface AgentEvent {
    val agent: AgentId

    data class Started(override val agent: AgentId, val session: ProviderSession?) : AgentEvent

    data class Delta(override val agent: AgentId, val text: String) : AgentEvent

    data class ToolUse(
        override val agent: AgentId,
        val tool: String,
        val detail: String? = null,
    ) : AgentEvent

    data class Finished(override val agent: AgentId, val outcome: AgentOutcome) : AgentEvent
}

data class AgentOutcome(
    val agent: AgentId,
    val text: String,
    val ok: Boolean,
    val failure: String? = null,
    val session: ProviderSession? = null,
    val usage: AgentUsage? = null,
)

/**
 * One harness. Claude Code and Codex are subprocess implementations; an in-process JVM agent or a
 * remote endpoint would implement the same interface without the conductor noticing.
 *
 * Implementations must tolerate concurrent calls for different agents, because blind rounds run
 * every participant at once.
 */
interface AgentRuntime {
    val kind: RuntimeKind

    fun run(request: AgentRequest): Flow<AgentEvent>
}

/** Drains a run to its outcome, forwarding intermediate events to [onEvent]. */
suspend fun AgentRuntime.await(
    request: AgentRequest,
    onEvent: (AgentEvent) -> Unit = {},
): AgentOutcome {
    var outcome: AgentOutcome? = null
    run(request).collect { event ->
        onEvent(event)
        if (event is AgentEvent.Finished) outcome = event.outcome
    }
    return outcome ?: AgentOutcome(
        agent = request.agent.id,
        text = "",
        ok = false,
        failure = "Runtime ${kind.name} produced no outcome",
    )
}

/**
 * A runtime with no subprocess and no network, so protocols can be tested and dry-run offline.
 * [reply] receives the fully compiled prompt, which makes it a useful probe for what an agent was
 * actually shown.
 */
class EchoRuntime(
    override val kind: RuntimeKind = RuntimeKind.Echo,
    private val reply: (AgentRequest) -> String = { "[${it.agent.name}] ${it.prompt.length} chars" },
) : AgentRuntime {
    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        emit(AgentEvent.Started(request.agent.id, null))
        val text = reply(request)
        emit(AgentEvent.Delta(request.agent.id, text))
        emit(
            AgentEvent.Finished(
                request.agent.id,
                AgentOutcome(agent = request.agent.id, text = text, ok = true),
            ),
        )
    }
}
