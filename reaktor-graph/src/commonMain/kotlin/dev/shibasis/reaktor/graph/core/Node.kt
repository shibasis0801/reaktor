@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.js.JsExport
import kotlin.js.JsName
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
interface RouteBinding<P: Props> {
    val props: MutableStateFlow<P>
    fun navigate(navCommand: NavCommand)
}

@JsExport
interface NavBinding<P: Props> {
    @JsName("updateFn")
    fun update(fn: (P) -> P)
    fun update(props: Props) = update { props as P }
}


@JsExport
abstract class RouteNode<P: Props>(
    graph: Graph,
    val pattern: RoutePattern
): Node(graph), RouteBinding<P> {
    @JsName("construct")
    constructor(graph: Graph, pattern: String): this(graph, RoutePattern.from(pattern))

    abstract val routeBinding: ProviderPort<out RouteBinding<P>>

    val navBinding by provides<NavBinding<P>>(object: NavBinding<P> {
        override fun update(fn: (P) -> P) {
            routeBinding.impl.props.update(fn)
        }
    })

    // llm -> What if the node is in another graph ?
    override fun navigate(navCommand: NavCommand) =
        (graph as NavigationCapability).dispatch(navCommand)

}

@JsExport
abstract class StatefulNode<State>(
    graph: Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    abstract val routeBinding: RequirerPort<out RouteBinding<out Props>>
}

/*
incomplete design.
must mimic https://github.com/cloudflare/actors (DurableObject wrapper),
take inspiration from erlang/akka/orleans

This would allow combining functionality from everywhere.
persistence through object-database/sqlite
timers through workmanager/bgtaskscheduler/quartz/etc
etc
*/
open class ActorNode<Message>(
    graph: Graph,
    dispatcher: CoroutineDispatcher = Dispatch.Default.coroutineDispatcher.limitedParallelism(1)
): Node(graph) {
    val channel = Channel<Message>()
}


