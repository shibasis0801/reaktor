package dev.shibasis.reaktor.flexbuffer.core

import dev.shibasis.reaktor.flexbuffer.flatbuffers.Map
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Vector

class FlexIntList(val vec: Vector) : AbstractList<Int>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readInt(index)
}

class FlexLongList(val vec: Vector) : AbstractList<Long>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readLong(index)
}

class FlexDoubleList(val vec: Vector) : AbstractList<Double>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readDouble(index)
}

class FlexFloatList(val vec: Vector) : AbstractList<Float>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readDouble(index).toFloat()
}

class FlexStringList(val vec: Vector) : AbstractList<String>() {
    override val size get() = vec.size
    override fun get(index: Int) = vec.readString(index)
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
        get() = (0 until map.size).map { i ->
            object : kotlin.collections.Map.Entry<String, Int> {
                override val key get() = map.keyAsString(i)
                override val value get() = map.getInt(i)
            }
        }.toSet()
}

class FlexStringDoubleMap(val map: Map) : AbstractMap<String, Double>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, Double>>
        get() = (0 until map.size).map { i ->
            object : kotlin.collections.Map.Entry<String, Double> {
                override val key get() = map.keyAsString(i)
                override val value get() = map.getDouble(i)
            }
        }.toSet()
}

class FlexStringStringMap(val map: Map) : AbstractMap<String, String>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, String>>
        get() = (0 until map.size).map { i ->
            object : kotlin.collections.Map.Entry<String, String> {
                override val key get() = map.keyAsString(i)
                override val value get() = map.getString(i)
            }
        }.toSet()
}

class FlexStringBooleanMap(val map: Map) : AbstractMap<String, Boolean>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, Boolean>>
        get() = (0 until map.size).map { i ->
            object : kotlin.collections.Map.Entry<String, Boolean> {
                override val key get() = map.keyAsString(i)
                override val value get() = map.getBoolean(i)
            }
        }.toSet()
}

class FlexAccessorMap<T>(val map: Map, val wrap: (Map) -> T) : AbstractMap<String, T>() {
    override val entries: Set<kotlin.collections.Map.Entry<String, T>>
        get() = (0 until map.size).map { i ->
            object : kotlin.collections.Map.Entry<String, T> {
                override val key get() = map.keyAsString(i)
                override val value get() = wrap(map.getMap(i))
            }
        }.toSet()
}
