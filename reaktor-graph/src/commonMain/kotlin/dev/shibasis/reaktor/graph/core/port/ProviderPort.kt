package dev.shibasis.reaktor.graph.core.port

import dev.shibasis.reaktor.graph.core.edge.Edge
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.port.Type.Companion.Type
import kotlin.js.JsExport
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty


@JsExport
class ProviderPort<Functionality: Any>(
    owner: PortCapability,
    key: Key,
    type: Type,
    val impl: Functionality,
    val edges: LinkedHashMap<ConsumerPort<Functionality>, Edge<Functionality>> = linkedMapOf()
): Port<Functionality>(owner, key, type), AutoCloseable {

    constructor(owner: PortCapability, key: String, impl: Functionality):
            this(owner, Key(key), Type(impl), impl)

    override fun isConnected() = edges.isNotEmpty()

    inline operator fun<R> invoke(fn: Functionality.() -> R): R = fn(impl)
    suspend inline fun<R> suspended(fn: suspend Functionality.() -> R): R = fn(impl)

    override fun close() {
        edges.keys.forEach { it.edge = null }
        edges.clear()
    }

    override fun toString(): String {
        return "${super.toString()} Consumers=${edges.size}, \n Owner: ${owner as Node}."
    }
}

fun <Functionality: Any> PortCapability.registerProvider(key: Key, type: Type, impl: Functionality): ProviderPort<Functionality> {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { ProviderPort(this, key, type, impl) } as ProviderPort<Functionality>
}

fun <Functionality: Any> PortCapability.getProvider(key: Key, type: Type): ProviderPort<Functionality>? {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .get(key) as? ProviderPort<Functionality>
}
inline fun <reified Functionality: Any> PortCapability.registerProvider(key: String = "", impl: Functionality): ProviderPort<Functionality> {
    return registerProvider(Key(key), Type<Functionality>(), impl)
}

inline fun <reified Functionality: Any> PortCapability.provides(impl: Functionality) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerProvider(property.name, impl)
        ReadOnlyProperty { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.getProvider(key: String = ""): ProviderPort<Functionality>? {
    return getProvider(Key(key), Type<Functionality>())
}