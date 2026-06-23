@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ReadBuffer
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
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
@OptIn(ExperimentalSerializationApi::class)
class FlexEncoderV2 private constructor() : AbstractEncoder() {

    // Mutable state — reset() before each top-level encode.
    private var builder: FlexBuffersBuilder = SENTINEL_BUILDER
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private val structureStack = StructureStack()
    private var pendingKey: String? = null

    private fun reset(builder: FlexBuffersBuilder) {
        this.builder = builder
        this.structureStack.clear()
        this.pendingKey = null
    }

    companion object {
        // Singleton sentinel used as the initial value of `builder` before reset(). Never
        // actually written to — every encode resets the builder before use.
        private val SENTINEL_BUILDER = FlexBuffersBuilder(8)

        // Per-platform pool: JVM/Android = ThreadLocal, Native = volatile, JS = plain.
        @JvmStatic
        private val pool = PerPlatformPool { FlexEncoderV2() }

        @JvmStatic
        private fun acquire(): FlexEncoderV2 = pool.acquire()

        @JvmStatic
        private fun release(e: FlexEncoderV2) = pool.release(e)

        inline fun <reified T> encode(value: T): ByteArray {
            return encode(serializer(), value)
        }

        /**
         * Encode using pooled FlexBuffersBuilder. The pool amortizes builder allocation.
         */
        fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
            return FlexBufferPool.withEncoder { builder ->
                val encoder = acquire()
                encoder.reset(builder)
                try {
                    encoder.encodeSerializableValue(serializer, value)
                    builder.finishToByteArray()
                } finally {
                    release(encoder)
                }
            }
        }

        /**
         * Encode into a caller-owned builder for zero-copy scenarios.
         */
        fun <T> encodeToBuffer(
            serializer: SerializationStrategy<T>,
            value: T,
            builder: FlexBuffersBuilder
        ): ReadBuffer {
            builder.clear()
            val encoder = acquire()
            encoder.reset(builder)
            try {
                encoder.encodeSerializableValue(serializer, value)
                return builder.finish()
            } finally {
                release(encoder)
            }
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

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        val descriptor = serializer.descriptor
        // Registry acceleration: skip the String hash entirely if no coders are registered.
        if (FlexCoderRegistry.codersBySerialName.isNotEmpty() &&
            tryEncodeRegisteredCoder(descriptor.serialName, value)
        ) return
        // Bulk-array fast paths only apply to LIST-kind descriptors.
        if (descriptor.kind == StructureKind.LIST) {
            if (tryEncodeBulkValue(descriptor, value)) return
        }
        serializer.serialize(this, value)
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
        val key = resolveKeyFrom(structureStack.peek())

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
        val key = resolveKeyFrom(structureStack.peek())

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

    @Suppress("UNCHECKED_CAST")
    private fun <T> tryEncodeRegisteredCoder(serialName: String, value: T): Boolean {
        if (value == null) return false
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) return false

        val coder = FlexCoderRegistry.getBySerialName<Any>(serialName) ?: return false
        coder.encode(builder, value as Any, resolveKeyFrom(current))
        return true
    }

    private fun tryEncodeBulkValue(descriptor: SerialDescriptor, value: Any?): Boolean {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) return false

        return when (descriptor.serialName) {
            "kotlin.ByteArray" -> {
                builder.set(resolveKeyFrom(current), value as? ByteArray ?: return false)
                true
            }
            "kotlin.ShortArray" -> {
                builder.set(resolveKeyFrom(current), value as? ShortArray ?: return false)
                true
            }
            "kotlin.IntArray" -> {
                builder.set(resolveKeyFrom(current), value as? IntArray ?: return false)
                true
            }
            "kotlin.LongArray" -> {
                builder.set(resolveKeyFrom(current), value as? LongArray ?: return false)
                true
            }
            "kotlin.FloatArray" -> {
                builder.set(resolveKeyFrom(current), value as? FloatArray ?: return false)
                true
            }
            "kotlin.DoubleArray" -> {
                builder.set(resolveKeyFrom(current), value as? DoubleArray ?: return false)
                true
            }
            "kotlin.UByteArray" -> {
                builder.set(resolveKeyFrom(current), value as? UByteArray ?: return false)
                true
            }
            "kotlin.UShortArray" -> {
                builder.set(resolveKeyFrom(current), value as? UShortArray ?: return false)
                true
            }
            "kotlin.UIntArray" -> {
                builder.set(resolveKeyFrom(current), value as? UIntArray ?: return false)
                true
            }
            "kotlin.ULongArray" -> {
                builder.set(resolveKeyFrom(current), value as? ULongArray ?: return false)
                true
            }
            else -> {
                val collection = value as? Collection<*> ?: return false
                tryEncodeBulkCollection(descriptor, current, collection)
            }
        }
    }

    private fun tryEncodeBulkCollection(
        descriptor: SerialDescriptor,
        current: StructureEntry?,
        values: Collection<*>
    ): Boolean {
        if (descriptor.kind != StructureKind.LIST || descriptor.elementsCount == 0) return false

        val elementDescriptor = descriptor.getElementDescriptor(0)
        return when (elementDescriptor.serialName) {
            "kotlin.Boolean" -> {
                builder.set(resolveKeyFrom(current), values.toBooleanIntArray() ?: return false)
                true
            }
            "kotlin.Byte" -> {
                val array = values.toShortArrayOrNull() ?: return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.Short" -> {
                val array = values.toShortArrayOrNull() ?: return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.Int" -> {
                val ints = values.asIntCollection() ?: return false
                builder.setIntCollection(resolveKeyFrom(current), ints)
                true
            }
            "kotlin.Long" -> {
                val longs = values.asLongCollection() ?: return false
                builder.setLongCollection(resolveKeyFrom(current), longs)
                true
            }
            "kotlin.Float" -> {
                builder.setFloatCollection(resolveKeyFrom(current), values.asFloatCollection() ?: return false)
                true
            }
            "kotlin.Double" -> {
                builder.setDoubleCollection(resolveKeyFrom(current), values.asDoubleCollection() ?: return false)
                true
            }
            "kotlin.Char" -> {
                builder.set(resolveKeyFrom(current), values.toCharCodeArray() ?: return false)
                true
            }
            "kotlin.UByte" -> {
                builder.set(resolveKeyFrom(current), values.toUByteArrayOrNull() ?: return false)
                true
            }
            "kotlin.UShort" -> {
                builder.set(resolveKeyFrom(current), values.toUShortArrayOrNull() ?: return false)
                true
            }
            "kotlin.UInt" -> {
                builder.set(resolveKeyFrom(current), values.toUIntArrayOrNull() ?: return false)
                true
            }
            "kotlin.ULong" -> {
                builder.set(resolveKeyFrom(current), values.toULongArrayOrNull() ?: return false)
                true
            }
            else -> false
        }
    }
}

enum class StructureType {
    CLASS, OBJECT, LIST, MAP
}

/**
 * Mutable entry on the structure stack. Reused via reset() to avoid allocation.
 */
class StructureEntry(
    @JvmField var kind: StructureType = StructureType.CLASS,
    @JvmField var position: Int = 0,
    @JvmField var key: String? = null,
    @JvmField var elementIndex: Int = 0,
    @JvmField var expectingKey: Boolean = false,
    @JvmField var capturedKey: String? = null
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
class StructureStack(initialCapacity: Int = 4) {
    private val entries = ArrayList<StructureEntry>(initialCapacity)
    private var size = 0
    // Lazy allocation: pre-filling 16 entries showed up as 23% of allocs in raw-encode
    // flamegraph. Allocate on first push instead. Steady-state depth is 2-4.

    fun push(kind: StructureType, position: Int, key: String? = null) {
        if (size >= entries.size) {
            entries.add(StructureEntry())
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

private fun Collection<*>.toBooleanIntArray(): IntArray? {
    val result = IntArray(size)
    var index = 0
    for (value in this) {
        result[index++] = when (value) {
            true -> 1
            false -> 0
            else -> return null
        }
    }
    return result
}

private fun Collection<*>.toShortArrayOrNull(): ShortArray? {
    val result = ShortArray(size)
    var index = 0
    for (value in this) {
        val number = value as? Number ?: return null
        result[index++] = number.toShort()
    }
    return result
}

@Suppress("UNCHECKED_CAST")
private fun Collection<*>.asIntCollection(): Collection<Int>? {
    for (value in this) if (value !is Int) return null
    return this as Collection<Int>
}

@Suppress("UNCHECKED_CAST")
private fun Collection<*>.asLongCollection(): Collection<Long>? {
    for (value in this) if (value !is Long) return null
    return this as Collection<Long>
}

@Suppress("UNCHECKED_CAST")
private fun Collection<*>.asFloatCollection(): Collection<Float>? {
    for (value in this) if (value !is Float) return null
    return this as Collection<Float>
}

@Suppress("UNCHECKED_CAST")
private fun Collection<*>.asDoubleCollection(): Collection<Double>? {
    for (value in this) if (value !is Double) return null
    return this as Collection<Double>
}

private fun Collection<*>.toCharCodeArray(): IntArray? {
    val result = IntArray(size)
    var index = 0
    for (value in this) {
        val charValue = value as? Char ?: return null
        result[index++] = charValue.code
    }
    return result
}

private fun Collection<*>.toUByteArrayOrNull(): UByteArray? {
    val result = UByteArray(size)
    var index = 0
    for (value in this) {
        result[index++] = value as? UByte ?: return null
    }
    return result
}

private fun Collection<*>.toUShortArrayOrNull(): UShortArray? {
    val result = UShortArray(size)
    var index = 0
    for (value in this) {
        result[index++] = value as? UShort ?: return null
    }
    return result
}

private fun Collection<*>.toUIntArrayOrNull(): UIntArray? {
    val result = UIntArray(size)
    var index = 0
    for (value in this) {
        result[index++] = value as? UInt ?: return null
    }
    return result
}

private fun Collection<*>.toULongArrayOrNull(): ULongArray? {
    val result = ULongArray(size)
    var index = 0
    for (value in this) {
        result[index++] = value as? ULong ?: return null
    }
    return result
}
