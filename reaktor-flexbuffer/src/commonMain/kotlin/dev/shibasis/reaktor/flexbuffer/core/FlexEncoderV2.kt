package dev.shibasis.reaktor.flexbuffer.core

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * FlexBuffer encoder using pure Kotlin FlexBuffersBuilder (no JNI).
 *
 * Hot path: encodeElement → resolveKey → builder.set().
 * The dominant cost is FlexBuffersBuilder.set() which writes to its internal byte buffer.
 * Everything above it (key resolution, stack peek) is O(1) with zero allocation in steady state.
 *
 * Map<K,V> encoding protocol (from kotlinx.serialization):
 *   Even indices = keys, odd indices = values.
 *   Keys arrive via encodePrimitive() before their corresponding value.
 *   We capture the key as a string and pass it to builder.set() with the value.
 *   Non-string keys are converted via toString() — this is inherent to FlexBuffer's T_KEY type.
 *
 * Ref: https://flatbuffers.dev/flexbuffers.html — binary format spec
 * Ref: "Game Programming Patterns" (Nystrom, ch.19) — Object Pool pattern for StructureStack
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalUnsignedTypes::class)
class FlexEncoderV2 private constructor(
    private val builder: FlexBuffersBuilder,
    override val serializersModule: SerializersModule
) : AbstractEncoder() {

    private val structureStack = StructureStack()
    private var pendingKey: String? = null

    companion object {
        // Reusable module instance — EmptySerializersModule() returns a singleton internally,
        // but caching the reference avoids the function call overhead in tight loops.
        private val MODULE = EmptySerializersModule()

        inline fun <reified T> encode(value: T): ByteArray {
            return encode(serializer(), value)
        }

        /**
         * Encode using pooled FlexBuffersBuilder. The pool amortizes builder allocation.
         * After encoding, finish() returns a ReadBuffer pointing to the builder's internal
         * storage, which is then copied to a new ByteArray via toByteArray().
         *
         * If you want to avoid this final copy, use encodeToBuffer() with your own builder.
         */
        fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
            return FlexBufferPool.withEncoder { builder ->
                val encoder = FlexEncoderV2(builder, MODULE)
                encoder.encodeSerializableValue(serializer, value)
                builder.finish().toByteArray()
            }
        }

        /**
         * Encode into a caller-owned builder for zero-copy scenarios.
         * The returned ReadBuffer points directly into the builder's byte buffer.
         * Valid only until the builder is cleared or reused.
         */
        fun <T> encodeToBuffer(
            serializer: SerializationStrategy<T>,
            value: T,
            builder: FlexBuffersBuilder
        ): ReadBuffer {
            builder.clear()
            val encoder = FlexEncoderV2(builder, MODULE)
            encoder.encodeSerializableValue(serializer, value)
            return builder.finish()
        }
    }

    // ==================== Map Key Resolution ====================
    // FlexBuffer maps require keys as strings (T_KEY type).
    // When encoding Map<K,V>, kotlinx.serialization sends keys at even indices
    // and values at odd indices. We intercept keys in the encode methods
    // and hold them until the corresponding value is encoded.
    //
    // Each primitive encode method uses a single structureStack.peek() to both
    // check for map key capture AND resolve the encoding key. This avoids the
    // double-peek of separate handleMapKey()+getEncodingKey() calls.
    // It also avoids boxing primitives to Any in the non-map hot path.
    //
    // Ref: "Effective Java" (Bloch, Item 6) — avoid unnecessary object creation (boxing)

    /**
     * Resolves the key for the next builder.set() call from a pre-fetched stack entry.
     * Priority: capturedKey (from map key encoding) > pendingKey (from class field name).
     */
    private fun resolveKeyFrom(current: StructureEntry?): String? {
        if (current != null && current.kind == StructureType.MAP && current.capturedKey != null) {
            val key = current.capturedKey
            current.capturedKey = null
            return key
        }
        return consumeKey()
    }

    // ==================== Primitive Encoding ====================
    // Each override does a single peek(), checks map-key capture inline (no boxing),
    // then encodes with the resolved key.
    // builder.set(key, value) writes directly to the internal byte buffer.

    override fun encodeBoolean(value: Boolean) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeByte(value: Byte) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeShort(value: Short) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeInt(value: Int) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeLong(value: Long) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeFloat(value: Float) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeDouble(value: Double) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeChar(value: Char) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value.toString(); return
        }
        builder.set(resolveKeyFrom(current), value.code)
    }

    override fun encodeString(value: String) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = value; return
        }
        builder.set(resolveKeyFrom(current), value)
    }

    override fun encodeNull() {
        builder.putNull(resolveKeyFrom(structureStack.peek()))
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) {
            current.capturedKey = index.toString(); return
        }
        builder.set(resolveKeyFrom(current), index)
    }

    // ==================== Structure Handling ====================

    /**
     * Called before each element. Sets up pendingKey for class fields,
     * toggles key/value expectation for map entries.
     *
     * Cost: O(1). descriptor.getElementName() returns a cached string.
     */
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val current = structureStack.peek()
        if (current == null) {
            pendingKey = descriptor.getElementName(index)
            return true
        }

        when (current.kind) {
            StructureType.CLASS, StructureType.OBJECT -> {
                pendingKey = descriptor.getElementName(index)
            }
            StructureType.MAP -> {
                current.expectingKey = current.elementIndex % 2 == 0
                current.elementIndex++
            }
            StructureType.LIST -> {
                pendingKey = null
            }
        }
        return true
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        val key = consumeKey()

        when (descriptor.kind) {
            StructureKind.LIST -> {
                structureStack.push(StructureType.LIST, builder.startVector(), key)
            }
            StructureKind.MAP -> {
                structureStack.push(StructureType.MAP, builder.startMap(), key)
            }
            else -> {
                structureStack.push(StructureType.LIST, builder.startVector(), key)
            }
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val key = consumeKey()

        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                structureStack.push(StructureType.CLASS, builder.startMap(), key)
            }
            StructureKind.LIST -> {
                structureStack.push(StructureType.LIST, builder.startVector(), key)
            }
            StructureKind.MAP -> {
                structureStack.push(StructureType.MAP, builder.startMap(), key)
            }
            else -> {
                structureStack.push(StructureType.CLASS, builder.startMap(), key)
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

enum class StructureType {
    CLASS, OBJECT, LIST, MAP
}

/**
 * Mutable entry on the structure stack. Reused via reset() to avoid allocation.
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
 * Pre-allocated stack of StructureEntry objects.
 * Avoids per-structure allocation after the first encode pass warms the pool.
 * Grows by doubling if nesting exceeds initial capacity (amortized O(1) push).
 *
 * Ref: "Introduction to Algorithms" (CLRS, §17.4) — amortized analysis of doubling arrays
 * Ref: "Game Programming Patterns" (Nystrom, ch.19) — Object Pool pattern
 */
class StructureStack(initialCapacity: Int = 16) {
    private val entries = ArrayList<StructureEntry>(initialCapacity)
    private var size = 0

    init {
        repeat(initialCapacity) {
            entries.add(StructureEntry())
        }
    }

    fun push(kind: StructureType, position: Int, key: String? = null) {
        if (size >= entries.size) {
            val growBy = entries.size
            repeat(growBy) {
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
