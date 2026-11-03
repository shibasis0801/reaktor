package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.workspace.BoardActorKind
import dev.shibasis.reaktor.graph.ActorTransition
import dev.shibasis.reaktor.graph.PersistentActorNode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ActorDirective
import dev.shibasis.reaktor.graph.core.node.ActorNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.Reply
import dev.shibasis.reaktor.graph.core.node.actorKey
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.conductor.workspace.digest

internal sealed interface MemoryCommand {
    data class Remember(val draft: RememberFact) : MemoryCommand
    data class Review(val request: ReviewFact) : MemoryCommand
    data class Retire(val request: RetireFact) : MemoryCommand
    data class Revise(val request: ReviseFact) : MemoryCommand
    data class Import(val fact: MemoryFact) : MemoryCommand
}

internal data class MemoryEnvelope(val command: MemoryCommand, val reply: Reply<MemoryFact>)

internal fun memoryKey(scope: MemoryScope) = actorKey<MemoryEnvelope>("memory/${scope.kind}/${digest(scope.key)}")

internal class MemoryActor(graph: Graph, private val scope: MemoryScope, store: MemoryStore) : ActorNode<MemoryEnvelope>(graph, memoryKey(scope)) {
    private val persistent = graph.Node {
        PersistentActorNode(it, "${key.name}/transitions", "Memory ${scope.kind}: ${scope.key}", store.objects.storeName,
            store.state(scope), setOf("ready"), listOf("remember", "review", "retire", "revise", "import").map {
                ActorTransition(it, setOf("ready"), setOf("ready"))
            }, { _: MemoryState -> "ready" })
    }
    private val remember = persistent.transition<MemoryFact>("remember", Effect.LocalArtifactWrite) { command, state ->
        val draft = (command as MemoryCommand.Remember).draft
        require(draft.scope == scope && draft.provenance.sourceDate <= System.currentTimeMillis())
        val links = draft.links.distinct().sorted()
        val id = digest(ConductorJson.encodeToString(MemoryScope.serializer(), scope) + ":${draft.kind}:${draft.text}:" + links.joinToString("\u0000"))
        val previous = state.facts[id]
        if (previous != null) {
            require(previous.status != MemoryStatus.Retired) { "Retired facts require a new proposal" }
            require(previous.supersedes == draft.supersedes)
            val provenance = (previous.provenance + draft.provenance).distinct()
            if (provenance == previous.provenance) previous else previous.copy(provenance = provenance,
                revision = previous.revision + 1, updatedAt = System.currentTimeMillis()).also { state.facts[id] = it }
        } else {
            draft.supersedes?.let { require(state.facts[it]?.status == MemoryStatus.Kept) { "Supersede a kept fact in this scope" } }
            val now = System.currentTimeMillis()
            MemoryFact(id, scope, draft.kind, draft.title, draft.text, links, listOf(draft.provenance), MemoryStatus.Proposed,
                1, now, now, supersedes = draft.supersedes, importance = draft.importance).also { state.facts[id] = it }
        }
    }
    private val review = persistent.transition<MemoryFact>("review", Effect.LocalArtifactWrite) { command, state ->
        val request = (command as MemoryCommand.Review).request
        require(request.by.kind == BoardActorKind.Person && request.by.name.isNotBlank()) { "Only a person reviews memory" }
        val fact = requireNotNull(state.facts[request.id]) { "Unknown memory fact" }
        val status = if (request.keep) MemoryStatus.Kept else MemoryStatus.Retired
        if (fact.status == status) fact else {
            require(fact.revision == request.revision && fact.status == MemoryStatus.Proposed) { "Memory proposal changed; read it again" }
            if (request.keep) fact.supersedes?.let { prior ->
                require(state.facts[prior]?.status == MemoryStatus.Kept && state.facts.values.none {
                    it.status == MemoryStatus.Kept && it.supersedes == prior
                }) { "The superseded fact changed; propose a fresh revision" }
            }
            fact.copy(status = status, revision = fact.revision + 1, updatedAt = System.currentTimeMillis(), reviewedBy = request.by)
                .also { state.facts[it.id] = it }
        }
    }
    private val retire = persistent.transition<MemoryFact>("retire", Effect.LocalArtifactWrite) { command, state ->
        val request = (command as MemoryCommand.Retire).request
        require(request.by.name.isNotBlank())
        val fact = requireNotNull(state.facts[request.id]) { "Unknown memory fact" }
        require(fact.revision == request.revision) { "Memory changed; read it again" }
        if (fact.status == MemoryStatus.Retired) fact else fact.copy(status = MemoryStatus.Retired,
            revision = fact.revision + 1, updatedAt = System.currentTimeMillis()).also { state.facts[it.id] = it }
    }
    private val revise = persistent.transition<MemoryFact>("revise", Effect.LocalArtifactWrite) { command, state ->
        val request = (command as MemoryCommand.Revise).request
        val fact = requireNotNull(state.facts[request.id])
        require(fact.revision == request.revision && fact.status == MemoryStatus.Proposed && request.draft.scope == scope)
        val draft = request.draft
        require(draft.provenance.sourceDate <= System.currentTimeMillis())
        fact.copy(kind = draft.kind, title = draft.title, text = draft.text, links = draft.links.distinct(),
            provenance = (fact.provenance + draft.provenance).distinct(), revision = fact.revision + 1,
            updatedAt = System.currentTimeMillis(), importance = draft.importance).also { state.facts[it.id] = it }
    }
    private val imported = persistent.transition<MemoryFact>("import", Effect.LocalArtifactWrite) { command, state ->
        val fact = (command as MemoryCommand.Import).fact
        require(fact.scope == scope)
        state.facts[fact.id] ?: fact.also { state.facts[it.id] = it }
    }

    override suspend fun receive(message: MemoryEnvelope) {
        message.reply.complete(when (message.command) {
            is MemoryCommand.Remember -> remember(message.command)
            is MemoryCommand.Review -> review(message.command)
            is MemoryCommand.Retire -> retire(message.command)
            is MemoryCommand.Revise -> revise(message.command)
            is MemoryCommand.Import -> imported(message.command)
        })
    }

    override suspend fun onFailure(message: MemoryEnvelope, error: Throwable) = ActorDirective.Restart
}
