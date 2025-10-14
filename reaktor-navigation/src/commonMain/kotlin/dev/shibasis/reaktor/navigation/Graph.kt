package dev.shibasis.reaktor.navigation

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.navigation.koin.Koin
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import kotlin.js.JsExport
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

inline operator fun<reified T: Capability> T.invoke(fn: T.() -> Unit) = fn(this)
inline operator fun<reified T: Capability> T.invoke() = this


interface Unique {
    val id: Uuid
}

class UniqueImpl(override val id: Uuid = Uuid.random()): Unique

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
    val LogicNode = named("Reaktor.LogicNode")
    val RouteNode = named("Reaktor.RouteNode")
    val ViewNode = named("Reaktor.ViewNode")
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


@JsExport
interface SealedRegistryCapability<SealedType: Unique> : MutableMap<Uuid, SealedType>, Capability {
    fun add(item: SealedType)
    fun remove(item: SealedType)

    // The high-performance query method
    fun <T : SealedType> getAllOfType(type: KClass<T>): List<T>
}

// Inline helper for a cleaner call site
@JsExport
inline fun <reified SealedType: Unique> SealedRegistryCapability<SealedType>.getAllOfType(): List<SealedType> {
    return this.getAllOfType(SealedType::class)
}

@JsExport
class SealedRegistryCapabilityImpl<SealedType: Unique>(
    // todo not thread safe. implement a lock-free concurrentmap with atomicfu.
    val map: MutableMap<Uuid, SealedType> = hashMapOf()
):
    SealedRegistryCapability<SealedType>,
    MutableMap<Uuid, SealedType> by map {

    private val indexByType = hashMapOf<KClass<out SealedType>, MutableList<SealedType>>()

    override fun add(item: SealedType) {
        put(item.id, item)

        val list = indexByType.getOrPut(item::class) {
            arrayListOf()
        }

        list.add(item)
    }

    override fun remove(item: SealedType) {
        remove(item.id)
        indexByType[item::class]?.remove(item)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T: SealedType> getAllOfType(type: KClass<T>): List<T> {
        return indexByType[type]?.toList() as? List<T> ?: emptyList()
    }

    override fun close() {
        clear()
        indexByType.clear()
    }
}

@JsExport
open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher,
    override val id: Uuid = Uuid.random(),
    val dependency: ScopedDependency
):
    Unique,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(id.toString(), ReaktorScope.Graph, parentGraph?.koinScope, dependency),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(parentGraph?.coroutineScope?.coroutineContext, dispatcher),
    SealedRegistryCapability<Node> by SealedRegistryCapabilityImpl()
{
    override fun close() {
        this<LifecycleCapability> { close() }
        this<DependencyCapability> { close() }
        this<ConcurrencyCapability> { close() }
        this<SealedRegistryCapability<Node>> { close() }
    }
}

@JsExport
sealed class Node(
    val parent: Graph,
    koinQualifier: Qualifier,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val dependency: ScopedDependency = {},
    override val id: Uuid = Uuid.random(),
):
    Unique,
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(id.toString(), koinQualifier, parent.koinScope, dependency),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(parent.coroutineScope.coroutineContext, dispatcher),
    SealedRegistryCapability<DirectedEdge<*, *>> by SealedRegistryCapabilityImpl()
{
    override fun close() {
        this<LifecycleCapability> { close() }
        this<DependencyCapability> { close() }
        this<ConcurrencyCapability> { close() }
        this<SealedRegistryCapability<DirectedEdge<*, *>>> { close() }
    }

    infix fun<Request, Response> addEdge(other: Node) {

    }
}


/*
A GraphNode is responsible to expose functionality from inside of a Graph to external users through edge factories.
*/
@JsExport
open class GraphNode(
    val graph: Graph,
    parent: Graph
): Node(parent, ReaktorScope.GraphNode) {

}

@JsExport
abstract class LogicNode(
    graph: Graph
): Node(graph, ReaktorScope.LogicNode) {

}

@JsExport
abstract class RouteNode(
    val pattern: RoutePattern,
    graph: Graph
): Node(graph, ReaktorScope.RouteNode) {
//    fun getViewNode(): ViewNode<*, *>? {
//        return getAllOfType<ViewNode<*, *>>()?.firstOrNull()
//    }
}

@JsExport
abstract class ViewNode<State>(
    // mandated initial input for previews.
    previewState: State,
    graph: Graph
): Node(graph, ReaktorScope.ViewNode) {
    val state = MutableStateFlow(previewState)

    var isPreview = true
        private set

    init {
        Dispatch.Main.launch {
            state.drop(1).first()
            isPreview = false
        }
    }

    // Call your composable / component through this.
    open fun render() {}
}

/**
 * Using gRPC semantics, https://grpc.io/docs/what-is-grpc/core-concepts/#service-definition
 * gRPC lets you define four kinds of service method:
 *
 * Unary RPCs where the client sends a single request to the server and gets a single response back, just like a normal function call.
 *
 * rpc SayHello(HelloRequest) returns (HelloResponse);
 * Server streaming RPCs where the client sends a request to the server and gets a stream to read a sequence of messages back. The client reads from the returned stream until there are no more messages. gRPC guarantees message ordering within an individual RPC call.
 *
 * rpc LotsOfReplies(HelloRequest) returns (stream HelloResponse);
 * Client streaming RPCs where the client writes a sequence of messages and sends them to the server, again using a provided stream. Once the client has finished writing the messages, it waits for the server to read them and return its response. Again gRPC guarantees message ordering within an individual RPC call.
 *
 * rpc LotsOfGreetings(stream HelloRequest) returns (HelloResponse);
 * Bidirectional streaming RPCs where both sides send a sequence of messages using a read-write stream. The two streams operate independently, so clients and servers can read and write in whatever order they like: for example, the server could wait to receive all the client messages before writing its responses, or it could alternately read a message then write a message, or some other combination of reads and writes. The order of messages in each stream is preserved.
 *
 * rpc BidiHello(stream HelloRequest) returns (stream HelloResponse);
 *
 */
sealed class Command<Request, Response> {
    data class UnarySync<Request, Response>(
        val request: Request,
        val response: Response
    ): Command<Request, Response>()

    data class Unary<Request, Response>(
        val request: Request,
        val response: CompletableDeferred<Response>
    ): Command<Request, Response>()

    data class ResponseStream<Request, Response>(
        val request: Request,
        val response: Channel<Response>
    ): Command<Request, Response>()

    data class RequestStream<Request, Response>(
        val request: Channel<Request>,
        val response: CompletableDeferred<Response>
    ): Command<Request, Response>()

    data class BidirectionalStream<Request, Response>(
        val request: Channel<Request>,
        val response: Channel<Response>
    ): Command<Request, Response>()
}

sealed class DirectedEdge<Request, Response>(
    val start: Node,
    val end: Node,
    val outgoing: MutableSharedFlow<Command<Request, Response>> = MutableSharedFlow(),
): Unique by UniqueImpl() {

    suspend fun unary(request: Request): Response {
        val response = CompletableDeferred<Response>()
        val command = Command.Unary(request, response)
        outgoing.emit(command)
        return response.await()
    }

    suspend fun responseStream(request: Request): Channel<Response> {
        val response = Channel<Response>()
        val command = Command.ResponseStream(request, response)
        outgoing.emit(command)
        return response
    }

    suspend fun requestStream(request: Channel<Request>): Response {
        val response = CompletableDeferred<Response>()
        val command = Command.RequestStream(request, response)
        outgoing.emit(command)
        return response.await()
    }

    suspend fun responseStream(request: Channel<Request>): Channel<Response> {
        val response = Channel<Response>()
        val command = Command.BidirectionalStream(request, response)
        outgoing.emit(command)
        return response
    }
}

class UnaryDirectedEdge<Request, Response>(
    start: Node, end: Node
): DirectedEdge<Request, Response>(start, end)

class DuplexDirectedEdge<
    ForwardRequest, ForwardResponse,
    ReverseRequest, ReverseResponse>(
        start: Node,
        end: Node,
        val incoming: MutableSharedFlow<Command<ReverseRequest, ReverseResponse>> = MutableSharedFlow()
): DirectedEdge<ForwardRequest, ForwardResponse>(start, end) {
    suspend fun unaryReverse(request: ReverseRequest): ReverseResponse {
        val response = CompletableDeferred<ReverseResponse>()
        val command = Command.Unary(request, response)
        incoming.emit(command)
        return response.await()
    }

    suspend fun responseStreamReverse(request: ReverseRequest): Channel<ReverseResponse> {
        val response = Channel<ReverseResponse>()
        val command = Command.ResponseStream(request, response)
        incoming.emit(command)
        return response
    }

    suspend fun requestStreamReverse(request: Channel<ReverseRequest>): ReverseResponse {
        val response = CompletableDeferred<ReverseResponse>()
        val command = Command.RequestStream(request, response)
        incoming.emit(command)
        return response.await()
    }

    suspend fun responseStreamReverse(request: Channel<ReverseRequest>): Channel<ReverseResponse> {
        val response = Channel<ReverseResponse>()
        val command = Command.BidirectionalStream(request, response)
        incoming.emit(command)
        return response
    }
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

    open fun visit(edge: DirectedEdge<*, *>) {}
}


sealed interface HomeScreenCommand {
    data object ApiRequest: HomeScreenCommand
}

class HomeScreenNode(
    graph: Graph,
    val edge: UnaryDirectedEdge<HomeScreenCommand, HomeInteractorResponse>
): ViewNode<Unit>(Unit, graph) {

    fun t() {
        coroutineScope.launch {
            val x = edge.unary(HomeScreenCommand.ApiRequest)
            val c = Channel<HomeScreenCommand>()
            val y = edge.requestStream(c)

        }
    }
}

sealed interface HomeInteractorResponse {
    data object ApiResponse: HomeInteractorResponse
}

class HomeInteractorNode(graph: Graph): LogicNode(graph) {

}

fun t(graph: Graph) {
    val screen = HomeScreenNode(graph)
    val interactor = HomeInteractorNode(graph)

    val edge = UnaryDirectedEdge<HomeScreenCommand, HomeInteractorResponse>(screen, interactor)
}