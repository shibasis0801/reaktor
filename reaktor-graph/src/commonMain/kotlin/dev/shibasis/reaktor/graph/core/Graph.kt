package dev.shibasis.reaktor.graph.core

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.di.DependencyCapability
import dev.shibasis.reaktor.graph.di.DependencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.graph.navigation.NavigationCapability
import dev.shibasis.reaktor.graph.navigation.NavigationCapabilityImpl
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.port.flattenedValues
import dev.shibasis.reaktor.graph.di.Dependency
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.js.JsExport
import kotlin.uuid.Uuid

// steroids(https://developer.android.com/static/images/topic/libraries/architecture/navigation-design-graph-nested.png)
@JsExport
open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    dependencyAdapter: DependencyAdapter<*> = Feature.Dependency ?: throw IllegalStateException("Please Initialize Feature.Dependency"),
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val dependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
):
    Unique,
    Visitable,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(
        adapter = dependencyAdapter,
        id = id.toString(),
        parentScope = parentGraph?.let { (it as DependencyCapability).diScope },
        configure = dependencies
    ),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        parentGraph?.coroutineScope?.coroutineContext,
        dispatcher
    ),
    NavigationCapability by NavigationCapabilityImpl()
{
    val nodes = arrayListOf<Node>()
    val sentinel = RouteNode(this, "")

    init { builder() }

    fun<P: Payload> addRoot(routeNode: RouteNode<P, *>, payload: P) {
        val edge = sentinel.edge(routeNode)
        dispatch(Push(
            edge, payload
        ))
    }

    override fun onTransition(
        previous: Lifecycle,
        next: Lifecycle
    ) {
        val transitionNodes = { nodes.forEach { it.transition(next) } }

        when (next) {
            Lifecycle.Created -> transitionNodes()
            Lifecycle.Restoring -> transitionNodes()
            Lifecycle.Attaching -> { /* hook if needed */ }
            Lifecycle.Saving -> transitionNodes()
            Lifecycle.Destroying -> {
                nodes.toList().forEach { detach(it) }
                nodes.clear()
            }
        }
    }

    override fun close() {
        transition(Lifecycle.Destroying)
        invoke<DependencyCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
        invoke<LifecycleCapability> { close() }
    }

    override fun toString(): String {
        return "[Graph] label='$label' id='$id' nodes=${nodes.size} stackDepth=${activeStack.entries.value.size}, sentinel=$sentinel"
    }
}

fun Graph.attach(node: Node): Result<Unit> {
    if (nodes.find { it.id == node.id } != null) {
        Logger.w("Node ${node.id} is already attached. Ignoring.")
        return fail(ConcurrentModificationException("Node already attached"))
    }

    nodes += node
    node.transition(Lifecycle.Restoring)

    return succeed(Unit)
}

fun Graph.detach(node: Node) {
    node.transition(Lifecycle.Saving)
    nodes.remove(node)
}


fun Graph.autoWire() {
    val availableProviders = nodes
        .flatMap { node -> node.providerPorts.flattenedValues() }
        .groupBy { it.type }

    nodes.flatMap { it.consumerPorts.flattenedValues() }
        .filter { !it.isConnected() }
        .forEach { consumer ->
            val candidates = availableProviders[consumer.type] ?: emptyList()

            val match = when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> candidates.first()
                else -> {
                    // Ambiguity! Try to resolve by Key (Name)
                    val exactMatch = candidates.find { it.key == consumer.key }
                    if (exactMatch == null) {
                        error("Ambiguous wiring for ${consumer.type}. Found ${candidates.size} providers: ${candidates.map { it.owner }}")
                    }
                    exactMatch
                }
            }

            if (match != null) {
                connect(consumer, match)
            }
        }
}