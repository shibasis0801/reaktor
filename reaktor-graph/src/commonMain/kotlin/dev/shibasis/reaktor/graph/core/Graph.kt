package dev.shibasis.reaktor.graph.core

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.graph.di.DependencyCapability
import dev.shibasis.reaktor.graph.di.DependencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport
import kotlin.uuid.Uuid

// rooted graph
@JsExport
open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    dependencyAdapter: DependencyAdapter<*> = KoinDependencyAdapter(),
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val configureDependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
):
    Unique,
    Visitable,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(
        adapter = dependencyAdapter,
        id = id.toString(),
        parentScope = parentGraph?.let { (it as DependencyCapability).diScope },
        configure = configureDependencies
    ),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        parentGraph?.coroutineScope?.coroutineContext,
        dispatcher
    ),
    NavigationCapability by NavigationCapabilityImpl()
{
    val nodes = arrayListOf<Node>()
    init { builder() }

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
}

class GraphRoot(graph: Graph): RouteNode<Payload>(graph, "/") {
    override val props = MutableStateFlow(Payload())
    override val routeBinding by provides(this)
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