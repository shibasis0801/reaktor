package dev.shibasis.reaktor.flexbuffer.core

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

/**
 * FlexBuffers - High-performance binary serialization for Kotlin.
 *
 * Features:
 * - Schema-less binary format (self-describing)
 * - Zero-copy decoding where possible
 * - Thread-safe with object pooling
 * - Full Kotlin Serialization integration
 * - Minimum allocations in steady state
 *
 * Usage:
 * ```kotlin
 * // Encoding
 * val bytes = FlexBuffers.encode(myObject)
 *
 * // Decoding
 * val decoded = FlexBuffers.decode<MyClass>(bytes)
 *
 * // High-performance batch encoding
 * FlexBuffers.withEncoder { builder ->
 *     builder["name"] = "value"
 *     builder.putMap {
 *         this["nested"] = 123
 *     }
 * }
 * ```
 *
 * Thread Safety:
 * - All public methods are thread-safe
 * - Uses object pooling for FlexBuffersBuilder instances
 * - Each encode/decode operation is independent
 *
 * Performance Tips:
 * - For batch operations, use withEncoder to reuse builder
 * - For large objects, consider streaming if available
 * - Decoder reads directly from buffer (zero-copy for primitives)
 */
@OptIn(ExperimentalUnsignedTypes::class)
object FlexBuffers {

    // ==================== Encoding ====================

    /**
     * Encode a value to FlexBuffer bytes.
     *
     * @param value The value to encode
     * @return ByteArray containing the FlexBuffer data
     */
    inline fun <reified T> encode(value: T): ByteArray {
        return encode(serializer<T>(), value)
    }

    /**
     * Encode a value using an explicit serializer.
     *
     * @param serializer The serialization strategy to use
     * @param value The value to encode
     * @return ByteArray containing the FlexBuffer data
     */
    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
        return FlexEncoderV2.encode(serializer, value)
    }

    /**
     * Encode to a pre-allocated buffer for zero-copy scenarios.
     *
     * @param serializer The serialization strategy to use
     * @param value The value to encode
     * @param builder Pre-allocated FlexBuffersBuilder (will be cleared)
     * @return ReadBuffer containing the encoded data (points to builder's internal buffer)
     */
    fun <T> encodeToBuffer(
        serializer: SerializationStrategy<T>,
        value: T,
        builder: FlexBuffersBuilder
    ): ReadBuffer {
        return FlexEncoderV2.encodeToBuffer(serializer, value, builder)
    }

    /**
     * Execute a block with a pooled encoder for manual building.
     * Useful for dynamic or non-serializable data.
     *
     * @param block Lambda receiving FlexBuffersBuilder
     * @return Result of the block
     */
    inline fun <T> withEncoder(block: (FlexBuffersBuilder) -> T): T {
        return FlexBufferPool.withEncoder(block)
    }

    /**
     * Build FlexBuffer data manually with a DSL.
     *
     * ```kotlin
     * val bytes = FlexBuffers.build {
     *     this["name"] = "John"
     *     this["age"] = 30
     *     putVector("items") {
     *         put("item1")
     *         put("item2")
     *     }
     * }
     * ```
     */
    inline fun build(block: FlexBuffersBuilder.() -> Unit): ByteArray {
        return FlexBufferPool.encode(block)
    }

    // ==================== Decoding ====================

    /**
     * Decode FlexBuffer bytes to a value.
     *
     * @param bytes The FlexBuffer data to decode
     * @return Decoded value
     */
    inline fun <reified T> decode(bytes: ByteArray): T {
        return decode(serializer<T>(), bytes)
    }

    /**
     * Decode using an explicit deserializer.
     *
     * @param deserializer The deserialization strategy to use
     * @param bytes The FlexBuffer data to decode
     * @return Decoded value
     */
    fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return FlexDecoderV2.decode(deserializer, bytes)
    }

    /**
     * Decode from an existing ReadBuffer (zero-copy from encoder).
     *
     * @param deserializer The deserialization strategy to use
     * @param buffer ReadBuffer containing FlexBuffer data
     * @return Decoded value
     */
    fun <T> decode(deserializer: DeserializationStrategy<T>, buffer: ReadBuffer): T {
        return FlexDecoderV2.decode(deserializer, buffer)
    }

    /**
     * Get the root Reference for manual traversal.
     * Useful for dynamic or schema-less access.
     *
     * @param bytes The FlexBuffer data
     * @return Root Reference for manual traversal
     */
    fun getRoot(bytes: ByteArray): com.google.flatbuffers.kotlin.Reference {
        return getRoot(ArrayReadBuffer(bytes))
    }

    // ==================== Utilities ====================

    /**
     * Clear the encoder pool.
     * Useful for memory pressure situations or testing.
     */
    fun clearPool() {
        FlexBufferPool.clearPool()
    }

    /**
     * Check if bytes are valid FlexBuffer data.
     * Performs basic structural validation.
     */
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

    /**
     * Get human-readable string representation of FlexBuffer data.
     * Useful for debugging.
     */
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

/**
 * Encode this object to FlexBuffer bytes.
 */
inline fun <reified T> T.toFlexBuffer(): ByteArray {
    return FlexBuffers.encode(this)
}

/**
 * Decode FlexBuffer bytes to an object.
 */
inline fun <reified T> ByteArray.fromFlexBuffer(): T {
    return FlexBuffers.decode(this)
}
