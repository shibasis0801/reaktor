package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.capabilities.NavCommand
import dev.shibasis.reaktor.graph.capabilities.NavigationCapability
import dev.shibasis.reaktor.graph.capabilities.Payload
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.graph.core.port.ProviderPort
import dev.shibasis.reaktor.graph.core.port.provides
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
}

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


    private val binding = binder(this)

    val routeBinding = ProviderPort(this, portName, binding)

    val navBinding by provides<NavBinding<P>>(object: NavBinding<P> {
        override fun update(fn: (P) -> P) {
            binding.payload.update(fn)
        }
    })

    @JsExport
    fun <P: Payload> edge(
        destination: RouteNode<P, *>
    ) = NavigationEdge(this, destination)

    companion object {
        operator fun invoke(graph: Graph, pattern: String) =
            RouteNode(graph, pattern) { RouteBinding(Payload()) }
    }
}
