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

    inline operator fun<R> invoke(fn: Functionality.() -> R): R {
        return fn(invoke())
    }

    suspend inline fun<R> suspended(fn: suspend Functionality.() -> R): R {
        return fn(invoke())
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
    val ports = consumerPorts.getOrPut(type) { linkedMapOf() }
    val existing = ports[key] as? ConsumerPort<Functionality>
    if (existing != null) {
        return existing
    }

    val created = ConsumerPort<Functionality>(this, key, type)
    ports[key] = created as ConsumerPort<Any>
    emit(PortEvent.Created(created))
    return created
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.getConsumer(key: Key, type: Type): ConsumerPort<Functionality>? {
    return consumerPorts[type]?.get(key) as? ConsumerPort<Functionality>
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
