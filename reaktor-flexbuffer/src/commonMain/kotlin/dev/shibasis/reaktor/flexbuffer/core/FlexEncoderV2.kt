@file:OptIn(ExperimentalUnsignedTypes::class)

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
@OptIn(ExperimentalSerializationApi::class)
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

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        val descriptor = serializer.descriptor
        if (descriptor.kind == StructureKind.LIST || descriptor.serialName.endsWith("Array")) {
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

    private fun tryEncodeBulkValue(descriptor: SerialDescriptor, value: Any?): Boolean {
        val current = structureStack.peek()
        if (current != null && current.kind == StructureType.MAP && current.expectingKey) return false

        return when (descriptor.serialName) {
            "kotlin.ByteArray" -> {
                builder.set(resolveKeyFrom(current), value as? ByteArray ?: return false)
                true
            }
            "kotlin.ShortArray" -> {
                val array = value as? ShortArray ?: return false
                if (!array.allNonNegative()) return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.IntArray" -> {
                val array = value as? IntArray ?: return false
                if (!array.allNonNegative()) return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.LongArray" -> {
                val array = value as? LongArray ?: return false
                if (!array.allNonNegative()) return false
                builder.set(resolveKeyFrom(current), array)
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
                val array = values.toNonNegativeShortArray() ?: return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.Short" -> {
                val array = values.toNonNegativeShortArray() ?: return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.Int" -> {
                val array = values.toNonNegativeIntArray() ?: return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.Long" -> {
                val array = values.toNonNegativeLongArray() ?: return false
                builder.set(resolveKeyFrom(current), array)
                true
            }
            "kotlin.Float" -> {
                builder.set(resolveKeyFrom(current), values.toFloatArrayOrNull() ?: return false)
                true
            }
            "kotlin.Double" -> {
                builder.set(resolveKeyFrom(current), values.toDoubleArrayOrNull() ?: return false)
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

private fun ShortArray.allNonNegative(): Boolean = all { it >= 0 }

private fun IntArray.allNonNegative(): Boolean = all { it >= 0 }

private fun LongArray.allNonNegative(): Boolean = all { it >= 0L }

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

private fun Collection<*>.toNonNegativeShortArray(): ShortArray? {
    val result = ShortArray(size)
    var index = 0
    for (value in this) {
        val number = value as? Number ?: return null
        val shortValue = number.toShort()
        if (shortValue < 0) return null
        result[index++] = shortValue
    }
    return result
}

private fun Collection<*>.toNonNegativeIntArray(): IntArray? {
    val result = IntArray(size)
    var index = 0
    for (value in this) {
        val number = value as? Number ?: return null
        val intValue = number.toInt()
        if (intValue < 0) return null
        result[index++] = intValue
    }
    return result
}

private fun Collection<*>.toNonNegativeLongArray(): LongArray? {
    val result = LongArray(size)
    var index = 0
    for (value in this) {
        val number = value as? Number ?: return null
        val longValue = number.toLong()
        if (longValue < 0L) return null
        result[index++] = longValue
    }
    return result
}

private fun Collection<*>.toFloatArrayOrNull(): FloatArray? {
    val result = FloatArray(size)
    var index = 0
    for (value in this) {
        val number = value as? Number ?: return null
        result[index++] = number.toFloat()
    }
    return result
}

private fun Collection<*>.toDoubleArrayOrNull(): DoubleArray? {
    val result = DoubleArray(size)
    var index = 0
    for (value in this) {
        val number = value as? Number ?: return null
        result[index++] = number.toDouble()
    }
    return result
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
