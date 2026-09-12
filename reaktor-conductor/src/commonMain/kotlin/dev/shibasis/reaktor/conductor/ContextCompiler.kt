package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable

/**
 * What one agent may see of the thread for one turn.
 *
 * Blind rounds are enforced here, in code, rather than by asking a model not to look at something
 * that is already in its prompt. When [includePeers] is false the compiler drops peer material
 * even if a caller supplies it, so independence is a property of the system rather than a promise.
 */
@Serializable
data class Visibility(
    val includeHistory: Boolean = true,
    val includePeers: Boolean = false,
    val peerKinds: Set<EventKind> = setOf(EventKind.Proposal),
    val maxHistoryEvents: Int = 20,
    val maxPeerChars: Int = 6000,
) {
    companion object {
        /** Round one of a council: history, no peers. */
        val Blind = Visibility(includeHistory = true, includePeers = false)

        /** Later rounds: everything the protocol chose to expose. */
        val Shared = Visibility(includeHistory = true, includePeers = true)
    }
}

data class CompileRequest(
    val thread: ThreadDocument,
    val agent: AgentSpec,
    val task: String,
    val visibility: Visibility,
    val peers: List<ThreadEvent> = emptyList(),
)

/**
 * Turns the canonical thread into the one string a harness receives.
 *
 * This is the layer that keeps a long conversation from becoming a long prompt, and it is where
 * per-agent policy belongs: instructions, how much history, which peers, how much of each.
 */
fun interface ContextCompiler {
    fun compile(request: CompileRequest): String
}

class DefaultContextCompiler : ContextCompiler {
    override fun compile(request: CompileRequest): String = buildString {
        val agent = request.agent
        appendLine("You are ${agent.name}.")
        if (agent.instructions.isNotBlank()) {
            appendLine()
            appendLine(agent.instructions.trim())
        }

        // Anything about to be rendered as a peer is dropped from history, so no event is paid
        // for twice in one prompt.
        val peers = visiblePeers(request)
        val peerIds = peers.mapTo(mutableSetOf()) { it.id }

        if (request.visibility.includeHistory) {
            val history = request.thread.events
                .filter { it.kind != EventKind.Failure && it.id !in peerIds }
                .takeLast(request.visibility.maxHistoryEvents)
            if (history.isNotEmpty()) {
                appendLine()
                appendLine("## Conversation so far")
                history.forEach { event ->
                    appendLine()
                    appendLine("### ${describe(event.author)} · ${event.kind.name.lowercase()}")
                    appendLine(event.text.trim())
                }
            }
        }

        if (peers.isNotEmpty()) {
            appendLine()
            appendLine("## What the other participants said")
            peers.forEach { peer ->
                appendLine()
                appendLine("### ${describe(peer.author)} · ${peer.kind.name.lowercase()}")
                appendLine(peer.text.trim().take(request.visibility.maxPeerChars))
            }
        }

        appendLine()
        appendLine("## Your task")
        appendLine(request.task.trim())
    }

    /**
     * The enforcement point for a blind round: peer material is dropped unless this turn is
     * allowed to see it, an agent never receives its own output as a peer, and only the kinds the
     * protocol asked for cross the boundary.
     */
    private fun visiblePeers(request: CompileRequest): List<ThreadEvent> {
        if (!request.visibility.includePeers) return emptyList()
        return request.peers
            .filter { it.kind in request.visibility.peerKinds }
            .filter { (it.author as? Author.Agent)?.id != request.agent.id }
    }

    private fun describe(author: Author): String = when (author) {
        is Author.Human -> author.name
        is Author.Agent -> author.id.value
        is Author.Orchestrator -> "conductor(${author.protocol})"
    }
}
