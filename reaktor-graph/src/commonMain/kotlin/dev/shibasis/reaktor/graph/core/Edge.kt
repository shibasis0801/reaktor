@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
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
class NavigationEdge<P: Props>(
    val start: RouteNode<*, *>,
    val end: RouteNode<P, out RouteBinding<P>>
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

fun <P: Props> RouteNode<*, *>.navigationEdge(
    destination: RouteNode<P, out RouteBinding<P>>
) = NavigationEdge(this, destination)


class HomeProps: Props()
class ChatProps: Props()
class OnboardingProps: Props()
class EventProps: Props()

interface HomeBinding: RouteBinding<HomeProps> {
    val chatEdge: NavigationEdge<ChatProps>
    val onboardingEdge: NavigationEdge<OnboardingProps>
    val eventEdge: NavigationEdge<EventProps>
}

class HomeRoute(
    graph: Graph,
    chatRoute: RouteNode<ChatProps, *>,
    onboardingRoute: RouteNode<OnboardingProps, *>,
    eventRoute: RouteNode<EventProps, *>
):
    RouteNode<HomeProps, HomeBinding>(graph, "/home"),
    HomeBinding {

    override val props = MutableStateFlow(HomeProps())
    override val chatEdge = navigationEdge(chatRoute)
    override val onboardingEdge = navigationEdge(onboardingRoute)
    override val eventEdge = navigationEdge(eventRoute)

    override val routeBinding by provides<HomeBinding>(this)
}


class HomeNode(
    graph: Graph
): StatefulNode<Unit, HomeBinding>(graph) {
    override val state = MutableStateFlow(Unit)
    override val routeBinding by requires<HomeBinding>()

    init {
        routeBinding {
            navigate(NavCommand.Push(chatEdge, ChatProps()))
            val result = CompletableDeferred<String>()
            navigate(NavCommand.Push(onboardingEdge, OnboardingProps(), result))

            invoke<ConcurrencyCapability> {
                launch {
                    val x = result.await()
                }
            }
        }
    }
}

