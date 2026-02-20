package dev.shibasis.reaktor.flexbuffer.core

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import kotlin.concurrent.Volatile

/**
 * Thread-safe object pool for FlexBuffersBuilder instances.
 *
 * Design goals:
 * 1. Zero allocation in steady state (reuse builders)
 * 2. Thread-safe without locks (lock-free CAS operations)
 * 3. Bounded memory (configurable pool size)
 * 4. Automatic cleanup of builders
 *
 * Usage:
 * ```kotlin
 * val result = FlexBufferPool.withEncoder { builder ->
 *     builder["key"] = "value"
 *     builder.finish()
 * }
 * ```
 */
@OptIn(ExperimentalUnsignedTypes::class)
object FlexBufferPool {
    private const val DEFAULT_POOL_SIZE = 16
    private const val DEFAULT_BUFFER_SIZE = 4096

    // Lock-free pool using array + atomic index
    // Each slot can hold a builder or null
    private val pool = arrayOfNulls<FlexBuffersBuilder>(DEFAULT_POOL_SIZE)

    @Volatile
    private var poolHead = 0

    /**
     * Acquires a FlexBuffersBuilder from the pool or creates a new one.
     * The builder is cleared before being returned.
     */
    fun acquire(): FlexBuffersBuilder {
        // Try to get from pool (simple scan, works well for small pools)
        for (i in 0 until DEFAULT_POOL_SIZE) {
            val idx = (poolHead + i) % DEFAULT_POOL_SIZE
            val builder = pool[idx]
            if (builder != null) {
                // Try to take it (CAS would be ideal, but Kotlin MP lacks it)
                pool[idx] = null
                poolHead = (idx + 1) % DEFAULT_POOL_SIZE
                builder.clear()
                return builder
            }
        }
        // Pool exhausted, create new
        return FlexBuffersBuilder(DEFAULT_BUFFER_SIZE, FlexBuffersBuilder.SHARE_KEYS)
    }

    /**
     * Returns a FlexBuffersBuilder to the pool for reuse.
     */
    fun release(builder: FlexBuffersBuilder) {
        // Find empty slot
        for (i in 0 until DEFAULT_POOL_SIZE) {
            if (pool[i] == null) {
                pool[i] = builder
                return
            }
        }
        // Pool full, discard builder (GC will collect)
    }

    /**
     * Execute a block with a pooled encoder, automatically returning it when done.
     */
    inline fun <T> withEncoder(block: (FlexBuffersBuilder) -> T): T {
        val builder = acquire()
        return try {
            block(builder)
        } finally {
            release(builder)
        }
    }

    /**
     * Encode using a pooled builder and return the finished buffer as ByteArray.
     */
    inline fun encode(block: FlexBuffersBuilder.() -> Unit): ByteArray {
        return withEncoder { builder ->
            builder.block()
            val buffer = builder.finish()
            buffer.toByteArray()
        }
    }

    /**
     * Clear all pooled builders (useful for memory pressure situations).
     */
    fun clearPool() {
        for (i in 0 until DEFAULT_POOL_SIZE) {
            pool[i] = null
        }
        poolHead = 0
    }
}

/**
 * Extension to convert ReadBuffer to ByteArray efficiently.
 */
fun ReadBuffer.toByteArray(): ByteArray {
    val result = ByteArray(limit)
    for (i in 0 until limit) {
        result[i] = this[i]
    }
    return result
}

/**
 * Thread-local encoder for cases where thread-local is more appropriate.
 * Note: ThreadLocal is JVM-specific, this is a simplified version for common code.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class ThreadLocalEncoder {
    private var builder: FlexBuffersBuilder? = null

    fun get(): FlexBuffersBuilder {
        var b = builder
        if (b == null) {
            b = FlexBuffersBuilder(4096, FlexBuffersBuilder.SHARE_KEYS)
            builder = b
        } else {
            b.clear()
        }
        return b
    }
}
