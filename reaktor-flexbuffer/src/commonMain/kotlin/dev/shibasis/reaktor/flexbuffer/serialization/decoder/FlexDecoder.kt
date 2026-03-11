package dev.shibasis.reaktor.core.serialization.decoder

import com.google.flatbuffers.kotlin.Reference
import com.google.flatbuffers.kotlin.Vector
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import com.google.flatbuffers.kotlin.Map as FlexMap

/**
 * Decodes a FlexBuffer Reference tree into Kotlin @Serializable objects.
 *
 * Navigation strategy:
 * - CLASS/OBJECT: the Reference is a FlexBuffer map. Fields are accessed by name from the descriptor.
 * - MAP: the Reference is a FlexBuffer map. Entries are iterated in FlexBuffer's sorted key order.
 *        Keys are T_KEY (strings) so non-string map keys are parsed from their string representation.
 * - LIST: the Reference is a vector. Elements are accessed by index.
 * - VALUE: a single scalar or the root element before beginStructure is called.
 *
 * --- Performance Tradeoffs & Bottlenecks ---
 * 1. Allocation Overhead:
 *    - `beginStructure` creates a new `FlexDecoder` instance for every recursive map, list, or nested object.
 *    - `MapKeyDecoder` is instantiated for every single map key.
 *    - Together, these create high garbage collection pressure for deep or large structures.
 * 2. Element Resolution (`resolveElement`):
 *    - Calling `flexMap!![name]` or `vector!![index]` allocates a new `Reference` wrapper object.
 * 3. Map Key Parsing (`mapKeyString(index)`):
 *    - FlexBuffers strictly store keys as `T_KEY` strings.
 *    - Non-string keys (e.g., Int, Boolean) must be read as UTF-8 Strings and then parsed via `.toInt()`.
 *    - This is extremely inefficient compared to reading raw binary keys.
 *
 * --- Future Optimizations ---
 * - Pool `FlexDecoder` and `Reference` instances to reduce GC overhead.
 * - Process `T_KEY` bytes directly to primitives (e.g., parse Int from the byte buffer) instead of allocating a String intermediate.
 * - Flatten the decoder architecture to track state linearly rather than allocating tree nodes.
 */
class FlexDecoder(
    private val ref: Reference,
    private val kind: DecoderKind = DecoderKind.VALUE
) : Decoder, CompositeDecoder {

    override val serializersModule: SerializersModule = EmptySerializersModule()

    enum class DecoderKind { VALUE, CLASS, MAP, LIST }

    // For CLASS/MAP: the FlexBuffer map
    private var flexMap: FlexMap? = null
    // For LIST: the FlexBuffer vector
    private var vector: Vector? = null
    // For MAP: number of entries in the map
    private var mapSize: Int = 0

    init {
        when (kind) {
            DecoderKind.CLASS -> {
                flexMap = ref.toMap()
            }
            DecoderKind.MAP -> {
                val m = ref.toMap()
                flexMap = m
                mapSize = m.size
            }
            DecoderKind.LIST -> {
                vector = ref.toVector()
            }
            DecoderKind.VALUE -> {}
        }
    }

    // --- Sequential decoding ---

    @ExperimentalSerializationApi
    override fun decodeSequentially(): Boolean = true

    private var elementIndex = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = elementIndex++

    // --- Collection size ---

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        return when (kind) {
            DecoderKind.MAP -> mapSize
            DecoderKind.LIST -> vector?.size ?: 0
            else -> 0
        }
    }

    // --- Structure lifecycle ---

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val childKind = when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> DecoderKind.CLASS
            StructureKind.MAP -> DecoderKind.MAP
            StructureKind.LIST -> DecoderKind.LIST
            else -> DecoderKind.CLASS
        }
        return FlexDecoder(ref, childKind)
    }

    override fun endStructure(descriptor: SerialDescriptor) {}

    // --- Element resolution ---

    /**
     * Resolves a Reference for a composite element at [index].
     * For MAP keys, returns null (keys are handled separately as strings).
     */
    private fun resolveElement(descriptor: SerialDescriptor, index: Int): Reference? {
        return when (kind) {
            DecoderKind.CLASS -> {
                val name = descriptor.getElementName(index)
                flexMap!![name]
            }
            DecoderKind.MAP -> {
                val entryIndex = index / 2
                if (index % 2 == 0) {
                    // Key: handled via mapKeyString, return null to signal
                    null
                } else {
                    // Value: get value at entry position
                    flexMap!![entryIndex]
                }
            }
            DecoderKind.LIST -> {
                vector!![index]
            }
            DecoderKind.VALUE -> ref
        }
    }

    /**
     * Gets the map key as a string for the entry at [entryIndex].
     * Map keys in FlexBuffer are always stored as strings (T_KEY type).
     * Non-string Kotlin map keys were converted via toString() during encoding.
     */
    private fun mapKeyString(entryIndex: Int): String {
        return flexMap!!.keyAsString(entryIndex)
    }

    // --- Primitive element decoders (CompositeDecoder) ---

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2).toBoolean()
        return ref.toBoolean()
    }

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2).toByte()
        return ref.toLong().toByte()
    }

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2)[0]
        return ref.toInt().toChar()
    }

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2).toDouble()
        return ref.toDouble()
    }

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2).toFloat()
        return ref.toFloat()
    }

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2).toInt()
        return ref.toInt()
    }

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2).toLong()
        return ref.toLong()
    }

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2).toShort()
        return ref.toLong().toShort()
    }

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        val ref = resolveElement(descriptor, index)
        if (ref == null) return mapKeyString(index / 2)
        return ref.toString()
    }

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val ref = resolveElement(descriptor, index) ?: return MapKeyDecoder(mapKeyString(index / 2))
        return FlexDecoder(ref, DecoderKind.VALUE)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        // For MAP keys, use the MapKeyDecoder
        if (kind == DecoderKind.MAP && index % 2 == 0) {
            val entryIndex = index / 2
            val keyDecoder = MapKeyDecoder(mapKeyString(entryIndex))
            return keyDecoder.decodeSerializableValue(deserializer)
        }
        val childRef = resolveElement(descriptor, index)!!
        val childDecoder = FlexDecoder(childRef, DecoderKind.VALUE)
        return childDecoder.decodeSerializableValue(deserializer)
    }

    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        val childRef = resolveElement(descriptor, index) ?: return null
        if (childRef.isNull) return null
        val childDecoder = FlexDecoder(childRef, DecoderKind.VALUE)
        return childDecoder.decodeSerializableValue(deserializer)
    }

    // --- Primitive decoders (Decoder interface, for top-level scalars) ---

    override fun decodeBoolean(): Boolean = ref.toBoolean()
    override fun decodeByte(): Byte = ref.toLong().toByte()
    override fun decodeChar(): Char = ref.toInt().toChar()
    override fun decodeDouble(): Double = ref.toDouble()
    override fun decodeFloat(): Float = ref.toFloat()
    override fun decodeInt(): Int = ref.toInt()
    override fun decodeLong(): Long = ref.toLong()
    override fun decodeShort(): Short = ref.toLong().toShort()
    override fun decodeString(): String = ref.toString()
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = ref.toInt()
    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this

    @ExperimentalSerializationApi
    override fun decodeNotNullMark(): Boolean = !ref.isNull

    @ExperimentalSerializationApi
    override fun decodeNull(): Nothing? = null

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return deserializer.deserialize(this)
    }

    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? {
        if (ref.isNull) return null
        return deserializer.deserialize(this)
    }
}

/**
 * Lightweight decoder for map keys stored as FlexBuffer T_KEY strings.
 * Parses the string representation back to the expected primitive type.
 */
@OptIn(ExperimentalSerializationApi::class)
private class MapKeyDecoder(private val key: String) : Decoder {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        error("Map keys cannot be composite types")
    }

    override fun decodeBoolean(): Boolean = key.toBoolean()
    override fun decodeByte(): Byte = key.toByte()
    override fun decodeChar(): Char = key[0]
    override fun decodeDouble(): Double = key.toDouble()
    override fun decodeFloat(): Float = key.toFloat()
    override fun decodeInt(): Int = key.toInt()
    override fun decodeLong(): Long = key.toLong()
    override fun decodeShort(): Short = key.toShort()
    override fun decodeString(): String = key
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = key.toInt()
    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this
    override fun decodeNotNullMark(): Boolean = true
    override fun decodeNull(): Nothing? = null

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return deserializer.deserialize(this)
    }

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? {
        return deserializer.deserialize(this)
    }
}
