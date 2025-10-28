@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation.graph

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapability
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.io.network.toRoutePattern
import dev.shibasis.reaktor.navigation.visitor.Visitable
import dev.shibasis.reaktor.navigation.visitor.Visitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
sealed class Node(
    val graph: Graph,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
):
    Unique, Visitable,
    LifecycleCapability by LifecycleCapabilityImpl(),
    PortCapability by PortCapabilityImpl(
        graph.coroutineScope.coroutineContext
    ),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        graph.coroutineScope.coroutineContext,
        dispatcher
    )
{
    init {
        graph.attach(this)
    }

    override fun close() {
        invoke<LifecycleCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
    }
}

@JsExport
open class GraphNode(
    val childGraph: Graph,
    parent: Graph
): Node(parent) {

}

fun Graph.graph(graph: Graph) = GraphNode(graph, this)
    .also { attach(it) }

@JsExport
abstract class LogicNode(
    graph: Graph
): Node(graph)

fun Graph.logic(fn: Graph.() -> LogicNode) = fn()

@JsExport
@Serializable
open class Properties(
    val routeParams: HashMap<String, String> = hashMapOf()
)

@JsExport
interface RouteBinding<P: Properties> {
    fun props(): StateFlow<P>
}

@JsExport
class RouteNode<Props: Properties>(
    graph: Graph,
    val pattern: RoutePattern,
    props: Props
): Node(graph), RouteBinding<Props> {
    val routeBinding by provider<RouteBinding<Props>>(this)
    val propFlow = MutableStateFlow(props)
    override fun props() = propFlow
}

@JsExport
fun<Props: Properties> Graph.route(pattern: String, initialProps: Props) =
    RouteNode(this, pattern.toRoutePattern(), initialProps)
        .also { attach(it) }


// todo use recomposer / rerenderer to check useless renders
abstract class ViewNode<Props: Properties, State>(
    graph: Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    val routeBinding by consumer<RouteBinding<Props>>()
}

@JsExport
fun<Props: Properties, State> Graph.view(fn: Graph.() -> ViewNode<Props, State>) = fn()

@JsExport
interface View {

}

interface ComposeView: View {
    @Composable
    fun Compose(content: @Composable () -> Unit = {})
}

abstract class ComposeViewNode<Props: Properties, State>(
    graph: Graph
): ViewNode<Props, State>(graph), ComposeView









