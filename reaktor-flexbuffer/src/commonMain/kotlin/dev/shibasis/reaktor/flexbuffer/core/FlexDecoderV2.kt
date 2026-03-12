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

internal typealias FlexMap = com.google.flatbuffers.kotlin.Map

/**
 * FlexBuffer decoder using kotlinx.serialization's AbstractDecoder.
 *
 * Architecture: uses a pooled context stack to track position in nested structures.
 * Each context holds a reference to the current FlexBuffer node (Map, Vector, or scalar).
 *
 * Hot path: decodeElementIndex → getCurrentReference → decodePrimitive.
 * This path executes for every field in every object, so it must be allocation-free.
 *
 * Field lookup in MAP_OBJECT context: FlexBuffer maps store keys sorted, so
 * Map.get(String) uses binary search — O(log n) per field. For a class with k fields
 * present in a map of n entries, total cost is O(k * log n). Fields missing from the
 * buffer are skipped in O(1) per field via the iterative scan in decodeElementIndex.
 *
 * Ref: "Effective Java" (Bloch, Item 6) — avoid creating unnecessary objects
 * Ref: https://flatbuffers.dev/flexbuffers.html — FlexBuffer binary layout
 */
@OptIn(ExperimentalSerializationApi::class)
class FlexDecoderV2 private constructor(
    private val root: Reference,
    override val serializersModule: SerializersModule
) : AbstractDecoder() {

    // Pre-allocated context stack. Avoids per-structure allocations after warmup.
    // Ref: "Object Pool" pattern — "Game Programming Patterns" (Nystrom, ch.19)
    private val contextStack = DecodingContextStack()

    private var currentContext: DecodingContext? = null
    private var elementIndex = 0

    companion object {
        inline fun <reified T> decode(bytes: ByteArray): T {
            return decode(serializer(), bytes)
        }

        fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
            val buffer = ArrayReadBuffer(bytes)
            val root = getRoot(buffer)
            val decoder = FlexDecoderV2(root, EmptySerializersModule())
            return decoder.decodeSerializableValue(deserializer)
        }

        fun <T> decode(deserializer: DeserializationStrategy<T>, buffer: ReadBuffer): T {
            val root = getRoot(buffer)
            val decoder = FlexDecoderV2(root, EmptySerializersModule())
            return decoder.decodeSerializableValue(deserializer)
        }
    }

    // ==================== Element Index Tracking ====================

    /**
     * Returns the next element index to decode, or DECODE_DONE.
     *
     * MAP_OBJECT: iterates descriptor fields, skipping any not present in the FlexBuffer map.
     * Uses a while loop instead of recursion to avoid stack depth proportional to missing fields.
     * Worst case: class with N fields, none present → N iterations, zero stack growth.
     *
     * VECTOR: direct index increment — O(1).
     * MAP_ENTRIES: alternates key/value — O(1).
     */
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val ctx = currentContext ?: return CompositeDecoder.DECODE_DONE

        return when (ctx.type) {
            ContextType.MAP_OBJECT -> {
                val map = ctx.mapRef
                val count = descriptor.elementsCount
                // Iterative scan replaces the original recursive call.
                // Each iteration is a single Map.get (binary search on sorted keys).
                while (ctx.fieldIndex < count) {
                    val fieldName = descriptor.getElementName(ctx.fieldIndex)
                    val ref = map?.get(fieldName)
                    if (ref != null && !ref.isNull) {
                        ctx.currentRef = ref
                        val idx = ctx.fieldIndex
                        ctx.fieldIndex++
                        return idx
                    }
                    ctx.fieldIndex++
                }
                CompositeDecoder.DECODE_DONE
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
                if (ctx.mapEntryIndex >= ctx.size * 2) {
                    CompositeDecoder.DECODE_DONE
                } else {
                    val pairIndex = ctx.mapEntryIndex / 2
                    val isKey = ctx.mapEntryIndex % 2 == 0
                    if (isKey) {
                        ctx.currentMapKey = ctx.mapRef?.keyAsString(pairIndex)
                    } else {
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

    override fun decodeSequentially(): Boolean = false

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        return currentContext?.size ?: 0
    }

    // ==================== Primitive Decoding ====================
    // Each method reads directly from the FlexBuffer Reference — zero copy for primitives.
    // Reference.toInt() etc. read from the underlying byte buffer without allocation.
    // Ref: https://flatbuffers.dev/flexbuffers.html — packed value types

    override fun decodeBoolean(): Boolean {
        return getCurrentReference()?.toBoolean() ?: false
    }

    override fun decodeByte(): Byte {
        return getCurrentReference()?.toByte() ?: 0
    }

    override fun decodeShort(): Short {
        return getCurrentReference()?.toShort() ?: 0
    }

    override fun decodeInt(): Int {
        return getCurrentReference()?.toInt() ?: 0
    }

    override fun decodeLong(): Long {
        return getCurrentReference()?.toLong() ?: 0L
    }

    override fun decodeFloat(): Float {
        return getCurrentReference()?.toFloat() ?: 0f
    }

    override fun decodeDouble(): Double {
        return getCurrentReference()?.toDouble() ?: 0.0
    }

    override fun decodeChar(): Char {
        return getCurrentReference()?.toInt()?.toChar() ?: '\u0000'
    }

    override fun decodeString(): String {
        val ctx = currentContext
        // Fast path: map key decoding. The key string is already materialized by
        // keyAsString() in decodeElementIndex, so we return it directly without
        // any Reference traversal.
        if (ctx != null && ctx.type == ContextType.MAP_ENTRIES) {
            val key = ctx.currentMapKey
            if (key != null) {
                ctx.currentMapKey = null
                return key
            }
        }
        return getCurrentReference()?.toString() ?: ""
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        return getCurrentReference()?.toInt() ?: 0
    }

    override fun decodeNull(): Nothing? = null

    override fun decodeNotNullMark(): Boolean {
        val ref = getCurrentReference()
        return ref != null && !ref.isNull
    }

    // ==================== Structure Handling ====================

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val ref = getCurrentReference() ?: root

        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (ref.isMap) {
                    val map = ref.toMap()
                    contextStack.push(ContextType.MAP_OBJECT, mapRef = map, size = map.size)
                } else {
                    contextStack.push(ContextType.ROOT)
                }
            }
            StructureKind.LIST -> {
                if (ref.isVector || ref.isTypedVector) {
                    val vec = ref.toVector()
                    contextStack.push(ContextType.VECTOR, vectorRef = vec, size = vec.size)
                } else {
                    contextStack.push(ContextType.VECTOR, size = 0)
                }
            }
            StructureKind.MAP -> {
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
    MAP_OBJECT,     // Decoding object fields from a map (binary search lookup)
    VECTOR,         // Decoding vector elements (O(1) indexed access)
    MAP_ENTRIES     // Decoding map as key-value pairs (for Map<K,V>)
}

/**
 * Mutable context for tracking position during decoding.
 * Instances are pooled in DecodingContextStack to avoid GC pressure.
 *
 * Fields are reset via reset() rather than creating new instances.
 * This trades code clarity for allocation avoidance — critical in tight decode loops.
 *
 * Ref: "Effective Java" (Bloch, Item 6) — reuse objects instead of creating new ones
 */
class DecodingContext {
    var type: ContextType = ContextType.ROOT
    var size: Int = 0

    var mapRef: FlexMap? = null
    var fieldIndex: Int = 0

    var vectorRef: Vector? = null
    var vectorIndex: Int = 0

    var mapEntryIndex: Int = 0
    var currentMapKey: String? = null

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
 * Pre-allocated stack of DecodingContext objects.
 * Grows by doubling when capacity is exceeded, but never shrinks — contexts are reused.
 * Initial capacity of 16 handles nesting depth up to 16 without reallocation.
 *
 * Ref: "Introduction to Algorithms" (CLRS, §17.4) — amortized doubling
 */
class DecodingContextStack(initialCapacity: Int = 16) {
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
            val growBy = contexts.size
            repeat(growBy) {
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
