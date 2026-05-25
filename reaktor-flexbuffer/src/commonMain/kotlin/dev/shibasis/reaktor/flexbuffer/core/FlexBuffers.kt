package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.ArrayReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot
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
 * The FlexBuffersBuilder is acquired from a bounded CAS-backed pool.
 *
 * Ref: https://flatbuffers.dev/flexbuffers.html — format specification
 * Ref: https://google.github.io/flatbuffers/ — FlatBuffers documentation
 */
@OptIn(ExperimentalUnsignedTypes::class)
object FlexBuffers {

    // ==================== Encoding ====================

    inline fun <reified T : Any> encode(value: T): ByteArray {
        FlexCoderRegistry.get<T>()?.let { coder ->
            return encodeDirect(coder, value)
        }
        return encode(serializer<T>(), value)
    }

    @PublishedApi
    internal fun <T : Any> encodeDirect(coder: FlexCoder<T>, value: T): ByteArray {
        return FlexBufferPool.encode {
            coder.encode(this, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
        // Accelerate: if a FlexCoder is registered for this type, bypass serialization entirely
        val coder = FlexCoderRegistry.getBySerialName<Any>(serializer.descriptor.serialName)
        if (coder != null && value != null) {
            return encodeDirect(coder, value as Any)
        }
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
        val coder = FlexCoderRegistry.getBySerialName<Any>(serializer.descriptor.serialName)
        if (coder != null && value != null) {
            builder.clear()
            coder.encode(builder, value as Any)
            return builder.finish()
        }
        return FlexEncoderV2.encodeToBuffer(serializer, value, builder)
    }

    /**
     * Reified copy-free encode path. Uses a generated FlexCoder when registered,
     * otherwise falls back to the serializer path, and returns the caller-owned
     * builder's internal [ReadBuffer] without copying to a new ByteArray.
     */
    inline fun <reified T : Any> encodeToBuffer(
        value: T,
        builder: FlexBuffersBuilder
    ): ReadBuffer {
        FlexCoderRegistry.get<T>()?.let { coder ->
            builder.clear()
            coder.encode(builder, value)
            return builder.finish()
        }
        return encodeToBuffer(serializer<T>(), value, builder)
    }

    /**
     * Build a FlexBuffer into a caller-owned builder without the final ByteArray copy.
     * The returned buffer is valid until [builder] is cleared or reused.
     */
    inline fun buildToBuffer(
        builder: FlexBuffersBuilder,
        block: FlexBuffersBuilder.() -> Unit
    ): ReadBuffer {
        builder.clear()
        builder.block()
        return builder.finish()
    }

    inline fun <T> withEncoder(block: (FlexBuffersBuilder) -> T): T {
        return FlexBufferPool.withEncoder(block)
    }

    inline fun build(block: FlexBuffersBuilder.() -> Unit): ByteArray {
        return FlexBufferPool.encode(block)
    }

    // ==================== Decoding ====================

    inline fun <reified T : Any> decode(bytes: ByteArray): T {
        FlexCoderRegistry.get<T>()?.let { coder ->
            return decodeDirect(coder, bytes)
        }
        return decode(serializer<T>(), bytes)
    }

    @PublishedApi
    internal fun <T : Any> decodeDirect(coder: FlexCoder<T>, bytes: ByteArray): T {
        val buffer = ArrayReadBuffer(bytes)
        val root = getRoot(buffer)
        return coder.decode(root)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        // Accelerate: if a FlexCoder is registered for this type, bypass deserialization entirely
        val coder = FlexCoderRegistry.getBySerialName<Any>(deserializer.descriptor.serialName)
        if (coder != null) {
            return decodeDirect(coder, bytes) as T
        }
        return FlexDecoderV2.decode(deserializer, bytes)
    }

    /**
     * Decode from an existing ReadBuffer. Useful for zero-copy round-trips:
     *   val buf = FlexBuffers.encodeToBuffer(serializer, value, builder)
     *   val decoded = FlexBuffers.decode(deserializer, buf)
     * No intermediate ByteArray allocation.
     */
    fun <T> decode(deserializer: DeserializationStrategy<T>, buffer: ReadBuffer): T {
        val coder = FlexCoderRegistry.getBySerialName<Any>(deserializer.descriptor.serialName)
        if (coder != null) {
            @Suppress("UNCHECKED_CAST")
            return coder.decode(getRoot(buffer)) as T
        }
        return FlexDecoderV2.decode(deserializer, buffer)
    }

    fun getRoot(bytes: ByteArray): dev.shibasis.reaktor.flexbuffer.flatbuffers.Reference {
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

inline fun <reified T : Any> T.toFlexBuffer(): ByteArray {
    return FlexBuffers.encode(this)
}

inline fun <reified T : Any> ByteArray.fromFlexBuffer(): T {
    return FlexBuffers.decode(this)
}
