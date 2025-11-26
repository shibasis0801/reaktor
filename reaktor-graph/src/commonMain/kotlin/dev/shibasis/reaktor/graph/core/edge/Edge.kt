@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core.edge

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.graph.capabilities.NavCommand
import dev.shibasis.reaktor.graph.capabilities.NavigationCapability
import dev.shibasis.reaktor.graph.capabilities.Payload
import dev.shibasis.reaktor.graph.capabilities.Push
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.port.PortCapability
import dev.shibasis.reaktor.graph.core.port.PortEvent
import dev.shibasis.reaktor.graph.core.port.ProviderPort
import dev.shibasis.reaktor.graph.core.port.RequirerPort
import dev.shibasis.reaktor.graph.core.node.NavBinding
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.port.provides
import dev.shibasis.reaktor.graph.core.port.registerRequirer
import dev.shibasis.reaktor.graph.core.port.requires
import dev.shibasis.reaktor.graph.ui.ComposeNode
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport


/*
Must allow
1. Accessing Functionality from another node
2. Navigating to another node (screens, ui)
3. DAG execution dependencies forward/backward
4. Remote communication over multiple transports using Service Abstraction
5. decorate contract with permissions / arrow-kt resilience / circuit breakers / retries, etc (maybe as middleware for invoke/suspended)
*/
@JsExport
open class Edge<Contract: Any>(
    val source: PortCapability,
    val requirer: RequirerPort<Contract>,
    val destination: PortCapability,
    val provider: ProviderPort<Contract>
): Unique by UniqueImpl(), Visitable {
    init {
        requirer.edge = this
        provider.edges[requirer] = this

        source.emit(PortEvent.Connected(requirer, provider))
        destination.emit(PortEvent.Connected(provider, requirer))
    }

    inline operator fun<R> invoke(fn: Contract.() -> R): R = provider.invoke(fn)
    suspend inline fun<R> suspended(fn: suspend Contract.() -> R): R = provider.suspended(fn)
}

@JsExport
class NavigationEdge<P: Payload>(
    val start: RouteNode<*, *>,
    val end: RouteNode<P, *>
): Edge<NavBinding<P>>(
    start,
    start.registerRequirer<NavBinding<P>>(end.id.toString()),
    end,
    end.navBinding
) {
    init {
        requirer.edge = this
        provider.edges[requirer] = this

        source.emit(PortEvent.Connected(requirer, provider))
        destination.emit(PortEvent.Connected(provider, requirer))
    }
}


class HomePayload: Payload()
class ChatPayload: Payload()
class OnboardingPayload: Payload()
class EventPayload: Payload()

interface HomeBinding: RouteBinding<HomePayload> {
    val chatEdge: NavigationEdge<ChatPayload>
    val onboardingEdge: NavigationEdge<OnboardingPayload>
    val eventEdge: NavigationEdge<EventPayload>
}
lateinit var chatRoute: RouteNode<ChatPayload, RouteBinding<ChatPayload>>
lateinit var onboardingRoute: RouteNode<OnboardingPayload, RouteBinding<OnboardingPayload>>
lateinit var eventRoute: RouteNode<EventPayload, RouteBinding<EventPayload>>

class HomeRoute(
    graph: Graph,
    binder: (RouteNode<HomePayload, HomeBinding>) -> HomeBinding
): RouteNode<HomePayload, HomeBinding>(
    graph, "/home", binder
)

val homeRoute = { graph: Graph ->
    HomeRoute(graph) { node ->
        object: HomeBinding {
            override val payload = MutableStateFlow(HomePayload())
            override val chatEdge = node.edge(chatRoute)
            override val onboardingEdge = node.edge(onboardingRoute)
            override val eventEdge = node.edge(eventRoute)
        }
    }
}

class HomeNode(
    graph: Graph
): ComposeNode<Unit>(graph) {
    override val state = MutableStateFlow(Unit)
    override val routeBinding by requires<HomeBinding>()

    init {
        routeBinding {
            graph.dispatch(Push(chatEdge, ChatPayload()))
            val result = CompletableDeferred<String>()
            graph.dispatch(Push(onboardingEdge, OnboardingPayload(), result))

            invoke<ConcurrencyCapability> {
                launch {
                    val x = result.await()
                }
            }
        }
    }

    @Composable
    override fun Content(content: @Composable (() -> Unit)) {
        routeBinding {

        }
    }
}

