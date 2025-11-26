package dev.shibasis.reaktor.graph.core.port

import dev.shibasis.reaktor.graph.core.edge.Edge
import dev.shibasis.reaktor.graph.core.port.Type.Companion.Type
import kotlin.js.JsExport
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty


@JsExport
class RequirerPort<Functionality: Any>(
    owner: PortCapability,
    key: Key,
    type: Type,
    var edge: Edge<Functionality>? = null
): Port<Functionality>(owner, key, type), AutoCloseable {
    val functionality: Functionality?
        get() = edge?.provider?.impl

    override fun isConnected() = functionality != null

    inline operator fun<R> invoke(fn: Functionality.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(functionality!!)
    }

    suspend inline fun<R> suspended(fn: suspend Functionality.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(functionality!!)
    }

    override fun close() {
        edge?.provider?.edges?.remove(this)
        edge = null
    }
}



fun <Functionality: Any> PortCapability.registerRequirer(key: Key, type: Type): RequirerPort<Functionality> {
    return requirerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { RequirerPort(this, key, type) } as RequirerPort<Functionality>
}

fun <Functionality: Any> PortCapability.getRequirer(key: Key, type: Type): RequirerPort<Functionality>? {
    return requirerPorts
        .getOrPut(type) { linkedMapOf() }
        .get(key) as? RequirerPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerRequirer(key: String = ""): RequirerPort<Functionality> {
    return registerRequirer(Key(key), Type<Functionality>())
}

inline fun <reified Functionality: Any> PortCapability.requires() =
    PropertyDelegateProvider<PortCapability, PortDelegate<RequirerPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerRequirer<Functionality>(property.name)
        ReadOnlyProperty { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.getRequirer(key: String = ""): RequirerPort<Functionality>? {
    return getRequirer(Key(key), Type<Functionality>())
}
