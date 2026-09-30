package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.KeyType
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.graph.navigation.NavCommand
import dev.shibasis.reaktor.graph.navigation.Pop
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.navigation.Replace
import dev.shibasis.reaktor.graph.navigation.Return
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
open class RouteBinding<P: Payload>(
    initial: P
) {
    val payload = MutableStateFlow(initial)
    var dispatch: (NavCommand) -> Unit = {}
        internal set

    fun<T: Payload> NavigationEdge<T>.push(payload: T) {
        dispatch(Push(this, payload))
    }

    fun<T: Payload> NavigationEdge<T>.replace(payload: T) {
        dispatch(Replace(this, payload))
    }

    @JsName("pop")
    fun back() {
        dispatch(Pop)
    }

    @JsName("backWithResult")
    fun<R> back(result: R) {
        dispatch(Return(result))
    }
}

typealias Binding = RouteBinding<Payload>

@JsExport
interface NavBinding<P: Payload> {
    @JsName("updateFn")
    fun update(fn: (P) -> P)
    fun update(payload: Payload) = update { payload as P }
}


typealias Binder<P, Binding> = (RouteNode<P, Binding>) -> Binding

@JsExport
open class RouteNode<P: Payload, Binding: RouteBinding<P>>(
    graph: Graph,
    val pattern: RoutePattern,
    portName: String,
    binder: Binder<P, Binding>
): Node(graph) {
    @JsName("constructNamed")
    constructor(graph: Graph, pattern: String, portName: String, binder: (RouteNode<P, Binding>) -> Binding):
            this(graph, RoutePattern.from(pattern), portName, binder)

    @JsName("construct")
    constructor(graph: Graph, pattern: String, binder: (RouteNode<P, Binding>) -> Binding):
            this(graph, RoutePattern.from(pattern), "routeBinding", binder)


    private val navigationEdges = mutableMapOf<String, NavigationEdge<out Payload>>()

    private val binding = binder(this).apply { dispatch = graph::dispatch }

    // todo must allow only one stateful node to connect.
    val routeBinding = registerProvider(KeyType(Key(portName), Type(binding)), binding)

    val navBinding by provides<NavBinding<P>>(object: NavBinding<P> {
        override fun update(fn: (P) -> P) {
            binding.payload.update(fn)
        }
    })

    fun attachedNodes(): List<Routable> =
        routeBinding.edges.values
            .mapNotNull { it.source as? Routable }
            .distinct()

    fun navigationTargets(): List<RouteNode<*, *>> =
        consumerPorts.flattenedValues()
            .mapNotNull { consumer ->
                val edge = consumer.edge ?: return@mapNotNull null
                if (edge.provider.impl !is NavBinding<*>) {
                    return@mapNotNull null
                }
                edge.destination as? RouteNode<*, *>
            }
            .distinct()

    fun attachedNode(): Routable? {
        return attachedNodes().firstOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun <D: Payload> edge(
        destination: RouteNode<D, *>
    ): NavigationEdge<D> =
        navigationEdges.getOrPut(destination.id.toString()) {
            NavigationEdge(this, destination)
        } as NavigationEdge<D>

    companion object {
        operator fun invoke(graph: Graph, pattern: String) =
            RouteNode(graph, pattern) { RouteBinding(Payload()) }
    }

    override fun toString(): String {
        return "${super.toString()} [Route] pattern='$pattern'"
    }
}


fun<P: Payload, Binding: RouteBinding<P>> Graph.Route(pattern: String, binder: Binder<P, Binding>): RouteNode<P, Binding> {
    val node = RouteNode(this, pattern, binder)
    attach(node)
    return node
}
