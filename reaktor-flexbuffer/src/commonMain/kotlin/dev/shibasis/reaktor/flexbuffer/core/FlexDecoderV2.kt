@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.ArrayReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Reference
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Vector
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

internal typealias FlexMap = dev.shibasis.reaktor.flexbuffer.flatbuffers.Map

/**
 * FlexBuffer decoder using kotlinx.serialization's AbstractDecoder.
 *
 * Optimization: field index caching. FlexBuffer maps store keys sorted alphabetically.
 * The mapping from descriptor field index → map position index is deterministic per class
 * and computed once on first decode. Subsequent decodes reuse the cached mapping for O(1)
 * field access instead of O(log n) binary search per field.
 *
 * When cached indices are available, primitive fields are read via direct map scalar reads
 * (map.getInt, map.getString, etc.) bypassing Reference allocation entirely.
 *
 * Hot path: decodeElementIndex(sets currentMapIndex) → decodeXxx(direct map read).
 * With caching: zero binary search, zero Reference allocation for primitives.
 * Reference allocation only for nested structures (unavoidable for type dispatch).
 */
@OptIn(ExperimentalSerializationApi::class)
class FlexDecoderV2 private constructor() : AbstractDecoder() {

    // Mutable state — reset() before each top-level decode.
    private var root: Reference = NullReference
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private val contextStack = DecodingContextStack()
    private var currentContext: DecodingContext? = null
    private var elementIndex = 0

    private fun reset(root: Reference) {
        this.root = root
        this.contextStack.clear()
        this.currentContext = null
        this.elementIndex = 0
    }

    companion object {
        private val MODULE = EmptySerializersModule()

        // Per-platform pool: JVM/Android = ThreadLocal, Native = volatile slot, JS = plain.
        // Each runtime uses its cheapest "one re-usable slot" primitive instead of paying
        // for cross-platform CAS that's only optimal on JVM. Native CAS was measured
        // adding ~30 ns per acquire — visible in the iOS benchmark profile.
        @JvmStatic
        private val pool = PerPlatformPool { FlexDecoderV2() }

        @JvmStatic
        private fun acquire(): FlexDecoderV2 = pool.acquire()

        @JvmStatic
        private fun release(d: FlexDecoderV2) = pool.release(d)

        private val NullReference: Reference
            get() = getRoot(byteArrayOf(0, 0, 0))

        /**
         * Field index cache: serialName → IntArray mapping descriptor index to map position.
         *
         * FlexBuffer maps are always sorted alphabetically by key. For a class with fields
         * [name, age, bio], the map order is [age, bio, name] → indices [2, 0, 1] meaning
         * descriptor index 0 ("name") maps to map position 2, etc.
         *
         * Computed once per class, reused across all decode calls.
         */
        private val fieldIndexCache = HashMap<String, IntArray>()

        private fun getOrBuildFieldIndices(descriptor: SerialDescriptor): IntArray {
            val serialName = descriptor.serialName
            fieldIndexCache[serialName]?.let { return it }

            val count = descriptor.elementsCount
            val names = Array(count) { descriptor.getElementName(it) }
            val encodedNames = Array(count) { names[it].encodeToByteArray() }
            val sortedByName = names.indices.sortedWith { left, right ->
                val a = encodedNames[left]
                val b = encodedNames[right]
                val n = minOf(a.size, b.size)
                var result = a.size - b.size
                for (i in 0 until n) {
                    val delta = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                    if (delta != 0) {
                        result = delta
                        break
                    }
                }
                result
            }
            // sortedByName[mapPos] = descriptorIndex → invert to descriptorIndex → mapPos
            val result = IntArray(count)
            for (mapPos in sortedByName.indices) {
                result[sortedByName[mapPos]] = mapPos
            }
            fieldIndexCache[serialName] = result
            return result
        }

        inline fun <reified T> decode(bytes: ByteArray): T {
            return decode(serializer(), bytes)
        }

        fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
            val root = getRoot(bytes)
            val decoder = acquire()
            decoder.reset(root)
            try {
                return decoder.decodeSerializableValue(deserializer)
            } finally {
                release(decoder)
            }
        }

        fun <T> decode(deserializer: DeserializationStrategy<T>, buffer: ReadBuffer): T {
            val root = getRoot(buffer)
            val decoder = acquire()
            decoder.reset(root)
            try {
                return decoder.decodeSerializableValue(deserializer)
            } finally {
                release(decoder)
            }
        }
    }

    // ==================== Element Index Tracking ====================

    /**
     * MAP_OBJECT fast path: uses cached field indices for O(1) lookup.
     * Sets currentMapIndex for subsequent decodeXxx() calls to read directly from map.
     * Does NOT allocate Reference — scalar reads go through map.getXxx(index).
     *
     * MAP_OBJECT slow path: binary search fallback when field count mismatch.
     * Sets currentRef as before.
     */
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val ctx = currentContext ?: return CompositeDecoder.DECODE_DONE

        return when (ctx.type) {
            ContextType.MAP_OBJECT -> {
                val map = ctx.mapRef ?: return CompositeDecoder.DECODE_DONE
                val count = descriptor.elementsCount
                val indices = ctx.fieldIndices

                if (indices != null) {
                    // Fast path: O(1) per field, no binary search, no Reference
                    while (ctx.fieldIndex < count) {
                        val mapIdx = indices[ctx.fieldIndex]
                        if (!map.isNullAt(mapIdx)) {
                            ctx.currentMapIndex = mapIdx
                            ctx.currentRef = null
                            val idx = ctx.fieldIndex
                            ctx.fieldIndex++
                            return idx
                        }
                        ctx.fieldIndex++
                    }
                    CompositeDecoder.DECODE_DONE
                } else {
                    // Slow path: binary search by name. Keep the map index so scalar reads
                    // still use Map.getXxx(index) and avoid Reference allocation.
                    while (ctx.fieldIndex < count) {
                        val fieldName = descriptor.getElementName(ctx.fieldIndex)
                        val mapIdx = map.indexOf(fieldName)
                        if (mapIdx >= 0 && !map.isNullAt(mapIdx)) {
                            ctx.currentRef = null
                            ctx.currentMapIndex = mapIdx
                            val idx = ctx.fieldIndex
                            ctx.fieldIndex++
                            return idx
                        }
                        ctx.fieldIndex++
                    }
                    CompositeDecoder.DECODE_DONE
                }
            }
            ContextType.VECTOR -> {
                if (ctx.vectorIndex >= ctx.size) {
                    CompositeDecoder.DECODE_DONE
                } else {
                    // Fast path: don't allocate a Reference. Set currentVectorIndex
                    // so decodeXxx() reads directly via vec.readInt(i) etc.
                    ctx.currentVectorIndex = ctx.vectorIndex
                    ctx.currentRef = null
                    ctx.currentMapIndex = -1
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
                        ctx.currentRef = null
                        ctx.currentMapKey = ctx.mapRef?.keyAsString(pairIndex)
                        ctx.currentMapIndex = -1
                    } else {
                        // Fast path: don't allocate a Reference. Use currentMapIndex so
                        // primitive decodeXxx() reads directly via mapRef.getXxx(index).
                        // This saves 1 Reference allocation per value for Map<K,V> decode.
                        ctx.currentRef = null
                        ctx.currentMapIndex = pairIndex
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
                    ctx.currentMapIndex = -1
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

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        val descriptor = deserializer.descriptor
        // Acceleration: if a FlexCoder is registered for this serial name, route to it.
        // The FlexCoder path is 2-3x faster than the kotlinx.serialization-driven decode.
        // Skip the registry lookup entirely when registry is empty (very cheap check).
        if (FlexCoderRegistry.codersBySerialName.isNotEmpty()) {
            val coder = FlexCoderRegistry.getBySerialName<Any>(descriptor.serialName)
            if (coder != null) {
                val ref = resolveCurrentReference()
                @Suppress("UNCHECKED_CAST")
                return coder.decode(ref) as T
            }
        }
        // Bulk-array fast paths only apply to LIST-kind descriptors. Gate the
        // String.endsWith call on the cheap kind check first.
        if (descriptor.kind == StructureKind.LIST &&
            descriptor.serialName.endsWith("Array")
        ) {
            tryDecodeBulkValue(descriptor)?.let { value ->
                @Suppress("UNCHECKED_CAST")
                return value as T
            }
        }
        return deserializer.deserialize(this)
    }

    /**
     * Resolves the current Reference, consuming currentMapIndex/currentVectorIndex if set.
     * Used by registered-coder dispatch and bulk-array fast paths to materialize one
     * Reference for the coder to decode from.
     */
    private fun resolveCurrentReference(): Reference {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.get(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.get(vi) }
        }
        return getCurrentReference() ?: root
    }

    // ==================== Primitive Decoding ====================
    // Each method checks currentMapIndex first for the fast path (direct map read,
    // no Reference allocation). Falls back to currentRef for VECTOR/MAP_ENTRIES/slow path.

    override fun decodeBoolean(): Boolean {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getBoolean(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readBoolean(vi) }
        }
        consumeMapKey()?.let { return it.toBooleanStrictOrNull() ?: false }
        return getCurrentReference()?.toBoolean() ?: false
    }

    override fun decodeByte(): Byte {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getInt(mi).toByte() }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readInt(vi).toByte() }
        }
        consumeMapKey()?.let { return it.toByte() }
        return getCurrentReference()?.toByte() ?: 0
    }

    override fun decodeShort(): Short {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getInt(mi).toShort() }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readInt(vi).toShort() }
        }
        consumeMapKey()?.let { return it.toShort() }
        return getCurrentReference()?.toShort() ?: 0
    }

    override fun decodeInt(): Int {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getInt(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readInt(vi) }
        }
        consumeMapKey()?.let { return it.toInt() }
        return getCurrentReference()?.toInt() ?: 0
    }

    override fun decodeLong(): Long {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getLong(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readLong(vi) }
        }
        consumeMapKey()?.let { return it.toLong() }
        return getCurrentReference()?.toLong() ?: 0L
    }

    override fun decodeFloat(): Float {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getFloat(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readDouble(vi).toFloat() }
        }
        consumeMapKey()?.let { return it.toFloat() }
        return getCurrentReference()?.toFloat() ?: 0f
    }

    override fun decodeDouble(): Double {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getDouble(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readDouble(vi) }
        }
        consumeMapKey()?.let { return it.toDouble() }
        return getCurrentReference()?.toDouble() ?: 0.0
    }

    override fun decodeChar(): Char {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getInt(mi).toChar() }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readInt(vi).toChar() }
        }
        consumeMapKey()?.let { return it.singleOrNull() ?: '\u0000' }
        return getCurrentReference()?.toInt()?.toChar() ?: '\u0000'
    }

    override fun decodeString(): String {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getString(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readString(vi) }
        }
        consumeMapKey()?.let { return it }
        return getCurrentReference()?.toString() ?: ""
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val ctx = currentContext
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            if (mi >= 0) { ctx.currentMapIndex = -1; return ctx.mapRef!!.getInt(mi) }
            val vi = ctx.currentVectorIndex
            if (vi >= 0) { ctx.currentVectorIndex = -1; return ctx.vectorRef!!.readInt(vi) }
        }
        consumeMapKey()?.let { return it.toInt() }
        return getCurrentReference()?.toInt() ?: 0
    }

    override fun decodeNull(): Nothing? = null

    override fun decodeNotNullMark(): Boolean {
        // VECTOR context: null check via vector.isNullAt(i) if we have an index
        val ctxv = currentContext
        if (ctxv != null) {
            val vi = ctxv.currentVectorIndex
            if (vi >= 0) {
                // Cheap inline null check via Vector — but Vector doesn't have isNullAt.
                // Fall through to standard Reference path for vectors with nullable elements.
                ctxv.currentVectorIndex = -1
                val r = ctxv.vectorRef!!.get(vi)
                ctxv.currentRef = r
                return !r.isNull
            }
        }
        val ctx = currentContext
        // Fast path: use currentMapIndex directly
        val mi = ctx?.currentMapIndex ?: -1
        if (mi >= 0) {
            return !ctx!!.mapRef!!.isNullAt(mi)
        }
        val ref = getCurrentReference()
        return ref != null && !ref.isNull
    }

    // ==================== Structure Handling ====================

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val ctx = currentContext
        // Fast path: if we have a pending map/vector index AND a known descriptor kind,
        // skip Reference allocation by going directly to Map/Vector readers.
        if (ctx != null) {
            val mi = ctx.currentMapIndex
            val vi = ctx.currentVectorIndex
            when (descriptor.kind) {
                StructureKind.CLASS, StructureKind.OBJECT,
                PolymorphicKind.SEALED, PolymorphicKind.OPEN -> {
                    if (mi >= 0) {
                        ctx.currentMapIndex = -1
                        val map = ctx.mapRef!!.getMap(mi)
                        pushMapObject(map, descriptor)
                        currentContext = contextStack.peek()
                        return this
                    }
                    if (vi >= 0) {
                        ctx.currentVectorIndex = -1
                        val map = ctx.vectorRef!!.readMap(vi)
                        pushMapObject(map, descriptor)
                        currentContext = contextStack.peek()
                        return this
                    }
                }
                StructureKind.LIST -> {
                    if (mi >= 0) {
                        ctx.currentMapIndex = -1
                        val vec = ctx.mapRef!!.getVector(mi)
                        contextStack.push(ContextType.VECTOR, vectorRef = vec, size = vec.size)
                        currentContext = contextStack.peek()
                        return this
                    }
                    if (vi >= 0) {
                        ctx.currentVectorIndex = -1
                        val vec = ctx.vectorRef!!.readVector(vi)
                        contextStack.push(ContextType.VECTOR, vectorRef = vec, size = vec.size)
                        currentContext = contextStack.peek()
                        return this
                    }
                }
                StructureKind.MAP -> {
                    if (mi >= 0) {
                        ctx.currentMapIndex = -1
                        val map = ctx.mapRef!!.getMap(mi)
                        contextStack.push(ContextType.MAP_ENTRIES, mapRef = map, size = map.size)
                        currentContext = contextStack.peek()
                        return this
                    }
                    if (vi >= 0) {
                        ctx.currentVectorIndex = -1
                        val map = ctx.vectorRef!!.readMap(vi)
                        contextStack.push(ContextType.MAP_ENTRIES, mapRef = map, size = map.size)
                        currentContext = contextStack.peek()
                        return this
                    }
                }
                else -> { /* fall through to slow path */ }
            }
        }

        // Slow path: materialise a Reference and dispatch on its actual type.
        val ref: Reference = getCurrentReference() ?: root

        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT,
            PolymorphicKind.SEALED, PolymorphicKind.OPEN -> {
                if (ref.isMap) {
                    pushMapObject(ref.toMap(), descriptor)
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

    /**
     * Push a MAP_OBJECT context with the field index cache resolved.
     * Hoisted out of beginStructure() so both fast and slow paths share it.
     */
    private fun pushMapObject(map: FlexMap, descriptor: SerialDescriptor) {
        val allFieldsPresent = map.size == descriptor.elementsCount
        val candidate = if (allFieldsPresent) getOrBuildFieldIndices(descriptor) else null
        // Equal field count is not a schema guarantee: a rename can otherwise route
        // a value into the wrong descriptor slot silently. ASCII keyEquals is allocation-
        // free; Unicode falls back only on the compatibility path.
        val indices = if (candidate != null && candidate.indices.all { descriptorIndex ->
                map.keyEquals(candidate[descriptorIndex], descriptor.getElementName(descriptorIndex))
            }) candidate else null
        contextStack.push(
            ContextType.MAP_OBJECT,
            mapRef = map,
            size = map.size,
            fieldIndices = indices
        )
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> tryDecodeRegisteredCoder(serialName: String): T? {
        val coder = FlexCoderRegistry.getBySerialName<Any>(serialName) ?: return null
        val ref = consumeCurrentReference() ?: return null
        return coder.decode(ref) as T
    }

    private fun consumeCurrentReference(): Reference? {
        val ctx = currentContext
        val mi = ctx?.currentMapIndex ?: -1
        if (mi >= 0) {
            val ref = ctx!!.mapRef!!.get(mi)
            ctx.currentMapIndex = -1
            return ref
        }
        return getCurrentReference()
    }

    private fun consumeMapKey(): String? {
        val ctx = currentContext ?: return null
        if (ctx.type != ContextType.MAP_ENTRIES) return null
        val key = ctx.currentMapKey ?: return null
        ctx.currentMapKey = null
        return key
    }

    private fun tryDecodeBulkValue(descriptor: SerialDescriptor): Any? {
        val ctx = currentContext
        if (ctx?.type == ContextType.MAP_ENTRIES && ctx.currentMapKey != null) return null

        // Check if this is a bulk-decodable type BEFORE consuming currentMapIndex.
        // Non-bulk types (e.g. Array<ComplexType>) must leave currentMapIndex intact
        // for beginStructure() to resolve the correct nested FlexBuffer node.
        val serialName = descriptor.serialName
        if (serialName != "kotlin.ByteArray" && serialName != "kotlin.ShortArray" &&
            serialName != "kotlin.IntArray" && serialName != "kotlin.LongArray" &&
            serialName != "kotlin.FloatArray" && serialName != "kotlin.DoubleArray" &&
            serialName != "kotlin.UByteArray" && serialName != "kotlin.UShortArray" &&
            serialName != "kotlin.UIntArray" && serialName != "kotlin.ULongArray"
        ) return null

        // Resolve Reference, consuming currentMapIndex
        val mi = ctx?.currentMapIndex ?: -1
        val ref: Reference
        if (mi >= 0) {
            ref = ctx!!.mapRef!!.get(mi)
            ctx.currentMapIndex = -1
        } else {
            ref = getCurrentReference() ?: return null
        }

        return when (serialName) {
            "kotlin.ByteArray" -> if (ref.isBlob) ref.toBlob().toByteArray() else ref.toByteArray()
            "kotlin.ShortArray" -> ref.toShortArray()
            "kotlin.IntArray" -> ref.toIntArray()
            "kotlin.LongArray" -> ref.toLongArray()
            "kotlin.FloatArray" -> ref.toFloatArray()
            "kotlin.DoubleArray" -> ref.toDoubleArray()
            "kotlin.UByteArray" -> ref.toUByteArray()
            "kotlin.UShortArray" -> ref.toUShortArray()
            "kotlin.UIntArray" -> ref.toUIntArray()
            "kotlin.ULongArray" -> ref.toULongArray()
            else -> null // unreachable due to early check above
        }
    }
}

/**
 * Type of decoding context.
 */
enum class ContextType {
    ROOT,           // Root level
    MAP_OBJECT,     // Decoding object fields from a map
    VECTOR,         // Decoding vector elements (O(1) indexed access)
    MAP_ENTRIES     // Decoding map as key-value pairs (for Map<K,V>)
}

/**
 * Mutable context for tracking position during decoding.
 * Instances are pooled in DecodingContextStack to avoid GC pressure.
 *
 * [fieldIndices]: cached descriptor-index → map-position mapping.
 * Set only when map.size == descriptor.elementsCount (all fields present).
 *
 * [currentMapIndex]: the map position index for the current field.
 * Set by decodeElementIndex() fast path. Read by decodeXxx() for direct scalar access.
 * Value -1 means "not set" — fall back to currentRef.
 */
class DecodingContext {
    @JvmField var type: ContextType = ContextType.ROOT
    @JvmField var size: Int = 0

    @JvmField var mapRef: FlexMap? = null
    @JvmField var fieldIndex: Int = 0
    @JvmField var fieldIndices: IntArray? = null
    @JvmField var currentMapIndex: Int = -1

    @JvmField var vectorRef: Vector? = null
    @JvmField var vectorIndex: Int = 0
    // Direct vector-index reads: when set (>=0), decodeXxx reads via vec.readXxx(idx)
    // bypassing Reference allocation entirely.
    @JvmField var currentVectorIndex: Int = -1

    @JvmField var mapEntryIndex: Int = 0
    @JvmField var currentMapKey: String? = null

    @JvmField var currentRef: Reference? = null

    fun reset(
        type: ContextType,
        mapRef: FlexMap? = null,
        vectorRef: Vector? = null,
        size: Int = 0,
        fieldIndices: IntArray? = null
    ) {
        this.type = type
        this.mapRef = mapRef
        this.vectorRef = vectorRef
        this.size = size
        this.fieldIndex = 0
        this.fieldIndices = fieldIndices
        this.currentMapIndex = -1
        this.currentVectorIndex = -1
        this.vectorIndex = 0
        this.mapEntryIndex = 0
        this.currentMapKey = null
        this.currentRef = null
    }
}

/**
 * Pre-allocated stack of DecodingContext objects.
 * Grows by doubling when capacity is exceeded, but never shrinks — contexts are reused.
 */
class DecodingContextStack(initialCapacity: Int = 4) {
    private val contexts = ArrayList<DecodingContext>(initialCapacity)
    private var size = 0
    // Lazy allocation: do NOT pre-fill with DecodingContext instances.
    // For typical workloads, decode depth is 2-3 levels — pre-allocating 16 instances
    // showed up as 22% of allocations in raw-decode flamegraph. Allocate on first push instead.

    fun push(
        type: ContextType,
        mapRef: FlexMap? = null,
        vectorRef: Vector? = null,
        size: Int = 0,
        fieldIndices: IntArray? = null
    ) {
        // Allocate exactly one more context if we need it. Steady-state depth
        // is 2-3 so we won't allocate after the first few decodes; sparing the
        // doubling avoids over-allocation for short-lived decoders.
        if (this.size >= contexts.size) {
            contexts.add(DecodingContext())
        }
        contexts[this.size].reset(type, mapRef, vectorRef, size, fieldIndices)
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
