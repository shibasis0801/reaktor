package dev.shibasis.reaktor.graph.utilities

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
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

    data class CacheResult<T>(
        val result: Result<T>,
        val isCached: Boolean
    )

    // todo Should use Cache Strategy. not this.
    protected suspend inline fun<reified T: Any> writeThrough(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>,
    ): CacheResult<T> {
        try {
            val cachedData = store.read<T>(cacheKey)
            if (cachedData != null) return CacheResult(succeed(cachedData.value), true)

            val result = fetcher()
            val data =
                result.getOrNull() ?: return CacheResult(fail(result.exceptionOrNull()!!), false)
            store.write(cacheKey, data)
            return CacheResult(succeed(data), false)
        } catch (e: Throwable) {
            return CacheResult(fail(e), false)
        }
    }

    protected suspend inline fun<reified T: Any> write(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = writeThrough(cacheKey, fetcher)

    protected suspend inline fun<reified T: Any> writeAndGet(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = write(cacheKey, fetcher).result
}