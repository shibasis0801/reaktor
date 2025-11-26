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

// todo needs improvements, very confusing for consumers.
@JsExport
interface RouteBinding<P: Payload> {
    val payload: MutableStateFlow<P>
}

@JsExport
interface NavBinding<P: Payload> {
    @JsName("updateFn")
    fun update(fn: (P) -> P)
    fun update(payload: Payload) = update { payload as P }
}


@JsExport
abstract class RouteNode<P: Payload, Binding: RouteBinding<P>>(
    graph: Graph,
    val pattern: RoutePattern,
    portName: String,
    binder: (RouteNode<P, Binding>) -> Binding
): Node(graph) {
    @JsName("constructNamed")
    constructor(graph: Graph, pattern: String, portName: String, binder: (RouteNode<P, Binding>) -> Binding):
            this(graph, RoutePattern.from(pattern), portName, binder)

    @JsName("construct")
    constructor(graph: Graph, pattern: String, binder: (RouteNode<P, Binding>) -> Binding):
            this(graph, RoutePattern.from(pattern), "routeBinding", binder)

    val routeBinding = ProviderPort(this, portName, binder(this))

    val navBinding by provides<NavBinding<P>>(object: NavBinding<P> {
        override fun update(fn: (P) -> P) {
            routeBinding.impl.payload.update(fn)
        }
    })

    @JsExport
    fun <P: Payload> edge(
        destination: RouteNode<P, *>
    ) = NavigationEdge(this, destination)
}
