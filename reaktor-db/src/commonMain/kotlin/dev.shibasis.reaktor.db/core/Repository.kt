package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.db.ObjectDatabase


/*
todo:
Enhancement:
    Add a high performance reliable SyncAdapter

Experiment:
    Instead of the usual (api, repository, interactions), does a DataHolder abstraction make sense ?

*/

abstract class Repository(
    storeName: String,
    database: ObjectDatabase = Feature.Database ?: throw IllegalStateException("You need to initialize the database"),
): Adapter<ObjectDatabase>(database) {
    protected val store = ObjectStore(database, storeName)
    protected suspend inline fun<reified T: Any> write(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = store.writeThrough(cacheKey, fetcher)

    protected suspend inline fun<reified T: Any> writeAndGet(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = write(cacheKey, fetcher).result
}
