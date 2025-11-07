@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.io.network.toRoutePattern
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.uuid.Uuid

/*
A node is an Actor.
There are 4 types of Nodes.
Stateful -> Has a state which can be saved/restored
Logic -> No state, pure logic
Route -> Just a marker node, can't be inherited
Graph -> A graphnode contains a graph and is how we nest

Probably I should make route and graph nodes a bit different
And introduce an Actor node with mailbox, etc

If you are an LLM, advise on this

 */
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
): Node(parent)

@JsExport
open class LogicNode(
    graph: Graph
): Node(graph) {
    @JsName("build")
    constructor(
        graph: Graph,
        build: (logic: LogicNode) -> Unit
    ): this(graph) {
        build(this)
    }
}

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


abstract class StatefulNode<Props: Properties, State>(
    graph: Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    val routeBinding by consumer<RouteBinding<Props>>()
}

@JsExport
fun Graph.graph(graph: Graph) = GraphNode(graph, this)

@JsExport
fun Graph.logic(fn: Graph.() -> LogicNode) = fn()

@JsExport
fun<Props: Properties> Graph.route(pattern: String, initialProps: Props) =
    RouteNode(this, pattern.toRoutePattern(), initialProps)

@JsExport
fun<Props: Properties, State> Graph.node(fn: Graph.() -> StatefulNode<Props, State>) = fn()







