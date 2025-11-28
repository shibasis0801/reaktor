package dev.shibasis.reaktor.graph.core.port

import dev.shibasis.reaktor.graph.core.edge.Edge
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.port.Type.Companion.Type
import kotlin.js.JsExport
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

@JsExport
class ConsumerPort<Functionality: Any>(
    owner: PortCapability,
    key: Key,
    type: Type
): Port<Functionality>(owner, key, type), AutoCloseable {
    var edge: Edge<Functionality>? = null
        internal set

    val impl: Functionality?
        get() = edge?.provider?.impl

    override fun isConnected() = impl != null

    fun __guard() {
        require(isConnected()) {
            "Can't invoke functions through unconnected ports. ${toString()}, \n Owner: ${owner as Node}."
        }
    }

    inline operator fun<R> invoke(fn: Functionality.() -> R): R {
        __guard()
        return fn(impl!!)
    }

    suspend inline fun<R> suspended(fn: suspend Functionality.() -> R): R {
        __guard()
        return fn(impl!!)
    }

    override fun close() {
        edge?.provider?.edges?.remove(this)
        edge = null
    }

    override fun toString(): String {
        val connectionState = if (isConnected()) "Connected -> ${edge?.id}" else "Unconnected"
        return "${super.toString()} $connectionState"
    }
}



fun <Functionality: Any> PortCapability.registerConsumer(key: Key, type: Type): ConsumerPort<Functionality> {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { ConsumerPort(this, key, type) } as ConsumerPort<Functionality>
}

fun <Functionality: Any> PortCapability.getConsumer(key: Key, type: Type): ConsumerPort<Functionality>? {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .get(key) as? ConsumerPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerConsumer(key: String = ""): ConsumerPort<Functionality> {
    return registerConsumer(Key(key), Type<Functionality>())
}

inline fun <reified Functionality: Any> PortCapability.consumes() =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerConsumer<Functionality>(property.name)
        ReadOnlyProperty { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.getConsumer(key: String = ""): ConsumerPort<Functionality>? {
    return getConsumer(Key(key), Type<Functionality>())
}
