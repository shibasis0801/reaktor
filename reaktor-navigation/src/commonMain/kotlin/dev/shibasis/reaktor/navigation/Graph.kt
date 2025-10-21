@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.navigation.koin.Koin
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.ScopeDSL
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

// todo add https://github.com/turansky/seskar for kotlin/js sugar
/*

A reactive graph library for application nodes inspired from Uber/RIBs and Actor frameworks like Akka/Erlang.
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

This needs to be concurrent to make use of all cores properly.
*/

// Your close() must be idempotent and can be called multiple times.
interface Capability: AutoCloseable {

}

abstract class AtomicCapability : Capability {
    private val closed = atomic(false)
    final override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) doClose()
    }
    protected abstract fun doClose()
}

inline operator fun<reified T: Capability> T.invoke(fn: T.() -> Unit) = fn(this)
inline operator fun<reified T: Capability> T.invoke() = this


interface Unique {
    val id: Uuid
}

class UniqueImpl(override val id: Uuid = Uuid.random()): Unique





sealed class Lifecycle {
    object Created: Lifecycle()
    object Restoring: Lifecycle()
    object Attached: Lifecycle()
    object Saving: Lifecycle()
    object Destroyed: Lifecycle()
}


interface LifecycleCapability: Capability {
    val lifecycle: MutableStateFlow<Lifecycle>
    val validTransitions: Set<Pair<Lifecycle, Lifecycle>>
        get() = setOf(
            Lifecycle.Created to Lifecycle.Restoring,
            Lifecycle.Restoring to Lifecycle.Attached,
            Lifecycle.Attached to Lifecycle.Saving,
            //
            Lifecycle.Saving to Lifecycle.Destroyed,
            Lifecycle.Created to Lifecycle.Destroyed,
            Lifecycle.Attached to Lifecycle.Destroyed,
            Lifecycle.Restoring to Lifecycle.Destroyed
        )

    fun transition(new: Lifecycle) {
        lateinit var previous: Lifecycle
        lifecycle.update { old ->
            previous = old
            if (validTransitions.contains(old to new))
                new
            else old
        }
        if (previous != new)
            onTransition(previous, new)
    }

    fun onTransition(previous: Lifecycle, current: Lifecycle) {}
    fun save() {}
    fun restore() {}
}


class LifecycleCapabilityImpl: LifecycleCapability {
    override val lifecycle: MutableStateFlow<Lifecycle> = MutableStateFlow(Lifecycle.Created)
    override fun close() {}
}




object ReaktorScope {
    val Graph = named("Reaktor.Graph")
    val GraphNode = named("Reaktor.GraphNode")
    val LogicNode = named("Reaktor.LogicNode")
    val RouteNode = named("Reaktor.RouteNode")
    val ViewNode = named("Reaktor.ViewNode")
}








typealias ScopedDependency = ScopeDSL.() -> Unit


interface DependencyCapability: Capability {
    val koinScope: Scope
    val koinQualifier: Qualifier
}


class DependencyCapabilityImpl(
    id: String,
    override val koinQualifier: Qualifier,
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
        parentScope?.let { koinScope.linkTo(it) }
        // todo no thread safety
        Feature.Koin.load(module)
    }

    override fun close() {
        Feature.Koin.unload(module)
        parentScope?.let { koinScope.unlink(it) }
        koinScope.close()
    }
}

inline fun <reified T : Any> DependencyCapability.Get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null,
): T {
    return koinScope.get(T::class, qualifier, parameters)
}














interface ConcurrencyCapability: Capability {
    val coroutineScope: CoroutineScope
}


class ConcurrencyCapabilityImpl(
    context: CoroutineContext? = null,
    dispatcher: CoroutineDispatcher
): ConcurrencyCapability {

    val supervisorJob = SupervisorJob()

    override val coroutineScope: CoroutineScope = CoroutineScope(
        (context ?: EmptyCoroutineContext) +
                dispatcher +
                supervisorJob
    )

    override fun close() {
        coroutineScope.cancel(CancellationException("Reaktor:AutoCloseable"))
    }
}








class Registry<InnerType: Any, OuterType: Any>(
    val linkedHashMap: LinkedHashMap<KClass<*>, OuterType>
): MutableMap<KClass<*>, OuterType> by linkedHashMap {
    inline operator fun<reified T: InnerType> get(key: String? = null) {
        val field = linkedHashMap[T::class]

    }
}




open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    override val id: Uuid = Uuid.random(),
    val dependency: ScopedDependency = {}
):
    Unique,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(id.toString(), ReaktorScope.Graph, parentGraph?.koinScope, dependency),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(parentGraph?.coroutineScope?.coroutineContext, dispatcher)
{
    private val children = linkedMapOf<Uuid, Node>()

    fun <N : Node> attach(builder: (parent: Graph) -> N): N {
        val node = builder(this)
        if (children.containsKey(node.id)) {
            Logger.w("Node ${node.id} is already attached. Ignoring.")
            return children[node.id] as N
        }

        Logger.v("Attaching Node: ${node::class.simpleName} (${node.id})")
        children[node.id] = node
        node.transition(Lifecycle.Restoring)
        node.restore()
        node.transition(Lifecycle.Attached)
        return node
    }

    fun detach(node: Node) {
        children.remove(node.id)?.let {
            Logger.v("Detaching Node: ${it::class.simpleName} (${it.id})")
            it.transition(Lifecycle.Saving)
            it.save()
            it.transition(Lifecycle.Destroyed)
            it.close()
        }
    }

    inline fun <reified C : Any> connect(from: Node, to: Node): Result<Unit> {
        val contract = C::class
        val outputPort = to.outputPorts[contract] as? OutPort<C>
            ?: return fail("Node ${to::class.simpleName} does not provide ${contract.simpleName}")

        val inputPort = from.inputPorts[contract]
            ?: return fail("Node ${from::class.simpleName} does not require ${contract.simpleName}")

        val edge = DirectedEdge(from, to, outputPort.impl)
        from.edges[contract] = edge
        return succeed(Unit)
    }

    override fun close() {
        Logger.v("Closing Graph: ${this::class.simpleName} ($id)")
        children.values.toList().forEach { detach(it) } // Detach all children
        this<LifecycleCapability> { close() }
        this<DependencyCapability> { close() }
        this<ConcurrencyCapability> { close() }
    }
}

class ChatFeatureGraph(parent: Graph): Graph(parent) {
    init {
        val logicNode = attach(::ChatInteractorNode)
        val viewNode = attach(::ChatViewNode)

        connect<ChatInterface>(from = viewNode, to = logicNode)
            .onFailure { Logger.e("Failed to wire ChatFeature: $it") }
    }
}


sealed class Node(
    val graph: Graph,
    koinQualifier: Qualifier,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val dependency: ScopedDependency = {},
    override val id: Uuid = Uuid.random(),
):
    Unique,
    LifecycleCapability by LifecycleCapabilityImpl(),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(graph.coroutineScope.coroutineContext, dispatcher)
{
    override fun close() {
        this<LifecycleCapability> { close() }
        this<ConcurrencyCapability> { close() }
    }

    val inputPorts = linkedMapOf<KClass<*>, InPort<*>>()
    val outputPorts = linkedMapOf<KClass<*>, OutPort<*>>()
    val edges = linkedMapOf<KClass<*>, DirectedEdge<*>>()

    inline fun <reified T : Any> Get(
        noinline parameters: ParametersDefinition? = null,
    ): T {
        val fromNode = graph.Get<T>(graph.koinQualifier, parameters)
        val fromEdge = edges[T::class] as? DirectedEdge<T>
        return fromEdge?.delegate ?: fromNode
    }
}

sealed interface Port<out Contract: Any> { val owner: Node }
class OutPort<Contract: Any>(override val owner: Node, val impl: Contract): Port<Contract>
class InPort<Contract: Any>(override val owner: Node, val kClass: KClass<Contract>): Port<Contract>

inline fun <reified Contract: Any> Node.provides(impl: Contract) = OutPort(this, impl).also { outputPorts[Contract::class] = it }
inline fun <reified Contract: Any> Node.requires() = InPort(this, Contract::class).also { inputPorts[Contract::class] = it }


class DirectedEdge<Contract: Any>(
    val from: Node,
    val to: Node,
    val delegate: Contract,
): Unique by UniqueImpl() {
    inline operator fun<R> invoke(fn: Contract.() -> R) = fn(delegate)
}

interface ChatInterface {
    fun getChats(): StateFlow<List<String>>
}


class ChatViewNode(
    parent: Graph
): ViewNode<Unit>(Unit, parent) {
    val chatPort = requires<ChatInterface>()

    fun t() {
        val chatInterface = Get<ChatInterface>()
        val edge = edges[ChatInterface::class]!! as DirectedEdge<ChatInterface>
        val chats = edge { getChats() }
    }
}

class ChatInteractorNode(
    parent: Graph
): LogicNode(parent), ChatInterface {
    val chatPort = provides<ChatInterface>(this)

    override fun getChats(): StateFlow<List<String>> {
        TODO("Not yet implemented")
    }
}

inline fun<reified Contract : Any> Node.addEdge(to: Node): Result<Unit> {
    val from = this
    val inputPort = from.inputPorts[Contract::class] ?: return fail("Null Input Port for type ${Contract::class}")
    val outputPort = to.outputPorts[Contract::class] ?: return fail("Null Output Port for type ${Contract::class}")

    val edge = DirectedEdge(from, to, outputPort.impl as Contract)
    from.edges[Contract::class] = edge
    to.edges[Contract::class] = edge

    return succeed(Unit)
}


/*
A GraphNode is responsible to expose functionality from inside of a Graph to external users through edge factories.
*/

open class GraphNode(
    val childGraph: Graph,
    parent: Graph
): Node(parent, ReaktorScope.GraphNode) {

}


abstract class LogicNode(
    graph: Graph
): Node(graph, ReaktorScope.LogicNode) {

}


abstract class RouteNode(
    val pattern: RoutePattern,
    graph: Graph
): Node(graph, ReaktorScope.RouteNode) {

}


abstract class ViewNode<State>(
    // mandated initial input for previews.
    previewState: State,
    graph: Graph
): Node(graph, ReaktorScope.ViewNode) {
    val state = MutableStateFlow(previewState)

    var isPreview = true
        private set

    init {
        coroutineScope.launch {

            state.drop(1).first()
            isPreview = false
        }
    }

    // Call your composable / component through this.
    open fun render() {}
}










/*
Instead of hardwiring functionality,
We will use Visitor pattern to traverse the graph as needed.

1. Graph level cached Traversals with invalidation on graphnode changes
2. Traversal strategies (DFS, BFS, PreOrder, PostOrder, etc)
3.
 */
open class Visitor {
    open fun visit(graph: Graph) {}

    open fun visit(graphNode: GraphNode) {}

    open fun visit(logicNode: LogicNode) {}
    open fun visit(routeNode: RouteNode) {}
    open fun visit(viewNode: ViewNode<*>) {}

//    open fun visit(edge: DirectedEdge<*, *>) {}
}


/*


1. Layouts
2. Stacks


*/
