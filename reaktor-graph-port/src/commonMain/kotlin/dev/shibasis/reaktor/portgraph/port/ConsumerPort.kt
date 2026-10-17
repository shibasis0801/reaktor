package dev.shibasis.reaktor.portgraph.port

import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.graph.disconnectInternal
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

@JsExport
open class ConsumerPort<Functionality: Any>(
    owner: PortCapability,
    key: Key,
    type: Type
): Port<Functionality>(owner, key, type), AutoCloseable {
    private var implementation: Functionality? = null
    val impl: Functionality?
        get() = implementation

    var edge: Edge<Functionality>? = null
        internal set(value) {
            field = value
            implementation = value?.provider?.impl
        }

    override fun isConnected() = implementation != null

    @JsName("target")
    inline operator fun invoke(): Functionality {
        return impl ?: error("Can't invoke functions through unconnected ports. ${toString()}")
    }

    /**
     * K1 — the call crosses the port here, so this is where it can be observed or altered.
     * Deliberately not `inline`: an inlined body leaves no frame to wrap. When nothing is
     * attached the cost is one volatile read.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun<R> invoke(fn: Functionality.() -> R): R {
        val target = impl ?: error("Can't invoke functions through unconnected ports. ${toString()}")
        val chain = interceptorChain() ?: return fn(target)
        return runInterceptors(chain, PortInvocation(this, edge)) { fn(target) } as R
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun<R> suspended(fn: suspend Functionality.() -> R): R {
        val target = impl ?: error("Can't invoke functions through unconnected ports. ${toString()}")
        val chain = interceptorChain() ?: return fn(target)
        return runInterceptorsSuspend(chain, PortInvocation(this, edge)) { fn(target) } as R
    }

    override fun close() {
        edge?.provider?.let { provider ->
            disconnectInternal(this, provider)
        }
    }

    override fun toString(): String {
        val connectionState = if (isConnected()) "Connected -> ${edge?.id}" else "Unconnected"
        return "${super.toString()} $connectionState"
    }
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerConsumer(key: Key, type: Type): ConsumerPort<Functionality> {
    // See registerProvider: one atomic registration, one Created event per port.
    val registration = consumerPorts.putIfAbsent(type, key) { ConsumerPort<Functionality>(this, key, type) as ConsumerPort<Any> }
    val port = registration.value as ConsumerPort<Functionality>
    if (registration.created) emit(PortEvent.Created(port))
    return port
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.getConsumer(key: Key, type: Type): ConsumerPort<Functionality>? {
    return consumerPorts.get(type, key) as? ConsumerPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerConsumer(key: String = ""): ConsumerPort<Functionality> {
    return registerConsumer(Key(key), Type<Functionality>())
}

inline fun <reified Functionality: Any> PortCapability.consumes(name: String? = null) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerConsumer<Functionality>(name ?: property.name)
        PortDelegate { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.getConsumer(key: String = ""): ConsumerPort<Functionality>? {
    return getConsumer(Key(key), Type<Functionality>())
}
