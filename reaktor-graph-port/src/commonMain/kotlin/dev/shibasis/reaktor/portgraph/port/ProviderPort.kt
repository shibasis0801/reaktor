package dev.shibasis.reaktor.portgraph.port

import dev.shibasis.reaktor.portgraph.graph.disconnectInternal
import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty


@JsExport
open class ProviderPort<Functionality: Any>(
    owner: PortCapability,
    key: Key,
    type: Type,
    val impl: Functionality,
    val edges: LinkedHashMap<ConsumerPort<Functionality>, Edge<Functionality>> = linkedMapOf()
): Port<Functionality>(owner, key, type), AutoCloseable {

    @JsName("create")
    constructor(owner: PortCapability, key: String, impl: Functionality):
            this(owner, Key(key), Type(impl), impl)

    override fun isConnected() = edges.isNotEmpty()

    inline operator fun<R> invoke(fn: Functionality.() -> R): R = fn(impl)
    suspend inline fun<R> suspended(fn: suspend Functionality.() -> R): R = fn(impl)

    override fun close() {
        edges.keys.toList().forEach { consumer ->
            disconnectInternal(consumer, this)
        }
        edges.clear()
    }

    override fun toString(): String {
        return "${super.toString()} Consumers=${edges.size}"
    }
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerProvider(key: Key, type: Type, impl: Functionality): ProviderPort<Functionality> {
    val ports = providerPorts.getOrPut(type) { linkedMapOf() }
    val existing = ports[key] as? ProviderPort<Functionality>
    if (existing != null) {
        require(existing.impl === impl) {
            "Provider already registered for key='${key.key}' type='${type.type}' with a different implementation."
        }
        return existing
    }

    val created = ProviderPort(this, key, type, impl)
    ports[key] = created as ProviderPort<Any>
    emit(PortEvent.Created(created))
    return created
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.getProvider(key: Key, type: Type): ProviderPort<Functionality>? {
    return providerPorts[type]?.get(key) as? ProviderPort<Functionality>
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
