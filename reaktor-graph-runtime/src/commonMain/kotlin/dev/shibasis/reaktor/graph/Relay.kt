package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.port.getProvider
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider

interface Provides<out T : Any> {
    val port: ProviderPort<out T>
}

class Relay<out T : Any>(graph: Graph, val source: Provides<T>) : BasicNode(graph), Provides<T> {
    val origin: Provides<T> = if (source is Relay) source.origin else source
    private val relayed = origin.port.owner as? Node

    init {
        source.port.owner.providerPorts.flattenedValues().forEach { upstream ->
            connect(registerConsumer<Any>(upstream.key, upstream.type), upstream).getOrThrow()
            registerProvider(upstream.key, upstream.type, upstream.impl)
        }
    }

    override val port: ProviderPort<out T> = checkNotNull(getProvider<T>(source.port.key, source.port.type))

    override val label: String get() = relayed?.run { label.ifBlank { this::class.simpleName.orEmpty() } }.orEmpty()

    override val kind: NodeKind get() = relayed?.kind ?: NodeKind.Node
}

fun <T : Any> Graph.relay(source: Provides<T>): Relay<T> = Node { Relay(it, source) }
