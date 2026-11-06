package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.Provides
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.jvm.JvmName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class GraphOutput(val name: String, val ports: List<ProviderPort<Any>>)

fun <N : Node> Graph.provides(node: N) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, N>> { graph, property ->
    graph.offer(property.name, node, node.providerPorts.flattenedValues())
    ReadOnlyProperty { _, _ -> node }
}

fun <S : Provides<*>> Graph.provides(service: S) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, S>> { graph, property ->
    val owner = service.port.owner
    graph.offer(property.name, owner, owner.providerPorts.flattenedValues())
    ReadOnlyProperty { _, _ -> service }
}

@JvmName("providesServiceNode")
fun <S> Graph.provides(service: S) where S : Node, S : Provides<*> = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, S>> { graph, property ->
    graph.offer(property.name, service, service.providerPorts.flattenedValues())
    ReadOnlyProperty { _, _ -> service }
}

fun <T : Any> Graph.provides(port: ProviderPort<T>) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, ProviderPort<T>>> { graph, property ->
    graph.offer(property.name, port)
    ReadOnlyProperty { _, _ -> port }
}

@JvmName("providesOptional")
fun <T : Any> Graph.provides(port: ProviderPort<T>?) = PropertyDelegateProvider<Graph, ReadOnlyProperty<Graph, ProviderPort<T>?>> { graph, property ->
    port?.let { graph.offer(property.name, it) }
    ReadOnlyProperty { _, _ -> port }
}

@Suppress("UNCHECKED_CAST")
private fun Graph.offer(name: String, port: ProviderPort<*>) = offer(name, port.owner, listOf(port as ProviderPort<Any>))

operator fun <T : Any> ProviderPort<T>.getValue(thisRef: Any?, property: KProperty<*>): T = impl
