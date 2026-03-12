package dev.shibasis.reaktor.flexbuffer.core

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

/**
 * Public API for FlexBuffer binary serialization.
 *
 * Encoding pipeline:
 *   T → kotlinx.serialization → FlexEncoderV2 → FlexBuffersBuilder → ByteArray
 *   - FlexEncoderV2 is allocation-free after warmup (pooled builder + pooled structure stack)
 *   - FlexBuffersBuilder writes to a reusable internal byte buffer
 *   - Final ByteArray copy is the only allocation per encode()
 *
 * Decoding pipeline:
 *   ByteArray → ArrayReadBuffer → getRoot(Reference) → FlexDecoderV2 → T
 *   - ArrayReadBuffer wraps the byte[] without copying
 *   - getRoot() parses the root type tag from the last 2 bytes (O(1))
 *   - FlexDecoderV2 navigates the binary tree using pooled context stack
 *   - Primitive reads (toInt, toLong, etc.) are zero-copy from the buffer
 *
 * Thread safety: each encode/decode creates its own encoder/decoder instance.
 * The FlexBuffersBuilder is acquired from a thread-safe pool (FlexBufferPool).
 *
 * Ref: https://flatbuffers.dev/flexbuffers.html — format specification
 * Ref: https://google.github.io/flatbuffers/ — FlatBuffers documentation
 */
@OptIn(ExperimentalUnsignedTypes::class)
object FlexBuffers {

    // ==================== Encoding ====================

    inline fun <reified T> encode(value: T): ByteArray {
        return encode(serializer<T>(), value)
    }

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
        return FlexEncoderV2.encode(serializer, value)
    }

    /**
     * Encode into a caller-owned builder, returning a ReadBuffer that points to the
     * builder's internal storage. Avoids the final ByteArray copy — use when you need
     * to pass the result directly to another FlexBuffer consumer or network layer.
     * The returned ReadBuffer is invalidated when the builder is cleared or reused.
     */
    fun <T> encodeToBuffer(
        serializer: SerializationStrategy<T>,
        value: T,
        builder: FlexBuffersBuilder
    ): ReadBuffer {
        return FlexEncoderV2.encodeToBuffer(serializer, value, builder)
    }

    inline fun <T> withEncoder(block: (FlexBuffersBuilder) -> T): T {
        return FlexBufferPool.withEncoder(block)
    }

    inline fun build(block: FlexBuffersBuilder.() -> Unit): ByteArray {
        return FlexBufferPool.encode(block)
    }

    // ==================== Decoding ====================

    inline fun <reified T> decode(bytes: ByteArray): T {
        return decode(serializer<T>(), bytes)
    }

    fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return FlexDecoderV2.decode(deserializer, bytes)
    }

    /**
     * Decode from an existing ReadBuffer. Useful for zero-copy round-trips:
     *   val buf = FlexBuffers.encodeToBuffer(serializer, value, builder)
     *   val decoded = FlexBuffers.decode(deserializer, buf)
     * No intermediate ByteArray allocation.
     */
    fun <T> decode(deserializer: DeserializationStrategy<T>, buffer: ReadBuffer): T {
        return FlexDecoderV2.decode(deserializer, buffer)
    }

    fun getRoot(bytes: ByteArray): com.google.flatbuffers.kotlin.Reference {
        return getRoot(ArrayReadBuffer(bytes))
    }

    // ==================== Utilities ====================

    fun clearPool() {
        FlexBufferPool.clearPool()
    }

    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return try {
            val buffer = ArrayReadBuffer(bytes)
            val root = getRoot(buffer)
            !root.isNull || root.type.value >= 0
        } catch (e: Exception) {
            false
        }
    }

    fun toString(bytes: ByteArray): String {
        return try {
            val root = getRoot(bytes)
            root.toString()
        } catch (e: Exception) {
            "Invalid FlexBuffer: ${e.message}"
        }
    }
}

// ==================== Extension Functions ====================

inline fun <reified T> T.toFlexBuffer(): ByteArray {
    return FlexBuffers.encode(this)
}

inline fun <reified T> ByteArray.fromFlexBuffer(): T {
    return FlexBuffers.decode(this)
}
