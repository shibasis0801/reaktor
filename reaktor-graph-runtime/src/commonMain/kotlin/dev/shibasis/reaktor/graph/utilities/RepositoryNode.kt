package dev.shibasis.reaktor.graph.utilities

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode

/*
must have opt-in into stale-while-revalidate/etc

*/
abstract class RepositoryNode(
    graph: Graph,
    name: String
): BasicNode(graph) {
    protected val store = (Feature.Database
        ?: throw IllegalStateException("You need to initialize the database"))
        .store(name)

    data class CacheResult<T>(
        val result: Result<T>,
        val isCached: Boolean
    )

    protected suspend inline fun<reified T: Any> writeThrough(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>,
    ): CacheResult<T> {
        try {
            val cachedData = store.get<T>(cacheKey)
            if (cachedData != null) return CacheResult(succeed(cachedData.value), true)

            val result = fetcher()
            val data =
                result.getOrNull() ?: return CacheResult(fail(result.exceptionOrNull()!!), false)
            store.put(cacheKey, data)
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
