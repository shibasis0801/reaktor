package dev.shibasis.reaktor.flexbuffer.core

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * High-performance FlexBuffer encoder using pure Kotlin FlexBuffersBuilder.
 *
 * Design goals:
 * 1. Minimum allocations - reuse builders via pooling
 * 2. No JNI overhead - pure Kotlin implementation
 * 3. Thread-safe - can be used from multiple threads with pooling
 * 4. Full type support - all Kotlin types including nested structures
 *
 * Performance optimizations:
 * - Uses object pooling for builders
 * - Inline functions where beneficial
 * - Avoids string allocations for field names by caching
 * - Direct primitive encoding without boxing
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalUnsignedTypes::class)
class FlexEncoderV2 private constructor(
    private val builder: FlexBuffersBuilder,
    override val serializersModule: SerializersModule
) : AbstractEncoder() {

    // Stack for tracking nested structures
    private val structureStack = StructureStack()

    // Current field name for map keys
    private var pendingKey: String? = null

    companion object {
        /**
         * Encode a value to FlexBuffer bytes using object pooling.
         * This is the recommended way to encode.
         */
        inline fun <reified T> encode(value: T): ByteArray {
            return encode(serializer(), value)
        }

        /**
         * Encode a value using explicit serializer.
         */
        fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
            return FlexBufferPool.withEncoder { builder ->
                val encoder = FlexEncoderV2(builder, EmptySerializersModule())
                encoder.encodeSerializableValue(serializer, value)
                builder.finish().toByteArray()
            }
        }

        /**
         * Encode to a pre-allocated buffer (for zero-copy scenarios).
         */
        fun <T> encodeToBuffer(
            serializer: SerializationStrategy<T>,
            value: T,
            builder: FlexBuffersBuilder
        ): ReadBuffer {
            builder.clear()
            val encoder = FlexEncoderV2(builder, EmptySerializersModule())
            encoder.encodeSerializableValue(serializer, value)
            return builder.finish()
        }
    }

    // ==================== Primitive Encoding ====================

    override fun encodeBoolean(value: Boolean) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeByte(value: Byte) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeShort(value: Short) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeInt(value: Int) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeLong(value: Long) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeFloat(value: Float) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeDouble(value: Double) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeChar(value: Char) {
        // Encode char as int (code point)
        val key = consumeKey()
        if (key != null) {
            builder[key] = value.code
        } else {
            builder.put(value.code)
        }
    }

    override fun encodeString(value: String) {
        val key = consumeKey()
        if (key != null) {
            builder[key] = value
        } else {
            builder.put(value)
        }
    }

    override fun encodeNull() {
        val key = consumeKey()
        builder.putNull(key)
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        // Encode enum as its ordinal value
        val key = consumeKey()
        if (key != null) {
            builder[key] = index
        } else {
            builder.put(index)
        }
    }

    // ==================== Structure Handling ====================

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val current = structureStack.peek()
        if (current == null) {
            // Root level - just set field name
            pendingKey = descriptor.getElementName(index)
            return true
        }

        when (current.kind) {
            StructureType.CLASS, StructureType.OBJECT -> {
                // For classes, field names come from descriptor
                pendingKey = descriptor.getElementName(index)
            }
            StructureType.MAP -> {
                // For maps, even indices are keys, odd indices are values
                // Keys are encoded as values first, then become the key for the next value
                if (current.elementIndex % 2 == 0) {
                    // This is a key position - the actual key will be set in encodeValue
                    current.expectingKey = true
                } else {
                    // This is a value position - key was already captured
                    current.expectingKey = false
                }
                current.elementIndex++
            }
            StructureType.LIST -> {
                // For lists, no keys needed
                pendingKey = null
            }
        }
        return true
    }

    override fun encodeValue(value: Any) {
        val current = structureStack.peek()

        // Handle map key capture
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            // This value is a map key - store it for the next value
            current.capturedKey = value.toString()
            return // Don't encode the key as a value
        }

        // Use captured key for map values
        if (current != null && current.kind == StructureType.MAP && current.capturedKey != null) {
            pendingKey = current.capturedKey
            current.capturedKey = null
        }

        // Dispatch to appropriate encode method
        when (value) {
            is Boolean -> encodeBoolean(value)
            is Byte -> encodeByte(value)
            is Short -> encodeShort(value)
            is Int -> encodeInt(value)
            is Long -> encodeLong(value)
            is Float -> encodeFloat(value)
            is Double -> encodeDouble(value)
            is Char -> encodeChar(value)
            is String -> encodeString(value)
            else -> throw IllegalArgumentException("Unsupported primitive type: ${value::class}")
        }
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        val key = consumeKey()

        when (descriptor.kind) {
            StructureKind.LIST -> {
                val start = builder.startVector()
                structureStack.push(StructureType.LIST, start, key)
            }
            StructureKind.MAP -> {
                val start = builder.startMap()
                structureStack.push(StructureType.MAP, start, key)
            }
            else -> {
                // Treat as list
                val start = builder.startVector()
                structureStack.push(StructureType.LIST, start, key)
            }
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val key = consumeKey()

        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val start = builder.startMap()
                structureStack.push(StructureType.CLASS, start, key)
            }
            StructureKind.LIST -> {
                val start = builder.startVector()
                structureStack.push(StructureType.LIST, start, key)
            }
            StructureKind.MAP -> {
                val start = builder.startMap()
                structureStack.push(StructureType.MAP, start, key)
            }
            else -> {
                // Default to map for unknown structures
                val start = builder.startMap()
                structureStack.push(StructureType.CLASS, start, key)
            }
        }
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val entry = structureStack.pop() ?: return

        when (entry.kind) {
            StructureType.CLASS, StructureType.OBJECT, StructureType.MAP -> {
                builder.endMap(entry.position, entry.key)
            }
            StructureType.LIST -> {
                builder.endVector(entry.key, entry.position)
            }
        }
    }

    // ==================== Helpers ====================

    private fun consumeKey(): String? {
        val key = pendingKey
        pendingKey = null
        return key
    }
}

/**
 * Type of structure being encoded.
 */
enum class StructureType {
    CLASS,
    OBJECT,
    LIST,
    MAP
}

/**
 * Entry on the structure stack.
 */
class StructureEntry(
    var kind: StructureType = StructureType.CLASS,
    var position: Int = 0,
    var key: String? = null,
    var elementIndex: Int = 0,
    var expectingKey: Boolean = false,
    var capturedKey: String? = null
) {
    fun reset(kind: StructureType, position: Int, key: String?) {
        this.kind = kind
        this.position = position
        this.key = key
        this.elementIndex = 0
        this.expectingKey = false
        this.capturedKey = null
    }
}

/**
 * Pooled stack for tracking nested structures.
 * Pre-allocates entries to avoid allocations during encoding.
 */
class StructureStack(initialCapacity: Int = 32) {
    private val entries = ArrayList<StructureEntry>(initialCapacity)
    private var size = 0

    init {
        repeat(initialCapacity) {
            entries.add(StructureEntry())
        }
    }

    fun push(kind: StructureType, position: Int, key: String? = null) {
        if (size >= entries.size) {
            // Expand capacity
            repeat(entries.size) {
                entries.add(StructureEntry())
            }
        }
        entries[size].reset(kind, position, key)
        size++
    }

    fun pop(): StructureEntry? {
        if (size == 0) return null
        size--
        return entries[size]
    }

    fun peek(): StructureEntry? {
        if (size == 0) return null
        return entries[size - 1]
    }

    fun clear() {
        size = 0
    }

    fun isEmpty(): Boolean = size == 0
}
