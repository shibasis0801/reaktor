package dev.shibasis.reaktor.flexbuffer.core

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.Reference
import com.google.flatbuffers.kotlin.Vector
import com.google.flatbuffers.kotlin.getRoot
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

// Type alias to avoid conflict with kotlin.collections.Map
internal typealias FlexMap = com.google.flatbuffers.kotlin.Map

/**
 * High-performance FlexBuffer decoder.
 *
 * Design goals:
 * 1. Zero-copy where possible - reads directly from buffer
 * 2. Minimum allocations - reuses internal structures
 * 3. Full type support - primitives, collections, nested objects
 * 4. Thread-safe - stateless after construction
 *
 * Key concepts:
 * - Uses a DecodingContext stack to track position in nested structures
 * - Maps use binary search for key lookup (O(log n))
 * - Vectors/arrays use direct index access (O(1))
 */
@OptIn(ExperimentalSerializationApi::class)
class FlexDecoderV2 private constructor(
    private val root: Reference,
    override val serializersModule: SerializersModule
) : AbstractDecoder() {

    // Stack of decoding contexts for nested structures
    private val contextStack = DecodingContextStack()

    // Current context being decoded
    private var currentContext: DecodingContext? = null

    // Index tracking for sequential decoding
    private var elementIndex = 0

    companion object {
        /**
         * Decode FlexBuffer bytes to a value.
         * This is the recommended entry point.
         */
        inline fun <reified T> decode(bytes: ByteArray): T {
            return decode(serializer(), bytes)
        }

        /**
         * Decode using explicit deserializer.
         */
        fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
            val buffer = ArrayReadBuffer(bytes)
            val root = getRoot(buffer)
            val decoder = FlexDecoderV2(root, EmptySerializersModule())
            return decoder.decodeSerializableValue(deserializer)
        }

        /**
         * Decode from an existing ReadBuffer (zero-copy from encoder).
         */
        fun <T> decode(deserializer: DeserializationStrategy<T>, buffer: ReadBuffer): T {
            val root = getRoot(buffer)
            val decoder = FlexDecoderV2(root, EmptySerializersModule())
            return decoder.decodeSerializableValue(deserializer)
        }
    }

    // ==================== Element Index Tracking ====================

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val ctx = currentContext ?: return CompositeDecoder.DECODE_DONE

        return when (ctx.type) {
            ContextType.MAP_OBJECT -> {
                // For objects decoded from maps, iterate through descriptor fields
                if (ctx.fieldIndex >= descriptor.elementsCount) {
                    CompositeDecoder.DECODE_DONE
                } else {
                    val fieldName = descriptor.getElementName(ctx.fieldIndex)
                    val ref = ctx.mapRef?.get(fieldName)
                    if (ref != null && !ref.isNull) {
                        ctx.currentRef = ref
                        ctx.fieldIndex++
                        ctx.fieldIndex - 1
                    } else {
                        // Field not found in map, try next
                        ctx.fieldIndex++
                        decodeElementIndex(descriptor)
                    }
                }
            }
            ContextType.VECTOR -> {
                if (ctx.vectorIndex >= ctx.size) {
                    CompositeDecoder.DECODE_DONE
                } else {
                    ctx.currentRef = ctx.vectorRef?.get(ctx.vectorIndex)
                    val idx = ctx.vectorIndex
                    ctx.vectorIndex++
                    idx
                }
            }
            ContextType.MAP_ENTRIES -> {
                // For Map<K,V> iteration - return key/value pairs
                if (ctx.mapEntryIndex >= ctx.size * 2) {
                    CompositeDecoder.DECODE_DONE
                } else {
                    val pairIndex = ctx.mapEntryIndex / 2
                    val isKey = ctx.mapEntryIndex % 2 == 0

                    if (isKey) {
                        // Return map key
                        ctx.currentMapKey = ctx.mapRef?.keyAsString(pairIndex)
                    } else {
                        // Return map value
                        ctx.currentRef = ctx.mapRef?.get(pairIndex)
                    }
                    val idx = ctx.mapEntryIndex
                    ctx.mapEntryIndex++
                    idx
                }
            }
            ContextType.ROOT -> {
                if (elementIndex > 0) {
                    CompositeDecoder.DECODE_DONE
                } else {
                    ctx.currentRef = root
                    elementIndex++
                    0
                }
            }
        }
    }

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        val ctx = currentContext ?: return 0
        return ctx.size
    }

    // ==================== Primitive Decoding ====================

    override fun decodeBoolean(): Boolean {
        val ref = getCurrentReference()
        return ref?.toBoolean() ?: false
    }

    override fun decodeByte(): Byte {
        val ref = getCurrentReference()
        return ref?.toByte() ?: 0
    }

    override fun decodeShort(): Short {
        val ref = getCurrentReference()
        return ref?.toShort() ?: 0
    }

    override fun decodeInt(): Int {
        val ref = getCurrentReference()
        return ref?.toInt() ?: 0
    }

    override fun decodeLong(): Long {
        val ref = getCurrentReference()
        return ref?.toLong() ?: 0L
    }

    override fun decodeFloat(): Float {
        val ref = getCurrentReference()
        return ref?.toFloat() ?: 0f
    }

    override fun decodeDouble(): Double {
        val ref = getCurrentReference()
        return ref?.toDouble() ?: 0.0
    }

    override fun decodeChar(): Char {
        val ref = getCurrentReference()
        return ref?.toInt()?.toChar() ?: '\u0000'
    }

    override fun decodeString(): String {
        val ctx = currentContext

        // Check if we're decoding a map key
        if (ctx != null && ctx.type == ContextType.MAP_ENTRIES) {
            val key = ctx.currentMapKey
            if (key != null) {
                ctx.currentMapKey = null
                return key
            }
        }

        val ref = getCurrentReference()
        return ref?.toString() ?: ""
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val ref = getCurrentReference()
        return ref?.toInt() ?: 0
    }

    override fun decodeNull(): Nothing? {
        return null
    }

    override fun decodeNotNullMark(): Boolean {
        val ref = getCurrentReference()
        return ref != null && !ref.isNull
    }

    // ==================== Structure Handling ====================

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val ref = getCurrentReference() ?: root

        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                // Classes are encoded as maps
                if (ref.isMap) {
                    val map = ref.toMap()
                    contextStack.push(ContextType.MAP_OBJECT, mapRef = map, size = map.size)
                } else {
                    // Fallback: treat as single value
                    contextStack.push(ContextType.ROOT)
                }
            }
            StructureKind.LIST -> {
                // Lists are encoded as vectors
                if (ref.isVector || ref.isTypedVector) {
                    val vec = ref.toVector()
                    contextStack.push(ContextType.VECTOR, vectorRef = vec, size = vec.size)
                } else {
                    contextStack.push(ContextType.VECTOR, size = 0)
                }
            }
            StructureKind.MAP -> {
                // Maps iterate as key-value pairs
                if (ref.isMap) {
                    val map = ref.toMap()
                    contextStack.push(ContextType.MAP_ENTRIES, mapRef = map, size = map.size)
                } else {
                    contextStack.push(ContextType.MAP_ENTRIES, size = 0)
                }
            }
            else -> {
                contextStack.push(ContextType.ROOT)
            }
        }

        currentContext = contextStack.peek()
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        contextStack.pop()
        currentContext = contextStack.peek()
    }

    // ==================== Element Decoding ====================

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        return decodeSerializableValue(deserializer)
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        return this
    }

    // ==================== Helpers ====================

    private fun getCurrentReference(): Reference? {
        return currentContext?.currentRef ?: root
    }
}

/**
 * Type of decoding context.
 */
enum class ContextType {
    ROOT,           // Root level
    MAP_OBJECT,     // Decoding object fields from a map
    VECTOR,         // Decoding vector elements
    MAP_ENTRIES     // Decoding map as key-value pairs
}

/**
 * Context for tracking position during decoding.
 */
class DecodingContext {
    var type: ContextType = ContextType.ROOT
    var size: Int = 0

    // For MAP_OBJECT: iterating through object fields
    var mapRef: FlexMap? = null
    var fieldIndex: Int = 0

    // For VECTOR: iterating through elements
    var vectorRef: Vector? = null
    var vectorIndex: Int = 0

    // For MAP_ENTRIES: iterating through map entries
    var mapEntryIndex: Int = 0
    var currentMapKey: String? = null

    // Current reference being decoded
    var currentRef: Reference? = null

    fun reset(
        type: ContextType,
        mapRef: FlexMap? = null,
        vectorRef: Vector? = null,
        size: Int = 0
    ) {
        this.type = type
        this.mapRef = mapRef
        this.vectorRef = vectorRef
        this.size = size
        this.fieldIndex = 0
        this.vectorIndex = 0
        this.mapEntryIndex = 0
        this.currentMapKey = null
        this.currentRef = null
    }
}

/**
 * Pooled stack for decoding contexts.
 */
class DecodingContextStack(initialCapacity: Int = 32) {
    private val contexts = ArrayList<DecodingContext>(initialCapacity)
    private var size = 0

    init {
        repeat(initialCapacity) {
            contexts.add(DecodingContext())
        }
    }

    fun push(
        type: ContextType,
        mapRef: FlexMap? = null,
        vectorRef: Vector? = null,
        size: Int = 0
    ) {
        if (this.size >= contexts.size) {
            repeat(contexts.size) {
                contexts.add(DecodingContext())
            }
        }
        contexts[this.size].reset(type, mapRef, vectorRef, size)
        this.size++
    }

    fun pop(): DecodingContext? {
        if (size == 0) return null
        size--
        return contexts[size]
    }

    fun peek(): DecodingContext? {
        if (size == 0) return null
        return contexts[size - 1]
    }

    fun clear() {
        size = 0
    }

    fun isEmpty(): Boolean = size == 0
}
