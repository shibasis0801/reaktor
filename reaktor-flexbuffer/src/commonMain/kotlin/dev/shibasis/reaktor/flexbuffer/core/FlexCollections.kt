package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.Map
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Vector

class FlexIntList(val vec: Vector) : AbstractList<Int>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readInt(index)
    inline fun forEachValue(block: (Int) -> Unit) = vec.forEachInt(block)
}

class FlexLongList(val vec: Vector) : AbstractList<Long>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readLong(index)
    inline fun forEachValue(block: (Long) -> Unit) = vec.forEachLong(block)
    inline fun foldValues(initial: Long, operation: (acc: Long, value: Long) -> Long): Long =
        vec.foldLong(initial, operation)
}

class FlexDoubleList(val vec: Vector) : AbstractList<Double>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readDouble(index)
    inline fun forEachValue(block: (Double) -> Unit) = vec.forEachDouble(block)
}

class FlexFloatList(val vec: Vector) : AbstractList<Float>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readDouble(index).toFloat()
}

class FlexStringList(val vec: Vector) : AbstractList<String>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readString(index)
    fun byteLength(index: Int): Int = vec.readStringByteLength(index)
    inline fun forEachValue(block: (String) -> Unit) = vec.forEachString(block)
}

class FlexBooleanList(val vec: Vector) : AbstractList<Boolean>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readBoolean(index)
}

class FlexAccessorList<T>(val vec: Vector, val wrap: (Map) -> T) : AbstractList<T>() {
    override val size get() = vec.size
    override fun get(index: Int) = wrap(vec.readMap(index))
}

class FlexStringIntMap(val map: Map) : AbstractMap<String, Int>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, Int>>
        get() = indexedEntries(map.size, { map.keyAsString(it) }, { map.getInt(it) })

    inline fun forEachEntry(block: (key: String, value: Int) -> Unit) = map.forEachStringInt(block)
}

class FlexStringDoubleMap(val map: Map) : AbstractMap<String, Double>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, Double>>
        get() = indexedEntries(map.size, { map.keyAsString(it) }, { map.getDouble(it) })

    inline fun forEachEntry(block: (key: String, value: Double) -> Unit) = map.forEachStringDouble(block)
}

class FlexStringStringMap(val map: Map) : AbstractMap<String, String>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, String>>
        get() = indexedEntries(map.size, { map.keyAsString(it) }, { map.getString(it) })

    inline fun forEachEntry(block: (key: String, value: String) -> Unit) = map.forEachStringString(block)
    fun valueByteLength(index: Int): Int = map.getStringByteLength(index)
}

class FlexStringBooleanMap(val map: Map) : AbstractMap<String, Boolean>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, Boolean>>
        get() = indexedEntries(map.size, { map.keyAsString(it) }, { map.getBoolean(it) })

    inline fun forEachEntry(block: (key: String, value: Boolean) -> Unit) = map.forEachStringBoolean(block)
}

class FlexAccessorMap<T>(val map: Map, val wrap: (Map) -> T) : AbstractMap<String, T>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, T>>
        get() = indexedEntries(map.size, { map.keyAsString(it) }, { wrap(map.getMap(it)) })

    inline fun forEachEntry(block: (key: String, value: T) -> Unit) {
        for (i in 0 until map.size) block(map.keyAsString(i), wrap(map.getMap(i)))
    }
}

private inline fun <V> indexedEntries(
    entryCount: Int,
    crossinline keyAt: (Int) -> String,
    crossinline valueAt: (Int) -> V
): Set<kotlin.collections.Map.Entry<String, V>> =
    object : AbstractSet<kotlin.collections.Map.Entry<String, V>>() {
        override val size: Int get() = entryCount

        override fun iterator(): Iterator<kotlin.collections.Map.Entry<String, V>> =
            object : Iterator<kotlin.collections.Map.Entry<String, V>> {
                private var index = 0

                override fun hasNext(): Boolean = index < entryCount

                override fun next(): kotlin.collections.Map.Entry<String, V> {
                    if (!hasNext()) throw NoSuchElementException()
                    val current = index++
                    return object : kotlin.collections.Map.Entry<String, V> {
                        override val key: String get() = keyAt(current)
                        override val value: V get() = valueAt(current)
                    }
                }
            }
    }
