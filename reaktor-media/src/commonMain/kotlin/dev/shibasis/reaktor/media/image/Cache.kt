package dev.shibasis.reaktor.media.image


interface Cache<T> {
    suspend fun store(key: String, contents: T)
    suspend fun retrieve(key: String): T?
    suspend fun retrieveWithFetch(key: String, fetch: suspend () -> T?): T? { return null }
}
