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
class RouteNode<P: Parameters>(
    graph: Graph,
    val pattern: RoutePattern,
    params: P,
    val navEdges: Edge<Navigable<out Parameters>>
): Node(graph), RouteBinding<P> {
    val routeBinding by provides<RouteBinding<P>>(this)
    val params = MutableStateFlow(params)
    override fun paramFlow() = params
}


abstract class StatefulNode<Props: Parameters, State>(
    graph: Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    val routeBinding by requires<RouteBinding<Props>>()
}

/*
incomplete design.
must mimic https://github.com/cloudflare/actors,
take inspiration from erlang/akka/orleans

This would allow combining functionality from everywhere.
persistence through object-database/sqlite
timers through workmanager/bgtaskscheduler/quartz/etc
etc
*/
class ActorNode<Message>(
    graph: Graph,
    dispatcher: CoroutineDispatcher = Dispatch.Default.coroutineDispatcher.limitedParallelism(1)
): Node(graph) {
    val channel = Channel<Message>()
}


