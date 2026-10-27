package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.KeyType
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.graph.navigation.NavCommand
import dev.shibasis.reaktor.graph.navigation.Pop
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.navigation.Replace
import dev.shibasis.reaktor.graph.navigation.Return
import dev.shibasis.reaktor.graph.navigation.payloadOf
import dev.shibasis.reaktor.graph.navigation.toRoute
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import dev.shibasis.reaktor.graph.core.NodeKind

@JsExport
open class RouteBinding<P: Payload>(
    initial: P
) {
    val payload = MutableStateFlow(initial)
    var dispatch: (NavCommand) -> Unit = {}
        internal set
    internal val exits = mutableListOf<RouteExit>()

    fun NavigationEdge<Payload>.push(payload: Payload) {
        dispatch(Push(this, payload))
    }

    fun NavigationEdge<Payload>.replace(payload: Payload) {
        dispatch(Replace(this, payload))
    }

    @JsExport.Ignore
    inline fun <reified R : Any> NavigationEdge<Payload>.push(route: R) = push(payloadOf(route))

    @JsExport.Ignore
    inline fun <reified R : Any> NavigationEdge<Payload>.replace(route: R) = replace(payloadOf(route))

    @JsExport.Ignore
    inline fun <reified R : Any> toRoute(): R? = payload.value.toRoute()

    @JsName("pop")
    fun back() {
        dispatch(Pop)
    }

    @JsName("backWithResult")
    fun<R> back(result: R) {
        dispatch(Return(result))
    }
}

typealias Binding = RouteBinding<Payload>

class RouteExit internal constructor(val name: String, val destination: KClass<*>) : ReadOnlyProperty<RouteBinding<*>, NavigationEdge<Payload>> {
    internal var port: ConsumerPort<NavBinding<Payload>>? = null

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: RouteBinding<*>, property: KProperty<*>): NavigationEdge<Payload> =
        port?.edge as? NavigationEdge<Payload> ?: error("No ${destination.simpleName} route is wired to $name, an exit of ${thisRef::class.simpleName}")
}

inline fun <reified Destination : Any> exit() = exit(Destination::class)

fun exit(destination: KClass<*>) = PropertyDelegateProvider<RouteBinding<*>, RouteExit> { binding, property ->
    RouteExit(property.name, destination).also(binding.exits::add)
}

internal val NavigationKey = Key("navBinding")

internal fun navigation(destination: KClass<*>) = Type<NavBinding<*>>().copy(kClass = destination)

internal class ExitPort(private val route: RouteNode<*, *>, val exit: RouteExit) :
    ConsumerPort<NavBinding<Payload>>(route, NavigationKey, navigation(exit.destination)) {
    @Suppress("UNCHECKED_CAST")
    override fun edgeTo(provider: ProviderPort<NavBinding<Payload>>): Edge<NavBinding<Payload>> {
        val end = (provider.owner as? ContainerNode)?.route ?: provider.owner as RouteNode<Payload, *>
        return NavigationEdge(route, end, this).also { route.navigationEdges.getOrPut(end.id.toString()) { it } }
    }
}

@JsExport
interface NavBinding<P: Payload> {
    @JsName("updateFn")
    fun update(fn: (P) -> P)
    fun update(payload: Payload) = update { payload as P }
}


typealias Binder<P, Binding> = (RouteNode<P, Binding>) -> Binding

@JsExport
open class RouteNode<P: Payload, Binding: RouteBinding<P>>(
    graph: Graph,
    val pattern: RoutePattern,
    portName: String,
    binder: Binder<P, Binding>
): Node(graph) {
    override val kind: NodeKind get() = NodeKind.Route

    @JsName("constructNamed")
    constructor(graph: Graph, pattern: String, portName: String, binder: (RouteNode<P, Binding>) -> Binding):
            this(graph, RoutePattern.from(pattern), portName, binder)

    @JsName("construct")
    constructor(graph: Graph, pattern: String, binder: (RouteNode<P, Binding>) -> Binding):
            this(graph, RoutePattern.from(pattern), "routeBinding", binder)


    internal val navigationEdges = mutableMapOf<String, NavigationEdge<out Payload>>()

    private val binding = binder(this).apply { dispatch = graph::dispatch }

    // todo must allow only one stateful node to connect.
    val routeBinding = registerProvider(KeyType(Key(portName), Type(binding)), binding)

    val navBinding: ProviderPort<NavBinding<P>> = registerProvider(NavigationKey, navigation(binding::class), object: NavBinding<P> {
        override fun update(fn: (P) -> P) {
            binding.payload.update(fn)
        }
    })

    init {
        binding.exits.forEach { exit -> exit.port = registerConsumer(NavigationKey, navigation(exit.destination)) { ExitPort(this, exit) } }
    }

    fun attachedNodes(): List<Routable> =
        routeBinding.edges.values
            .mapNotNull { it.source as? Routable }
            .distinct()

    /** Each declared exit keeps its name, including two exits to the same destination. */
    fun navigationExits(): List<Pair<String, RouteNode<*, *>>> =
        consumerPorts.flattenedValues().mapNotNull { consumer ->
            val edge = consumer.edge ?: return@mapNotNull null
            if (edge.provider.impl !is NavBinding<*>) return@mapNotNull null
            val destination = edge.destination as? RouteNode<*, *> ?: return@mapNotNull null
            ((consumer as? ExitPort)?.exit?.name ?: consumer.key.key) to destination
        }.distinct()

    fun navigationTargets(): List<RouteNode<*, *>> =
        consumerPorts.flattenedValues()
            .mapNotNull { consumer ->
                val edge = consumer.edge ?: return@mapNotNull null
                if (edge.provider.impl !is NavBinding<*>) {
                    return@mapNotNull null
                }
                edge.destination as? RouteNode<*, *>
            }
            .distinct()

    fun attachedNode(): Routable? {
        return attachedNodes().firstOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun <D: Payload> edge(
        destination: RouteNode<D, *>
    ): NavigationEdge<D> =
        navigationEdges.getOrPut(destination.id.toString()) {
            NavigationEdge(this, destination)
        } as NavigationEdge<D>

    companion object {
        operator fun invoke(graph: Graph, pattern: String) =
            RouteNode(graph, pattern) { RouteBinding(Payload()) }
    }

    override fun toString(): String {
        return "${super.toString()} [Route] pattern='$pattern'"
    }
}


fun<P: Payload, Binding: RouteBinding<P>> Graph.Route(pattern: String, binder: Binder<P, Binding>): RouteNode<P, Binding> {
    val node = RouteNode(this, pattern, binder)
    attach(node)
    return node
}

fun<P: Payload, Binding: RouteBinding<P>> Graph.Route(pattern: String, binding: () -> Binding, screen: (Graph) -> Node): RouteNode<P, Binding> =
    Route(pattern) { binding() }.also { Node(screen) }

fun <Binding : RouteBinding<Payload>> Graph.Root(pattern: String, binding: () -> Binding, screen: (Graph) -> Node): RouteNode<Payload, Binding> =
    Route(pattern, binding, screen).also { addRoot(it, it.routeBinding.impl.payload.value) }

fun <C : ContainerNode> Graph.Root(container: (Graph) -> C): C = Node(container).also { addRoot(it.route) }

val Graph.currentRoute: RouteNode<*, *>? get() = backStack.top.value?.edge?.end

fun Graph.replaceWith(route: RouteNode<Payload, *>, payload: Payload = Payload()) =
    dispatch(Replace(sentinel.edge(route), payload))

fun Graph.popToRoot() {
    while (backStack.entries.value.size > 1) dispatch(Pop)
}
