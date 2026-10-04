package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What a provider stopped to ask about. Silence is not an answer: the turn waits. */
@Serializable
enum class RequestKind { CommandApproval, PatchApproval, Permission, Input, Elicitation }

/**
 * One thing the provider is blocked on.
 *
 * [scope] is the concrete subject — the command, the file, the permission — and is shown before any
 * approval control, because an approval whose scope is not visible is not informed consent.
 */
@Serializable
data class PendingRequest(
    val id: String,
    val kind: RequestKind,
    val title: String,
    val scope: String? = null,
    val options: List<String> = emptyList(),
    val providerRequestId: String? = null,
    val turnId: String? = null,
    val questions: List<PendingQuestion> = emptyList(),
    val schema: JsonObject? = null,
    val url: String? = null,
)

@Serializable
data class PendingQuestion(val id: String, val title: String, val options: List<String> = emptyList())

@Serializable
sealed interface AgentDecision {
    @Serializable
    data object Approve : AgentDecision

    @Serializable
    data class Deny(val reason: String? = null) : AgentDecision

    @Serializable
    data class Answer(val text: String) : AgentDecision

    @Serializable
    data class Answers(val values: Map<String, List<String>>) : AgentDecision

    @Serializable
    data class Form(val values: JsonObject) : AgentDecision
}

/**
 * Why a command could not be carried out, kept as a value rather than an exception.
 *
 * Steering a turn that already ended is ordinary — a person clicked a moment late — and must not
 * look like a transport failure, or callers learn to ignore both.
 */
@Serializable
sealed interface CommandOutcome {
    @Serializable
    data object Accepted : CommandOutcome

    /** The turn this command named is no longer the active one. */
    @Serializable
    data class Stale(val expected: String?, val active: String?) : CommandOutcome

    @Serializable
    data class Unsupported(val capability: String) : CommandOutcome

    @Serializable
    data class Failed(val reason: String) : CommandOutcome
}

/**
 * A live provider session.
 *
 * Separate from [AgentRuntime] because most of what a batch harness does has no session to hold:
 * the subprocess adapters run a turn and exit, and pretending they can be steered would put a
 * control in front of a person that silently does nothing.
 */
interface AgentSession : AutoCloseable {
    fun nativeAgents(): List<NativeAgentState> = emptyList()
    suspend fun controlNative(id: String, expectedTurn: String, text: String?): CommandOutcome = CommandOutcome.Unsupported("native subagent control")
    val events: Flow<AgentEvent>

    /** The provider's own id for the turn in flight, or null between turns. */
    val activeTurn: String?

    /** Adds input to the turn in flight. [expectedTurn] is a precondition, not a hint. */
    suspend fun steer(text: String, expectedTurn: String?): CommandOutcome

    /** Answers one pending request. An id that is not pending is [CommandOutcome.Stale]. */
    suspend fun resolve(requestId: String, decision: AgentDecision): CommandOutcome

    suspend fun interrupt(): CommandOutcome
}

/**
 * A runtime that can hold a session open rather than only running a turn to completion.
 *
 * [interactive] is the capability record for the session itself: a runtime may implement this
 * interface and still report that a given control is unqualified on the installed version.
 */
interface InteractiveAgentRuntime : AgentRuntime {
    val interactive: Qualification

    suspend fun open(request: AgentRequest): AgentSession
}

/**
 * Drains one turn, handing the live session to [onSession] first when the runtime has one.
 *
 * The batch adapters have nothing to hand over and fall through to [await], so a caller writes one
 * path and the difference stays where it belongs: in whether a control is offered at all.
 */
suspend fun AgentRuntime.awaitSession(
    request: AgentRequest,
    onSession: (AgentSession) -> Unit,
    onClosed: (AgentSession) -> Unit = {},
    onEvent: suspend (AgentEvent) -> Unit = {},
): AgentOutcome {
    if (this !is InteractiveAgentRuntime) return await(request, onEvent)
    val session = open(request)
    onSession(session)
    var outcome: AgentOutcome? = null
    try {
        session.events.collect { event ->
            onEvent(event)
            if (event is AgentEvent.Finished) outcome = event.outcome
        }
    } finally {
        try { session.close() } finally { onClosed(session) }
    }
    return outcome ?: AgentOutcome(
        agent = request.agent.id,
        text = "",
        ok = false,
        failure = "Session for ${kind.name} produced no outcome",
    )
}
