package dev.shibasis.reaktor.navigation

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.navigation.koin.Koin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.ScopeDSL
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.js.JsExport
import kotlin.uuid.Uuid


// todo add https://github.com/turansky/seskar for kotlin/js sugar

/*

A reactive graph library for application nodes inspired by Uber/RIBs and Actor frameworks like Akka and other stuff.
Large applications for mobile and servers can be decomposed into fully functional modules and visitors can orchestrate stuff on it.


Graph, Node, Edge (UI & Server)
-> dependencies & structured concurrency
-> observable, reactive graph state reconciliation
-> visitor pattern
-> cycle detection
-> unreachable detection
-> DAG validation & topological sort
-> Visitor for JSON / React-flow

RouteNode
-> Route (pattern, input, output)
-> View, Interaction used within - platform
(no containers, just nested views) - independent
-> Navigation Edges for visual, auth checks
-> Navigation Visitor
-> Feature Toggles / guards / etc

ApiNode
-> App (input, output)
-> Resilience4j - circuit breaker, retry, bulkhead, etc
-> auth checks
-> Execution Visitor


*/

interface Capability: AutoCloseable {

}

inline operator fun<reified T: Capability> T.invoke(fn: T.() -> Unit) = fn(this)
inline operator fun<reified T: Capability> T.invoke() = this

@JsExport
sealed class Lifecycle {
    object Created: Lifecycle()
    object Attached: Lifecycle()
    object Destroyed: Lifecycle()
}

@JsExport
interface LifecycleCapability: Capability {
    val lifecycle: MutableStateFlow<Lifecycle>
    fun transition(new: Lifecycle) {
        lateinit var previous: Lifecycle
        lifecycle.update { old ->
            previous = old
            when(old to new) {
                (Lifecycle.Created to Lifecycle.Attached) -> {
                    new
                }
                (Lifecycle.Created to Lifecycle.Destroyed) ->  {
                    new
                }
                (Lifecycle.Attached to Lifecycle.Destroyed) -> {
                    new
                }
                else -> {
                    Logger.e { "Invalid State Transition from $old to $new" }
                    old
                }
            }
        }
        if (previous != new)
            onTransition(previous, new)
    }

    fun onTransition(previous: Lifecycle, current: Lifecycle) {}
}


@JsExport
class LifecycleCapabilityImpl: LifecycleCapability {
    override val lifecycle: MutableStateFlow<Lifecycle> = MutableStateFlow(Lifecycle.Created)
    override fun close() {}
}


object ReaktorScope {
    val Graph = named("Reaktor.Graph")
    val GraphNode = named("Reaktor.GraphNode")
    val ApiNode = named("Reaktor.ApiNode")
    val RouteNode = named("Reaktor.RouteNode")
    val ViewNode = named("Reaktor.ViewNode")
    val InteractorNode = named("Reaktor.InteractorNode")
}


typealias ScopedDependency = ScopeDSL.() -> Unit

@JsExport
interface DependencyCapability: Capability {
    val koinScope: Scope
}

@JsExport
class DependencyCapabilityImpl(
    id: String,
    koinQualifier: Qualifier,
    val parentScope: Scope?,
    dependencies: ScopedDependency
): DependencyCapability {
    override val koinScope: Scope = Feature.Koin.koin().createScope(id, koinQualifier)
    val module = module {
        scope(koinQualifier) {
            dependencies()
        }
    }

    init {

        parentScope?.linkTo()
        parentScope?.let { koinScope.linkTo(it) }
        Feature.Koin.load(module)
    }

    override fun close() {
        Feature.Koin.unload(module)
        parentScope?.let { koinScope.unlink(it) }
    }
}


@JsExport
inline fun <reified T : Any> DependencyCapability.get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null,
): T {
    return koinScope.get(T::class, qualifier, parameters)
}


@JsExport
interface ConcurrencyCapability: Capability {
    val coroutineScope: CoroutineScope
}


@JsExport
class ConcurrencyCapabilityImpl(
    context: CoroutineContext? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
): ConcurrencyCapability {

    val supervisorJob = SupervisorJob()

    override val coroutineScope: CoroutineScope = CoroutineScope(
        (context ?: EmptyCoroutineContext) +
                dispatcher +
                supervisorJob
    )

    override fun close() {
        supervisorJob.cancel(CancellationException("Reaktor:AutoCloseable"))
    }
}


@JsExport
open class Graph(
    parentGraph: Graph? = null,
    val id: Uuid = Uuid.random(),
    val dependency: ScopedDependency,
    val nodes: List<Node> = arrayListOf()
):
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(id.toString(), ReaktorScope.Graph, parentGraph?.koinScope, dependency),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(parentGraph?.coroutineScope?.coroutineContext)
{
    override fun close() {
        this<LifecycleCapability> { close() }
        this<DependencyCapability> { close() }
        this<ConcurrencyCapability> { close() }
    }
}

@JsExport
sealed class Node(
    val parent: Graph,
    koinQualifier: Qualifier,
    val dependency: ScopedDependency = {},
    val id: Uuid = Uuid.random(),
):
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(id.toString(), koinQualifier, parent.koinScope, dependency),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(parent.coroutineScope.coroutineContext)
{
    override fun close() {
        this<LifecycleCapability> { close() }
        this<DependencyCapability> { close() }
        this<ConcurrencyCapability> { close() }
    }
}


@JsExport
open class GraphNode(
    val graph: Graph,
    parent: Graph
): Node(parent, ReaktorScope.GraphNode) {

}

@JsExport
abstract class ApiNode(
    graph: Graph
): Node(graph, ReaktorScope.ApiNode) {

}


@JsExport
abstract class RouteNode(
    val pattern: RoutePattern,
    graph: Graph
): Node(graph, ReaktorScope.RouteNode) {

}


@JsExport
abstract class ViewNode<Input, Signal>(
    // mandated initial input for previews.
    previewInput: Input
): LifecycleCapability by LifecycleCapabilityImpl() {

    val state = MutableStateFlow(previewInput)
    val signal = MutableSharedFlow<Signal>()

    var isPreview = true
        private set

    init {
        Dispatch.Main.launch {
            state.drop(1).first()
            isPreview = false
        }
    }
}


open class Visitor {
    open fun visit(node: Node) {}
}

sealed class Edge(val from: Node, val to: Node) {
    class UndirectedEdge(from: Node, to: Node): Edge(from, to)

    open class DirectedEdge(from: Node, to: Node): Edge(from, to)
    class RouteEdge(from: Node, to: Node): DirectedEdge(from, to)
    class ApiEdge(from: Node, to: Node): DirectedEdge(from, to)
}





