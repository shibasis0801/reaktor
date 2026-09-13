package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

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
)

@Serializable
sealed interface AgentDecision {
    @Serializable
    data object Approve : AgentDecision

    @Serializable
    data class Deny(val reason: String? = null) : AgentDecision

    @Serializable
    data class Answer(val text: String) : AgentDecision
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
