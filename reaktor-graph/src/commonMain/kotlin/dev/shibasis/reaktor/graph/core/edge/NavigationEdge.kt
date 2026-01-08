package dev.shibasis.reaktor.graph.core.edge

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Binder
import dev.shibasis.reaktor.graph.core.node.NavBinding
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.port.PortEvent
import dev.shibasis.reaktor.graph.core.port.registerConsumer
import dev.shibasis.reaktor.graph.core.port.consumes
import dev.shibasis.reaktor.graph.ui.ComposeNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport


@JsExport
class NavigationEdge<P: Payload>(
    val start: RouteNode<*, *>,
    val end: RouteNode<P, *>
): Edge<NavBinding<P>>(
    start,
    start.registerConsumer<NavBinding<P>>(end.id.toString()),
    end,
    end.navBinding
) {
    override fun toString(): String {
        return "[NavigationEdge] $id: ${start.pattern} -> ${end.pattern}"
    }
}

class HomePayload: Payload()
class ChatPayload: Payload()
class OnboardingPayload: Payload()
class EventPayload: Payload()

class HomeBinding(
    val chatEdge: NavigationEdge<ChatPayload>,
    val onboardingEdge: NavigationEdge<OnboardingPayload>,
    val eventEdge: NavigationEdge<EventPayload>
): RouteBinding<HomePayload>(HomePayload())

lateinit var chatRoute: RouteNode<ChatPayload, RouteBinding<ChatPayload>>
lateinit var onboardingRoute: RouteNode<OnboardingPayload, RouteBinding<OnboardingPayload>>
lateinit var eventRoute: RouteNode<EventPayload, RouteBinding<EventPayload>>

class HomeRoute(
    graph: Graph,
    binder: Binder<HomePayload, HomeBinding>
): RouteNode<HomePayload, HomeBinding>(
    graph, "/home", binder
)

val homeRoute = { graph: Graph ->
    HomeRoute(graph) { node ->
        HomeBinding(
            node.edge(chatRoute),
            node.edge(onboardingRoute),
            node.edge(eventRoute)
        )
    }
}

val x = { graph: Graph ->
    RouteNode(graph, "/") { node ->
        HomeBinding(
            node.edge(chatRoute),
            node.edge(onboardingRoute),
            node.edge(eventRoute)
        )
    }
}

class HomeNode(
    graph: Graph
): ComposeNode<Unit>(graph) {
    override val state = MutableStateFlow(Unit)
    override val routeBinding by consumes<HomeBinding>()

    init {
        routeBinding {
            graph.dispatch(Push(chatEdge, ChatPayload()))
            val result = CompletableDeferred<String>()
            graph.dispatch(Push(onboardingEdge, OnboardingPayload(), result))

            launch {
                val x = result.await()
            }
        }
    }

    @Composable
    override fun Content() {
        routeBinding {

        }
    }
}
