package dev.shibasis.reaktor.conductor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class EventId(val value: String)

@Serializable
@JvmInline
value class ThreadId(val value: String)

@Serializable
sealed interface Author {
    @Serializable
    @SerialName("human")
    data class Human(val name: String = "user") : Author

    @Serializable
    @SerialName("agent")
    data class Agent(val id: AgentId) : Author

    /** The orchestrator itself, when it records a round boundary or a protocol decision. */
    @Serializable
    @SerialName("conductor")
    data class Orchestrator(val protocol: String) : Author
}

/**
 * What an event is, so that a later stage can select by meaning instead of scraping a transcript.
 * A synthesizer asks for proposals and critiques; it never asks for "the last twelve messages".
 */
@Serializable
enum class EventKind {
    Prompt,
    Proposal,
    Critique,
    Revision,
    Synthesis,
    Note,
    Failure,
}

/**
 * Where a provider left its own session, retained for qualified future continuation adapters.
 *
 * Provider identity is not the canonical conversation. Losing it does not erase saved thread
 * events or workspace artifacts; resuming provider-internal context requires provider support.
 */
@Serializable
data class ProviderSession(val runtime: RuntimeKind, val sessionId: String)

@Serializable
enum class UsageScope { Turn, ProviderSessionTotal, ProviderSessionDelta }

@Serializable
data class AgentUsage(
    /** Inclusive input: cache reads and cache writes are subsets, not extra tokens to add. */
    val inputTokens: Long? = null,
    /** Reasoning tokens, when reported, are a subset of output. Missing counts remain null. */
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val durationMillis: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val scope: UsageScope = UsageScope.Turn,
)

/**
 * One node in the conversation.
 *
 * [parents] is what makes this a causal graph rather than a list: three agents answering the same
 * question share one parent, and the synthesis that reconciles them has three. The UI can still
 * render it as a flat conversation, but branching, joining, forking, retrying, and "what did this
 * answer actually depend on" are all answerable without inventing a second model.
 */
@Serializable
data class ThreadEvent(
    val id: EventId,
    val author: Author,
    val kind: EventKind,
    val text: String,
    val parents: List<EventId> = emptyList(),
    val round: Int = 0,
    val createdAtEpochMillis: Long = 0L,
    val session: ProviderSession? = null,
    val usage: AgentUsage? = null,
    val reportedUsage: AgentUsage? = null,
    val attributes: Map<String, String> = emptyMap(),
)

class ThreadIntegrityException(message: String) : IllegalArgumentException(message)

/**
 * The canonical, Reaktor-owned conversation. Provider sessions are projections of this; if they
 * are lost, this is what rebuilds them.
 */
@Serializable
data class ThreadDocument(
    val id: ThreadId,
    val title: String,
    val participants: List<AgentSpec> = emptyList(),
    val events: List<ThreadEvent> = emptyList(),
    val version: Int = 1,
    val providerSessions: Map<String, ProviderSession> = emptyMap(),
) {
    fun event(id: EventId): ThreadEvent? = events.firstOrNull { it.id == id }

    fun agent(id: AgentId): AgentSpec? = participants.firstOrNull { it.id == id }

    /** Events nothing else points at: where the next turn attaches. */
    fun heads(): List<ThreadEvent> {
        val referenced = events.flatMapTo(mutableSetOf()) { it.parents }
        return events.filter { it.id !in referenced }
    }

    fun byKind(kind: EventKind, round: Int? = null): List<ThreadEvent> =
        events.filter { it.kind == kind && (round == null || it.round == round) }

    /** Transitive parents in document order, so a context projection is reproducible. */
    fun ancestorsOf(id: EventId): List<ThreadEvent> {
        val seen = mutableSetOf<EventId>()
        val pending = ArrayDeque(event(id)?.parents.orEmpty())
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!seen.add(next)) continue
            event(next)?.parents?.forEach(pending::addLast)
        }
        return events.filter { it.id in seen }
    }
}

/**
 * Appends one event, rejecting anything that would break the graph.
 *
 * Every parent must already exist, which is what makes a cycle unrepresentable rather than merely
 * discouraged: an event can only ever point backwards in time.
 */
fun ThreadDocument.append(next: ThreadEvent): ThreadDocument {
    if (event(next.id) != null) {
        throw ThreadIntegrityException("Duplicate event id: ${next.id.value}")
    }
    if (next.id in next.parents) {
        throw ThreadIntegrityException("Event is its own parent: ${next.id.value}")
    }
    if (next.parents.distinct().size != next.parents.size) {
        throw ThreadIntegrityException("Repeated parent in event: ${next.id.value}")
    }
    next.parents.forEach { parent ->
        if (event(parent) == null) {
            throw ThreadIntegrityException("Unknown parent ${parent.value} for event ${next.id.value}")
        }
    }
    if (next.author is Author.Agent && agent(next.author.id) == null) {
        throw ThreadIntegrityException("Event ${next.id.value} is authored by an agent outside the thread")
    }
    return copy(events = events + next)
}

fun ThreadDocument.appendAll(events: List<ThreadEvent>): ThreadDocument =
    events.fold(this) { document, event -> document.append(event) }

/** Forward-compatible on read, compact on write: the convention `GraphDocument` already uses. */
val ConductorJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

fun ThreadDocument.encode(): String = ConductorJson.encodeToString(ThreadDocument.serializer(), this)

fun decodeThread(text: String): ThreadDocument =
    ConductorJson.decodeFromString(ThreadDocument.serializer(), text).also { document ->
        require(document.participants.map { it.id }.distinct().size == document.participants.size) {
            "Duplicate participants in thread"
        }
        document.copy(events = emptyList()).appendAll(document.events)
    }
