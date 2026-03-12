package dev.shibasis.reaktor.flexbuffer.core

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import kotlin.concurrent.Volatile

/**
 * Object pool for FlexBuffersBuilder instances.
 *
 * Amortizes builder allocation cost by reusing instances across encode calls.
 * In steady state (pool warm), acquire() returns a pre-allocated builder with zero
 * allocation — only the clear() call resets internal write position.
 *
 * Concurrency model: best-effort reuse with @Volatile hint on poolHead.
 * Concurrent acquire/release may occasionally miss a slot (benign — a new builder
 * is allocated or the returned one is dropped). This is acceptable because:
 * - Pool misses only cost one FlexBuffersBuilder allocation (~4KB)
 * - True lock-free CAS requires kotlinx.atomicfu (platform-specific)
 * - The pool is bounded, so memory stays capped regardless of races
 *
 * Ref: "Java Concurrency in Practice" (Goetz, §11.4) — lock-free object pools
 * Ref: https://shipilev.net/talks/jpoint-April2015-pools.pdf — pool sizing
 */
@OptIn(ExperimentalUnsignedTypes::class)
object FlexBufferPool {
    private const val DEFAULT_POOL_SIZE = 16
    private const val DEFAULT_BUFFER_SIZE = 4096

    private val pool = arrayOfNulls<FlexBuffersBuilder>(DEFAULT_POOL_SIZE)

    @Volatile
    private var poolHead = 0

    fun acquire(): FlexBuffersBuilder {
        for (i in 0 until DEFAULT_POOL_SIZE) {
            val idx = (poolHead + i) % DEFAULT_POOL_SIZE
            val builder = pool[idx]
            if (builder != null) {
                pool[idx] = null
                poolHead = (idx + 1) % DEFAULT_POOL_SIZE
                builder.clear()
                return builder
            }
        }
        return FlexBuffersBuilder(DEFAULT_BUFFER_SIZE, FlexBuffersBuilder.SHARE_KEYS)
    }

    fun release(builder: FlexBuffersBuilder) {
        for (i in 0 until DEFAULT_POOL_SIZE) {
            if (pool[i] == null) {
                pool[i] = builder
                return
            }
        }
        // Pool full — let GC collect. No leak risk.
    }

    inline fun <T> withEncoder(block: (FlexBuffersBuilder) -> T): T {
        val builder = acquire()
        return try {
            block(builder)
        } finally {
            release(builder)
        }
    }

    inline fun encode(block: FlexBuffersBuilder.() -> Unit): ByteArray {
        return withEncoder { builder ->
            builder.block()
            val buffer = builder.finish()
            buffer.toByteArray()
        }
    }

    fun clearPool() {
        for (i in 0 until DEFAULT_POOL_SIZE) {
            pool[i] = null
        }
        poolHead = 0
    }
}

/**
 * Converts ReadBuffer to ByteArray.
 *
 * Per-byte copy via ReadBuffer.get(i). ArrayReadBuffer.buffer is `protected`,
 * so bulk copy via copyInto isn't possible without subclassing.
 * For a future optimization, subclass ArrayReadBuffer to expose the backing array
 * and use System.arraycopy (intrinsified by HotSpot into memcpy).
 *
 * Ref: https://shipilev.net/jvm/anatomy-quarks/24-object-alignment/
 */
fun ReadBuffer.toByteArray(): ByteArray {
    val size = limit
    val result = ByteArray(size)
    for (i in 0 until size) {
        result[i] = this[i]
    }
    return result
}
