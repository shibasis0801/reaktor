package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.Promise

internal external interface RawRateLimiter {
    fun limit(options: dynamic): Promise<dynamic>
}

class RateLimiter internal constructor(private val raw: RawRateLimiter) {
    suspend fun allows(key: String): Boolean {
        val options = js("({})")
        options.key = key
        return raw.limit(options).await().success == true
    }
}
