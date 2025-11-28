package dev.shibasis.reaktor.graph.utilities

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode

abstract class RepositoryNode(
    graph: Graph,
    name: String
): BasicNode(graph) {
    protected val store = ObjectStore(
        Feature.Database ?: throw IllegalStateException("You need to initialize the database"),
        name
    )

    protected suspend inline fun<reified T: Any> write(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = store.writeThrough(cacheKey, fetcher)

    protected suspend inline fun<reified T: Any> writeAndGet(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = write(cacheKey, fetcher).result
}