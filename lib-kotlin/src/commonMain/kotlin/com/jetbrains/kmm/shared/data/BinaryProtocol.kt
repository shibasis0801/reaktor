package com.jetbrains.kmm.shared.data

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.JSONParser
import com.google.flatbuffers.kotlin.Reference
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

object FlexConverterRegistry {
    val delegates: MutableMap<KClass<*>, FlexBufferTypeConverter<*>> = mutableMapOf()

    // Register a delegate for a specific type
    fun <T: Any> register(clazz: KClass<T>, delegate: FlexBufferTypeConverter<T>) {
        delegates[clazz] = delegate
    }

    // Retrieve a delegate for a specific type
    inline fun <reified T: Any> get(): FlexBufferTypeConverter<T> {
        if (!delegates.contains(T::class))
            throw NullPointerException("No converter for ${T::class}")
        return delegates[T::class] as FlexBufferTypeConverter<T>
    }
}

fun t() {
    val converter = FlexConverterRegistry.get<String>()
    val parser = JSONParser()
}
data class FlexConversionError(val type: String): Exception("Wrong type converter called, $type")
interface FlexBufferTypeConverter<T> {
    fun throwTypeError(type: String) { throw FlexConversionError(type) }
    fun fromBinary(ref: Reference): T
    fun toBinary(builder: FlexBuffersBuilder, data: T)
}

@Serializable
data class Person(val id: Int, val name: String)
object PersonConverter: FlexBufferTypeConverter<Person> {
    private val intConverter by lazy { FlexConverterRegistry.get<Int>() }
    private val stringConverter by lazy { FlexConverterRegistry.get<String>() }

    override fun fromBinary(ref: Reference): Person {
        if (!ref.isMap) throwTypeError("PersonConverter")
        val id = intConverter.fromBinary(ref["id"])
        val name = stringConverter.fromBinary(ref["name"])

        return Person(id, name)
    }

    override fun toBinary(builder: FlexBuffersBuilder, data: Person) {
        builder.apply {
            putMap {
                this["id"] = data.id
                this["name"] = data.name
            }
        }
    }
}

fun registerConverters() {
    FlexConverterRegistry.register(Int::class, IntConverter)
    FlexConverterRegistry.register(String::class, StringConverter)
    FlexConverterRegistry.register(Person::class, PersonConverter)
}


object IntConverter: FlexBufferTypeConverter<Int> {
    override fun fromBinary(ref: Reference): Int {
        if (!ref.isInt) throwTypeError("Int")
        return ref.toInt()
    }

    override fun toBinary(builder: FlexBuffersBuilder, data: Int) {
        builder.put(data)
    }
}
object StringConverter: FlexBufferTypeConverter<String> {
    override fun fromBinary(ref: Reference): String {
        if (!ref.isString) throwTypeError("String")
        return ref.toString()
    }

    override fun toBinary(builder: FlexBuffersBuilder, data: String) {
        builder.put(data)
    }
}

object IntArrayFlexConverter: FlexBufferTypeConverter<ArrayList<Int>> {
    override fun fromBinary(ref: Reference): ArrayList<Int> {
        if (!ref.isVector) throwTypeError("Vector")

        val vector = ref.toVector()
        val result = ArrayList<Int>()
        val size = result.size
        println(result.size)
        val indices = vector.indices
        println(indices)
        for (i in indices) {
            val element = vector[i]
            if (!element.isInt) throwTypeError("Int in Vector")
            result.add(element.toInt())
        }

        return result
    }

    override fun toBinary(builder: FlexBuffersBuilder, data: ArrayList<Int>) {
        val start = builder.startVector()
        data.forEach { builder.put(it) }
        builder.endVector(start)
    }
}