@file:OptIn(ExperimentalAtomicApi::class)

package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ArrayReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ReadBuffer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Object pool for FlexBuffersBuilder instances.
 *
 * Amortizes builder allocation cost by reusing instances across encode calls.
 * In steady state (pool warm), acquire() returns a pre-allocated builder with zero
 * allocation — only the clear() call resets internal write position.
 *
 * Concurrency model: bounded CAS slots. A builder is claimed with compare-and-set
 * before reuse, so concurrent encoders cannot receive the same mutable builder.
 * Contention may still miss a slot and allocate a fresh builder, which is acceptable
 * because misses only cost one FlexBuffersBuilder allocation and the pool is bounded.
 *
 * Ref: "Java Concurrency in Practice" (Goetz, §11.4) — object pools and contention
 * Ref: https://shipilev.net/talks/jpoint-April2015-pools.pdf — pool sizing
 */
@OptIn(ExperimentalUnsignedTypes::class)
object FlexBufferPool {
    private const val DEFAULT_POOL_SIZE = 16
    // 16 KB initial buffer: pre-grown so ApiResponse-sized payloads (7.5 KB) and
    // smaller never trigger a buffer resize during encode. The iOS sample profile
    // showed ArrayReadWriteBuffer.requestCapacity at 12.1% of CPU — most of that
    // was bounds-check overhead, not real resizes. Pre-growing also means the
    // builder amortises one allocation across thousands of encodes (pool reuse).
    // Memory cost is negligible: 16 pool slots × 16 KB = 256 KB resident.
    private const val DEFAULT_BUFFER_SIZE = 16 * 1024
    private const val POOL_MASK = DEFAULT_POOL_SIZE - 1

    private val pool = Array(DEFAULT_POOL_SIZE) { AtomicReference<FlexBuffersBuilder?>(null) }
    private val poolHead = AtomicInt(0)

    fun acquire(): FlexBuffersBuilder {
        val start = poolHead.load()
        for (i in 0 until DEFAULT_POOL_SIZE) {
            val idx = (start + i) and POOL_MASK
            val builder = pool[idx].load()
            if (builder != null && pool[idx].compareAndSet(builder, null)) {
                poolHead.store((idx + 1) and POOL_MASK)
                builder.clear()
                return builder
            }
        }
        return FlexBuffersBuilder(DEFAULT_BUFFER_SIZE, FlexBuffersBuilder.SHARE_KEYS)
    }

    fun release(builder: FlexBuffersBuilder) {
        val start = poolHead.load()
        for (i in 0 until DEFAULT_POOL_SIZE) {
            val idx = (start + i) and POOL_MASK
            if (pool[idx].compareAndSet(null, builder)) {
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
            builder.finishToByteArray()
        }
    }

    fun clearPool() {
        for (i in 0 until DEFAULT_POOL_SIZE) {
            pool[i].store(null)
        }
        poolHead.store(0)
    }
}

/**
 * Converts ReadBuffer to ByteArray using bulk array copy.
 *
 * ReadBuffer.data() returns the backing ByteArray without copying (it's a direct reference).
 * copyInto() maps to System.arraycopy on JVM (intrinsified to memcpy by HotSpot)
 * and to optimized memcpy on Kotlin/Native.
 *
 * Array-backed slices copy [offset, offset + limit); other implementations use
 * their public bulk-read contract. Builder buffers normally have offset zero.
 *
 * Ref: "Computer Systems: A Programmer's Perspective" (Bryant & O'Hallaron, §6.2)
 *      — spatial locality: sequential bulk copy is cache-line friendly
 */
fun ReadBuffer.toByteArray(): ByteArray {
    val size = limit
    val result = ByteArray(size)
    if (this is ArrayReadBuffer) {
        data().copyInto(result, 0, offset, offset + size)
    } else {
        getBytes(result, 0, size)
    }
    return result
}
