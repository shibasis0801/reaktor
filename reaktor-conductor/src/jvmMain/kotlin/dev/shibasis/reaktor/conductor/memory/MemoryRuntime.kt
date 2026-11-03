package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.provides
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.graph.remote
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over

class MemoryRuntime(val graph: Graph, database: ObjectDatabase) {
    val store = MemoryStore(database)
    private val actors = mutableMapOf<MemoryScope, MemoryActor>()

    @Synchronized private fun actor(scope: MemoryScope) = actors.getOrPut(scope) {
        graph.provides(memoryKey(scope)) { MemoryActor(it, scope, store) }
    }

    private suspend fun change(scope: MemoryScope, command: MemoryCommand): MemoryFact = actor(scope).ask { MemoryEnvelope(command, it) }

    private suspend fun scope(id: String): MemoryScope = requireNotNull(store.get(id)) { "Unknown memory fact" }.scope

    private val server = object : MemoryApi() {
        override val remember = super.remember.handle { change(it.scope, MemoryCommand.Remember(it)) }
        override val get = super.get.handle(store::get)
        override val search = super.search.handle(store::search)
        override val recall = super.recall.handle { request ->
            store.search(MemoryQuery(request.scopes, limit = 100)).filter { fact ->
                request.links.isEmpty() || fact.links.isEmpty() || fact.links.any { it in request.links }
            }.take(request.limit.also { require(it in 1..100) })
        }
        override val review = super.review.handle { change(scope(it.id), MemoryCommand.Review(it)) }
        override val retire = super.retire.handle { change(scope(it.id), MemoryCommand.Retire(it)) }
        override val revise = super.revise.handle { change(scope(it.id), MemoryCommand.Revise(it)) }
    }

    val served = graph.serve(server)
    val api = (MemoryApi() over LocalLink(requireNotNull(served.served))).also { graph.remote(it) }

    internal suspend fun import(fact: MemoryFact): MemoryFact = change(fact.scope, MemoryCommand.Import(fact))
}
