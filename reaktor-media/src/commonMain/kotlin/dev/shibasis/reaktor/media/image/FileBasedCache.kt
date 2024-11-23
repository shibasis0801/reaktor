package dev.shibasis.reaktor.media.image

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.io.adapters.File


private val inMemoryCache = hashMapOf<String, ByteArray>()

object FileBasedCache: Cache<ByteArray> {
    override fun store(key: String, contents: ByteArray) {
        val file = Feature.File
        if (file == null) {
            Logger.e { "Please initialize Feature.File, without which this is no-op" }
            return
        }
        val cacheFile = "${file.cacheDirectory}/$key"
        file.writeBinaryFile(cacheFile, contents)
    }

    override fun retrieve(key: String): ByteArray? {
        val file = Feature.File
        if (file == null) {
            Logger.e { "Please initialize Feature.File, without which this is no-op" }
            return null
        }
        val cacheFile = "${file.cacheDirectory}/$key"
        return Feature.File?.readBinaryFile(cacheFile)
    }
}

object MultiLevelCache: Cache<ByteArray> {
    override fun store(key: String, contents: ByteArray) {
        inMemoryCache[key] = contents
        FileBasedCache.store(key, contents)
    }

    override fun retrieve(key: String): ByteArray? {
        if (!inMemoryCache.containsKey(key)) {
            val contents = FileBasedCache.retrieve(key)
            if (contents != null) {
                inMemoryCache[key] = contents
            }
        }
        return inMemoryCache[key]
    }

    override suspend fun retrieveWithFetch(key: String, fetch: suspend () -> ByteArray?): ByteArray? {
        return retrieve(key) ?: fetch()?.also { store(key, it) }
    }
}