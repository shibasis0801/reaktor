@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core.port

import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.atomicfu.atomic
import kotlin.js.ExperimentalJsStatic
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.JsStatic
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass

// ------- Provider/Consumer ports at nodes allow edges for interface based communication -------
@JsExport
sealed class Port<Functionality: Any>(
    val owner: PortCapability,
    val key: Key,
    val type: Type
): Visitable {
    abstract fun isConnected(): Boolean
    val node: Node
        get() = owner as Node

    @JsName("createWithStrings")
    constructor(owner: PortCapability, key: String, type: String)
            : this(owner, Key(key), Type(type))

    override fun toString(): String {
        val ownerId = (owner as? Unique)?.let { "${it.label} (${it.id})" } ?: "Unknown"
        return "[${this::class.simpleName}] key='${key.key}' type='${type.type}' owner='$ownerId'"
    }

    val qualifier = "Port:$key:$type"
}



@JsExport
data class Key(val key: String)

@JsExport
data class Type(val type: String, val kClass: KClass<*>? = null) {
    companion object {
        val _sequence = atomic(0)
        @JsExport.Ignore
        inline fun<reified T> Type() = Type(
            T::class.simpleName ?: "anonymous_${_sequence.getAndIncrement()}",
            T::class
        )

        @JsExport.Ignore
        fun<T: Any> Type(value: T) = Type(
            value::class.simpleName ?: "anonymous_${_sequence.getAndIncrement()}",
            value::class
        )
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
