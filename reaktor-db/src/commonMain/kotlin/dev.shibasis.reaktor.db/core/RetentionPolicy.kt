package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.StoredObject
import kotlin.time.Clock
import kotlin.time.Duration

interface RetentionPolicy {
    fun <T : Any> isValid(stored: StoredObject<T>): Boolean
}

object KeepForever : RetentionPolicy {
    override fun <T : Any> isValid(stored: StoredObject<T>): Boolean = true
}

class ExpireAfter(
    private val ttl: Duration,
    private val now: () -> Long = {
        Clock.System.now().toEpochMilliseconds()
    },
) : RetentionPolicy {
    override fun <T : Any> isValid(stored: StoredObject<T>): Boolean {
        return now() - stored.updatedAt <= ttl.inWholeMilliseconds
    }
}
