package dev.shibasis.reaktor.media.image


interface Cache<T> {
    fun store(key: String, contents: T)
    fun retrieve(key: String): T?
    suspend fun retrieveWithFetch(key: String, fetch: suspend () -> T?): T? { return null }
}
