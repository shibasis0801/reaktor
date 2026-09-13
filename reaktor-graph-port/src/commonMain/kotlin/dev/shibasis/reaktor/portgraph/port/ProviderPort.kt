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
    val edges: PortEdges<Functionality> = PortEdges()
): Port<Functionality>(owner, key, type), AutoCloseable {

    @JsName("create")
    constructor(owner: PortCapability, key: String, impl: Functionality):
            this(owner, Key(key), Type(impl), impl)

    override fun isConnected() = edges.isNotEmpty()

    @JsName("target")
    inline operator fun invoke(): Functionality = impl
    /** K1 — see [ConsumerPort.invoke]. */
    @Suppress("UNCHECKED_CAST")
    operator fun<R> invoke(fn: Functionality.() -> R): R {
        val chain = interceptorChain() ?: return fn(impl)
        return runInterceptors(chain, PortInvocation(this)) { fn(impl) } as R
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun<R> suspended(fn: suspend Functionality.() -> R): R {
        val chain = interceptorChain() ?: return fn(impl)
        return runInterceptorsSuspend(chain, PortInvocation(this)) { fn(impl) } as R
    }

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
    // One atomic registration rather than read-then-write, so two callers racing the same port
    // agree on a single winner and Created is announced exactly once.
    val registration = providerPorts.putIfAbsent(type, key) { ProviderPort(this, key, type, impl) as ProviderPort<Any> }
    val port = registration.value as ProviderPort<Functionality>
    require(port.impl === impl) {
        "Provider already registered for key='${key.key}' type='${type.type}' with a different implementation."
    }
    if (registration.created) emit(PortEvent.Created(port))
    return port
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.getProvider(key: Key, type: Type): ProviderPort<Functionality>? {
    return providerPorts.get(type, key) as? ProviderPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerProvider(key: String = "", impl: Functionality): ProviderPort<Functionality> {
    return registerProvider(Key(key), Type<Functionality>(), impl)
}

inline fun <reified Functionality: Any> PortCapability.provides(impl: Functionality, name: String? = null) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerProvider(name ?: property.name, impl)
        PortDelegate { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.getProvider(key: String = ""): ProviderPort<Functionality>? {
    return getProvider(Key(key), Type<Functionality>())
}
