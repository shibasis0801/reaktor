@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.portgraph.port

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.visitor.Visitable
import kotlinx.atomicfu.atomic
import kotlin.js.ExperimentalJsStatic
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.JsStatic
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass

@JsExport
sealed class Port<Functionality: Any>(
    val owner: PortCapability,
    val key: Key,
    val type: Type
): Visitable {
    abstract fun isConnected(): Boolean

    @JsName("createWithStrings")
    constructor(owner: PortCapability, key: String, type: String)
            : this(owner, Key(key), Type(type))

    override fun toString(): String {
        val ownerId = (owner as? Unique)?.let { "${it.label} (${it.id})" } ?: "Unknown"
        return "[${this::class.simpleName}] key='${key.key}' type='${type.type}' owner='$ownerId'"
    }

    val qualifier = "Port:$key:$type"
}

private val _sequence = atomic(0)
fun KClass<*>.name(): String {
    val simple = simpleName
    if (!simple.isNullOrBlank()) return simple
    return "anonymous_${_sequence.getAndIncrement()}"
}

@JsExport
data class Key(val key: String)

@JsExport
data class Type(val type: String, val kClass: KClass<*>? = null) {
    companion object {
        fun create(kClass: KClass<*>) = Type(kClass.name(), kClass)

        @JsExport.Ignore
        inline fun<reified T> Type() = create(T::class)

        @JsExport.Ignore
        fun<T: Any> Type(value: T) = create(value::class)
    }

    override fun toString() = type
}

@JsExport
data class KeyType(val key: Key, val type: Type) {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        operator fun invoke(key: String, type: String) = KeyType(Key(key), Type(type))
    }
}

typealias TypedKeyedMap<Value> = MutableMap<Type, MutableMap<Key, Value>>
inline fun<reified Port> TypedKeyedMap<Port>.flattenedValues() = values.flatMap { it.values }

typealias PortDelegate<Port> = ReadOnlyProperty<PortCapability, Port>
