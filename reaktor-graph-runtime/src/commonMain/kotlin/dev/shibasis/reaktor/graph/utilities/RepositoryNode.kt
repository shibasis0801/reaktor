package dev.shibasis.reaktor.graph.utilities

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode

abstract class RepositoryNode(
    graph: Graph,
    name: String
): BasicNode(graph) {
    protected val store = checkNotNull(Feature.Database) { "You need to initialize the database" }.store(name)

    protected suspend inline fun <reified T : Any> cached(key: String, crossinline fetch: suspend () -> Result<T>): Result<T> = runCatching {
        store.get<T>(key)?.value ?: fetch().getOrThrow().also { store.put(key, it) }
    }
}
