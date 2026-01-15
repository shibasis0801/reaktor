package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.graph.core.port.Key
import dev.shibasis.reaktor.graph.core.port.KeyType
import dev.shibasis.reaktor.graph.core.port.ProviderPort
import dev.shibasis.reaktor.graph.core.port.Type.Companion.Type
import dev.shibasis.reaktor.graph.core.port.provides
import dev.shibasis.reaktor.graph.navigation.NavCommand
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


    private val binding = binder(this).apply { dispatch = graph::dispatch }

    // todo must allow only one stateful node to connect.
    val routeBinding = registerProvider(KeyType(Key(portName), Type(binding)), binding)

    val navBinding by provides<NavBinding<P>>(object: NavBinding<P> {
        override fun update(fn: (P) -> P) {
            binding.payload.update(fn)
        }
    })

    fun attachedNode(): ControllerNode<*>? {
        val edge = routeBinding.edges.values.firstOrNull()
        val bound = edge?.source ?: return null
        if (bound !is ControllerNode<*>)
            return null

        return bound
    }

    fun <D: Payload> edge(
        destination: RouteNode<D, *>
    ) = NavigationEdge(this, destination)

    companion object {
        operator fun invoke(graph: Graph, pattern: String) =
            RouteNode(graph, pattern) { RouteBinding(Payload()) }
    }

    override fun toString(): String {
        return "${super.toString()} [Route] pattern='$pattern'"
    }
}


fun<P: Payload, Binding: RouteBinding<P>> Graph.Route(pattern: String, binder: Binder<P, Binding>) =
    RouteNode(this, pattern, binder)